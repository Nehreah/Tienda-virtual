package com.automata.app.workers

import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.infra.db.OutboxTable
import com.automata.infra.db.PublicacionesTable
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class OutboxEntryRecord(
    val eventId: String,
    val sku: String,
    val channel: String,
    val version: Long,
    val stockObjetivo: Int,
    val status: String,
    val attempts: Int
)

class OutboxWorker(
    private val adapters: List<CatalogPublishingPort>,
    private val maxRetries: Int = 3
) {

    fun enqueueStockChange(sku: String, channel: String, version: Long, stockObjetivo: Int): String = transaction {
        val eventId = "OUT-${UUID.randomUUID()}"
        OutboxTable.insert { row ->
            row[OutboxTable.eventId] = eventId
            row[OutboxTable.tipo] = "stock_changed"
            row[OutboxTable.sku] = sku
            row[OutboxTable.channel] = channel
            row[OutboxTable.version] = version
            row[OutboxTable.stockObjetivo] = stockObjetivo
            row[OutboxTable.status] = "PENDING"
            row[OutboxTable.attempts] = 0
            row[OutboxTable.createdAt] = Instant.now()
        }
        eventId
    }

    suspend fun processBatch(batchSize: Int = 10): Int {
        val pending = transaction {
            val now = Instant.now()
            OutboxTable.select {
                (OutboxTable.status eq "PENDING") or (OutboxTable.status eq "FAILED")
            }
                .andWhere { OutboxTable.proximaEjecucion lessEq now }
                .limit(batchSize)
                .map { row -> row.toRecord() }
        }

        var processedCount = 0
        for (entry in pending) {
            val marked = transaction {
                OutboxTable.update({ (OutboxTable.eventId eq entry.eventId) and ((OutboxTable.status eq "PENDING") or (OutboxTable.status eq "FAILED")) }) { row ->
                    row[OutboxTable.status] = "SENDING"
                    row[OutboxTable.attempts] = entry.attempts + 1
                    row[OutboxTable.lastAttemptAt] = Instant.now()
                }
            }
            if (marked == 0) continue

            if (!com.automata.infra.config.AppConfig.channelWriteEnabled) {
                transaction {
                    OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                        row[OutboxTable.status] = "SKIPPED_READ_ONLY"
                        row[OutboxTable.errorMessage] = "CHANNEL_WRITE_ENABLED es false (Modo Solo Lectura)"
                    }
                }
                processedCount++
                continue
            }

            val targetAdapters = if (entry.channel.equals("ALL", ignoreCase = true)) {
                adapters
            } else {
                adapters.filter { it.channelName.equals(entry.channel, ignoreCase = true) }
            }

            if (targetAdapters.isEmpty()) {
                transaction {
                    OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                        row[OutboxTable.status] = "DEAD"
                        row[OutboxTable.errorMessage] = "No existe adaptador para el canal ${entry.channel}"
                    }
                }
                continue
            }

            try {
                val productKey = ProductKey(entry.sku)
                var allSuccess = true
                var lastErrorMessage: String? = null

                for (adapter in targetAdapters) {
                    val result = adapter.setStock(productKey, entry.sku, entry.stockObjetivo, entry.version)
                    if (result is PortResult.Failure) {
                        val errorMsg = when (result) {
                            is PortResult.Permanent -> "${result.code}: ${result.detail}"
                            is PortResult.Transient -> result.cause
                            is PortResult.RateLimited -> "Rate limited for ${result.retryAfterSeconds}s"
                            PortResult.AuthExpired -> "Auth expired"
                        }
                        if (!errorMsg.contains("NO_IMPLEMENTADO", ignoreCase = true)) {
                            allSuccess = false
                            lastErrorMessage = errorMsg
                        }
                    }
                }

                if (allSuccess) {
                    transaction {
                        OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                            row[OutboxTable.status] = "SENT"
                        }
                        for (adapter in targetAdapters) {
                            val count = PublicacionesTable.update({
                                (PublicacionesTable.sku eq entry.sku) and (PublicacionesTable.canal eq adapter.channelName)
                            }) { row ->
                                row[PublicacionesTable.stockPublicado] = entry.stockObjetivo
                                row[PublicacionesTable.ultimaSincronizacion] = Instant.now()
                            }
                            if (count == 0) {
                                PublicacionesTable.insert { row ->
                                    row[PublicacionesTable.sku] = entry.sku
                                    row[PublicacionesTable.canal] = adapter.channelName
                                    row[PublicacionesTable.externalId] = entry.sku
                                    row[PublicacionesTable.publicado] = true
                                    row[PublicacionesTable.stockPublicado] = entry.stockObjetivo
                                    row[PublicacionesTable.ultimaSincronizacion] = Instant.now()
                                }
                            }
                        }
                    }
                    processedCount++
                } else {
                    val newIntentos = entry.attempts + 1
                    val newStatus = if (newIntentos >= maxRetries) "DEAD" else "FAILED"
                    val retryDelaySec = (10L * newIntentos * newIntentos)

                    transaction {
                        OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                            row[OutboxTable.status] = newStatus
                            row[OutboxTable.errorMessage] = lastErrorMessage
                            row[OutboxTable.proximaEjecucion] = Instant.now().plusSeconds(retryDelaySec)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val newIntentos = entry.attempts + 1
                val newStatus = if (newIntentos >= maxRetries) "DEAD" else "FAILED"
                val retryDelaySec = (10L * newIntentos * newIntentos)

                transaction {
                    OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                        row[OutboxTable.status] = newStatus
                        row[OutboxTable.errorMessage] = e.message
                        row[OutboxTable.proximaEjecucion] = Instant.now().plusSeconds(retryDelaySec)
                    }
                }
            }
        }
        return processedCount
    }

    private fun ResultRow.toRecord() = OutboxEntryRecord(
        eventId = this[OutboxTable.eventId],
        sku = this[OutboxTable.sku],
        channel = this[OutboxTable.channel],
        version = this[OutboxTable.version],
        stockObjetivo = this[OutboxTable.stockObjetivo],
        status = this[OutboxTable.status],
        attempts = this[OutboxTable.attempts]
    )
}
