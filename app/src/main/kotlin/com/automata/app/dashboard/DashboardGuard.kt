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
    val trustProxyHops: Int = 1,
    /** Sesiones por cookie (formulario /login). null = solo HTTP Basic, como antes. */
    val sessions: SessionManager? = null,
    /** Marca la cookie como Secure (solo viaja por HTTPS). Se activa en producción. */
    val secureCookies: Boolean = false
) {
    /** El formulario de login solo tiene sentido si hay clave y un gestor de sesiones. */
    val loginEnabled: Boolean get() = auth.enabled && sessions != null
}

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
 * Quién hace la petición: primero la cookie de sesión (si es válida y su usuario sigue en la configuración) y, si no,
 * las credenciales HTTP Basic. null si no hay ninguna válida.
 */
fun ApplicationCall.dashboardPrincipal(guard: DashboardGuard): DashboardPrincipal? {
    if (!guard.auth.enabled) return guard.auth.authenticate(null)

    val fromCookie = guard.sessions?.verify(request.cookies[SessionManager.COOKIE_NAME])
    if (fromCookie != null && guard.auth.recognizes(fromCookie)) return fromCookie

    return guard.auth.authenticate(request.headers[HttpHeaders.Authorization])
}

/** Solo una dirección interna del propio sitio: evita que /login?next=https://otro-sitio nos use de redirección abierta. */
fun safeNextPath(raw: String?): String {
    val next = raw?.trim().orEmpty()
    val ok = next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") &&
        next.none { it == '\r' || it == '\n' } && !next.startsWith("/login") && !next.startsWith("/logout")
    return if (ok) next else "/"
}

/**
 * Protege una ruta del tablero. Devuelve false (ya respondió) cuando no hay acceso:
 * - 429 si el cliente está bloqueado por intentos fallidos;
 * - sin credenciales válidas: un navegador (GET que acepta HTML) va al formulario /login; una petición de htmx recibe
 *   401 con HX-Redirect a /login; el resto (curl, scripts) recibe 401 con el desafío HTTP Basic de siempre;
 * - 403 si la persona es de solo lectura y la ruta modifica datos ([mutating] = true);
 * - 403 si la ruta modifica datos y el Origin no es el propio (CSRF).
 */
suspend fun ApplicationCall.requireDashboardAuth(guard: DashboardGuard, mutating: Boolean = false): Boolean {
    val client = clientAddress(guard.trustProxy, guard.trustProxyHops)

    val wait = guard.throttle.retryAfterSeconds(client)
    if (wait > 0) {
        response.header(HttpHeaders.RetryAfter, wait.toString())
        respondText("Demasiados intentos fallidos. Intenta de nuevo en $wait segundos.", status = HttpStatusCode.TooManyRequests)
        return false
    }

    val principal = dashboardPrincipal(guard)
    if (principal == null) {
        val authorization = request.headers[HttpHeaders.Authorization]
        // Sin cabecera = primer desafío del navegador: no cuenta como intento fallido.
        if (authorization != null) guard.throttle.recordFailure(client)

        val esNavegador = request.httpMethod == HttpMethod.Get &&
            request.headers[HttpHeaders.Accept].orEmpty().contains("text/html", ignoreCase = true)
        when {
            guard.loginEnabled && esNavegador && request.headers["HX-Request"] == null -> {
                val destino = "/login?next=" + java.net.URLEncoder.encode(request.uri, Charsets.UTF_8)
                response.header(HttpHeaders.Location, destino)
                respond(HttpStatusCode.Found)
            }
            guard.loginEnabled && request.headers["HX-Request"] != null -> {
                response.header("HX-Redirect", "/login")
                respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
            }
            else -> {
                response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"Tienda Virtual Multicanal\", charset=\"UTF-8\"")
                respondText("Autenticación requerida", status = HttpStatusCode.Unauthorized)
            }
        }
        return false
    }

    if (mutating && principal.role != DashboardRole.ADMIN) {
        respondText("Tu usuario es de solo lectura.", status = HttpStatusCode.Forbidden)
        return false
    }

    if (mutating && !isSameOriginRequest(guard.trustProxy)) {
        respondText("Origen no permitido", status = HttpStatusCode.Forbidden)
        return false
    }
    return true
}
