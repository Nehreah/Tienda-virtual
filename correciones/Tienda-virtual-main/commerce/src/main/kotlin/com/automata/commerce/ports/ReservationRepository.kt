package com.automata.commerce.ports

import com.automata.commerce.model.Reservation
import kotlinx.datetime.Instant

/**
 * Puerto de persistencia de reservas (Fase F1 — Hallazgos H1/H2).
 * Las implementaciones son:
 *  - [InMemoryReservationRepository] para tests sin base de datos.
 *  - ExposedReservationRepository (en :infra) para PostgreSQL en producción.
 */
interface ReservationRepository {

    /**
     * Intenta crear la reserva de forma atómica.
     * Devuelve true si fue creada; false si no había stock disponible
     * o ya existe una reserva ACTIVE para la misma conversación+producto.
     */
    suspend fun tryReserve(reservation: Reservation): Boolean

    /**
     * Marca la reserva como CONFIRMED (pago verificado por el asesor).
     * Idempotente: no falla si ya estaba confirmada.
     */
    suspend fun confirm(reservationId: String): Boolean

    /**
     * Cancela la reserva (CANCELLED). El stock queda libre. Idempotente.
     */
    suspend fun release(reservationId: String): Boolean

    /**
     * Marca como EXPIRED todas las reservas ACTIVE cuyo expires_at <= [now].
     * Devuelve la lista de reservas expiradas para que el pipeline libere
     * el stock en memoria y actualice las conversaciones asociadas.
     */
    suspend fun expireDue(now: Instant): List<Reservation>

    /**
     * Devuelve la reserva ACTIVE o CONFIRMED más reciente de una conversación,
     * o null si no existe. Se usa para mostrar el tiempo restante al cliente.
     */
    suspend fun findActiveByConversation(conversationId: String): Reservation?

    /** Busca cualquier reserva por su ID (cualquier estado). */
    suspend fun findById(reservationId: String): Reservation?

    /**
     * Carga todas las reservas con status ACTIVE o CONFIRMED.
     * Se llama al arrancar el servidor para recuperar el estado ante
     * reinicios y re-hidratar el mapa en memoria (Hallazgo H1).
     */
    suspend fun loadAllActive(): List<Reservation>

    /**
     * Extiende la fecha de expiración de una reserva.
     * Se usa al recibir un comprobante de pago (proofHold de 12 h).
     */
    suspend fun extendExpiry(reservationId: String, newExpiry: Instant): Boolean
}
