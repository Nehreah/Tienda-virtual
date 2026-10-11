package com.automata.app.routes

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import io.ktor.http.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogoFormularioTest {

    private fun form(vararg pares: Pair<String, String>): Parameters =
        Parameters.build { pares.forEach { (k, v) -> append(k, v) } }

    private val base = arrayOf(
        "key" to "PROD-001", "item" to "Audífonos", "precioCompra" to "70000",
        "cantidad" to "2", "estado" to "Comprado"
    )

    private fun conCambios(vararg cambios: Pair<String, String>): Parameters =
        form(*(base.toMap() + cambios.toMap()).toList().toTypedArray())

    @Test
    fun `alta valida produce el articulo sin precio minimo`() {
        val r = leerArticulo(conCambios("precioPublicado" to "\$120.000", "fechaCompra" to "2026-10-01"), existente = null)
        val item = (r as Lectura.Ok).item
        assertEquals("PROD-001", item.key.value)
        assertEquals(70_000L, item.precioCompra.amountInCop)
        assertEquals(120_000L, item.precioPublicado?.amountInCop)
        assertEquals(2, item.cantidad)
        assertNull(item.precioMinimo)
    }

    @Test
    fun `rechaza claves que rompen rutas`() {
        listOf("A/B", "A B", "../x", "").forEach {
            val r = leerArticulo(conCambios("key" to it), existente = null)
            assertTrue(r is Lectura.Error, "debería rechazar '$it'")
        }
    }

    @Test
    fun `rechaza vendido e inactivo como estado manual`() {
        listOf("Vendido", "Inactivo", "Inventado").forEach {
            assertTrue(leerArticulo(conCambios("estado" to it), null) is Lectura.Error, it)
        }
    }

    @Test
    fun `rechaza montos y cantidades invalidos`() {
        assertTrue(leerArticulo(conCambios("precioCompra" to "12,5"), null) is Lectura.Error)
        assertTrue(leerArticulo(conCambios("precioCompra" to "-1"), null) is Lectura.Error)
        assertTrue(leerArticulo(conCambios("precioPublicado" to "abc"), null) is Lectura.Error)
        assertTrue(leerArticulo(conCambios("cantidad" to "-3"), null) is Lectura.Error)
        assertTrue(leerArticulo(conCambios("cantidad" to "1.5"), null) is Lectura.Error)
    }

    @Test
    fun `rechaza fecha de publicacion anterior a la compra y fechas mal escritas`() {
        val anterior = leerArticulo(conCambios("fechaCompra" to "2026-10-05", "fechaPublicacion" to "2026-10-01"), null)
        assertTrue(anterior is Lectura.Error)
        assertTrue(leerArticulo(conCambios("fechaCompra" to "05/10/2026"), null) is Lectura.Error)
    }

    @Test
    fun `la edicion conserva la key y descarta el precio minimo viejo`() {
        val existente = InventarioItem(
            key = ProductKey("LEGACY/9"), item = "Viejo", estado = EstadoItem.COMPRADO,
            precioCompra = Money.of(100_000), precioMinimo = Money.of(150_000), costoTotal = Money.of(100_000)
        )
        val r = leerArticulo(
            form("key" to "OTRA", "item" to "Nuevo nombre", "precioCompra" to "50000", "precioPublicado" to "80000",
                "cantidad" to "1", "estado" to "Publicado"),
            existente
        )
        val item = (r as Lectura.Ok).item
        assertEquals("LEGACY/9", item.key.value)
        assertEquals("Nuevo nombre", item.item)
        assertNull(item.precioMinimo)
        assertEquals(EstadoItem.PUBLICADO, item.estado)
    }

    @Test
    fun `parseCop acepta pesos con separador de miles y rechaza el resto`() {
        assertEquals(1_200_000L, parseCop("1.200.000"))
        assertEquals(120_000L, parseCop("\$120.000"))
        assertEquals(0L, parseCop("0"))
        assertNull(parseCop("1.5"))
        assertNull(parseCop("12,5"))
        assertNull(parseCop(""))
        assertNull(parseCop(null))
    }

    @Test
    fun `mensajeLegible toma la primera linea del error de PostgreSQL`() {
        val sql = java.sql.SQLException("ERROR: El monto (5) supera el saldo (3)\n  Where: PL/pgSQL function x")
        val envuelta = RuntimeException("Statement(s): SELECT ...", sql)
        assertEquals("El monto (5) supera el saldo (3)", envuelta.mensajeLegible())
        assertEquals("Ya existe", IllegalArgumentException("Ya existe").mensajeLegible())
    }
}
