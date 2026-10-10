package com.automata.app.dashboard

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OriginGuardTest {

    @Test
    fun `same origin is accepted`() {
        assertTrue(OriginGuard.isSameOrigin("http://localhost:8080", null, "localhost:8080"))
        assertTrue(OriginGuard.isSameOrigin("https://tienda.com", null, "tienda.com"))
        assertTrue(OriginGuard.isSameOrigin("HTTPS://Tienda.COM", null, "tienda.com"))
    }

    @Test
    fun `foreign origin is rejected`() {
        assertFalse(OriginGuard.isSameOrigin("https://otro.com", null, "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("http://localhost:9999", null, "localhost:8080"))
        assertFalse(OriginGuard.isSameOrigin("https://tienda.com.evil.com", null, "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("https://evil.com@tienda.com", null, "tienda.com"))
    }

    @Test
    fun `origin null or garbage is rejected and does not fall back to referer`() {
        assertFalse(OriginGuard.isSameOrigin("null", "https://tienda.com/panel", "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("javascript:alert(1)", null, "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("tienda.com", null, "tienda.com"))
    }

    @Test
    fun `referer is used only when origin is missing`() {
        assertTrue(OriginGuard.isSameOrigin(null, "https://tienda.com/panel?x=1", "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin(null, "https://otro.com/tienda.com", "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("", "https://otro.com/", "tienda.com"))
    }

    @Test
    fun `missing origin and referer or missing host is rejected`() {
        assertFalse(OriginGuard.isSameOrigin(null, null, "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("", "  ", "tienda.com"))
        assertFalse(OriginGuard.isSameOrigin("https://tienda.com", null, null))
        assertFalse(OriginGuard.isSameOrigin("https://tienda.com", null, ""))
    }

    @Test
    fun `default ports are equivalent to no port`() {
        assertTrue(OriginGuard.isSameOrigin("https://tienda.com", null, "tienda.com:443"))
        assertTrue(OriginGuard.isSameOrigin("https://tienda.com:443", null, "tienda.com"))
        assertTrue(OriginGuard.isSameOrigin("http://tienda.com", null, "tienda.com:80"))
        assertFalse(OriginGuard.isSameOrigin("https://tienda.com:8443", null, "tienda.com"))
    }

    @Test
    fun `ipv6 hosts are compared with their port`() {
        assertTrue(OriginGuard.isSameOrigin("http://[::1]:8080", null, "[::1]:8080"))
        assertFalse(OriginGuard.isSameOrigin("http://[::1]:8081", null, "[::1]:8080"))
    }

    @Test
    fun `host names with underscores are supported`() {
        assertTrue(OriginGuard.isSameOrigin("http://tienda_app:8080", null, "tienda_app:8080"))
    }
}
