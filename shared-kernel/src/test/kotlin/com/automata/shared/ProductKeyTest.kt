package com.automata.shared

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProductKeyTest {

    @Test
    fun `claves habituales son validas`() {
        listOf("PROD-001", "A-0412", "SKU_1.2", "teclado-002", "Ñandú-7", "7", "A").forEach {
            assertNull(ProductKey.validar(it), "debería aceptar $it")
        }
    }

    @Test
    fun `se recortan espacios exteriores al crear`() {
        assertEquals("PROD-001", ProductKey.parse("  PROD-001 ").value)
    }

    @Test
    fun `rechaza caracteres que rompen rutas`() {
        listOf("A/B", "A\\B", "A?B", "A#B", "A%2FB", "A B", "A\"B", "A'B", "..", ".", "-A", "_A", "A\nB", "A\tB")
            .forEach { assertNotNull(ProductKey.validar(it), "debería rechazar '$it'") }
    }

    @Test
    fun `rechaza vacia nula y demasiado larga`() {
        assertNotNull(ProductKey.validar(null))
        assertNotNull(ProductKey.validar("   "))
        assertNotNull(ProductKey.validar("A".repeat(ProductKey.MAX_LARGO + 1)))
        assertNull(ProductKey.validar("A".repeat(ProductKey.MAX_LARGO)))
    }

    @Test
    fun `parse lanza con mensaje y parseOrNull devuelve null`() {
        val ex = assertThrows(IllegalArgumentException::class.java) { ProductKey.parse("A/B") }
        assertTrue(ex.message!!.contains("letras"))
        assertNull(ProductKey.parseOrNull("A/B"))
        assertNotNull(ProductKey.parseOrNull("A-B"))
    }

    @Test
    fun `el constructor sigue siendo permisivo para leer claves existentes`() {
        assertEquals("A/B", ProductKey("A/B").value)
        assertThrows(IllegalArgumentException::class.java) { ProductKey("  ") }
    }
}
