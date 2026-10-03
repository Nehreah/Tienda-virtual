package com.automata.infra.db

import com.automata.commerce.model.HistorialEntry
import com.automata.commerce.ports.HistorialRepository
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Solo lectura: el historial lo escriben los triggers de la base. */
class ExposedHistorialRepository : HistorialRepository {

    override fun porKey(key: ProductKey, limit: Int): List<HistorialEntry> = transaction {
        HistorialTable.select { HistorialTable.key eq key.value }
            .orderBy(HistorialTable.creadoEn to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    override fun recientes(limit: Int): List<HistorialEntry> = transaction {
        HistorialTable.selectAll()
            .orderBy(HistorialTable.creadoEn to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    private fun ResultRow.toEntry() = HistorialEntry(
        id = this[HistorialTable.id],
        tabla = this[HistorialTable.tabla],
        key = this[HistorialTable.key],
        operacion = this[HistorialTable.operacion],
        cambiosJson = this[HistorialTable.cambios],
        origen = this[HistorialTable.origen],
        usuario = this[HistorialTable.usuario],
        creadoEn = this[HistorialTable.creadoEn]
    )
}
