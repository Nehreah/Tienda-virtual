package com.automata.app.workers

import com.automata.app.pipeline.InboundMessagePipeline
import com.automata.infra.db.EventosEntrantesTable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

/**
 * Arma el payload JSON del evento entrante con el serializador (v15). Antes se armaba a mano y no escapaba los
 * caracteres de control (U+0000 a U+001F distintos de \n, \r y \t): el JSON quedaba inválido, la columna jsonb lo
 * rechazaba y el mensaje se perdía. El carácter NUL (U+0000) tampoco cabe en jsonb, por eso se descarta.
 */
internal fun buildInboundPayload(senderId: String, messageId: String, text: String): String =
    buildJsonObject {
        put("senderId", senderId.replace("\u0000", ""))
        put("messageId", messageId.replace("\u0000", ""))
        put("text", text.replace("\u0000", ""))
    }.toString()

/**
 * Worker de entrada de mensajes (Meta / WhatsApp).
 *
 * v15 · Recuperación de filas huérfanas: al tomar un evento se marca PROCESSING y se le pone un "arriendo" de
 * [leaseSeconds] en proxima_ejecucion. Si la app se cae a mitad, cuando vence el arriendo otro ciclo lo retoma; si ya
 * agotó [maxRetries] intentos pasa a DEAD. Los eventos se toman en orden de llegada (creado_en).
 */
class InboundWebhookWorker(
    private val pipeline: InboundMessagePipeline,
    private val maxRetries: Int = 3,
    private val leaseSeconds: Long = 300
) {

    /** Registra un evento entrante de mensaje con estado RECIBIDO (idempotente por canal + eventoExternoId). */
    fun enqueue(canal: String, senderId: String, messageId: String, text: String): String = transaction {
        val eventId = "EVT-${UUID.randomUUID()}"
        val payload = buildInboundPayload(senderId, messageId, text)
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
                (EventosEntrantesTable.estado eq "RECIBIDO") or (EventosEntrantesTable.estado eq "FAILED") or (EventosEntrantesTable.estado eq "PROCESSING")
            }
                .andWhere { EventosEntrantesTable.proximaEjecucion lessEq now }
                .orderBy(EventosEntrantesTable.creadoEn)
                .limit(batchSize)
                .map { row -> row.toRecord() }
        }

        var processedCount = 0
        for (event in pendingEvents) {
            val ahora = Instant.now()

            // Evento huérfano (quedó en PROCESSING y su arriendo venció): si ya se interrumpió demasiadas veces, se abandona.
            if (event.estado == "PROCESSING" && event.intentos >= maxRetries) {
                transaction {
                    EventosEntrantesTable.update({
                        (EventosEntrantesTable.id eq event.id) and
                            (EventosEntrantesTable.estado eq "PROCESSING") and
                            (EventosEntrantesTable.proximaEjecucion lessEq ahora)
                    }) { row ->
                        row[EventosEntrantesTable.estado] = "DEAD"
                        row[EventosEntrantesTable.errorMsg] = "Abandonado: quedó sin terminar tras ${event.intentos} intentos (el proceso se interrumpió mientras procesaba)"
                    }
                }
                continue
            }

            // Toma atómica con arriendo (ver OutboxWorker).
            val marked = transaction {
                EventosEntrantesTable.update({
                    (EventosEntrantesTable.id eq event.id) and
                        ((EventosEntrantesTable.estado eq "RECIBIDO") or (EventosEntrantesTable.estado eq "FAILED") or (EventosEntrantesTable.estado eq "PROCESSING")) and
                        (EventosEntrantesTable.proximaEjecucion lessEq ahora)
                }) { row ->
                    row[EventosEntrantesTable.estado] = "PROCESSING"
                    row[EventosEntrantesTable.intentos] = event.intentos + 1
                    row[EventosEntrantesTable.proximaEjecucion] = ahora.plusSeconds(leaseSeconds)
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

                // Pasar al pipeline de mensajes
                pipeline.handle(event.canal, senderId, messageId, text)

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

    private fun ResultRow.toRecord() = InboundEventRecord(
        id = this[EventosEntrantesTable.id],
        canal = this[EventosEntrantesTable.canal],
        eventoExternoId = this[EventosEntrantesTable.eventoExternoId],
        payloadJson = this[EventosEntrantesTable.payload],
        estado = this[EventosEntrantesTable.estado],
        intentos = this[EventosEntrantesTable.intentos]
    )
}
