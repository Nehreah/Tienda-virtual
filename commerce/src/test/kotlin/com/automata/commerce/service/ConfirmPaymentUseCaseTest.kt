package com.automata.commerce.service

import com.automata.commerce.ports.InMemoryInventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.ReparteSocios

class ConfirmPaymentUseCaseTest {

    private lateinit var repo: InMemoryInventarioRepository
    private lateinit var useCase: ConfirmPaymentUseCase

    private val k1 = ProductKey("KEY-001")

    @BeforeEach
    fun setup() {
        repo = InMemoryInventarioRepository()
        repo.store[k1.value] = InventarioItem(
            key = k1,
            item = "Producto KEY-001",
            precioPublicado = Money.of(150_000),
            precioMinimo = Money.of(100_000),
            precioCompra = Money.of(70_000),
            costoTotal = Money.of(70_000),
            estado = EstadoItem.PUBLICADO,
            reparte = ReparteSocios(mapOf(1L to java.math.BigDecimal(50), 2L to java.math.BigDecimal(50)))
        )
        useCase = ConfirmPaymentUseCase(repo)
    }

    @Test
    fun `confirmar pago atómico con precio de venta valido`() {
        val req = ConfirmPaymentRequest(
            dealId = "DEAL-100",
            productKey = k1,
            salePrice = Money.of(150_000),
            channel = "whatsapp"
        )

        val result = useCase.execute(req)
        assertTrue(result.success)
        assertNull(result.errorMessage)
    }

    @Test
    fun `falla si precio de venta es 0 o negativo`() {
        val req = ConfirmPaymentRequest(
            dealId = "DEAL-101",
            productKey = k1,
            salePrice = Money.of(0),
            channel = "whatsapp"
        )

        assertThrows(IllegalArgumentException::class.java) {
            useCase.execute(req)
        }
    }

    @Test
    fun `repetir la misma confirmacion es idempotente y no cambia la venta`() {
        val req = ConfirmPaymentRequest("DEAL-200", k1, Money.of(150_000), "whatsapp")

        val primera = useCase.execute(req)
        assertTrue(primera.success)
        assertFalse(primera.isIdempotentRepeat)
        assertTrue(primera.persisted)

        val repetida = useCase.execute(req)
        assertTrue(repetida.success)
        assertTrue(repetida.isIdempotentRepeat)
        assertEquals(Money.of(150_000), repetida.item?.vendidoEn)
        assertEquals(EstadoItem.VENDIDO, repo.findByKey(k1)?.estado)
    }

    @Test
    fun `el reintento con canal escrito distinto en mayusculas sigue siendo el mismo pago`() {
        useCase.execute(ConfirmPaymentRequest("DEAL-201", k1, Money.of(150_000), "whatsapp"))

        val repetida = useCase.execute(ConfirmPaymentRequest("DEAL-201", k1, Money.of(150_000), "  WhatsApp "))

        assertTrue(repetida.success)
        assertTrue(repetida.isIdempotentRepeat)
    }

    @Test
    fun `producto ya vendido con otro precio se rechaza y no se pisa la venta`() {
        useCase.execute(ConfirmPaymentRequest("DEAL-202", k1, Money.of(150_000), "whatsapp"))

        val conflicto = useCase.execute(ConfirmPaymentRequest("DEAL-203", k1, Money.of(120_000), "whatsapp"))

        assertFalse(conflicto.success)
        assertFalse(conflicto.isIdempotentRepeat)
        assertTrue(conflicto.errorMessage?.contains("ya está vendido") == true)
        assertEquals(Money.of(150_000), repo.findByKey(k1)?.vendidoEn)
    }

    @Test
    fun `producto ya vendido por otro canal se rechaza`() {
        useCase.execute(ConfirmPaymentRequest("DEAL-204", k1, Money.of(150_000), "whatsapp"))

        val conflicto = useCase.execute(ConfirmPaymentRequest("DEAL-205", k1, Money.of(150_000), "instagram"))

        assertFalse(conflicto.success)
        assertFalse(conflicto.isIdempotentRepeat)
    }

    @Test
    fun `producto inexistente devuelve error claro`() {
        val result = useCase.execute(ConfirmPaymentRequest("DEAL-206", ProductKey("NO-EXISTE"), Money.of(150_000), "whatsapp"))

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("No se encontró") == true)
    }

    @Test
    fun `una regla distinta de ya vendido no se confunde con un reintento`() {
        val sinReparto = ProductKey("KEY-002")
        val base = repo.store.getValue(k1.value)
        repo.store[sinReparto.value] = base.copy(key = sinReparto, item = "Sin reparto", reparte = ReparteSocios.SIN_DEFINIR)

        val result = useCase.execute(ConfirmPaymentRequest("DEAL-207", sinReparto, Money.of(150_000), "whatsapp"))

        assertFalse(result.success)
        assertFalse(result.isIdempotentRepeat)
        assertTrue(result.errorMessage?.contains("porcentajes") == true)
    }

    @Test
    fun `sin repositorio el exito se marca como no persistido`() {
        val soloMemoria = ConfirmPaymentUseCase(null)

        val result = soloMemoria.execute(ConfirmPaymentRequest("DEAL-208", k1, Money.of(150_000), "whatsapp"))

        assertTrue(result.success)
        assertFalse(result.persisted)
        assertNull(result.item)
    }

    @Test
    fun `dos confirmaciones simultaneas dan una venta y un reintento idempotente`() {
        val req = ConfirmPaymentRequest("DEAL-209", k1, Money.of(150_000), "whatsapp")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val salida = CountDownLatch(1)
            val tareas = List(2) {
                pool.submit(Callable { salida.await(); useCase.execute(req) })
            }
            salida.countDown()
            val resultados = tareas.map { it.get(5, TimeUnit.SECONDS) }

            assertTrue(resultados.all { it.success })
            assertEquals(1, resultados.count { !it.isIdempotentRepeat })
            assertEquals(1, resultados.count { it.isIdempotentRepeat })
        } finally {
            pool.shutdownNow()
        }
    }
}
