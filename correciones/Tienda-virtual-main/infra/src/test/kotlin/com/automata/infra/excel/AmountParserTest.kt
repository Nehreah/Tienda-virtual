package com.automata.infra.excel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AmountParserTest {

    private fun assertValid(expected: Long, raw: String?) {
        assertEquals(AmountResult.Valid(expected), AmountParser.parse(raw), "entrada: '$raw'")
    }

    private fun invalidReason(raw: String?): String {
        val result = AmountParser.parse(raw)
        assertTrue(result is AmountResult.Invalid, "se esperaba rechazo para '$raw' pero fue $result")
        return (result as AmountResult.Invalid).reason
    }

    @Test
    fun `acepta los formatos de monto del plan`() {
        assertValid(120_000, "120000")
        assertValid(120_000, "120.000")
        assertValid(120_000, "120,000")
        assertValid(120_000, "120 000")
        assertValid(120_000, "\$120000")
        assertValid(120_000, "\$ 120.000")
        assertValid(120_000, "COP 120.000")
        assertValid(1_200_000, "1.200.000")
        assertValid(1_200_000, "1,200,000")
    }

    @Test
    fun `acepta espacios raros y parte decimal en cero`() {
        assertValid(120_000, "\u00A0120\u00A0000")
        assertValid(120_000, "120.000,00")
        assertValid(120_000, "120000.0")
        assertValid(0, "0")
    }

    @Test
    fun `rechaza vacios y Consultar sin convertirlos en cero`() {
        assertTrue(invalidReason(null).contains("vacío"))
        assertTrue(invalidReason("").contains("vacío"))
        assertTrue(invalidReason("   ").contains("vacío"))
        assertTrue(invalidReason("Consultar").contains("Consultar"))
        assertTrue(invalidReason("N/A").contains("no es un monto válido"))
        assertTrue(invalidReason("abc123").contains("no es un monto válido"))
    }

    @Test
    fun `rechaza negativos tambien cuando traen la marca de la defensa de formulas`() {
        assertTrue(invalidReason("-5000").contains("negativo"))
        assertTrue(invalidReason("'-5000").contains("negativo"))
    }

    @Test
    fun `rechaza decimales distintos de cero y separadores mezclados`() {
        assertTrue(invalidReason("12.50").contains("decimales"))
        assertTrue(invalidReason("1.200,000").contains("mezcla"))
    }

    @Test
    fun `rechaza valores demasiado grandes`() {
        assertTrue(invalidReason("99999999999999999999").contains("demasiado grande"))
        assertTrue(invalidReason("2.000.000.000.000").contains("demasiado grande"))
    }
}
