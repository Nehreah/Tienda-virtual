package com.automata.shared

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Eventos de Dominio que comunican desacopladamente los contextos de Mensajería y Comercio.
 * Regla de Oro: Ningún evento de dominio puede contener información financiera interna
 * (PM, Costo, Ganancia o Margen).
 */
@Serializable
sealed interface DomainEvent {
    val eventId: String
    val timestamp: Instant
}

@Serializable
data class OfferReceivedEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val conversationId: String,
    val messageId: String,
    val productKey: ProductKey,
    val offeredAmount: Money,
    val tradeOffered: String? = null
) : DomainEvent

@Serializable
data class ReservationRequestedEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val conversationId: String,
    val productKey: ProductKey,
    val quantity: Int = 1
) : DomainEvent

@Serializable
data class NegotiationDecidedEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val conversationId: String,
    val negotiationId: String,
    val accepted: Boolean,
    val agreedPrice: Money?,
    val level: String, // L0..L4
    val messageToCustomer: String
) : DomainEvent

@Serializable
data class ReservationConfirmedEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val reservationId: String,
    val conversationId: String,
    val productKey: ProductKey,
    val quantity: Int,
    val expiresAt: Instant
) : DomainEvent

@Serializable
data class ReservationExpiredEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val reservationId: String,
    val productKey: ProductKey
) : DomainEvent

@Serializable
data class ProductSoldEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val orderId: String,
    val channel: String, // e.g., "shopify", "whatsapp", "mercadolibre"
    val productKey: ProductKey,
    val quantity: Int,
    val soldPrice: Money
) : DomainEvent

@Serializable
data class ProductUnavailableEvent(
    override val eventId: String,
    override val timestamp: Instant = Clock.System.now(),
    val productKey: ProductKey,
    val reason: String
) : DomainEvent
