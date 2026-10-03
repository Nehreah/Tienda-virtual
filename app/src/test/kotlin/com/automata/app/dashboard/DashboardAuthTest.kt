package com.automata.app.dashboard

import com.automata.infra.config.AppEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class DashboardAuthTest {

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    @Test
    fun `without a password the protection is off and everything passes`() {
        val auth = DashboardAuth("admin", "")
        assertFalse(auth.enabled)
        assertTrue(auth.isAuthorized(null))
    }

    @Test
    fun `correct credentials pass`() {
        val auth = DashboardAuth("admin", "s3creta:con:dos:puntos")
        assertTrue(auth.enabled)
        assertTrue(auth.isAuthorized(basic("admin", "s3creta:con:dos:puntos")))
    }

    @Test
    fun `wrong password, wrong user, missing or malformed header are rejected`() {
        val auth = DashboardAuth("admin", "s3creta")
        assertFalse(auth.isAuthorized(null))
        assertFalse(auth.isAuthorized(basic("admin", "otra")))
        assertFalse(auth.isAuthorized(basic("root", "s3creta")))
        assertFalse(auth.isAuthorized("Bearer abc"))
        assertFalse(auth.isAuthorized("Basic %%%no-es-base64%%%"))
        assertFalse(auth.isAuthorized("Basic " + Base64.getEncoder().encodeToString("sin-dos-puntos".toByteArray())))
    }

    // ---------- Fase 1.1: política de arranque (falla cerrado) ----------

    @Test
    fun `production without a password refuses to start`() {
        val e = assertThrows(IllegalStateException::class.java) {
            DashboardSecurity.plan(AppEnv.PRODUCTION, "admin", "")
        }
        assertTrue(e.message!!.contains("DASHBOARD_PASSWORD"))
    }

    @Test
    fun `production with a blank or too short password refuses to start`() {
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.plan(AppEnv.PRODUCTION, "admin", "            ") }
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.plan(AppEnv.PRODUCTION, "admin", "a".repeat(11)) }
    }

    @Test
    fun `production without a user refuses to start`() {
        assertThrows(IllegalStateException::class.java) { DashboardSecurity.plan(AppEnv.PRODUCTION, "", "a".repeat(20)) }
    }

    @Test
    fun `production with a 12 character password starts, protected and open to the network`() {
        val plan = DashboardSecurity.plan(AppEnv.PRODUCTION, "admin", "a".repeat(12))
        assertTrue(plan.auth.enabled)
        assertEquals(DashboardSecurity.ALL_INTERFACES_HOST, plan.host)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `local without a password listens only on loopback and warns`() {
        val plan = DashboardSecurity.plan(AppEnv.LOCAL, "admin", "")
        assertEquals("127.0.0.1", plan.host)
        assertFalse(plan.auth.enabled)
        assertTrue(plan.warnings.isNotEmpty())
    }

    @Test
    fun `local with a password also listens only on loopback`() {
        val plan = DashboardSecurity.plan(AppEnv.LOCAL, "admin", "a".repeat(20))
        assertEquals("127.0.0.1", plan.host)
        assertTrue(plan.auth.enabled)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `local with a short password starts but warns`() {
        val plan = DashboardSecurity.plan(AppEnv.LOCAL, "admin", "corta")
        assertTrue(plan.auth.enabled)
        assertTrue(plan.warnings.isNotEmpty())
    }
}
