package com.automata.commerce

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Reparacion
import com.automata.commerce.model.ReparteSocios
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ModelosTest {

    private val key = ProductKey("A-0412")
    private val mitad = ReparteSocios(BigDecimal("50"), BigDecimal("50"))

    // ---------- D3: PM <= PP solo cuando está publicado ----------

    @Test
    fun `PM mayor que PP es rechazado si esta Publicado o en Negociacion`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", estado = EstadoItem.PUBLICADO, precioPublicado = Money.of(1_000), precioMinimo = Money.of(1_200))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", estado = EstadoItem.NEGOCIACION, precioPublicado = Money.of(1_000), precioMinimo = Money.of(1_200))
        }
    }

    @Test
    fun `PM mayor que PP se tolera mientras no este publicado`() {
        InventarioItem(key, "iPhone 11", estado = EstadoItem.COMPRADO, precioPublicado = Money.of(1_000), precioMinimo = Money.of(1_200))
    }

    // ---------- Venta manual: estado Vendido <=> datos de venta ----------

    @Test
    fun `Vendido sin fecha de venta o sin Vendido en es rechazado`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", estado = EstadoItem.VENDIDO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", estado = EstadoItem.VENDIDO, vendidoEn = Money.of(1_000))
        }
    }

    @Test
    fun `se puede dejar fecha de venta y Vendido en llenos antes de cambiar el estado`() {
        // Así se hace a mano en el editor de tablas: primero los datos, después el estado.
        val item = InventarioItem(
            key, "iPhone 11", estado = EstadoItem.PUBLICADO,
            vendidoEn = Money.of(1_000), fechaVenta = LocalDate.of(2026, 10, 1)
        )
        assertFalse(item.estaVendido)
    }

    @Test
    fun `publicacion anterior a compra es rechazada`() {
        assertThrows(IllegalArgumentException::class.java) {
            InventarioItem(key, "iPhone 11", fechaCompra = LocalDate.of(2026, 9, 10), fechaPublicacion = LocalDate.of(2026, 9, 1))
        }
    }

    @Test
    fun `registrarVenta pasa a Vendido, guarda el canal y no permite vender dos veces`() {
        val item = InventarioItem(key, "iPhone 11", estado = EstadoItem.PUBLICADO, reparte = mitad)
        val vendido = item.registrarVenta(Money.of(1_200_000), LocalDate.of(2026, 10, 1), " Shopify ")
        assertEquals(EstadoItem.VENDIDO, vendido.estado)
        assertEquals(Money.of(1_200_000), vendido.vendidoEn)
        assertEquals("shopify", vendido.canalVenta)
        assertThrows(IllegalStateException::class.java) { vendido.registrarVenta(Money.of(1), LocalDate.of(2026, 10, 2)) }
    }

    @Test
    fun `D7 - no se puede vender sin reparto de socios`() {
        val item = InventarioItem(key, "iPhone 11", estado = EstadoItem.PUBLICADO)   // 0 / 0
        assertThrows(IllegalStateException::class.java) { item.registrarVenta(Money.of(1_000), LocalDate.of(2026, 10, 1)) }
    }

    @Test
    fun `no se puede vender antes de la compra ni antes de la publicacion`() {
        val item = InventarioItem(
            key, "iPhone 11", estado = EstadoItem.PUBLICADO, reparte = mitad,
            fechaCompra = LocalDate.of(2026, 9, 1), fechaPublicacion = LocalDate.of(2026, 9, 5)
        )
        assertThrows(IllegalArgumentException::class.java) { item.registrarVenta(Money.of(1), LocalDate.of(2026, 8, 31)) }
        assertThrows(IllegalArgumentException::class.java) { item.registrarVenta(Money.of(1), LocalDate.of(2026, 9, 3)) }
        item.registrarVenta(Money.of(1), LocalDate.of(2026, 9, 5))   // el mismo día de la publicación sí
    }

    @Test
    fun `anularVenta devuelve el producto a Publicado y borra los datos de venta`() {
        val vendido = InventarioItem(key, "iPhone 11", estado = EstadoItem.PUBLICADO, reparte = mitad)
            .registrarVenta(Money.of(1_200_000), LocalDate.of(2026, 10, 1), "shopify")
        val reabierto = vendido.anularVenta()
        assertEquals(EstadoItem.PUBLICADO, reabierto.estado)
        assertNull(reabierto.fechaVenta)
        assertNull(reabierto.vendidoEn)
        assertNull(reabierto.canalVenta)
        assertThrows(IllegalStateException::class.java) { reabierto.anularVenta() }   // ya no estaba vendido
        assertThrows(IllegalArgumentException::class.java) { vendido.anularVenta(EstadoItem.VENDIDO) }
    }

    // ---------- Reparación y reparto ----------

    @Test
    fun `costo total de Reparacion es compra mas reparacion`() {
        val rep = Reparacion(key, precioReparacion = Money.of(100_000), detalles = "pantalla cambiada")
        assertEquals(Money.of(900_000), rep.costoTotal(Money.of(800_000)))
    }

    @Test
    fun `ReparteSocios exige suma 100 o ambos en cero`() {
        ReparteSocios()                                                  // 0 / 0: ok
        ReparteSocios(BigDecimal("50"), BigDecimal("50"))                // ok
        ReparteSocios(BigDecimal("33.33"), BigDecimal("66.67"))          // ok
        assertThrows(IllegalArgumentException::class.java) { ReparteSocios(BigDecimal("60"), BigDecimal("60")) }
        assertThrows(IllegalArgumentException::class.java) { ReparteSocios(BigDecimal("30"), BigDecimal("30")) }
        assertThrows(IllegalArgumentException::class.java) { ReparteSocios(BigDecimal("-10"), BigDecimal("110")) }
    }

    @Test
    fun `ReparteSocios reparte con suma exacta y sin definir devuelve vacio`() {
        assertEquals(20_000L to 20_000L, mitad.repartir(40_000))
        val r = ReparteSocios(BigDecimal("33.33"), BigDecimal("66.67")).repartir(12_345)!!
        assertEquals(12_345L, r.first + r.second)
        assertNull(ReparteSocios.SIN_DEFINIR.repartir(300))
        assertFalse(ReparteSocios.SIN_DEFINIR.definida)
        assertTrue(mitad.definida)
    }

    @Test
    fun `estados reales y busqueda sin tildes`() {
        assertEquals(
            listOf("Comprado", "Publicado", "Negociación", "Vendido", "Inactivo", "Otro"),
            EstadoItem.entries.map { it.etiqueta }
        )
        assertEquals(EstadoItem.NEGOCIACION, EstadoItem.desdeEtiqueta("negociacion"))
        assertEquals(EstadoItem.VENDIDO, EstadoItem.desdeEtiqueta("  VENDIDO "))
        assertNull(EstadoItem.desdeEtiqueta("Descartado"))
    }
}
