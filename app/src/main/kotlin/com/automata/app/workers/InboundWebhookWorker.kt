package com.automata.app.workers

import com.automata.app.pipeline.InboundMessagePipeline
import com.automata.infra.db.EventosEntrantesTable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

data class InboundEventRecord(
    val id: String,
    val canal: String,
    val eventoExternoId: String,
    val payloadJson: String,
    val estado: String,
    val intentos: Int
)

class InboundWebhookWorker(
    private val pipeline: InboundMessagePipeline,
    private val maxRetries: Int = 3
) {

    /** Registra un evento entrante de mensaje con estado RECIBIDO (idempotente por canal + eventoExternoId). */
    fun enqueue(canal: String, senderId: String, messageId: String, text: String): String = transaction {
        val eventId = "EVT-${UUID.randomUUID()}"
        val payload = """{"senderId":"${escapeJson(senderId)}","messageId":"${escapeJson(messageId)}","text":"${escapeJson(text)}"}"""
        EventosEntrantesTable.insertIgnore { row ->
            row[EventosEntrantesTable.id] = eventId
            row[EventosEntrantesTable.canal] = canal
            row[EventosEntrantesTable.eventoExternoId] = messageId
            row[EventosEntrantesTable.payload] = payload
            row[EventosEntrantesTable.estado] = "RECIBIDO"
            row[EventosEntrantesTable.intentos] = 0
            row[EventosEntrantesTable.creadoEn] = Instant.now()
        }
        eventId
    }

    /** Procesar un lote de eventos entrantes pendientes o fallidos reprogramados. */
    suspend fun processBatch(batchSize: Int = 10): Int {
        val pendingEvents = transaction {
            val now = Instant.now()
            EventosEntrantesTable.select {
                (EventosEntrantesTable.estado eq "RECIBIDO") or (EventosEntrantesTable.estado eq "FAILED")
            }
                .andWhere { EventosEntrantesTable.proximaEjecucion lessEq now }
                .limit(batchSize)
                .map { row -> row.toRecord() }
        }

        var processedCount = 0
        for (event in pendingEvents) {
            val marked = transaction {
                EventosEntrantesTable.update({ (EventosEntrantesTable.id eq event.id) and ((EventosEntrantesTable.estado eq "RECIBIDO") or (EventosEntrantesTable.estado eq "FAILED")) }) { row ->
                    row[EventosEntrantesTable.estado] = "PROCESSING"
                    row[EventosEntrantesTable.intentos] = event.intentos + 1
                }
            }
            if (marked == 0) continue

            try {
                var senderId = event.eventoExternoId
                var messageId = event.eventoExternoId
                var text = event.payloadJson

                try {
                    val jsonObj = Json.parseToJsonElement(event.payloadJson).jsonObject
                    senderId = jsonObj["senderId"]?.jsonPrimitive?.content ?: senderId
                    messageId = jsonObj["messageId"]?.jsonPrimitive?.content ?: messageId
                    text = jsonObj["text"]?.jsonPrimitive?.content ?: text
                } catch (e: Exception) {
                    // Si el payload es texto simple sin JSON, se procesa tal cual
                }

                // Pasar al pipeline de mensajes y registrar el trato en memoria (B-9)
                val outcome = pipeline.handle(event.canal, senderId, messageId, text)
                com.automata.app.AppStore.recordDeal(outcome)

                transaction {
                    EventosEntrantesTable.update({ EventosEntrantesTable.id eq event.id }) { row ->
                        row[EventosEntrantesTable.estado] = "DONE"
                        row[EventosEntrantesTable.procesadoEn] = Instant.now()
                    }
                }
                processedCount++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val newIntentos = event.intentos + 1
                val newStatus = if (newIntentos >= maxRetries) "DEAD" else "FAILED"
                val retryDelaySec = (10L * newIntentos * newIntentos)

                transaction {
                    EventosEntrantesTable.update({ EventosEntrantesTable.id eq event.id }) { row ->
                        row[EventosEntrantesTable.estado] = newStatus
                        row[EventosEntrantesTable.errorMsg] = e.message
                        row[EventosEntrantesTable.proximaEjecucion] = Instant.now().plusSeconds(retryDelaySec)
                    }
                }
            }
        }
        return processedCount
    }

    private fun escapeJson(str: String): String =
        str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun ResultRow.toRecord() = InboundEventRecord(
        id = this[EventosEntrantesTable.id],
        canal = this[EventosEntrantesTable.canal],
        eventoExternoId = this[EventosEntrantesTable.eventoExternoId],
        payloadJson = this[EventosEntrantesTable.payload],
        estado = this[EventosEntrantesTable.estado],
        intentos = this[EventosEntrantesTable.intentos]
    )
}
