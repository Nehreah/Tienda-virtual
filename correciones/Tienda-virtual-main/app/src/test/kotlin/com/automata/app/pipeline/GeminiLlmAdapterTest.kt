package com.automata.app.pipeline

import com.automata.infra.llm.GeminiLlmAdapter
import com.automata.messaging.ports.CustomerIntentType
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GeminiLlmAdapterTest {

    private val adapter = GeminiLlmAdapter()
    private val keys = listOf(ProductKey("PROD-001"), ProductKey("PROD-002"))

    @Test
    fun `offer is detected even when the message also contains a product code`() = runBlocking {
        val intent = adapter.parseIntent("Hola, PROD-001 te ofrezco 110000", keys)

        assertEquals(CustomerIntentType.MAKE_OFFER, intent.intentType)
        assertEquals(ProductKey("PROD-001"), intent.extractedProductKey)
        assertEquals(110_000L, intent.offeredPrice?.amountInCop)
    }

    @Test
    fun `price with thousand separators is read`() = runBlocking {
        val intent = adapter.parseIntent("te pago \$110.000 por el PROD-002", keys)

        assertEquals(110_000L, intent.offeredPrice?.amountInCop)
        assertEquals(ProductKey("PROD-002"), intent.extractedProductKey)
    }

    @Test
    fun `a product code alone is not a price`() = runBlocking {
        val intent = adapter.parseIntent("info del PROD-001 por favor", keys)

        assertNull(intent.offeredPrice)
        assertEquals(CustomerIntentType.QUERY_PRODUCT_INFO, intent.intentType)
    }

    @Test
    fun `mil and k suffixes are read as thousands`() = runBlocking {
        assertEquals(110_000L, adapter.parseIntent("te ofrezco 110 mil", keys).offeredPrice?.amountInCop)
        assertEquals(95_000L, adapter.parseIntent("dejalo en 95K", keys).offeredPrice?.amountInCop)
        assertEquals(CustomerIntentType.MAKE_OFFER, adapter.parseIntent("PROD-001 en 100 mil pesos?", keys).intentType)
    }

    @Test
    fun `comma as thousand separator is read`() = runBlocking {
        assertEquals(110_000L, adapter.parseIntent("te pago 110,000", keys).offeredPrice?.amountInCop)
    }

    @Test
    fun `plain words with mil or k are not prices`() = runBlocking {
        assertNull(adapter.parseIntent("mil gracias, ¿tienen 2 kilos de empaque?", keys).offeredPrice)
    }

    @Test
    fun `short agreement messages are read as accepting a counter offer`() = runBlocking {
        for (msg in listOf("ok", "Dale", "listo, gracias", "sí, lo tomo", "de una", "Perfecto!", "me sirve")) {
            assertEquals(CustomerIntentType.ACCEPT_COUNTER_OFFER, adapter.parseIntent(msg, keys).intentType, msg)
        }
    }

    @Test
    fun `messages that only start like an agreement are not acceptance`() = runBlocking {
        for (msg in listOf("si me lo envían a Bogotá", "ok pero más barato", "no, gracias", "dale 100 mil", "¿ok?", "sí pero tiene garantía")) {
            assertNotEquals(CustomerIntentType.ACCEPT_COUNTER_OFFER, adapter.parseIntent(msg, keys).intentType, msg)
        }
    }

    @Test
    fun `human and payment proof keywords win over acceptance`() = runBlocking {
        assertEquals(CustomerIntentType.SEND_PAYMENT_PROOF, adapter.parseIntent("listo, transferencia", keys).intentType)
        assertEquals(CustomerIntentType.REQUEST_HUMAN_AGENT, adapter.parseIntent("ok quiero un asesor", keys).intentType)
    }
}
