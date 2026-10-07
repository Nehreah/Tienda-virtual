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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * T_H1 — Prueba de Reinicio (Hallazgo H1).
 *
 * Verifica que una reserva creada antes de que el servidor se reinicie
 * pueda ser recuperada y confirmada tras re-hidratar el pipeline con [loadActiveReservations].
 */
class ReservationRepositoryRestartTest {

    private val t0 = Instant.parse("2026-10-03T12:00:00Z")
    private val k1 = ProductKey("PROD-001")

    private fun product() = CommercialProduct(
        key = k1, itemName = "Audífonos",
        publishedPrice = Money.of(120_000), minimumPrice = Money.of(95_000), cost = Money.of(70_000),
        status = "Activo", description = "Test", brand = "Marca"
    )

    private class FakeLlm(var next: ParsedCustomerIntent) : LlmPort {
        override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>) = next
    }

    private class FakePort : MessagingPort {
        override val channelName = "whatsapp"
        override suspend fun sendMessage(recipientId: String, text: String) = PortResult.Ok("id")
        override suspend fun sendProductCard(recipientId: String, productTitle: String, priceFormatted: String, imageUrl: String) =
            sendMessage(recipientId, productTitle)
    }

    private fun intent(type: CustomerIntentType, key: ProductKey? = null, price: Long? = null) =
        ParsedCustomerIntent(type, key, price?.let { Money.of(it) }, "texto")

    @Test
    fun `T_H1 reserva sobrevive al reinicio del pipeline`() = runBlocking {
        // ── FASE 1: crear reserva con el pipeline original ──
        val sharedStore = InMemoryConversationStore()
        val sharedRepo = InMemoryReservationRepository()

        val catalog1 = object : PipelineCatalog {
            val inv = mutableMapOf(k1 to OperationalInventory(k1, stockMaestro = 1))
            override fun productKeys() = listOf(k1)
            override fun product(key: ProductKey) = product().takeIf { key == k1 }
            override fun inventory(key: ProductKey) = inv[key]
            override fun saveInventory(inventory: OperationalInventory) { inv[inventory.productKey] = inventory }
        }

        val pipeline1 = InboundMessagePipeline(
            llm = FakeLlm(intent(CustomerIntentType.MAKE_OFFER, k1, 120_000)),
            engine = NegotiationEngine(),
            catalog = catalog1,
            conversations = sharedStore,
            router = ChannelRouter(listOf(FakePort())),
            reservationRepo = sharedRepo
        )

        val out1 = pipeline1.handle("whatsapp", "573001", "m1", "te pago 120000", t0)
        assertEquals(ConversationState.PAYMENT_PENDING, out1.conversation.state)
        assertNotNull(out1.conversation.currentReservationId)
        assertEquals(1, sharedRepo.store.size, "La reserva debe estar en el repositorio compartido")

        // ── FASE 2: simular reinicio creando un pipeline NUEVO con el mismo repositorio ──
        val catalog2 = object : PipelineCatalog {
            val inv = mutableMapOf(k1 to OperationalInventory(k1, stockMaestro = 1))
            override fun productKeys() = listOf(k1)
            override fun product(key: ProductKey) = product().takeIf { key == k1 }
            override fun inventory(key: ProductKey) = inv[key]
            override fun saveInventory(inventory: OperationalInventory) { inv[inventory.productKey] = inventory }
        }

        val pipeline2 = InboundMessagePipeline(
            llm = FakeLlm(intent(CustomerIntentType.OTHER)),
            engine = NegotiationEngine(),
            catalog = catalog2,
            conversations = sharedStore,
            router = ChannelRouter(listOf(FakePort())),
            reservationRepo = sharedRepo
        )

        // Re-hidratar reservas desde el repositorio (simula lo que hace Application.kt)
        pipeline2.loadActiveReservations()

        // ── FASE 3: verificar que el pipeline nuevo puede confirmar el pago ──
        val convId = out1.conversation.conversationId
        val confirmed = pipeline2.confirmPayment(convId, t0 + 5.minutes)
        assertTrue(confirmed, "El pipeline nuevo debe poder confirmar el pago de la reserva re-hidratada")

        val updatedConv = sharedStore.findById(convId)
        assertEquals(
            ConversationState.PAID_CONFIRMED, updatedConv?.state,
            "La conversación debe estar en PAID_CONFIRMED tras confirmar el pago en el pipeline reiniciado"
        )
    }
}
