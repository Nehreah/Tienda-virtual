package com.automata.commerce.service

import com.automata.commerce.ports.InMemoryInventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
            reparte = ReparteSocios(pctAntonio = 50.0.toBigDecimal(), pctJavier = 50.0.toBigDecimal())
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
}
