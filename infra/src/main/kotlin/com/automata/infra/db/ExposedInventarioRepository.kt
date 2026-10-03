package com.automata.infra.db

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.ports.InventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDate

class ExposedInventarioRepository : InventarioRepository {

    override fun findAll(): List<InventarioItem> = transaction {
        InventarioTable.selectAll().orderBy(InventarioTable.key).map { it.toItem() }
    }

    override fun findByKey(key: ProductKey): InventarioItem? = transaction {
        InventarioTable.select { InventarioTable.key eq key.value }.singleOrNull()?.toItem()
    }

    override fun upsert(item: InventarioItem, usuario: String?, origen: String): InventarioItem =
        DatabaseFactory.tx(origen, usuario) {
            val actualizadas = InventarioTable.update({ InventarioTable.key eq item.key.value }) { it.escribir(item) }
            if (actualizadas == 0) {
                InventarioTable.insert {
                    it[InventarioTable.key] = item.key.value
                    it.escribir(item)
                }
            }
            InventarioTable.select { InventarioTable.key eq item.key.value }.single().toItem()
        }

    override fun marcarVendido(
        key: ProductKey,
        vendidoEn: Money,
        fecha: LocalDate,
        canal: String?,
        usuario: String?,
        origen: String
    ): InventarioItem? = DatabaseFactory.tx(origen, usuario) {
        val actual = InventarioTable.select { InventarioTable.key eq key.value }.singleOrNull()?.toItem()
            ?: return@tx null
        val vendido = actual.registrarVenta(vendidoEn, fecha)   // falla si ya estaba vendido

        InventarioTable.update({ InventarioTable.key eq key.value }) {
            it[InventarioTable.estado] = vendido.estado.etiqueta
            it[InventarioTable.fechaVenta] = vendido.fechaVenta
            it[InventarioTable.vendidoEn] = vendido.vendidoEn?.amountInCop
        }

        val canalNormalizado = canal?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (canalNormalizado != null) {
            val actualizadas = CanalVentaTable.update({ CanalVentaTable.key eq key.value }) {
                it[CanalVentaTable.canal] = canalNormalizado
            }
            if (actualizadas == 0) {
                CanalVentaTable.insert {
                    it[CanalVentaTable.key] = key.value
                    it[CanalVentaTable.canal] = canalNormalizado
                }
            }
        }
        InventarioTable.select { InventarioTable.key eq key.value }.single().toItem()
    }

    /** Escribe todas las columnas editables (no toca la Key ni el costo, que lo calcula la base). */
    private fun UpdateBuilder<*>.escribir(item: InventarioItem) {
        this[InventarioTable.item] = item.item
        this[InventarioTable.link] = item.link
        this[InventarioTable.fotos] = item.fotos.joinToString("\n")
        this[InventarioTable.estado] = item.estado.etiqueta
        this[InventarioTable.fechaCompra] = item.fechaCompra
        this[InventarioTable.fechaPublicacion] = item.fechaPublicacion
        this[InventarioTable.fechaVenta] = item.fechaVenta
        this[InventarioTable.precioPublicado] = item.precioPublicado?.amountInCop
        this[InventarioTable.precioMinimo] = item.precioMinimo?.amountInCop
        this[InventarioTable.precioCompra] = item.precioCompra.amountInCop
        this[InventarioTable.vendidoEn] = item.vendidoEn?.amountInCop
        this[InventarioTable.descripcion] = item.descripcion
    }

    private fun ResultRow.toItem() = InventarioItem(
        key = ProductKey(this[InventarioTable.key]),
        item = this[InventarioTable.item],
        link = this[InventarioTable.link],
        fotos = this[InventarioTable.fotos].split('\n').map { it.trim() }.filter { it.isNotEmpty() },
        estado = EstadoItem.desdeEtiqueta(this[InventarioTable.estado]) ?: EstadoItem.OTRO,
        fechaCompra = this[InventarioTable.fechaCompra],
        fechaPublicacion = this[InventarioTable.fechaPublicacion],
        fechaVenta = this[InventarioTable.fechaVenta],
        precioPublicado = this[InventarioTable.precioPublicado]?.let { Money.of(it) },
        precioMinimo = this[InventarioTable.precioMinimo]?.let { Money.of(it) },
        precioCompra = Money.of(this[InventarioTable.precioCompra]),
        vendidoEn = this[InventarioTable.vendidoEn]?.let { Money.of(it) },
        costoCompraReparacion = Money.of(this[InventarioTable.costoCompraReparacion]),
        descripcion = this[InventarioTable.descripcion]
    )
}
