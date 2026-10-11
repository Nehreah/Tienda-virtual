package com.automata.app.dashboard

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/** Lo que necesitan las rutas protegidas del tablero: credenciales, límite de intentos y cómo leer la IP del cliente. */
class DashboardGuard(
    val auth: DashboardAuth,
    val throttle: AuthThrottle,
    val trustProxy: Boolean,
    /** Cuántos proxies de confianza hay delante (TRUST_PROXY_HOPS). Con 1 se usa la última entrada de X-Forwarded-For. */
    val trustProxyHops: Int = 1
)

/**
 * IP del cliente. Solo con TRUST_PROXY=true se lee X-Forwarded-For. Cada proxy de confianza agrega UNA entrada al final,
 * así que con [hops] proxies la IP real es la entrada número [hops] contando desde el final (las anteriores las puede
 * falsear el cliente). Si la cabecera trae menos entradas de las esperadas, se usa la primera disponible.
 */
fun ApplicationCall.clientAddress(trustProxy: Boolean, hops: Int = 1): String {
    if (trustProxy) {
        val entries = request.headers["X-Forwarded-For"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (entries.isNotEmpty()) {
            val index = (entries.size - hops.coerceAtLeast(1)).coerceAtLeast(0)
            return entries[index]
        }
    }
    return request.local.remoteHost
}

/** CSRF (fase 1.4): el Origin (o Referer) del POST debe coincidir con el host propio. */
fun ApplicationCall.isSameOriginRequest(trustProxy: Boolean): Boolean {
    val forwardedHost = if (trustProxy) {
        request.headers["X-Forwarded-Host"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } else null
    val host = forwardedHost ?: request.headers[HttpHeaders.Host]
    return OriginGuard.isSameOrigin(
        origin = request.headers[HttpHeaders.Origin],
        referer = request.headers[HttpHeaders.Referrer],
        host = host
    )
}

/**
 * Protege una ruta del tablero. Devuelve false (ya respondió) cuando no hay acceso:
 * 429 si el cliente está bloqueado por intentos fallidos, 401 si faltan o fallan las credenciales y,
 * para rutas que modifican datos ([mutating] = true), 403 si el Origin no es el propio.
 */
suspend fun ApplicationCall.requireDashboardAuth(guard: DashboardGuard, mutating: Boolean = false): Boolean {
    val client = clientAddress(guard.trustProxy, guard.trustProxyHops)

    val wait = guard.throttle.retryAfterSeconds(client)
    if (wait > 0) {
        response.header(HttpHeaders.RetryAfter, wait.toString())
        respondText("Demasiados intentos fallidos. Intenta de nuevo en $wait segundos.", status = HttpStatusCode.TooManyRequests)
        return false
    }

    val authorization = request.headers[HttpHeaders.Authorization]
    if (!guard.auth.isAuthorized(authorization)) {
        // Sin cabecera = primer desafío del navegador: no cuenta como intento fallido.
        if (authorization != null) guard.throttle.recordFailure(client)
        response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Tienda Virtual Multicanal\", charset=\"UTF-8\"")
        respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
        return false
    }

    if (mutating && !isSameOriginRequest(guard.trustProxy)) {
        respondText("Origen no permitido", status = HttpStatusCode.Forbidden)
        return false
    }
    return true
}
