package com.automata.app.routes

import com.automata.app.dashboard.AuthThrottle
import com.automata.app.dashboard.DashboardAuth
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.DashboardPrincipal
import com.automata.app.dashboard.DashboardRole
import com.automata.app.dashboard.SessionManager
import com.automata.commerce.service.ClienteService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Acceso al tablero de punta a punta: login por formulario, cookie de sesión, HTTP Basic, roles y límite de intentos,
 * sobre una ruta real (/api/clientes) y las rutas /login y /logout reales.
 */
class DashboardAccessTest {

    private fun ApplicationTestBuilder.montar(guard: DashboardGuard) =
        rutas {
            loginRoutes(guard)
            clientesRoutes(guard, ClienteService())
        }

    private suspend fun HttpClient.entrar(usuario: String, clave: String, next: String? = null): HttpResponse =
        submitForm(
            url = "/login",
            formParameters = parameters {
                append("usuario", usuario)
                append("clave", clave)
                if (next != null) append("next", next)
            }
        ) { desdeElSitio() }

    /** "tv_session=VALOR" tal como lo reenviaría el navegador, o null si la respuesta no dejó cookie de sesión. */
    private fun HttpResponse.cookieDeSesion(): String? =
        headers.getAll(HttpHeaders.SetCookie)
            ?.firstOrNull { it.startsWith("${SessionManager.COOKIE_NAME}=") }
            ?.substringBefore(';')

    // ---------------- sin sesión ----------------

