package com.automata.messaging.ports

import com.automata.messaging.model.Message
import com.automata.shared.PortResult

interface MessagingPort {
    val channelName: String
    suspend fun sendMessage(recipientId: String, text: String): PortResult<String>
    suspend fun sendProductCard(recipientId: String, productTitle: String, priceFormatted: String, imageUrl: String): PortResult<String>
}
