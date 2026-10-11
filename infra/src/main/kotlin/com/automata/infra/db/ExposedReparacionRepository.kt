package com.automata.infra.db

import com.automata.commerce.model.Reparacion
import com.automata.commerce.ports.ReparacionRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedReparacionRepository : ReparacionRepository {

    override fun find(key: ProductKey): Reparacion? = transaction {
        ReparacionesTable.select { ReparacionesTable.key eq key.value }.singleOrNull()?.let {
            Reparacion(
                key = key,
                precioReparacion = Money.of(it[ReparacionesTable.precioReparacion]),
                detalles = it[ReparacionesTable.detalles]
            )
        }
    }

    override fun findAll(): List<Reparacion> = transaction {
        ReparacionesTable.selectAll().map {
            Reparacion(
                key = ProductKey(it[ReparacionesTable.key]),
                precioReparacion = Money.of(it[ReparacionesTable.precioReparacion]),
                detalles = it[ReparacionesTable.detalles]
            )
        }
    }

    override fun guardar(rep: Reparacion, usuario: String?, origen: String): Reparacion =
        DatabaseFactory.tx(origen, usuario) {
            require(!InventarioTable.select { InventarioTable.key eq rep.key.value }.empty()) {
                "No existe el producto ${rep.key} en Inventario"
            }

            // La fila de reparaciones solo existe si requiere_reparacion = true: al marcarlo, el trigger
            // de la base crea la fila (precio 0) y aquí la completamos.
            InventarioTable.update({
                (InventarioTable.key eq rep.key.value) and (InventarioTable.requiereReparacion eq false)
            }) {
                it[InventarioTable.requiereReparacion] = true
            }

            // Al cambiar "precio_reparacion", el trigger de la base recalcula costo_total y precio_minimo.
            val actualizadas = ReparacionesTable.update({ ReparacionesTable.key eq rep.key.value }) {
                it[ReparacionesTable.precioReparacion] = rep.precioReparacion.amountInCop
                it[ReparacionesTable.detalles] = rep.detalles
            }
            if (actualizadas == 0) {
                ReparacionesTable.insert {
                    it[ReparacionesTable.key] = rep.key.value
                    it[ReparacionesTable.precioReparacion] = rep.precioReparacion.amountInCop
                    it[ReparacionesTable.detalles] = rep.detalles
                }
            }
            rep
        }
}
