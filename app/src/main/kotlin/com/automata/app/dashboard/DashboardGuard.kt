package com.automata.app.dashboard

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/** Lo que necesitan las rutas protegidas del tablero: credenciales, límite de intentos y cómo leer la IP del cliente. */
class DashboardGuard(
    val auth: DashboardAuth,
    val throttle: AuthThrottle,
    val trustProxy: Boolean
)

/** IP del cliente. Solo con TRUST_PROXY=true se lee X-Forwarded-For (la última entrada la agrega NUESTRO proxy). */
fun ApplicationCall.clientAddress(trustProxy: Boolean): String {
    if (trustProxy) {
        val forwarded = request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()
        if (!forwarded.isNullOrEmpty()) return forwarded
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
    val client = clientAddress(guard.trustProxy)

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
