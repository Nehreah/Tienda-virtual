package com.automata.app.dashboard

import org.junit.jupiter.api.Assertions.assertFalse
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
}
