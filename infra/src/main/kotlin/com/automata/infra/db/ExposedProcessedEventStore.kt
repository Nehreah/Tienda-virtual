package com.automata.infra.db

import com.automata.messaging.ports.InMemoryProcessedEventStore
import com.automata.messaging.ports.ProcessedEventStore
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Idempotencia persistente: inserta (canal, evento) en tienda.eventos_procesados.
 * Si la clave ya existe (SQLState 23505) el evento es un reenvío -> false.
 * Si la base falla por otra causa (caída, tabla no creada), NO se pierde el evento:
 * se degrada a la memoria del proceso y se avisa una sola vez.
 */
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

class ExposedProcessedEventStore(
    private val fallback: ProcessedEventStore = InMemoryProcessedEventStore()
) : ProcessedEventStore {

    @Volatile private var warned = false

    override fun tryStartProcessing(channel: String, externalEventId: String): Boolean {
        return try {
            transaction {
                val existing = EventosProcesadosTable.select {
                    (EventosProcesadosTable.canal eq channel) and (EventosProcesadosTable.eventoExternoId eq externalEventId)
                }.singleOrNull()

                if (existing != null) {
                    val status = existing[EventosProcesadosTable.estado]
                    if (status == "DONE" || status == "PROCESSING") {
                        false
                    } else {
                        EventosProcesadosTable.update({
                            (EventosProcesadosTable.canal eq channel) and (EventosProcesadosTable.eventoExternoId eq externalEventId)
                        }) {
                            it[estado] = "PROCESSING"
                        }
                        true
                    }
                } else {
                    EventosProcesadosTable.insert {
                        it[canal] = channel
                        it[eventoExternoId] = externalEventId
                        it[estado] = "PROCESSING"
                    }
                    true
                }
            }
        } catch (e: ExposedSQLException) {
            if (isDuplicate(e)) false else fallback.tryStartProcessing(channel, externalEventId)
        } catch (e: Exception) {
            fallback.tryStartProcessing(channel, externalEventId)
        }
    }

    override fun markDone(channel: String, externalEventId: String): Unit {
        try {
            transaction {
                EventosProcesadosTable.update({
                    (EventosProcesadosTable.canal eq channel) and (EventosProcesadosTable.eventoExternoId eq externalEventId)
                }) {
                    it[estado] = "DONE"
                }
            }
        } catch (e: Exception) {
            fallback.markDone(channel, externalEventId)
        }
    }

    override fun markFailed(channel: String, externalEventId: String, errorMessage: String?): Unit {
        try {
            transaction {
                EventosProcesadosTable.update({
                    (EventosProcesadosTable.canal eq channel) and (EventosProcesadosTable.eventoExternoId eq externalEventId)
                }) {
                    it[estado] = "FAILED"
                }
            }
        } catch (e: Exception) {
            fallback.markFailed(channel, externalEventId, errorMessage)
        }
    }

    override fun markIfNew(channel: String, externalEventId: String): Boolean {
        return tryStartProcessing(channel, externalEventId).also {
            if (it) markDone(channel, externalEventId)
        }
    }

    private fun isDuplicate(e: ExposedSQLException): Boolean =
        e.sqlState == "23505" || (e.cause as? java.sql.SQLException)?.sqlState == "23505"
}
