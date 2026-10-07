package com.automata.infra.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class UnresolvedEventDto(
    val id: Long,
    val canal: String,
    val tipo: String,
    val referencia: String?,
    val sku: String?,
    val motivo: String,
    val payload: String,
    val estado: String,
    val creadoEn: String
)

class ExposedUnresolvedEventStore {

    fun recordUnresolvedEvent(
        canal: String,
        tipo: String,
        referencia: String?,
        sku: String?,
        motivo: String,
        payloadJson: String
    ): Long = transaction {
        EventosSinResolverTable.insert { row ->
            row[EventosSinResolverTable.canal] = canal
            row[EventosSinResolverTable.tipo] = tipo
            row[EventosSinResolverTable.referencia] = referencia
            row[EventosSinResolverTable.sku] = sku
            row[EventosSinResolverTable.motivo] = motivo
            row[EventosSinResolverTable.payload] = payloadJson
            row[EventosSinResolverTable.estado] = "PENDIENTE"
            row[EventosSinResolverTable.creadoEn] = Instant.now()
        } get EventosSinResolverTable.id
    }

    fun findPendingEvents(): List<UnresolvedEventDto> = transaction {
        EventosSinResolverTable.select { EventosSinResolverTable.estado eq "PENDIENTE" }
            .map { row ->
                UnresolvedEventDto(
                    id = row[EventosSinResolverTable.id],
                    canal = row[EventosSinResolverTable.canal],
                    tipo = row[EventosSinResolverTable.tipo],
                    referencia = row[EventosSinResolverTable.referencia],
                    sku = row[EventosSinResolverTable.sku],
                    motivo = row[EventosSinResolverTable.motivo],
                    payload = row[EventosSinResolverTable.payload],
                    estado = row[EventosSinResolverTable.estado],
                    creadoEn = row[EventosSinResolverTable.creadoEn].toString()
                )
            }
    }

    fun resolveEvent(id: Long, nuevoEstado: String = "RESUELTO"): Boolean = transaction {
        val updated = EventosSinResolverTable.update({ EventosSinResolverTable.id eq id }) { row ->
            row[EventosSinResolverTable.estado] = nuevoEstado
            row[EventosSinResolverTable.resueltoEn] = Instant.now()
        }
        updated > 0
    }
}
