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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * T_H2 — Prueba de Concurrencia (Hallazgo H2).
 *
 * 50 corrutinas en paralelo intentan reservar la ÚLTIMA unidad disponible.
 * Exactamente una debe tener éxito; las demás deben recibir "no disponible".
 */
class ConcurrentReservationTest {

    private val t0 = Instant.parse("2026-10-03T12:00:00Z")
    private val k1 = ProductKey("PROD-UNICO")

    private fun product() = CommercialProduct(
        key = k1, itemName = "Último Audífono",
        publishedPrice = Money.of(120_000), minimumPrice = Money.of(95_000), cost = Money.of(70_000),
        status = "Activo", description = "Solo uno disponible", brand = "Marca"
    )

    private class FakePort : MessagingPort {
        override val channelName = "whatsapp"
        override suspend fun sendMessage(recipientId: String, text: String) = PortResult.Ok("id")
        override suspend fun sendProductCard(recipientId: String, productTitle: String, priceFormatted: String, imageUrl: String) =
            sendMessage(recipientId, productTitle)
    }

    @Test
    fun `T_H2 exactamente una corrutina obtiene la reserva cuando el stock es 1`() = runBlocking {
        val sharedRepo = InMemoryReservationRepository()
        val sharedStore = InMemoryConversationStore()

        // Catálogo compartido con stock = 1 y sincronización interna
        val sharedCatalog = object : PipelineCatalog {
            val inv = mutableMapOf(k1 to OperationalInventory(k1, stockMaestro = 1))
            override fun productKeys() = listOf(k1)
            override fun product(key: ProductKey) = product().takeIf { key == k1 }
            override fun inventory(key: ProductKey) = synchronized(this) { inv[key] }
            override fun saveInventory(inventory: OperationalInventory) = synchronized(this) { inv[inventory.productKey] = inventory }
        }

        val pipeline = InboundMessagePipeline(
            llm = object : LlmPort {
                override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>) =
                    ParsedCustomerIntent(CustomerIntentType.MAKE_OFFER, k1, Money.of(120_000), "")
            },
            engine = NegotiationEngine(),
            catalog = sharedCatalog,
            conversations = sharedStore,
            router = ChannelRouter(listOf(FakePort())),
            reservationRepo = sharedRepo
        )

        val numCoroutines = 50
        // Cada corrutina representa un cliente diferente con un senderId único
        val results = (1..numCoroutines).map { i ->
            async(Dispatchers.Default) {
                pipeline.handle("whatsapp", "573${"$i".padStart(7, '0')}", "m1", "te pago 120000", t0)
            }
        }.awaitAll()

        val reserved = results.filter { it.conversation.state == ConversationState.PAYMENT_PENDING }
        val notReserved = results.filter { it.conversation.state != ConversationState.PAYMENT_PENDING }

        assertEquals(
            1, reserved.size,
            "Exactamente 1 corrutina debe obtener la reserva; obtuvo: ${reserved.size}"
        )
        assertEquals(
            numCoroutines - 1, notReserved.size,
            "Las ${numCoroutines - 1} corrutinas restantes deben recibir 'no disponible'"
        )

        // El stock en el catálogo debe ser 0 disponibles (1 reservado)
        val inventory = sharedCatalog.inventory(k1)!!
        assertEquals(0, inventory.stockVivo, "El stock disponible debe ser 0 tras la única reserva exitosa")
        assertEquals(1, inventory.reservasActivas, "Debe haber exactamente 1 reserva activa")

        // El repositorio debe tener exactamente 1 reserva ACTIVE
        val activeReservations = sharedRepo.loadAllActive()
        assertEquals(1, activeReservations.size, "El repositorio debe tener exactamente 1 reserva ACTIVE")
    }
}
