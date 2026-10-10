package com.automata.infra.db

import com.automata.commerce.model.Reservation
import com.automata.commerce.ports.ReservationRepository
import com.automata.shared.ProductKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant as JInstant

/**
 * Implementación de [ReservationRepository] sobre tienda.reservas con Exposed + PostgreSQL.
 *
 * Concurrencia (H2): tryReserve usa INSERT directo. La exclusión mutua real se logra
 * mediante el constraint UNIQUE (conversation_id, product_key) WHERE status='ACTIVE'
 * o con el bloqueo pesimista de stock en OperationalInventory. En una instancia única
 * del proceso Ktor, los locks por producto en InboundMessagePipeline protegen la
 * sección crítica; la BD es el respaldo persistente.
 */
class ExposedReservationRepository : ReservationRepository {

    private val logger = LoggerFactory.getLogger(ExposedReservationRepository::class.java)

    override suspend fun tryReserve(reservation: Reservation): Boolean = withContext(Dispatchers.IO) {
        try {
            transaction {
                ReservasTable.insert { row ->
                    row[ReservasTable.reservationId]   = reservation.reservationId
                    row[ReservasTable.conversationId]  = reservation.conversationId
                    row[ReservasTable.productKey]      = reservation.productKey.value
                    row[ReservasTable.quantity]        = reservation.quantity
                    row[ReservasTable.status]          = reservation.status.name
                    row[ReservasTable.expiresAt]       = reservation.expiresAt.toJavaInstant()
                    row[ReservasTable.createdAt]       = reservation.createdAt.toJavaInstant()
                    row[ReservasTable.updatedAt]       = reservation.createdAt.toJavaInstant()
                }
            }
            true
        } catch (e: Exception) {
            // Violación de constraint (duplicado) o error de BD → no se pudo reservar.
            logger.warn("tryReserve falló para ${reservation.reservationId}: ${e.message}")
            false
        }
    }

    override suspend fun confirm(reservationId: String): Boolean = withContext(Dispatchers.IO) {
        transaction {
            val rows = ReservasTable.update({
                (ReservasTable.reservationId eq reservationId) and
                (ReservasTable.status eq Reservation.ReservationStatus.ACTIVE.name)
            }) {
                it[ReservasTable.status]    = Reservation.ReservationStatus.CONFIRMED.name
                it[ReservasTable.updatedAt] = JInstant.now()
            }
            rows > 0
        }
    }

    override suspend fun release(reservationId: String): Boolean = withContext(Dispatchers.IO) {
        transaction {
            val rows = ReservasTable.update({
                (ReservasTable.reservationId eq reservationId) and
                (ReservasTable.status eq Reservation.ReservationStatus.ACTIVE.name)
            }) {
                it[ReservasTable.status]    = Reservation.ReservationStatus.CANCELLED.name
                it[ReservasTable.updatedAt] = JInstant.now()
            }
            rows > 0
        }
    }

    override suspend fun expireDue(now: Instant): List<Reservation> = withContext(Dispatchers.IO) {
        transaction {
            val expiredRows = ReservasTable
                .select {
                    (ReservasTable.status eq Reservation.ReservationStatus.ACTIVE.name) and
                    (ReservasTable.expiresAt lessEq now.toJavaInstant())
                }
                .map { it.toReservation() }

            if (expiredRows.isNotEmpty()) {
                ReservasTable.update({
                    (ReservasTable.status eq Reservation.ReservationStatus.ACTIVE.name) and
                    (ReservasTable.expiresAt lessEq now.toJavaInstant())
                }) {
                    it[ReservasTable.status]    = Reservation.ReservationStatus.EXPIRED.name
                    it[ReservasTable.updatedAt] = JInstant.now()
                }
            }
            expiredRows
        }
    }

    override suspend fun findActiveByConversation(conversationId: String): Reservation? = withContext(Dispatchers.IO) {
        transaction {
            ReservasTable
                .select {
                    (ReservasTable.conversationId eq conversationId) and
                    (ReservasTable.status eq Reservation.ReservationStatus.ACTIVE.name or
                     (ReservasTable.status eq Reservation.ReservationStatus.CONFIRMED.name))
                }
                .orderBy(ReservasTable.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
                .firstOrNull()
                ?.toReservation()
        }
    }

    override suspend fun findById(reservationId: String): Reservation? = withContext(Dispatchers.IO) {
        transaction {
            ReservasTable
                .select { ReservasTable.reservationId eq reservationId }
                .singleOrNull()
                ?.toReservation()
        }
    }

    override suspend fun loadAllActive(): List<Reservation> = withContext(Dispatchers.IO) {
        transaction {
            ReservasTable
                .select {
                    ReservasTable.status eq Reservation.ReservationStatus.ACTIVE.name or
                    (ReservasTable.status eq Reservation.ReservationStatus.CONFIRMED.name)
                }
                .map { it.toReservation() }
        }
    }

    override suspend fun extendExpiry(reservationId: String, newExpiry: Instant): Boolean = withContext(Dispatchers.IO) {
        transaction {
            val rows = ReservasTable.update({ ReservasTable.reservationId eq reservationId }) {
                it[ReservasTable.expiresAt] = newExpiry.toJavaInstant()
                it[ReservasTable.updatedAt] = JInstant.now()
            }
            rows > 0
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun org.jetbrains.exposed.sql.ResultRow.toReservation(): Reservation {
        val statusName = this[ReservasTable.status]
        val status = runCatching { Reservation.ReservationStatus.valueOf(statusName) }
            .getOrDefault(Reservation.ReservationStatus.ACTIVE)

        return Reservation(
            reservationId   = this[ReservasTable.reservationId],
            conversationId  = this[ReservasTable.conversationId],
            productKey      = ProductKey(this[ReservasTable.productKey]),
            quantity        = this[ReservasTable.quantity],
            createdAt       = this[ReservasTable.createdAt].toKotlinInstant(),
            expiresAt       = this[ReservasTable.expiresAt].toKotlinInstant(),
            status          = status
        )
    }

    private fun JInstant.toKotlinInstant(): Instant =
        Instant.fromEpochMilliseconds(this.toEpochMilli())

    private fun Instant.toJavaInstant(): JInstant =
        JInstant.ofEpochMilli(this.toEpochMilliseconds())
}
