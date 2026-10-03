package com.automata.messaging.ports

import com.automata.shared.PortResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChannelRouterTest {

    private class FakePort(override val channelName: String) : MessagingPort {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun sendMessage(recipientId: String, text: String): PortResult<String> {
            sent.add(recipientId to text)
            return PortResult.Ok("id_$channelName")
        }
        override suspend fun sendProductCard(
            recipientId: String, productTitle: String, priceFormatted: String, imageUrl: String
        ): PortResult<String> = sendMessage(recipientId, "$productTitle $priceFormatted")
    }

    @Test
    fun `message goes only to the adapter of its channel`() = runBlocking {
        val wa = FakePort("whatsapp")
        val ig = FakePort("instagram")
        val router = ChannelRouter(listOf(wa, ig))

        val result = router.sendMessage("instagram", "user1", "hola")

        assertEquals(PortResult.Ok("id_instagram"), result)
        assertEquals(listOf("user1" to "hola"), ig.sent)
        assertTrue(wa.sent.isEmpty())
    }

    @Test
    fun `unknown channel returns a permanent failure instead of throwing`() = runBlocking {
        val router = ChannelRouter(listOf(FakePort("whatsapp")))
        val result = router.sendMessage("telegram", "u", "hola")
        assertTrue(result is PortResult.Permanent)
        assertFalse(router.supports("telegram"))
        assertTrue(router.supports("WhatsApp"))
    }

    @Test
    fun `two adapters for the same channel are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelRouter(listOf(FakePort("whatsapp"), FakePort("whatsapp")))
        }
    }
}
