package com.automata.commerce.service

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.ReparteSocios
import com.automata.commerce.ports.InMemoryInventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ArticuloLifecycleServiceTest {

    private lateinit var repo: InMemoryInventarioRepository
    private lateinit var service: ArticuloLifecycleService

    private val k1 = ProductKey("AURICULARES-001")
    private val k2 = ProductKey("TECLADO-002")

    private fun sampleItem(
        key: ProductKey,
        estado: EstadoItem = EstadoItem.PUBLICADO,
        fechaVenta: LocalDate? = if (estado == EstadoItem.VENDIDO) LocalDate.now() else null,
        vendidoEn: Money? = if (estado == EstadoItem.VENDIDO) Money.of(150_000) else null
    ) = InventarioItem(
        key = key,
        item = "Producto ${key.value}",
        precioPublicado = Money.of(150_000),
        precioMinimo = Money.of(100_000),
        precioCompra = Money.of(70_000),
        costoTotal = Money.of(70_000),
        estado = estado,
        fechaVenta = fechaVenta,
        vendidoEn = vendidoEn,
        reparte = ReparteSocios(mapOf(1L to java.math.BigDecimal(50), 2L to java.math.BigDecimal(50)))
    )

    @BeforeEach
    fun setup() {
        repo = InMemoryInventarioRepository()
        service = ArticuloLifecycleService(repo)
    }

    @Test
    fun `C2-Crear - crear articulo nuevo exitosamente`() {
        val result = service.crear(sampleItem(k1), usuario = "admin")
        assertEquals(k1, result.key)
        assertEquals(EstadoItem.PUBLICADO, result.estado)
        assertNotNull(repo.findByKey(k1))
    }

    @Test
    fun `C2-Crear - falla si ya existe el mismo key`() {
        repo.store[k1.value] = sampleItem(k1)
        assertThrows(IllegalArgumentException::class.java) {
            service.crear(sampleItem(k1), usuario = "admin")
        }
    }

    @Test
    fun `C2-Editar - editar articulo existente exitosamente`() {
        repo.store[k1.value] = sampleItem(k1)
        val modificado = sampleItem(k1).copy(item = "Auriculares Sony WH-1000XM5")
        val result = service.editar(modificado, usuario = "admin")
        assertEquals("Auriculares Sony WH-1000XM5", result.item)
    }

    @Test
    fun `C2-Editar - falla si el articulo no existe`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.editar(sampleItem(k1), usuario = "admin")
        }
    }

    @Test
    fun `C2-Archivar - archivar articulo publicado con motivo`() = runBlocking {
        repo.store[k1.value] = sampleItem(k1)
        val result = service.archivar(k1, motivo = "Descontinuado por proveedor", usuario = "admin")
        assertEquals(EstadoItem.INACTIVO, result.estado)
        assertEquals("Descontinuado por proveedor", result.motivoArchivo)
        assertNotNull(result.archivadoEn)
    }

    @Test
    fun `C2-Archivar - falla sin motivo`() = runBlocking {
        repo.store[k1.value] = sampleItem(k1)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.archivar(k1, motivo = "  ", usuario = "admin") }
        }
    }

    @Test
    fun `C2-Archivar - falla si el articulo esta Vendido`() = runBlocking {
        val vendido = sampleItem(k1, EstadoItem.VENDIDO).copy(
            fechaVenta = LocalDate.now(),
            vendidoEn = Money.of(150_000)
        )
        repo.store[k1.value] = vendido
        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.archivar(k1, motivo = "Intentar archivar vendido", usuario = "admin") }
        }
    }

    @Test
    fun `C2-Restaurar - restaurar articulo archivado`() = runBlocking {
        val archivado = sampleItem(k1, EstadoItem.INACTIVO).copy(
            archivadoEn = java.time.Instant.now(),
            archivadoPor = "admin",
            motivoArchivo = "Prueba archivado"
        )
        repo.store[k1.value] = archivado
        val result = service.restaurar(k1, usuario = "admin")
        assertEquals(EstadoItem.PUBLICADO, result.estado)
        assertNull(result.archivadoEn)
        assertNull(result.motivoArchivo)
    }

    @Test
    fun `C2-Eliminar - eliminar articulo sin historial`() {
        repo.store[k1.value] = sampleItem(k1)
        val deleted = service.eliminar(k1, usuario = "admin")
        assertTrue(deleted)
        assertNull(repo.findByKey(k1))
    }

    @Test
    fun `C2-Eliminar - falla si el articulo tiene historial de venta`() {
        val conHistorial = sampleItem(k1, EstadoItem.VENDIDO).copy(
            fechaVenta = LocalDate.now(),
            vendidoEn = Money.of(150_000)
        )
        repo.store[k1.value] = conHistorial
        assertThrows(IllegalStateException::class.java) {
            service.eliminar(k1, usuario = "admin")
        }
    }
}
