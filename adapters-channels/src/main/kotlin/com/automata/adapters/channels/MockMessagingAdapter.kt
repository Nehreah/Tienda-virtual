package com.automata.adapters.channels

import com.automata.messaging.ports.MessagingPort
import com.automata.shared.PortResult

class MockMessagingAdapter(
    override val channelName: String
) : MessagingPort {

    val sentMessages = mutableListOf<Pair<String, String>>()

    override suspend fun sendMessage(recipientId: String, text: String): PortResult<String> {
        sentMessages.add(recipientId to text)
        return PortResult.Ok("msg_mock_${System.currentTimeMillis()}")
    }

    override suspend fun sendProductCard(
        recipientId: String,
        productTitle: String,
        priceFormatted: String,
        imageUrl: String
    ): PortResult<String> {
        val text = "Tarjeta: $productTitle - $priceFormatted"
        return sendMessage(recipientId, text)
    }
}
