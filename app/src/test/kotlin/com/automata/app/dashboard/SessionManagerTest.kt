package com.automata.app.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

class SessionManagerTest {

    private var ahora = 1_000_000L
    private fun gestor(secreto: Byte = 1, ttl: Long = 60) =
        SessionManager(secret = ByteArray(32) { secreto }, ttlSeconds = ttl, clock = { ahora })

    private val admin = DashboardPrincipal("admin", DashboardRole.ADMIN)
    private val socio = DashboardPrincipal("socio", DashboardRole.LECTURA)

    @Test
    fun `una cookie emitida se verifica y conserva usuario y rol`() {
        val m = gestor()
        assertEquals(admin, m.verify(m.issue(admin)))
        assertEquals(socio, m.verify(m.issue(socio)))
    }

    @Test
    fun `vence exactamente al cumplirse el tiempo`() {
        val m = gestor(ttl = 60)
        val token = m.issue(admin)
        ahora += 59
        assertEquals(admin, m.verify(token))
        ahora += 1
        assertNull(m.verify(token))
    }

    @Test
    fun `otro secreto no acepta la cookie`() {
        assertNull(gestor(secreto = 2).verify(gestor(secreto = 1).issue(admin)))
    }

    @Test
    fun `cambiar el rol de LECTURA a ADMIN rompe la firma`() {
        val m = gestor()
        val (payload, firma) = m.issue(socio).split('.')
        val original = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        val falsificado = original.replaceFirst("LECTURA", "ADMIN")
        assertNotEquals(original, falsificado)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(falsificado.toByteArray()) + "." + firma
        assertNull(m.verify(token))
    }

    @Test
    fun `alargar la fecha de vencimiento rompe la firma`() {
        val m = gestor(ttl = 60)
        val (payload, firma) = m.issue(admin).split('.')
        val partes = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8).split('\n', limit = 3)
        val alargado = "${partes[0]}\n${partes[1].toLong() + 99999}\n${partes[2]}"
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(alargado.toByteArray()) + "." + firma
        assertNull(m.verify(token))
    }

    @Test
    fun `basura y valores vacios se rechazan sin lanzar excepcion`() {
        val m = gestor()
        listOf(null, "", " ", "abc", ".", "a.", ".b", "%%%.%%%", "a.b.c", "x".repeat(5000)).forEach {
            assertNull(m.verify(it), "debería rechazar '${it?.take(20)}'")
        }
    }

    @Test
    fun `un usuario con saltos de linea o puntos se conserva intacto`() {
        val m = gestor()
        val raro = DashboardPrincipal("a.b\nc|d", DashboardRole.LECTURA)
        assertEquals(raro, m.verify(m.issue(raro)))
    }

    @Test
    fun `fromSecret acepta cualquier largo y es determinista`() {
        val a = SessionManager.fromSecret("corto")
        val b = SessionManager.fromSecret("corto")
        assertEquals(admin, b.verify(a.issue(admin)))
        assertNull(SessionManager.fromSecret("otro").verify(a.issue(admin)))
    }
}
