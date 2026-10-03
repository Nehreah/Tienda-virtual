package com.automata.messaging.model

import com.automata.shared.ProductKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationState {
    IDLE,
    NEGOTIATING,
    RESERVED,
    PAYMENT_PENDING,
    PAID_CONFIRMED,
    ESCALATED_HUMAN,
    CLOSED
}

@Serializable
data class Message(
    val messageId: String,
    val sender: String, // "CUSTOMER" | "BOT" | "HUMAN"
    val content: String,
    val timestamp: Instant = Clock.System.now()
)

@Serializable
data class Conversation(
    val conversationId: String,
    val customerPhoneOrId: String,
    val channel: String, // "whatsapp", "instagram", "messenger"
    val activeProductKey: ProductKey? = null,
    val state: ConversationState = ConversationState.IDLE,
    val messages: List<Message> = emptyList(),
    val currentReservationId: String? = null,
    val agreedPriceCop: Long? = null, // precio acordado en la negociación (solo precio de venta, nunca PM/costo)
    val counterOfferCount: Int = 0, // contraofertas ya hechas al cliente por el producto activo
    val pendingCounterCop: Long? = null, // última contraoferta que el cliente aún no aceptó (siempre un precio de venta)
    val lastUpdated: Instant = Clock.System.now()
)
