package com.automata.app.routes

import com.automata.app.dashboard.AuthThrottle
import com.automata.app.dashboard.DashboardAuth
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.http.HttpHeaders
import java.util.Base64

/** Soporte común de las pruebas de rutas: se levantan SIN red, SIN base de datos y SIN arrancar Netty (testApplication). */

internal const val ADMIN_USER = "admin"
internal const val ADMIN_PASS = "clave-admin-larga-123"
internal const val VIEWER_USER = "socio"
internal const val VIEWER_PASS = "clave-socio-larga-456"
internal const val HOST_PROPIO = "tienda.test"

internal fun basic(user: String, pass: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

/**
 * Guardia de pruebas con un administrador y (opcional) un usuario de solo lectura.
 * trustProxy = true: así las pruebas controlan la IP (X-Forwarded-For) y el host (X-Forwarded-Host) sin depender
 * de cómo el motor de pruebas arma la cabecera Host.
 */
internal fun guardiaDePrueba(
    conViewer: Boolean = true,
    sesiones: SessionManager? = SessionManager(),
    throttle: AuthThrottle = AuthThrottle()
): DashboardGuard {
    val auth = DashboardAuth(ADMIN_USER, ADMIN_PASS).let { if (conViewer) it.withViewer(VIEWER_USER, VIEWER_PASS) else it }
    return DashboardGuard(auth = auth, throttle = throttle, trustProxy = true, trustProxyHops = 1, sessions = sesiones)
}

/** Cabeceras de una petición hecha desde una página del propio sitio (lo que envía un navegador en un POST legítimo). */
internal fun HttpRequestBuilder.desdeElSitio(ip: String = "10.0.0.1") {
    header("X-Forwarded-Host", HOST_PROPIO)
    header("X-Forwarded-For", ip)
    header(HttpHeaders.Origin, "https://$HOST_PROPIO")
}

/** Solo identifica la IP del cliente (para GET, que no necesitan Origin). */
internal fun HttpRequestBuilder.desde(ip: String = "10.0.0.1") {
    header("X-Forwarded-For", ip)
}

internal fun HttpRequestBuilder.comoAdmin() = header(HttpHeaders.Authorization, basic(ADMIN_USER, ADMIN_PASS))

internal fun HttpRequestBuilder.comoViewer() = header(HttpHeaders.Authorization, basic(VIEWER_USER, VIEWER_PASS))

/** Monta las rutas bajo prueba con JSON (ContentNegotiation) como en la aplicación real. */
internal fun ApplicationTestBuilder.rutas(bloque: Route.() -> Unit) {
    application {
        install(ContentNegotiation) { json() }
        routing { bloque() }
    }
}

/** Cliente que NO sigue redirecciones: así las pruebas ven el 302/303 y su Location. */
internal fun ApplicationTestBuilder.clienteSinRedirecciones(): HttpClient = createClient { followRedirects = false }
