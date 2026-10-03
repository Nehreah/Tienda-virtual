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
class ExposedProcessedEventStore(
    private val fallback: ProcessedEventStore = InMemoryProcessedEventStore()
) : ProcessedEventStore {

    @Volatile private var warned = false

    override fun markIfNew(channel: String, externalEventId: String): Boolean {
        return try {
            transaction {
                EventosProcesadosTable.insert {
                    it[EventosProcesadosTable.canal] = channel
                    it[EventosProcesadosTable.eventoExternoId] = externalEventId
                }
            }
            true
        } catch (e: ExposedSQLException) {
            if (isDuplicate(e)) false else degrade(e, channel, externalEventId)
        } catch (e: Exception) {
            degrade(e, channel, externalEventId)
        }
    }

    private fun isDuplicate(e: ExposedSQLException): Boolean =
        e.sqlState == "23505" || (e.cause as? java.sql.SQLException)?.sqlState == "23505"

    private fun degrade(e: Exception, channel: String, externalEventId: String): Boolean {
        if (!warned) {
            warned = true
            System.err.println(
                "Idempotencia: la base no está disponible o falta tienda.eventos_procesados " +
                    "(${e.message}). Se usa memoria del proceso; ejecuta db/migracion_fase3_idempotencia.sql."
            )
        }
        return fallback.markIfNew(channel, externalEventId)
    }
}
