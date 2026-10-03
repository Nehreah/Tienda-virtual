package com.automata.commerce

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Inversion
import com.automata.commerce.model.Revision
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ModelosTest {

    private val key = ProductKey("A-0412")

    @Test
    fun `PM mayor que PP es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", precioPublicado = Money.of(1_000), precioMinimo = Money.of(1_200))
        }
    }

    @Test
    fun `Vendido sin fecha de venta o sin Vendido en es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", estado = EstadoItem.VENDIDO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", estado = EstadoItem.PUBLICADO, vendidoEn = Money.of(1_000), fechaVenta = LocalDate.of(2026, 10, 1))
        }
    }

    @Test
    fun `publicacion anterior a compra es rechazada`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", fechaCompra = LocalDate.of(2026, 9, 10), fechaPublicacion = LocalDate.of(2026, 9, 1))
        }
    }

    @Test
    fun `registrarVenta pasa a Vendido y no permite vender dos veces`() {
        val item = InventarioItem(key, "iPhone 11", estado = EstadoItem.PUBLICADO)
        val vendido = item.registrarVenta(Money.of(1_200_000), LocalDate.of(2026, 10, 1))
        assertEquals(EstadoItem.VENDIDO, vendido.estado)
        assertEquals(Money.of(1_200_000), vendido.vendidoEn)
        assertThrows(IllegalStateException::class.java) { vendido.registrarVenta(Money.of(1), LocalDate.of(2026, 10, 2)) }
    }

    @Test
    fun `costo total de Revision es compra mas reparacion`() {
        val rev = Revision(key, reparacion = Money.of(100_000), detalles = "pantalla cambiada")
        assertEquals(Money.of(900_000), rev.costoTotal(Money.of(800_000)))
    }

    @Test
    fun `Inversion exige suma 100 o ambos en cero`() {
        Inversion(key)                                            // 0 / 0: ok
        Inversion(key, BigDecimal("50"), BigDecimal("50"))        // ok
        Inversion(key, BigDecimal("33.33"), BigDecimal("66.67"))  // ok
        assertThrows(IllegalArgumentException::class.java) { Inversion(key, BigDecimal("60"), BigDecimal("60")) }
        assertThrows(IllegalArgumentException::class.java) { Inversion(key, BigDecimal("30"), BigDecimal("30")) }
        assertThrows(IllegalArgumentException::class.java) { Inversion(key, BigDecimal("-10"), BigDecimal("110")) }
    }

    @Test
    fun `estados reales y busqueda sin tildes`() {
        assertEquals(
            listOf("Comprado", "Publicado", "Negociación", "Vendido", "Otro"),
            EstadoItem.entries.map { it.etiqueta }
        )
        assertEquals(EstadoItem.NEGOCIACION, EstadoItem.desdeEtiqueta("negociacion"))
        assertEquals(EstadoItem.VENDIDO, EstadoItem.desdeEtiqueta("  VENDIDO "))
        assertNull(EstadoItem.desdeEtiqueta("Descartado"))
    }
}
