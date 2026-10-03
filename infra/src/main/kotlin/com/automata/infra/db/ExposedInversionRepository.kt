package com.automata.infra.db

import com.automata.commerce.model.Inversion
import com.automata.commerce.ports.InversionRepository
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedInversionRepository : InversionRepository {

    override fun find(key: ProductKey): Inversion? = transaction {
        InversionTable.select { InversionTable.key eq key.value }.singleOrNull()?.let {
            Inversion(
                key = key,
                pctAntonio = it[InversionTable.pctAntonio],
                pctJavier = it[InversionTable.pctJavier]
            )
        }
    }

    override fun guardar(inv: Inversion, usuario: String?, origen: String): Inversion =
        DatabaseFactory.tx(origen, usuario) {
            require(!InventarioTable.select { InventarioTable.key eq inv.key.value }.empty()) {
                "No existe el producto ${inv.key} en Inventario"
            }
            val actualizadas = InversionTable.update({ InversionTable.key eq inv.key.value }) {
                it[InversionTable.pctAntonio] = inv.pctAntonio
                it[InversionTable.pctJavier] = inv.pctJavier
            }
            if (actualizadas == 0) {
                InversionTable.insert {
                    it[InversionTable.key] = inv.key.value
                    it[InversionTable.pctAntonio] = inv.pctAntonio
                    it[InversionTable.pctJavier] = inv.pctJavier
                }
            }
            inv
        }
}
