package com.automata.app.routes

import com.automata.commerce.ports.InMemorySocioRepository
import com.automata.commerce.service.SocioService
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SociosRoutesTest {

    private fun io.ktor.server.testing.ApplicationTestBuilder.montar(repo: InMemorySocioRepository, guard: com.automata.app.dashboard.DashboardGuard) =
        rutas { sociosRoutes(guard, SocioService(repo), repo) }

    @Test
    fun `sin credenciales la lista de socios es 401`() = testApplication {
        montar(InMemorySocioRepository(), guardiaDePrueba())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/socios").status)
    }

    @Test
    fun `el usuario de lectura ve la lista de socios`() = testApplication {
        montar(InMemorySocioRepository(), guardiaDePrueba())
        assertEquals(HttpStatusCode.OK, client.get("/api/socios") { comoViewer() }.status)
    }

    @Test
    fun `el administrador crea un socio`() = testApplication {
        val repo = InMemorySocioRepository()
        montar(repo, guardiaDePrueba())
        val r = client.submitForm("/api/socios", parameters { append("nombre", "Ana") }) { comoAdmin(); desdeElSitio() }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(listOf("Ana"), runBlocking { repo.listar(false) }.map { it.nombre })
    }

    @Test
    fun `un nombre repetido es 400 y no crea otro socio`() = testApplication {
        val repo = InMemorySocioRepository()
        montar(repo, guardiaDePrueba())
        repeat(2) { client.submitForm("/api/socios", parameters { append("nombre", "Ana") }) { comoAdmin(); desdeElSitio() } }
        val r = client.submitForm("/api/socios", parameters { append("nombre", "ana") }) { comoAdmin(); desdeElSitio() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(1, runBlocking { repo.listar(false) }.size)
    }

    @Test
    fun `un nombre vacio es 400`() = testApplication {
        montar(InMemorySocioRepository(), guardiaDePrueba())
        val r = client.submitForm("/api/socios", parameters { append("nombre", "  ") }) { comoAdmin(); desdeElSitio() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `el usuario de lectura no puede crear socios`() = testApplication {
        val repo = InMemorySocioRepository()
        montar(repo, guardiaDePrueba())
        val r = client.submitForm("/api/socios", parameters { append("nombre", "Ana") }) { comoViewer(); desdeElSitio() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, runBlocking { repo.listar(false) }.size)
    }

    @Test
    fun `crear un socio sin Origin propio es 403`() = testApplication {
        val repo = InMemorySocioRepository()
        montar(repo, guardiaDePrueba())
        val r = client.submitForm("/api/socios", parameters { append("nombre", "Ana") }) { comoAdmin(); desde() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(0, runBlocking { repo.listar(false) }.size)
    }
}
