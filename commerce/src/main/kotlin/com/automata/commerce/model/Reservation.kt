package com.automata.commerce.model

import com.automata.shared.ProductKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Reserva temporal con TTL para evitar ventas duplicadas durante las conversaciones de venta.
 */
data class Reservation(
    val reservationId: String,
    val conversationId: String,
    val productKey: ProductKey,
    val quantity: Int,
    val createdAt: Instant = Clock.System.now(),
    val expiresAt: Instant = Clock.System.now() + 15.minutes,
    val status: ReservationStatus = ReservationStatus.ACTIVE
) {
    val isExpired: Boolean
        get() = Clock.System.now() >= expiresAt || status == ReservationStatus.EXPIRED

    enum class ReservationStatus {
        ACTIVE, CONFIRMED_SALE, EXPIRED, CANCELLED
    }
}