    @Test
    fun `sin credenciales una API responde 401 con el desafio Basic`() = testApplication {
        montar(guardiaDePrueba())
        val r = client.get("/api/clientes")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(r.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Basic"))
    }

    @Test
    fun `un navegador sin sesion es enviado al formulario de login con su destino`() = testApplication {
        montar(guardiaDePrueba())
        val r = clienteSinRedirecciones().get("/api/clientes") { header(HttpHeaders.Accept, "text/html,application/xhtml+xml") }
        assertEquals(HttpStatusCode.Found, r.status)
        assertTrue(r.headers[HttpHeaders.Location].orEmpty().startsWith("/login?next="))
    }

    @Test
    fun `una peticion de htmx sin sesion recibe 401 con HX-Redirect al login`() = testApplication {
        montar(guardiaDePrueba())
        val r = client.get("/api/clientes") {
            header("HX-Request", "true")
            header(HttpHeaders.Accept, "text/html")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("/login", r.headers["HX-Redirect"])
    }

    // ---------------- HTTP Basic sigue funcionando ----------------

    @Test
    fun `HTTP Basic del administrador y del usuario de lectura pasan`() = testApplication {
        montar(guardiaDePrueba())
        assertEquals(HttpStatusCode.OK, client.get("/api/clientes") { comoAdmin() }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/clientes") { comoViewer() }.status)
    }

    @Test
    fun `una clave de Basic incorrecta es 401`() = testApplication {
        montar(guardiaDePrueba())
        val r = client.get("/api/clientes") { header(HttpHeaders.Authorization, basic(ADMIN_USER, "otra-clave")) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ---------------- login por formulario y cookie ----------------

    @Test
    fun `un login correcto deja una cookie firmada HttpOnly SameSite Strict y la cookie abre el acceso`() = testApplication {
        montar(guardiaDePrueba())
        val c = clienteSinRedirecciones()

        val login = c.entrar(ADMIN_USER, ADMIN_PASS, next = "/api/clientes")
        assertEquals(HttpStatusCode.SeeOther, login.status)
        assertEquals("/api/clientes", login.headers[HttpHeaders.Location])

        val setCookie = login.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith(SessionManager.COOKIE_NAME) }
        assertTrue(setCookie.contains("HttpOnly", ignoreCase = true), setCookie)
        assertTrue(setCookie.contains("SameSite=Strict", ignoreCase = true), setCookie)
        assertTrue(setCookie.contains("Path=/"), setCookie)

        val cookie = checkNotNull(login.cookieDeSesion()) { "el login no dejó cookie de sesión" }
        val r = c.get("/api/clientes") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `un login incorrecto es 401 y no deja cookie`() = testApplication {
        montar(guardiaDePrueba())
        val r = clienteSinRedirecciones().entrar(ADMIN_USER, "mala")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertNull(r.cookieDeSesion())
        assertTrue(r.bodyAsText().contains("incorrectos"))
    }

    @Test
    fun `el login ignora un destino externo y vuelve al inicio`() = testApplication {
        montar(guardiaDePrueba())
        val c = clienteSinRedirecciones()
        assertEquals("/", c.entrar(ADMIN_USER, ADMIN_PASS, next = "https://sitio-malo.example/").headers[HttpHeaders.Location])
        assertEquals("/", c.entrar(ADMIN_USER, ADMIN_PASS, next = "//sitio-malo.example").headers[HttpHeaders.Location])
    }

    @Test
    fun `el login desde otro origen es rechazado`() = testApplication {
        montar(guardiaDePrueba())
        val r = clienteSinRedirecciones().submitForm(
            url = "/login",
            formParameters = parameters { append("usuario", ADMIN_USER); append("clave", ADMIN_PASS) }
        ) {
            header("X-Forwarded-Host", HOST_PROPIO)
            header(HttpHeaders.Origin, "https://pagina-maliciosa.example")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertNull(r.cookieDeSesion())
    }

    @Test
    fun `una cookie manipulada no sirve`() = testApplication {
        montar(guardiaDePrueba())
        val c = clienteSinRedirecciones()
        val cookie = checkNotNull(c.entrar(VIEWER_USER, VIEWER_PASS).cookieDeSesion()) { "el login no dejó cookie de sesión" }
        // Se cambia un carácter del medio de la firma (el último solo lleva bits de relleno y podría no alterar nada).
        val chars = cookie.substringAfter('=').toCharArray()
        val i = chars.size - 10
        chars[i] = if (chars[i] == 'A') 'B' else 'A'
        val adulterada = "${SessionManager.COOKIE_NAME}=${String(chars)}"

        val r = c.get("/api/clientes") { header(HttpHeaders.Cookie, adulterada) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `una sesion de un usuario que ya no existe en la configuracion deja de valer`() = testApplication {
        val sesiones = SessionManager()
        // La cookie se emitió para "exsocio", que no está en la configuración actual.
        val cookie = "${SessionManager.COOKIE_NAME}=" + sesiones.issue(DashboardPrincipal("exsocio", DashboardRole.LECTURA))
        montar(guardiaDePrueba(conViewer = false, sesiones = sesiones))

        val r = client.get("/api/clientes") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `el cierre de sesion borra la cookie y vuelve al login`() = testApplication {
        montar(guardiaDePrueba())
        val r = clienteSinRedirecciones().post("/logout") { desdeElSitio() }
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/login", r.headers[HttpHeaders.Location])
        val borrada = r.headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith(SessionManager.COOKIE_NAME) }
        assertTrue(borrada.startsWith("${SessionManager.COOKIE_NAME}="), borrada)
        assertTrue(borrada.contains("1970"), "debe vencer en el pasado: $borrada")
    }

    @Test
    fun `el cierre de sesion exige origen propio`() = testApplication {
        montar(guardiaDePrueba())
        val r = clienteSinRedirecciones().post("/logout") { header("X-Forwarded-Host", HOST_PROPIO) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `sin clave configurada no hay formulario y el login redirige al inicio`() = testApplication {
        val abierta = DashboardGuard(
            auth = DashboardAuth(ADMIN_USER, ""), throttle = AuthThrottle(), trustProxy = true, sessions = null
        )
        montar(abierta)
        val r = clienteSinRedirecciones().get("/login")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/", r.headers[HttpHeaders.Location])
    }

    // ---------------- roles ----------------

    @Test
    fun `el usuario de solo lectura consulta pero no modifica`() = testApplication {
        montar(guardiaDePrueba())
        val r = client.post("/api/clientes/cualquiera/anonimizar") { comoViewer(); desdeElSitio() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("solo lectura"))
    }

    @Test
    fun `una sesion de solo lectura tampoco puede modificar`() = testApplication {
        montar(guardiaDePrueba())
        val c = clienteSinRedirecciones()
        val cookie = checkNotNull(c.entrar(VIEWER_USER, VIEWER_PASS).cookieDeSesion()) { "el login no dejó cookie de sesión" }

        assertEquals(HttpStatusCode.OK, c.get("/api/clientes") { header(HttpHeaders.Cookie, cookie) }.status)
        val r = c.post("/api/clientes/x/anonimizar") { header(HttpHeaders.Cookie, cookie); desdeElSitio() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `el administrador sin Origin propio es rechazado y con Origin llega a la logica`() = testApplication {
        montar(guardiaDePrueba())
        val sinOrigen = client.post("/api/clientes/x/anonimizar") { comoAdmin(); desde() }
        assertEquals(HttpStatusCode.Forbidden, sinOrigen.status)

        // Con Origin propio pasa la guardia y responde la lógica del dominio: el cliente "x" no existe.
        val conOrigen = client.post("/api/clientes/x/anonimizar") { comoAdmin(); desdeElSitio() }
        assertEquals(HttpStatusCode.NotFound, conOrigen.status)
    }

    // ---------------- límite de intentos ----------------

    @Test
    fun `cinco logins fallidos bloquean al cliente incluso con la clave correcta`() = testApplication {
        montar(guardiaDePrueba())
        val c = clienteSinRedirecciones()
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, c.entrar(ADMIN_USER, "mala-$it").status) }

        val r = c.entrar(ADMIN_USER, ADMIN_PASS)
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertNotNull(r.headers[HttpHeaders.RetryAfter])
        assertNull(r.cookieDeSesion())
    }

    @Test
    fun `el bloqueo es por IP y no afecta a otros clientes`() = testApplication {
        montar(guardiaDePrueba())
        val c = clienteSinRedirecciones()
        repeat(5) { c.submitForm("/login", parameters { append("usuario", ADMIN_USER); append("clave", "mala") }) { desdeElSitio("10.0.0.9") } }

        val otro = c.submitForm("/login", parameters { append("usuario", ADMIN_USER); append("clave", ADMIN_PASS) }) { desdeElSitio("10.0.0.10") }
        assertEquals(HttpStatusCode.SeeOther, otro.status)
    }

    @Test
    fun `cinco claves Basic incorrectas tambien bloquean`() = testApplication {
        montar(guardiaDePrueba())
        repeat(5) {
            client.get("/api/clientes") { header(HttpHeaders.Authorization, basic(ADMIN_USER, "mala-$it")); desde("10.0.0.20") }
        }
        val r = client.get("/api/clientes") { comoAdmin(); desde("10.0.0.20") }
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
    }
}
