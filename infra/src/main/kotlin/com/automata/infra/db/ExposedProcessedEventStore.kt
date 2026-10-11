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
import org.jetbrains.exposed.sql.or
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class ExposedProcessedEventStore(
    private val fallback: ProcessedEventStore = InMemoryProcessedEventStore(),
    /** Tiempo tras el cual un evento en PROCESSING se considera abandonado (el proceso murió) y puede reintentarse. */
    private val staleAfter: Duration = Duration.ofMinutes(5)
) : ProcessedEventStore {

    private val logger = LoggerFactory.getLogger(ExposedProcessedEventStore::class.java)

    @Volatile private var warned = false

    /**
     * La base falló y se usa la memoria del proceso. Se avisa con la traza completa la primera vez y en una línea
     * después, para que la degradación quede visible: en memoria la idempotencia se pierde al reiniciar la app.
     */
    private fun avisarDegradacion(operacion: String, channel: String, externalEventId: String, e: Exception) {
        if (!warned) {
            warned = true
            logger.error("Idempotencia en memoria: falló la base en $operacion ($channel/$externalEventId): ${e.message}", e)
        } else {
            logger.warn("Idempotencia en memoria: falló la base en $operacion ($channel/$externalEventId): ${e.message}")
        }
    }

    override fun tryStartProcessing(channel: String, externalEventId: String): Boolean {
        return try {
            transaction {
                val existing = EventosProcesadosTable.select {
                    (EventosProcesadosTable.canal eq channel) and (EventosProcesadosTable.eventoExternoId eq externalEventId)
                }.singleOrNull()

                if (existing != null) {
                    val status = existing[EventosProcesadosTable.estado]
                    val ahora = Instant.now()
                    val vencido = ahora.minus(staleAfter)
                    // Un PROCESSING viejo es de un proceso que se cayó a mitad: se puede retomar. El UPDATE condicional
                    // garantiza que si dos reintentos llegan a la vez, solo uno lo reclama (filas afectadas == 1).
                    val reclamado = EventosProcesadosTable.update({
                        (EventosProcesadosTable.canal eq channel) and
                            (EventosProcesadosTable.eventoExternoId eq externalEventId) and
                            (
                                (EventosProcesadosTable.estado eq "FAILED") or
                                ((EventosProcesadosTable.estado eq "PROCESSING") and
                                    (EventosProcesadosTable.actualizadoEn less vencido))
                            )
                    }) {
                        it[estado] = "PROCESSING"
                        it[actualizadoEn] = ahora
                    }
                    if (status == "DONE") false else reclamado == 1
                } else {
                    EventosProcesadosTable.insert {
                        it[canal] = channel
                        it[eventoExternoId] = externalEventId
                        it[estado] = "PROCESSING"
                        it[actualizadoEn] = Instant.now()
                    }
                    true
                }
            }
        } catch (e: ExposedSQLException) {
            if (isDuplicate(e)) false else fallback.tryStartProcessing(channel, externalEventId)
        } catch (e: Exception) {
            avisarDegradacion("tryStartProcessing", channel, externalEventId, e)
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
                    it[actualizadoEn] = Instant.now()
                }
            }
        } catch (e: Exception) {
            avisarDegradacion("markDone", channel, externalEventId, e)
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
                    it[actualizadoEn] = Instant.now()
                }
            }
        } catch (e: Exception) {
            avisarDegradacion("markFailed", channel, externalEventId, e)
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
