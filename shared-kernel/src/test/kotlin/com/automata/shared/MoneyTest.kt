package com.automata.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoneyTest {

    @Test
    fun `minus devuelve la diferencia cuando no es negativa`() {
        assertEquals(Money.of(70_000), Money.of(100_000) - Money.of(30_000))
    }

    @Test
    fun `minus con resultado negativo lanza un error claro que apunta a signedDiff`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Money.of(30_000) - Money.of(100_000)
        }
        assertTrue(error.message?.contains("signedDiff") == true)
    }

    @Test
    fun `signedDiff permite resultados negativos`() {
        assertEquals(SignedCop(-70_000), Money.of(30_000).signedDiff(Money.of(100_000)))
        assertEquals(SignedCop(70_000), Money.of(100_000).signedDiff(Money.of(30_000)))
    }

    @Test
    fun `formatCop usa punto como separador de miles`() {
        assertEquals("\$1.234.567", Money.of(1_234_567).formatCop())
        assertEquals("\$0", Money.ZERO.formatCop())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `el nombre antiguo formatFormattedCop sigue funcionando y da lo mismo`() {
        assertEquals(Money.of(2_500_000).formatCop(), Money.of(2_500_000).formatFormattedCop())
    }
}
