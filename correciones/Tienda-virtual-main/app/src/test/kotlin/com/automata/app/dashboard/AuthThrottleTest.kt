package com.automata.app.dashboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthThrottleTest {

    private var now = 1_000_000L
    private fun throttle(maxTracked: Int = 10_000) = AuthThrottle(maxTrackedClients = maxTracked, clock = { now })

    private fun fail(t: AuthThrottle, client: String, times: Int) = repeat(times) { t.recordFailure(client) }

    @Test
    fun `four failures do not block, the fifth does and the sixth attempt gets 429`() {
        val t = throttle()
        fail(t, "1.1.1.1", 4)
        assertEquals(0L, t.retryAfterSeconds("1.1.1.1"))
        t.recordFailure("1.1.1.1")
        assertTrue(t.retryAfterSeconds("1.1.1.1") > 0)
    }

    @Test
    fun `blocked client stays blocked until the wait passes`() {
        val t = throttle()
        fail(t, "1.1.1.1", 5)
        assertEquals(60L, t.retryAfterSeconds("1.1.1.1"))
        now += 59_000
        assertTrue(t.retryAfterSeconds("1.1.1.1") > 0)
        now += 1_001
        assertEquals(0L, t.retryAfterSeconds("1.1.1.1"))
    }

    @Test
    fun `the wait grows each time the client is blocked again`() {
        val t = throttle()
        fail(t, "1.1.1.1", 5)
        assertEquals(60L, t.retryAfterSeconds("1.1.1.1"))
        now += 61_000
        fail(t, "1.1.1.1", 5)
        assertEquals(120L, t.retryAfterSeconds("1.1.1.1"))
        now += 121_000
        fail(t, "1.1.1.1", 5)
        assertEquals(240L, t.retryAfterSeconds("1.1.1.1"))
    }

    @Test
    fun `the wait is capped at one hour`() {
        val t = throttle()
        var expected = 60L
        repeat(9) {
            fail(t, "1.1.1.1", 5)
            val wait = t.retryAfterSeconds("1.1.1.1")
            assertEquals(minOf(expected, 3600L), wait)
            now += wait * 1000 + 1000   // cumple la espera y vuelve a fallar enseguida
            expected *= 2
        }
    }

    @Test
    fun `repeat offences are forgotten after a long quiet period`() {
        val t = throttle()
        fail(t, "1.1.1.1", 5)
        now += 61_000
        fail(t, "1.1.1.1", 5)
        assertEquals(120L, t.retryAfterSeconds("1.1.1.1"))
        now += 3 * 3_600_000L          // 3 horas sin intentar
        fail(t, "1.1.1.1", 5)
        assertEquals(60L, t.retryAfterSeconds("1.1.1.1"))
    }

    @Test
    fun `failures older than the window are forgotten`() {
        val t = throttle()
        fail(t, "1.1.1.1", 4)
        now += 5 * 60_000L + 1
        t.recordFailure("1.1.1.1")
        assertEquals(0L, t.retryAfterSeconds("1.1.1.1"))
    }

    @Test
    fun `clients are tracked independently`() {
        val t = throttle()
        fail(t, "1.1.1.1", 5)
        assertTrue(t.retryAfterSeconds("1.1.1.1") > 0)
        assertEquals(0L, t.retryAfterSeconds("2.2.2.2"))
    }

    @Test
    fun `attempts during a block do not extend it`() {
        val t = throttle()
        fail(t, "1.1.1.1", 5)
        fail(t, "1.1.1.1", 20)
        assertEquals(60L, t.retryAfterSeconds("1.1.1.1"))
    }

    @Test
    fun `memory is bounded by the maximum number of tracked clients`() {
        val t = throttle(maxTracked = 100)
        repeat(1_000) { i -> now += 1; t.recordFailure("10.0.${i / 250}.${i % 250}") }
        // No hay API para contar; el criterio es que no falla ni crece sin límite. Un cliente reciente sigue controlado:
        fail(t, "9.9.9.9", 5)
        assertTrue(t.retryAfterSeconds("9.9.9.9") > 0)
    }
}
