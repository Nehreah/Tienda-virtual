package com.automata.app.pipeline

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.service.NegotiationEngine
import com.automata.messaging.model.ConversationState
import com.automata.messaging.ports.ChannelRouter
import com.automata.messaging.ports.CustomerIntentType
import com.automata.messaging.ports.InMemoryConversationStore
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

/** Fase 2.2: un producto Inactivo no se negocia, no se reserva y el bot responde "no disponible". */
class InactiveProductPipelineTest {

    private val t0 = Instant.parse("2026-10-03T12:00:00Z")
    private val activeKey = ProductKey("PROD-001")
    private val inactiveKey = ProductKey("PROD-002")

    private fun product(key: ProductKey, name: String, status: String) = CommercialProduct(
        key = key, itemName = name,
        publishedPrice = Money.of(120_000), minimumPrice = Money.of(95_000), cost = Money.of(70_000),
        status = status, description = "Descripción pública", brand = "Marca"
    )

    private class FakeCatalog(products: List<CommercialProduct>, stock: Int) : PipelineCatalog {
        val prods = products.associateBy { it.key }
        val inv = products.associate { it.key to OperationalInventory(it.key, stockMaestro = stock) }.toMutableMap()
        override fun productKeys() = prods.values.filter { it.isActive }.map { it.key }
        override fun knownKeys() = prods.keys.toList()
        override fun product(key: ProductKey) = prods[key]
        override fun inventory(key: ProductKey) = inv[key]
        override fun saveInventory(inventory: OperationalInventory) { inv[inventory.productKey] = inventory }
    }

    private class FakeLlm : LlmPort {
        var next: ParsedCustomerIntent = ParsedCustomerIntent(CustomerIntentType.OTHER, rawText = "")
        var receivedKeys: List<ProductKey> = emptyList()
        override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>): ParsedCustomerIntent {
            receivedKeys = availableProducts
            return next
        }
    }

    private class FakePort(override val channelName: String) : MessagingPort {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun sendMessage(recipientId: String, text: String): PortResult<String> {
            sent.add(recipientId to text)
            return PortResult.Ok("id")
        }
        override suspend fun sendProductCard(recipientId: String, productTitle: String, priceFormatted: String, imageUrl: String) =
            sendMessage(recipientId, productTitle)
    }

    private class Setup(val catalog: FakeCatalog) {
        val llm = FakeLlm()
        val store = InMemoryConversationStore()
        val pipeline = InboundMessagePipeline(llm, NegotiationEngine(), catalog, store, ChannelRouter(listOf(FakePort("whatsapp"))))
    }

    private fun setup(): Setup = Setup(
        FakeCatalog(
            listOf(
                product(activeKey, "Audífonos", "Activo"),
                product(inactiveKey, "Reloj retirado", "Inactivo")
            ),
            stock = 5
        )
    )

    private fun intent(type: CustomerIntentType, key: ProductKey? = null, price: Long? = null) =
        ParsedCustomerIntent(type, key, price?.let { Money.of(it) }, "texto")

    @Test
    fun `preguntar por un producto inactivo responde no disponible y ofrece los activos`() {
        runBlocking {
            val s = setup()
            s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO, inactiveKey)

            val out = s.pipeline.handle("whatsapp", "573001", "m1", "info del PROD-002", t0)

            val reply = out.reply!!
            assertTrue(reply.contains("no está disponible"), reply)
            assertTrue(reply.contains("PROD-001"), "debe ofrecer los productos activos: $reply")
            assertFalse(reply.contains("PROD-002"), "no debe listar el inactivo: $reply")
            assertNull(out.conversation.activeProductKey)
        }
    }

    @Test
    fun `ofertar o reservar un producto inactivo no negocia ni reserva`() {
        runBlocking {
            val s = setup()

            s.llm.next = intent(CustomerIntentType.MAKE_OFFER, inactiveKey, 120_000)
            val offer = s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000 por el PROD-002", t0)
            assertTrue(offer.reply!!.contains("no está disponible"))
            assertNotEquals(ConversationState.PAYMENT_PENDING, offer.conversation.state)

            s.llm.next = intent(CustomerIntentType.REQUEST_RESERVATION, inactiveKey)
            val reserve = s.pipeline.handle("whatsapp", "573001", "m2", "apártame el PROD-002", t0)
            assertTrue(reserve.reply!!.contains("no está disponible"))

            assertEquals(0, s.catalog.inv[inactiveKey]!!.reservasActivas)
            assertEquals(0, s.pipeline.activeReservationCount())
        }
    }

    @Test
    fun `el interprete recibe tambien la clave del producto inactivo para no confundirlo con otro`() {
        runBlocking {
            val s = setup()
            s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO, inactiveKey)

            s.pipeline.handle("whatsapp", "573001", "m1", "info del PROD-002", t0)

            assertTrue(inactiveKey in s.llm.receivedKeys)
            assertTrue(activeKey in s.llm.receivedKeys)
        }
    }

    @Test
    fun `un producto activo sigue negociandose con normalidad`() {
        runBlocking {
            val s = setup()
            s.llm.next = intent(CustomerIntentType.MAKE_OFFER, activeKey, 120_000)

            val out = s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000 por el PROD-001", t0)

            assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state)
            assertEquals(1, s.catalog.inv[activeKey]!!.reservasActivas)
        }
    }

    @Test
    fun `sin codigo en el mensaje el bot usa el unico producto activo, nunca el inactivo`() {
        runBlocking {
            val s = setup()
            s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO)

            val out = s.pipeline.handle("whatsapp", "573001", "m1", "¿cuánto vale?", t0)

            val reply = out.reply!!
            assertTrue(reply.contains("Audífonos"), reply)
            assertFalse(reply.contains("Reloj retirado"), reply)
        }
    }
}
