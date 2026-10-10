package com.automata.infra.db

import com.automata.commerce.model.InventarioItem
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class OutboxEventPublisher {

    private val logger = LoggerFactory.getLogger(OutboxEventPublisher::class.java)

    fun publishEvent(
        tipo: String,
        sku: String,
        canal: String = "ALL",
        stockObjetivo: Int = 0,
        payloadJson: String? = null
    ) {
        val eventId = "OUT-${UUID.randomUUID().toString().take(8).uppercase()}"
        try {
            transaction {
                OutboxTable.insert { row ->
                    row[OutboxTable.eventId] = eventId
                    row[OutboxTable.tipo] = tipo
                    row[OutboxTable.sku] = sku
                    row[OutboxTable.channel] = canal
                    row[OutboxTable.stockObjetivo] = stockObjetivo
                    row[OutboxTable.payloadJson] = payloadJson
                    row[OutboxTable.status] = "PENDING"
                    row[OutboxTable.attempts] = 0
                    row[OutboxTable.proximaEjecucion] = Instant.now()
                    row[OutboxTable.createdAt] = Instant.now()
                }
            }
            logger.info("Evento Outbox $eventId ($tipo para $sku) encolado exitosamente")
        } catch (e: Exception) {
            logger.error("Error encolando evento Outbox para $sku: ${e.message}", e)
        }
    }
}
