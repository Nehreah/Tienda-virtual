package com.automata.messaging.ports

import com.automata.commerce.model.Reservation
import com.automata.commerce.ports.ReservationRepository
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementación en memoria de [ReservationRepository] para tests y modo sin BD.
 *
 * La concurrencia se controla con [synchronized] sobre [store] —suficiente para tests
 * con corrutinas, donde el dispatcher es controlado por [runBlocking].
 */
class InMemoryReservationRepository : ReservationRepository {

    val store = ConcurrentHashMap<String, Reservation>()

    override suspend fun tryReserve(reservation: Reservation): Boolean {
        synchronized(store) {
            // Rechaza si ya existe una reserva ACTIVE para la misma conversación+producto
            val conflict = store.values.any {
                it.conversationId == reservation.conversationId &&
                it.productKey == reservation.productKey &&
                it.status == Reservation.ReservationStatus.ACTIVE
            }
            if (conflict) return false
            store[reservation.reservationId] = reservation
            return true
        }
    }

    override suspend fun confirm(reservationId: String): Boolean {
        synchronized(store) {
            val r = store[reservationId] ?: return false
            if (r.status != Reservation.ReservationStatus.ACTIVE) return true // idempotente
            store[reservationId] = r.copy(status = Reservation.ReservationStatus.CONFIRMED)
            return true
        }
    }

    override suspend fun release(reservationId: String): Boolean {
        synchronized(store) {
            val r = store[reservationId] ?: return false
            if (r.status != Reservation.ReservationStatus.ACTIVE) return true // idempotente
            store[reservationId] = r.copy(status = Reservation.ReservationStatus.CANCELLED)
            return true
        }
    }

    override suspend fun expireDue(now: Instant): List<Reservation> {
        synchronized(store) {
            val expired = store.values.filter {
                it.status == Reservation.ReservationStatus.ACTIVE && now >= it.expiresAt
            }
            expired.forEach { r ->
                store[r.reservationId] = r.copy(status = Reservation.ReservationStatus.EXPIRED)
            }
            return expired
        }
    }

    override suspend fun findActiveByConversation(conversationId: String): Reservation? =
        synchronized(store) {
            store.values
                .filter {
                    it.conversationId == conversationId &&
                    (it.status == Reservation.ReservationStatus.ACTIVE ||
                     it.status == Reservation.ReservationStatus.CONFIRMED)
                }
                .maxByOrNull { it.createdAt }
        }

    override suspend fun findById(reservationId: String): Reservation? =
        store[reservationId]

    override suspend fun loadAllActive(): List<Reservation> =
        synchronized(store) {
            store.values.filter {
                it.status == Reservation.ReservationStatus.ACTIVE ||
                it.status == Reservation.ReservationStatus.CONFIRMED
            }.toList()
        }

    override suspend fun extendExpiry(reservationId: String, newExpiry: Instant): Boolean {
        synchronized(store) {
            val r = store[reservationId] ?: return false
            store[reservationId] = r.copy(expiresAt = newExpiry)
            return true
        }
    }
}
