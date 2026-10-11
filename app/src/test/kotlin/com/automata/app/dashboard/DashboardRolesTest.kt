package com.automata.app.dashboard

import com.automata.infra.config.AppEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class DashboardRolesTest {

    private fun basic(u: String, p: String) = "Basic " + Base64.getEncoder().encodeToString("$u:$p".toByteArray())
    private val auth = DashboardAuth("admin", "clave-admin").withViewer("socio", "clave-socio")

    @Test
    fun `cada usuario recibe su rol`() {
        assertEquals(DashboardPrincipal("admin", DashboardRole.ADMIN), auth.verify("admin", "clave-admin"))
        assertEquals(DashboardPrincipal("socio", DashboardRole.LECTURA), auth.verify("socio", "clave-socio"))
    }

    @Test
    fun `mezclar usuario y clave de distintos roles no entra`() {
        assertNull(auth.verify("admin", "clave-socio"))
        assertNull(auth.verify("socio", "clave-admin"))
        assertNull(auth.verify("otro", "clave-admin"))
    }

    @Test
    fun `Basic devuelve el rol correcto`() {
        assertEquals(DashboardRole.LECTURA, auth.authenticate(basic("socio", "clave-socio"))?.role)
        assertEquals(DashboardRole.ADMIN, auth.authenticate(basic("admin", "clave-admin"))?.role)
        assertNull(auth.authenticate(basic("socio", "mala")))
    }

    @Test
    fun `sin usuario de lectura configurado nadie entra como lectura`() {
        val soloAdmin = DashboardAuth("admin", "clave-admin")
        assertFalse(soloAdmin.hasViewer)
        assertNull(soloAdmin.verify("socio", "clave-socio"))
    }

    @Test
    fun `sin clave configurada todos son administradores locales`() {
        val abierta = DashboardAuth("admin", "")
        assertEquals(DashboardRole.ADMIN, abierta.authenticate(null)?.role)
        assertFalse(abierta.hasViewer)
    }

    @Test
    fun `recognizes invalida usuarios retirados`() {
        assertTrue(auth.recognizes(DashboardPrincipal("admin", DashboardRole.ADMIN)))
        assertTrue(auth.recognizes(DashboardPrincipal("socio", DashboardRole.LECTURA)))
        assertFalse(auth.recognizes(DashboardPrincipal("socio", DashboardRole.ADMIN)))
        assertFalse(DashboardAuth("admin", "x").recognizes(DashboardPrincipal("socio", DashboardRole.LECTURA)))
    }

    // ---------- configuración del usuario de lectura ----------

    @Test
    fun `sin variables de lectura no hay usuario de lectura`() {
        assertNull(DashboardSecurity.viewerCredentials(AppEnv.PRODUCTION, "admin", "", ""))
    }

    @Test
    fun `usuario sin clave o clave sin usuario es error de configuracion`() {
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.viewerCredentials(AppEnv.LOCAL, "admin", "socio", "") }
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.viewerCredentials(AppEnv.LOCAL, "admin", "", "clave") }
    }

    @Test
    fun `el usuario de lectura debe ser distinto del administrador`() {
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.viewerCredentials(AppEnv.LOCAL, "admin", "admin", "x".repeat(20)) }
    }

    @Test
    fun `en produccion la clave de lectura exige 12 caracteres`() {
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.viewerCredentials(AppEnv.PRODUCTION, "admin", "socio", "x".repeat(11)) }
        assertEquals("socio" to "x".repeat(12), DashboardSecurity.viewerCredentials(AppEnv.PRODUCTION, "admin", "socio", "x".repeat(12)))
        assertEquals("socio" to "corta", DashboardSecurity.viewerCredentials(AppEnv.LOCAL, "admin", "socio", "corta"))
    }

    // ---------- destino tras el login ----------

    @Test
    fun `safeNextPath solo acepta rutas internas`() {
        assertEquals("/ventas", safeNextPath("/ventas"))
        assertEquals("/api/socios?archivados=true", safeNextPath("/api/socios?archivados=true"))
        listOf(null, "", "  ", "https://malo.example", "//malo.example", "/\\malo.example", "ventas", "/login", "/logout",
            "/ok\r\nSet-Cookie: a=b").forEach { assertEquals("/", safeNextPath(it), "debería rechazar '$it'") }
    }
}
