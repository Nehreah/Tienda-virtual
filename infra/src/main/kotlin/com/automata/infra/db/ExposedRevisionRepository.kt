package com.automata.infra.db

import com.automata.commerce.model.Revision
import com.automata.commerce.ports.RevisionRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedRevisionRepository : RevisionRepository {

    override fun find(key: ProductKey): Revision? = transaction {
        RevisionTable.select { RevisionTable.key eq key.value }.singleOrNull()?.let {
            Revision(
                key = key,
                reparacion = Money.of(it[RevisionTable.reparacion]),
                detalles = it[RevisionTable.detalles]
            )
        }
    }

    override fun guardar(rev: Revision, usuario: String?, origen: String): Revision =
        DatabaseFactory.tx(origen, usuario) {
            require(!InventarioTable.select { InventarioTable.key eq rev.key.value }.empty()) {
                "No existe el producto ${rev.key} en Inventario"
            }
            // Al cambiar "reparacion", el trigger de la base recalcula Costo: Compra + Reparación en Inventario.
            val actualizadas = RevisionTable.update({ RevisionTable.key eq rev.key.value }) {
                it[RevisionTable.reparacion] = rev.reparacion.amountInCop
                it[RevisionTable.detalles] = rev.detalles
            }
            if (actualizadas == 0) {
                RevisionTable.insert {
                    it[RevisionTable.key] = rev.key.value
                    it[RevisionTable.reparacion] = rev.reparacion.amountInCop
                    it[RevisionTable.detalles] = rev.detalles
                }
            }
            rev
        }
}
