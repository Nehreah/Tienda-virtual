package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.SessionManager
import com.automata.app.dashboard.clientAddress
import com.automata.app.dashboard.dashboardPrincipal
import com.automata.app.dashboard.isSameOriginRequest
import com.automata.app.dashboard.safeNextPath
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.date.GMTDate

/** Tamaño máximo del formulario de login (usuario + clave + destino). Es una ruta pública: no se lee un cuerpo sin tope. */
private const val MAX_LOGIN_BYTES = 8 * 1024

/**
 * Inicio y cierre de sesión del tablero.
 *
 * - GET  /login   formulario. Si ya hay sesión válida, redirige al destino.
 * - POST /login   valida usuario y clave, cuenta los fallos en el mismo límite de intentos que HTTP Basic y, si son
 *                 correctos, deja una cookie de sesión firmada (HttpOnly, SameSite=Strict, Secure en producción).
 * - POST /logout  borra la cookie. Es POST (no GET) y exige Origin propio para que otra página no pueda cerrar la sesión.
 *
 * Si el tablero no tiene clave (APP_ENV=local sin DASHBOARD_PASSWORD) no hay nada que iniciar: redirigen a "/".
 */
fun Route.loginRoutes(guard: DashboardGuard) {

    get("/login") {
        if (!guard.loginEnabled) {
            call.respondRedirect("/")
            return@get
        }
        val next = safeNextPath(call.request.queryParameters["next"])
        if (call.dashboardPrincipal(guard) != null) {
            call.respondRedirect(next)
            return@get
        }
        call.paginaLogin(next, error = null)
    }

    post("/login") {
        val sessions = guard.sessions
        if (!guard.loginEnabled || sessions == null) {
            call.respondRedirect("/")
            return@post
        }

        val client = call.clientAddress(guard.trustProxy, guard.trustProxyHops)
        val wait = guard.throttle.retryAfterSeconds(client)
        if (wait > 0) {
            call.response.header(HttpHeaders.RetryAfter, wait.toString())
            call.paginaLogin("/", "Demasiados intentos fallidos. Intenta de nuevo en $wait segundos.", HttpStatusCode.TooManyRequests)
            return@post
        }

        if (!call.isSameOriginRequest(guard.trustProxy)) {
            call.respondText("Origen no permitido", status = HttpStatusCode.Forbidden)
            return@post
        }

        val bytes = call.receiveBytesLimited(MAX_LOGIN_BYTES)
        if (bytes == null) {
            call.respondText("Formulario demasiado grande", status = HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val form = parseQueryString(String(bytes, Charsets.UTF_8))
        val next = safeNextPath(form["next"])

        val principal = guard.auth.verify(form["usuario"].orEmpty(), form["clave"].orEmpty())
        if (principal == null) {
            guard.throttle.recordFailure(client)
            call.paginaLogin(next, "Usuario o clave incorrectos.", HttpStatusCode.Unauthorized)
            return@post
        }

        call.response.cookies.append(cookieDeSesion(sessions.issue(principal), sessions.ttlSeconds, guard.secureCookies))
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Location, next)
        call.respond(HttpStatusCode.SeeOther)
    }

    post("/logout") {
        if (!call.isSameOriginRequest(guard.trustProxy)) {
            call.respondText("Origen no permitido", status = HttpStatusCode.Forbidden)
            return@post
        }
        call.response.cookies.append(cookieBorrada(guard.secureCookies))
        call.respondRedirect("/login")
    }
}

private fun cookieDeSesion(valor: String, ttlSeconds: Long, secure: Boolean) = Cookie(
    name = SessionManager.COOKIE_NAME,
    value = valor,
    maxAge = ttlSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    path = "/",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict")
)

private fun cookieBorrada(secure: Boolean) = Cookie(
    name = SessionManager.COOKIE_NAME,
    value = "",
    maxAge = 0,
    expires = GMTDate.START,
    path = "/",
    secure = secure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict")
)

private suspend fun ApplicationCall.paginaLogin(next: String, error: String?, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(htmlLogin(next, error), ContentType.Text.Html, status)
}

private fun escape(texto: String): String = buildString(texto.length + 16) {
    for (c in texto) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(c)
    }
}

/** Página de login. Sin JavaScript (la política de seguridad solo permite scripts propios) y con los colores del tablero. */
internal fun htmlLogin(next: String, error: String?): String {
    val aviso = if (error != null) """<p class="error" role="alert">${escape(error)}</p>""" else ""
    return """<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex">
    <title>Ingresar · Tienda Virtual</title>
    <style>
        :root { color-scheme: dark light; }
        * { box-sizing: border-box; }
        body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 16px;
               font-family: Inter, "Segoe UI", system-ui, -apple-system, Roboto, Arial, sans-serif;
               background: #070b1a; color: #eef2ff; }
        main { width: 100%; max-width: 380px; background: #0f1730; border: 1px solid rgba(133,156,255,.16);
               border-radius: 16px; padding: 28px; box-shadow: 0 10px 28px rgba(0,0,0,.28); }
        h1 { margin: 0 0 4px; font-size: 1.3rem; }
        .sub { margin: 0 0 20px; color: #8190b0; font-size: .9rem; }
        label { display: block; margin: 14px 0 6px; font-size: .85rem; color: #b0bbd6; }
        input { width: 100%; padding: 11px 12px; border-radius: 10px; font-size: 1rem;
                border: 1px solid rgba(133,156,255,.28); background: #141e3d; color: inherit; }
        input:focus-visible { outline: 2px solid #7567ff; outline-offset: 2px; }
        button { width: 100%; margin-top: 22px; padding: 12px; border: 0; border-radius: 10px; cursor: pointer;
                 font-size: 1rem; font-weight: 700; color: #fff; background: linear-gradient(135deg, #7567ff, #9b5cff); }
        .error { margin: 0 0 6px; padding: 10px 12px; border-radius: 10px; font-size: .9rem;
                 background: rgba(255,102,133,.12); color: #ff8fa6; border: 1px solid rgba(255,102,133,.32); }
        @media (prefers-color-scheme: light) {
            body { background: #f3f5fb; color: #1b2240; }
            main { background: #fff; border-color: #dfe3f2; box-shadow: 0 10px 28px rgba(20,30,70,.08); }
            .sub { color: #5d6785; } label { color: #46506f; }
            input { background: #f7f8fd; border-color: #cfd5ea; }
            .error { background: #fff0f3; color: #b4233f; border-color: #f4c2cc; }
        }
    </style>
</head>
<body>
    <main>
        <h1>Tienda Virtual</h1>
        <p class="sub">Ingresa con tu usuario y clave del tablero.</p>
        $aviso
        <form method="post" action="/login">
            <input type="hidden" name="next" value="${escape(next)}">
            <label for="usuario">Usuario</label>
            <input id="usuario" name="usuario" type="text" autocomplete="username" autocapitalize="none" spellcheck="false" required autofocus>
            <label for="clave">Clave</label>
            <input id="clave" name="clave" type="password" autocomplete="current-password" required>
            <button type="submit">Entrar</button>
        </form>
    </main>
</body>
</html>"""
}
