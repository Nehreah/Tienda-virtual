package com.automata.app.pipeline

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.service.NegotiationEngine
import com.automata.messaging.model.ConversationState
import com.automata.messaging.ports.ChannelRouter
import com.automata.messaging.ports.CustomerIntentType
import com.automata.messaging.ports.InMemoryConversationStore
import com.automata.messaging.ports.InMemoryReservationRepository
import com.automata.messaging.ports.LlmPort
import com.automata.messaging.ports.MessagingPort
import com.automata.messaging.ports.ParsedCustomerIntent
import com.automata.shared.Money
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import com.automata.commerce.model.Reservation
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * T_Payment — Prueba de Idempotencia de Pago.
 *
 * Confirmar el pago dos veces seguidas para la misma reserva
 * debe descontar el stock una sola vez y dejar la conversación en PAID_CONFIRMED.
 */
class IdempotentPaymentTest {

    private val t0 = Instant.parse("2026-10-03T12:00:00Z")
    private val k1 = ProductKey("PROD-001")

    private fun product() = CommercialProduct(
        key = k1, itemName = "Audífonos",
        publishedPrice = Money.of(120_000), minimumPrice = Money.of(95_000), cost = Money.of(70_000),
        status = "Activo", description = "Test", brand = "Marca"
    )

    private inner class Setup {
        val repo = InMemoryReservationRepository()
        val store = InMemoryConversationStore()
        val catalog = object : PipelineCatalog {
            val inv = mutableMapOf(k1 to OperationalInventory(ProductKey("PROD-001"), stockMaestro = 5))
            override fun productKeys() = listOf(k1)
            override fun product(key: ProductKey) = product().takeIf { key == k1 }
            override fun inventory(key: ProductKey) = inv[key]
            override fun saveInventory(inventory: OperationalInventory) { inv[inventory.productKey] = inventory }
        }
        val pipeline = InboundMessagePipeline(
            llm = object : LlmPort {
                override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>) =
                    ParsedCustomerIntent(CustomerIntentType.MAKE_OFFER, k1, Money.of(120_000), "")
            },
            engine = NegotiationEngine(),
            catalog = catalog,
            conversations = store,
            router = ChannelRouter(listOf(object : MessagingPort {
                override val channelName = "whatsapp"
                override suspend fun sendMessage(recipientId: String, text: String) = PortResult.Ok("id")
                override suspend fun sendProductCard(r: String, t: String, p: String, i: String) = PortResult.Ok("id")
            })),
            reservationRepo = repo
        )
    }

    @Test
    fun `T_Payment confirmar pago dos veces no descuenta el stock dos veces`() = runBlocking {
        val s = Setup()

        // 1. Crear reserva
        val out = s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)
        assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state)
        val convId = out.conversation.conversationId
        val initialDisponible = s.catalog.inventory(k1)!!.stockVivo

        // El stock en reserva debe ser 1 en el catálogo en memoria
        assertEquals(4, initialDisponible, "Deben quedar 4 disponibles tras la reserva (5 - 1 reservado)")

        // 2. Primera confirmación de pago
        val confirm1 = s.pipeline.confirmPayment(convId, t0 + 2.minutes)
        assertTrue(confirm1, "Primera confirmación debe devolver true")

        val convAfterFirst = s.store.findById(convId)!!
        assertEquals(ConversationState.PAID_CONFIRMED, convAfterFirst.state)

        // El stock NO debe haber cambiado (la reserva sigue activa, solo se extendió)
        assertEquals(4, s.catalog.inventory(k1)!!.stockVivo,
            "El stock disponible no debe cambiar tras la primera confirmación")

        // 3. Segunda confirmación de pago (idempotente)
        val confirm2 = s.pipeline.confirmPayment(convId, t0 + 3.minutes)
        assertTrue(confirm2, "Segunda confirmación (idempotente) debe devolver true")

        val convAfterSecond = s.store.findById(convId)!!
        assertEquals(ConversationState.PAID_CONFIRMED, convAfterSecond.state,
            "La conversación debe seguir en PAID_CONFIRMED")

        // El stock NO debe haber cambiado (no se confirmó dos veces)
        assertEquals(4, s.catalog.inventory(k1)!!.stockVivo,
            "El stock no debe descargarse dos veces por confirmaciones idempotentes")

        // La reserva debe estar en estado CONFIRMED (no duplicada)
        val reservationId = convAfterSecond.currentReservationId!!
        val reservation = s.repo.findById(reservationId)
        assertEquals(Reservation.ReservationStatus.CONFIRMED, reservation?.status,
            "La reserva debe estar en estado CONFIRMED")

        // Solo debe existir UNA reserva en el repositorio
        assertEquals(1, s.repo.store.size, "Solo debe existir 1 reserva en el repositorio")
    }

    @Test
    fun `T_Payment barrido no libera stock de reserva confirmada`() = runBlocking {
        val s = Setup()

        // 1. Crear reserva con TTL corto
        val out = s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)
        val convId = out.conversation.conversationId

        // 2. Confirmar el pago (extiende la expiración a 365 días)
        s.pipeline.confirmPayment(convId, t0 + 2.minutes)

        // 3. Barrido pasado el TTL original (15 min)
        s.pipeline.sweep(t0 + 16.minutes)

        // El stock NO debe liberarse: la reserva fue confirmada
        assertEquals(4, s.catalog.inventory(k1)!!.stockVivo,
            "El barrido NO debe liberar el stock de una reserva CONFIRMED")
        assertEquals(1, s.pipeline.activeReservationCount(),
            "La reserva CONFIRMED debe seguir en el mapa de reservas activas")
    }
}
