package com.automata.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SignedCopTest {

    @Test
    fun `SignedCop acepta negativos`() {
        val perdida = SignedCop(-200)
        assertTrue(perdida.isNegative)
        assertEquals(-200L, perdida.amount)
    }

    @Test
    fun `Money sigue rechazando negativos`() {
        assertThrows(IllegalArgumentException::class.java) { Money(-1) }
    }

    @Test
    fun `operaciones aritmeticas`() {
        assertEquals(SignedCop(50), SignedCop(100) - SignedCop(50))
        assertEquals(SignedCop(-30), SignedCop(-10) + SignedCop(-20))
        assertEquals(SignedCop(7), -SignedCop(-7))
    }

    @Test
    fun `formato con signo y separador de miles`() {
        assertEquals("\$1.234.567", SignedCop(1_234_567).format())
        assertEquals("-\$1.234.567", SignedCop(-1_234_567).format())
        assertEquals("\$0", SignedCop.ZERO.format())
    }

    @Test
    fun `from Money conserva el valor`() {
        assertEquals(SignedCop(900_000), SignedCop.from(Money.of(900_000)))
    }
}
