package com.automata.app.workers

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.ports.InventarioRepository
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
import org.slf4j.LoggerFactory
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
    val attempts: Int,
    val tipo: String = "stock_changed"
)

/**
 * Worker de salida hacia Shopify y Meta.
 *
 * v14: ahora respeta el TIPO de evento (antes todo se trataba como cambio de stock):
 *  - stock_changed              -> setStock en cada canal donde el artículo está publicado
 *  - product_updated            -> updateListing (datos y precio) + setStock
 *  - CATALOG_UPDATE / _PUBLISH  -> updateListing (el stock lo mandan los eventos de stock)
 *  - CATALOG_UNPUBLISH          -> unpublishProduct (stock 0 en el canal)
 * Los eventos con canal "ALL" se expanden a todos los canales de la tabla publicaciones.
 * Un artículo que no está a la venta (archivado, Comprado, Vendido...) siempre se manda con stock 0.
 *
 * [inventarioRepo] es opcional solo para las pruebas; sin él no se pueden armar los datos del producto.
 */
class OutboxWorker(
    private val adapters: List<CatalogPublishingPort>,
    private val maxRetries: Int = 3,
    private val inventarioRepo: InventarioRepository? = null
) {
    private val logger = LoggerFactory.getLogger(OutboxWorker::class.java)

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
                .orderBy(OutboxTable.createdAt)
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

            val canalObjetivo = entry.channel
            val pubs = transaction {
                PublicacionesTable.select { PublicacionesTable.sku eq entry.sku }.toList()
            }.filter { row ->
                row[PublicacionesTable.publicado] &&
                    (canalObjetivo.equals("ALL", ignoreCase = true) ||
                        row[PublicacionesTable.canal].equals(canalObjetivo, ignoreCase = true))
            }

            if (pubs.isEmpty()) {
                transaction {
                    OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                        row[OutboxTable.status] = "SKIPPED_NOT_PUBLISHED"
                        row[OutboxTable.errorMessage] = "El producto ${entry.sku} no está publicado en el canal ${entry.channel}"
                    }
                }
                processedCount++
                continue
            }

            val hayAdaptador = pubs.any { pub -> adapters.any { it.channelName.equals(pub[PublicacionesTable.canal], ignoreCase = true) } }
            if (!hayAdaptador) {
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
                val item: InventarioItem? = inventarioRepo?.findByKey(productKey)
                var isNotImplemented = false
                var allSuccess = true
                var lastErrorMessage: String? = null
                val canalesOk = mutableListOf<Pair<String, Int?>>()

                for (pub in pubs) {
                    val canal = pub[PublicacionesTable.canal]
                    val adapter = adapters.firstOrNull { it.channelName.equals(canal, ignoreCase = true) }
                    if (adapter == null) {
                        allSuccess = false
                        lastErrorMessage = "No existe adaptador para el canal $canal"
                        continue
                    }
                    val externalId = pub[PublicacionesTable.externalId]
                    val stockId = pub[PublicacionesTable.externalInventoryId] ?: externalId
                    val (result, stockEnviado) = ejecutar(entry, adapter, productKey, externalId, stockId, item)

                    if (result is PortResult.Failure) {
                        allSuccess = false
                        lastErrorMessage = when (result) {
                            is PortResult.Permanent -> {
                                if (result.code.equals("NO_IMPLEMENTADO", ignoreCase = true) || result.detail.contains("NO_IMPLEMENTADO", ignoreCase = true)) {
                                    isNotImplemented = true
                                }
                                "$canal: ${result.code}: ${result.detail}"
                            }
                            is PortResult.Transient -> "$canal: ${result.cause}"
                            is PortResult.RateLimited -> "$canal: Rate limited for ${result.retryAfterSeconds}s"
                            PortResult.AuthExpired -> "$canal: Auth expired"
                        }
                        logger.warn("Outbox ${entry.eventId} (${entry.tipo}, ${entry.sku}) falló en $canal: $lastErrorMessage")
                    } else {
                        canalesOk.add(canal to stockEnviado)
                    }
                }

                if (isNotImplemented && canalesOk.isEmpty()) {
                    transaction {
                        OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                            row[OutboxTable.status] = "SKIPPED_NOT_IMPLEMENTED"
                            row[OutboxTable.errorMessage] = lastErrorMessage
                        }
                    }
                    processedCount++
                } else if (allSuccess) {
                    transaction {
                        OutboxTable.update({ OutboxTable.eventId eq entry.eventId }) { row ->
                            row[OutboxTable.status] = "SENT"
                        }
                        registrarSincronizacion(entry.sku, canalesOk)
                    }
                    processedCount++
                } else {
                    // Los canales que sí respondieron OK quedan registrados; el reintento repite todo (es idempotente: stock absoluto).
                    if (canalesOk.isNotEmpty()) transaction { registrarSincronizacion(entry.sku, canalesOk) }
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

    /**
     * Ejecuta la acción que corresponde al tipo de evento en UN canal.
     * Devuelve el resultado y el stock que quedó enviado (null si el evento no toca stock).
     */
    private suspend fun ejecutar(
        entry: OutboxEntryRecord,
        adapter: CatalogPublishingPort,
        productKey: ProductKey,
        externalId: String,
        stockId: String,
        item: InventarioItem?
    ): Pair<PortResult<Unit>, Int?> {
        // Sin repositorio (pruebas) no se puede saber el estado: se confía en el evento.
        val aLaVenta = item?.estaALaVenta() ?: true
        val stock = if (aLaVenta) entry.stockObjetivo else 0
        return when (entry.tipo) {
            "stock_changed" ->
                adapter.setStock(productKey, stockId, stock, entry.version) to stock

            "product_updated" -> {
                val datos = actualizarDatos(adapter, externalId, item)
                if (datos is PortResult.Failure) datos to null
                else adapter.setStock(productKey, stockId, stock, entry.version) to stock
            }

            "CATALOG_UPDATE", "CATALOG_PUBLISH" ->
                actualizarDatos(adapter, externalId, item) to null

            "CATALOG_UNPUBLISH" ->
                adapter.unpublishProduct(productKey, stockId) to 0

            else ->
                PortResult.Permanent("NO_IMPLEMENTADO", "Tipo de evento '${entry.tipo}' no soportado por OutboxWorker") to null
        }
    }

    private suspend fun actualizarDatos(adapter: CatalogPublishingPort, externalId: String, item: InventarioItem?): PortResult<Unit> {
        if (item == null) {
            return PortResult.Permanent("ITEM_NO_ENCONTRADO", "No se pudo leer el artículo del inventario para actualizar el canal")
        }
        return adapter.updateListing(externalId, item.toChannelDto())
    }

    private fun registrarSincronizacion(sku: String, canales: List<Pair<String, Int?>>) {
        for ((canal, stock) in canales) {
            PublicacionesTable.update({ (PublicacionesTable.sku eq sku) and (PublicacionesTable.canal eq canal) }) { row ->
                if (stock != null) row[PublicacionesTable.stockPublicado] = stock
                row[PublicacionesTable.ultimaSincronizacion] = Instant.now()
            }
        }
    }

    private fun ResultRow.toRecord() = OutboxEntryRecord(
        eventId = this[OutboxTable.eventId],
        sku = this[OutboxTable.sku],
        channel = this[OutboxTable.channel],
        version = this[OutboxTable.version],
        stockObjetivo = this[OutboxTable.stockObjetivo],
        status = this[OutboxTable.status],
        attempts = this[OutboxTable.attempts],
        tipo = this[OutboxTable.tipo]
    )
}
