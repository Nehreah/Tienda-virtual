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
import kotlin.time.Duration.Companion.minutes

class InboundMessagePipelineTest {

    private val t0 = Instant.parse("2026-10-03T12:00:00Z")
    private val k1 = ProductKey("PROD-001")
    private val k2 = ProductKey("PROD-002")

    // PP 120.000 · PM 95.000 · costo 70.000
    private fun product(key: ProductKey, name: String) = CommercialProduct(
        key = key, itemName = name,
        publishedPrice = Money.of(120_000), minimumPrice = Money.of(95_000), cost = Money.of(70_000),
        status = "Activo", description = "Descripción pública", brand = "Marca"
    )

    private class FakeCatalog(products: List<CommercialProduct>, stock: Int) : PipelineCatalog {
        val prods = products.associateBy { it.key }
        val inv = products.associate { it.key to OperationalInventory(it.key, stockMaestro = stock) }.toMutableMap()
        override fun productKeys() = prods.keys.toList()
        override fun product(key: ProductKey) = prods[key]
        override fun inventory(key: ProductKey) = inv[key]
        override fun saveInventory(inventory: OperationalInventory) { inv[inventory.productKey] = inventory }
    }

    private class FakeLlm : LlmPort {
        var next: ParsedCustomerIntent = ParsedCustomerIntent(CustomerIntentType.OTHER, rawText = "")
        override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>) = next
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
        val port = FakePort("whatsapp")
        val store = InMemoryConversationStore()
        // Fase 3.1: seed fija para que los tests de negociación sean deterministas
        val engine = NegotiationEngine(random = java.util.Random(0L))
        val pipeline = InboundMessagePipeline(llm, engine, catalog, store, ChannelRouter(listOf(port)))
    }

    private fun setup(stock: Int = 5, vararg products: CommercialProduct) =
        Setup(FakeCatalog(products.toList(), stock))

    private fun intent(type: CustomerIntentType, key: ProductKey? = null, price: Long? = null) =
        ParsedCustomerIntent(type, key, price?.let { Money.of(it) }, "texto")

    @Test
    fun `info query answers with the public price and never leaks PM or cost`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO, k1)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "¿cuánto vale?", t0)

        val reply = out.reply!!
        assertTrue(reply.contains("120.000"))
        assertFalse(reply.contains("95.000"))
        assertFalse(reply.contains("70.000"))
        assertEquals(listOf("573001" to reply), s.port.sent)
        assertEquals(ConversationState.NEGOTIATING, out.conversation.state)
    }

    @Test
    fun `accepted offer reserves one unit and waits for payment`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 120_000)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)

        assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state)
        assertEquals(120_000L, out.conversation.agreedPriceCop)
        assertNotNull(out.conversation.currentReservationId)
        assertEquals(1, s.catalog.inv[k1]!!.reservasActivas)
        assertEquals(1, s.pipeline.activeReservationCount())
    }

    @Test
    fun `offer below PM is rejected without reserving`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "te doy 50000", t0)

        assertEquals(ConversationState.NEGOTIATING, out.conversation.state)
        assertNull(out.conversation.currentReservationId)
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
    }

    @Test
    fun `sold out product is never reserved`() = runBlocking {
        val s = setup(0, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.REQUEST_RESERVATION, k1)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "apártemelo", t0)

        assertTrue(out.reply!!.contains("no está disponible"))
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
        assertEquals(0, s.pipeline.activeReservationCount())
    }

    @Test
    fun `expired reservation releases the stock and reopens negotiation`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 120_000)
        s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)

        s.pipeline.sweep(t0 + 16.minutes)

        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
        assertEquals(0, s.pipeline.activeReservationCount())
        val conv = s.store.find("whatsapp", "573001")!!
        assertEquals(ConversationState.NEGOTIATING, conv.state)
        assertNull(conv.currentReservationId)
    }

    @Test
    fun `payment proof escalates to a human, keeps the reservation and silences the bot`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 120_000)
        s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)

        s.llm.next = intent(CustomerIntentType.SEND_PAYMENT_PROOF)
        val proof = s.pipeline.handle("whatsapp", "573001", "m2", "ya pagué, comprobante", t0 + 5.minutes)
        assertEquals(ConversationState.ESCALATED_HUMAN, proof.conversation.state)

        // pasan 30 min: la reserva en verificación NO debe vencer (el tiempo de gracia es mayor)
        s.pipeline.sweep(t0 + 35.minutes)
        assertEquals(1, s.catalog.inv[k1]!!.reservasActivas)

        s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO, k1)
        val silent = s.pipeline.handle("whatsapp", "573001", "m3", "¿ya?", t0 + 36.minutes)
        assertNull(silent.reply)
        assertEquals(2, s.port.sent.size) // solo las dos respuestas anteriores
    }

    @Test
    fun `message without text while payment is pending is treated as proof`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.REQUEST_RESERVATION, k1)
        s.pipeline.handle("whatsapp", "573001", "m1", "apártemelo", t0)

        val out = s.pipeline.handle("whatsapp", "573001", "m2", "", t0 + 2.minutes)

        assertEquals(ConversationState.ESCALATED_HUMAN, out.conversation.state)
    }

    @Test
    fun `with several products and no product mentioned it asks which one`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"), product(k2, "Reloj"))
        s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "hola", t0)

        val reply = out.reply!!
        assertTrue(reply.contains("PROD-001"))
        assertTrue(reply.contains("PROD-002"))
        assertFalse(reply.contains("95.000"))
    }

    @Test
    fun `an absurd amount such as a phone number never reserves`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 3_001_112_233)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "mi cel 3001112233", t0)

        assertTrue(out.reply!!.contains("No estoy seguro"))
        assertNull(out.conversation.currentReservationId)
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
        assertEquals(ConversationState.NEGOTIATING, out.conversation.state)
    }

    @Test
    fun `payment proof without a pending payment still escalates but does not claim a receipt`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.SEND_PAYMENT_PROOF)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "ya pagué por nequi", t0)

        assertEquals(ConversationState.ESCALATED_HUMAN, out.conversation.state)
        assertFalse(out.reply!!.contains("Recibimos"))
        assertTrue(out.reply!!.contains("asesor"))
    }

    @Test
    fun `late payment after the reservation expired escalates to a human`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 120_000)
        s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)
        s.pipeline.sweep(t0 + 16.minutes)

        s.llm.next = intent(CustomerIntentType.SEND_PAYMENT_PROOF)
        val out = s.pipeline.handle("whatsapp", "573001", "m2", "ya pagué, comprobante", t0 + 20.minutes)

        assertEquals(ConversationState.ESCALATED_HUMAN, out.conversation.state)
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
    }

    @Test
    fun `offer below PM gets a counter offer above PM and the floor is never mentioned`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)

        val out = s.pipeline.handle("whatsapp", "573001", "m1", "te doy 50000", t0)

        val counter = out.conversation.pendingCounterCop!!
        // Invariante: contraoferta > PM (95.000) y ≤ PP (120.000); no revela el PM.
        assertTrue(counter > 95_000L, "Contraoferta debe ser > PM")
        assertTrue(counter <= 120_000L, "Contraoferta debe ser <= PP")
        assertFalse(out.reply!!.contains("95.000"), "El mensaje no debe revelar el PM")
        assertEquals(ConversationState.NEGOTIATING, out.conversation.state)
        assertEquals(1, out.conversation.counterOfferCount)
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
    }

    @Test
    fun `insisting below PM lowers the counter step by step and never reveals PM`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)

        // Pasos 1-4: hasta que MAX_COUNTER_OFFERS=4 aún tiene contraofertas (no escala todavía).
        val results = (1..4).map { i ->
            s.pipeline.handle("whatsapp", "573001", "m$i", "te doy 50000", t0 + i.minutes)
        }

        val counters = results.mapNotNull { it.conversation.pendingCounterCop }
        assertTrue(counters.isNotEmpty(), "Debe haber al menos una contraoferta")
        assertTrue(counters.all { it > 95_000L }, "Todas las contraofertas deben ser > PM")
        assertTrue(counters.all { it <= 120_000L }, "Todas las contraofertas deben ser <= PP")

        // Ninguna respuesta (mientras aún las hubo) revela el PM.
        val replies = results.mapNotNull { it.reply }
        assertTrue(replies.none { it.contains("95.000") }, "Ningún mensaje debe revelar el PM")
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
    }

    @Test
    fun `accepting the pending counter offer reserves at that price`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)
        val firstOut = s.pipeline.handle("whatsapp", "573001", "m1", "te doy 50000", t0)
        val pendingPrice = firstOut.conversation.pendingCounterCop!!

        s.llm.next = intent(CustomerIntentType.ACCEPT_COUNTER_OFFER)
        val out = s.pipeline.handle("whatsapp", "573001", "m2", "dale", t0 + 1.minutes)

        assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state)
        // El precio acordado debe coincidir con la contraoferta pendiente (nunca el precio del cliente).
        assertEquals(pendingPrice, out.conversation.agreedPriceCop)
        assertNull(out.conversation.pendingCounterCop)
        assertEquals(0, out.conversation.counterOfferCount)
        assertEquals(1, s.catalog.inv[k1]!!.reservasActivas)
    }

    @Test
    fun `saying ok with no counter offer pending does not reserve`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.QUERY_PRODUCT_INFO, k1)
        s.pipeline.handle("whatsapp", "573001", "m1", "info", t0)

        s.llm.next = intent(CustomerIntentType.ACCEPT_COUNTER_OFFER)
        val out = s.pipeline.handle("whatsapp", "573001", "m2", "ok", t0 + 1.minutes)

        assertNull(out.conversation.currentReservationId)
        assertEquals(0, s.catalog.inv[k1]!!.reservasActivas)
    }

    @Test
    fun `switching product forgets the pending counter offer`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"), product(k2, "Reloj"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)
        s.pipeline.handle("whatsapp", "573001", "m1", "PROD-001 te doy 50000", t0)

        s.llm.next = intent(CustomerIntentType.ACCEPT_COUNTER_OFFER, k2)
        val out = s.pipeline.handle("whatsapp", "573001", "m2", "PROD-002 dale", t0 + 1.minutes)

        assertNull(out.conversation.currentReservationId)
        assertNull(out.conversation.pendingCounterCop)
        assertEquals(0, s.catalog.inv[k2]!!.reservasActivas)
    }

    // ── Fase 1.1 Tests: aceptar contraoferta en rondas 2, 3 y 4 ──────────────────────────────

    /**
     * Escenario base para probar aceptación en ronda N:
     * hace N-1 ofertas bajas para acumular contraofertas, luego acepta.
     * Debe producir PAYMENT_PENDING al precio de la última contraoferta, nunca escalar a humano.
     */
    private suspend fun runAcceptCounterOfferRound(round: Int): PipelineOutcome {
        // PP=120.000 PM=95.000: cualquier oferta <95.000 es rechazada y genera contraoferta.
        val s = setup(5, product(k1, "Audífonos"))

        // Rondas 1..(round-1): acumular contraofertas sin aceptar.
        for (i in 1 until round) {
            s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)
            s.pipeline.handle("whatsapp", "573001", "m$i", "te doy 50000", t0 + i.minutes)
        }

        // Ronda N: el cliente acepta la contraoferta pendiente.
        s.llm.next = intent(CustomerIntentType.ACCEPT_COUNTER_OFFER)
        return s.pipeline.handle("whatsapp", "573001", "mAccept", "dale", t0 + round.minutes)
    }

    @Test
    fun `accepting counter offer in round 2 reserves at the pending price`() = runBlocking {
        val out = runAcceptCounterOfferRound(2)
        assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state,
            "Ronda 2: debe quedar en PAYMENT_PENDING, no en ${out.conversation.state}")
        assertNotNull(out.conversation.currentReservationId, "Debe haber reserva activa")
        assertNull(out.conversation.pendingCounterCop, "La contraoferta pendiente debe limpiarse")
        assertEquals(0, out.conversation.counterOfferCount, "El contador de contraofertas debe resetearse")
    }

    @Test
    fun `accepting counter offer in round 3 reserves at the pending price`() = runBlocking {
        val out = runAcceptCounterOfferRound(3)
        assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state,
            "Ronda 3: debe quedar en PAYMENT_PENDING, no en ${out.conversation.state}")
        assertNotNull(out.conversation.currentReservationId)
        assertNull(out.conversation.pendingCounterCop)
    }

    @Test
    fun `accepting counter offer in round 4 reserves at the pending price (not escalated)`() = runBlocking {
        // Round 4 = MAX_COUNTER_OFFERS. Con el bug antiguo esto escalaba a humano.
        val out = runAcceptCounterOfferRound(4)
        assertEquals(ConversationState.PAYMENT_PENDING, out.conversation.state,
            "Ronda 4: debe quedar en PAYMENT_PENDING, no en ${out.conversation.state}. " +
            "Si es ESCALATED_HUMAN el bug de ACCEPT_COUNTER_OFFER no está corregido.")
        assertNotNull(out.conversation.currentReservationId)
        assertNull(out.conversation.pendingCounterCop)
        assertNotEquals(ConversationState.ESCALATED_HUMAN, out.conversation.state)
    }

    @Test
    fun `the agreed price when accepting a counter offer matches the pending counter price`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)
        val firstOut = s.pipeline.handle("whatsapp", "573001", "m1", "te doy 50000", t0)
        val counterPrice = firstOut.conversation.pendingCounterCop!!

        // Tercera ronda: otra oferta baja para generar una segunda contraoferta.
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 50_000)
        val secondOut = s.pipeline.handle("whatsapp", "573001", "m2", "te doy 50000", t0 + 1.minutes)
        val counter2Price = secondOut.conversation.pendingCounterCop!!
        assertNotEquals(counterPrice, counter2Price, "Las contraofertas de rondas distintas suelen diferir")

        // Aceptar la segunda contraoferta: el precio acordado debe ser counter2Price (no el del cliente).
        s.llm.next = intent(CustomerIntentType.ACCEPT_COUNTER_OFFER)
        val acceptOut = s.pipeline.handle("whatsapp", "573001", "m3", "dale", t0 + 2.minutes)
        assertEquals(ConversationState.PAYMENT_PENDING, acceptOut.conversation.state)
        assertEquals(counter2Price, acceptOut.conversation.agreedPriceCop,
            "El precio acordado debe ser la contraoferta del servidor, no el precio del cliente (50.000)")
        assertTrue(acceptOut.conversation.agreedPriceCop!! > 95_000L,
            "El precio acordado siempre debe ser > PM (95.000)")
    }

    // ── Fase 1.5 Test: confirmPayment() no libera el stock ───────────────────────────────────

    @Test
    fun `confirmPayment keeps the reservation alive after normal expiry time`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        // 1. Hacer una reserva.
        s.llm.next = intent(CustomerIntentType.MAKE_OFFER, k1, 120_000)
        val reserved = s.pipeline.handle("whatsapp", "573001", "m1", "te pago 120000", t0)
        assertEquals(ConversationState.PAYMENT_PENDING, reserved.conversation.state)
        assertEquals(1, s.catalog.inv[k1]!!.reservasActivas)

        // 2. Confirmar el pago (el asesor lo hace desde el dashboard).
        val confirmed = s.pipeline.confirmPayment(reserved.conversation.conversationId, t0 + 5.minutes)
        assertTrue(confirmed, "confirmPayment debe retornar true cuando hay reserva activa")

        // 3. El barrido de 16 minutos NO debe liberar el stock (la reserva fue extendida).
        s.pipeline.sweep(t0 + 16.minutes)
        assertEquals(1, s.catalog.inv[k1]!!.reservasActivas,
            "El stock NO debe liberarse tras confirmar el pago, aunque hayan pasado los 15 min de reserva")
        assertEquals(1, s.pipeline.activeReservationCount())

        // 4. La conversación debe estar en PAID_CONFIRMED.
        val updatedConv = s.store.find("whatsapp", "573001")
        assertEquals(ConversationState.PAID_CONFIRMED, updatedConv?.state,
            "La conversación debe estar en PAID_CONFIRMED tras confirmar el pago")
    }

    @Test
    fun `confirmPayment returns false when there is no active reservation`() = runBlocking {
        val s = setup(5, product(k1, "Audífonos"))
        // Sin reserva: confirmPayment debe retornar false.
        val result = s.pipeline.confirmPayment("whatsapp:573001")
        assertFalse(result, "confirmPayment debe retornar false si no hay conversación/reserva activa")
    }
}

