package com.automata.app.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.SQLException

/** respondError: dato inválido, regla de negocio y error interno no se confunden, y el interno nunca filtra detalles. */
class RespondErrorTest {

    private val log = LoggerFactory.getLogger("RespondErrorTest")

    private fun io.ktor.server.testing.ApplicationTestBuilder.montar() = rutas {
        get("/dato") { call.respondError(log, "hacer algo", IllegalArgumentException("Dato malo")) }
        get("/regla") { call.respondError(log, "hacer algo", IllegalStateException("Regla violada")) }
        get("/duplicado") { call.respondError(log, "hacer algo", SQLException("clave duplicada", "23505")) }
        get("/trigger") { call.respondError(log, "hacer algo", SQLException("ERROR: Artículo vendido\n  Where: función", "P0001")) }
        get("/caida") { call.respondError(log, "hacer algo", SQLException("Connection to db.interno:5432 refused", "08001")) }
        get("/bug") { call.respondError(log, "hacer algo", RuntimeException("secreto interno xyz")) }
    }

    @Test
    fun `dato invalido es 400 con su mensaje`() = testApplication {
        montar()
        val r = client.get("/dato")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("Dato malo"))
    }

    @Test
    fun `regla de negocio es 409 con su mensaje`() = testApplication {
        montar()
        val r = client.get("/regla")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("Regla violada"))
    }

    @Test
    fun `restriccion de la base y RAISE EXCEPTION son 409`() = testApplication {
        montar()
        assertEquals(HttpStatusCode.Conflict, client.get("/duplicado").status)
        val trigger = client.get("/trigger")
        assertEquals(HttpStatusCode.Conflict, trigger.status)
        val cuerpo = trigger.bodyAsText()
        assertTrue(cuerpo.contains("vendido"), cuerpo)
        assertFalse(cuerpo.contains("Where"), "solo la primera línea del mensaje: $cuerpo")
    }

    @Test
    fun `una caida de la base es 500 sin filtrar el host`() = testApplication {
        montar()
        val r = client.get("/caida")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        assertFalse(r.bodyAsText().contains("db.interno"))
    }

    @Test
    fun `un error inesperado es 500 generico con codigo y sin el mensaje interno`() = testApplication {
        montar()
        val r = client.get("/bug")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        val cuerpo = r.bodyAsText()
        assertTrue(cuerpo.contains("No se pudo hacer algo"), cuerpo)
        assertFalse(cuerpo.contains("secreto interno"), cuerpo)
    }
}
