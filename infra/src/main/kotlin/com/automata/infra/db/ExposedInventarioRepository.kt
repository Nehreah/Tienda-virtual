package com.automata.infra.db

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.ReparteSocios
import com.automata.commerce.ports.InventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.TextColumnType
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
            val estadoActual = InventarioTable.select { InventarioTable.key eq item.key.value }
                .singleOrNull()
                ?.let { EstadoItem.desdeEtiqueta(it[InventarioTable.estado]) }

            // Una importación (Excel) o cualquier upsert no debe "des-vender" un producto por accidente:
            // para reabrir una venta se usa anularVenta().
            if (estadoActual == EstadoItem.VENDIDO && !item.estaVendido) {
                throw IllegalStateException(
                    "El producto ${item.key} ya está vendido; no se puede sobrescribir. Usa anularVenta() si quieres reabrirlo."
                )
            }

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
        val vendido = actual.registrarVenta(vendidoEn, fecha, canal)   // falla si ya estaba vendido o falta el reparto

        // UNA sola sentencia UPDATE: el trigger de la base ve estado + fecha + precio + canal a la vez,
        // crea la venta y las métricas por socio.
        InventarioTable.update({ InventarioTable.key eq key.value }) {
            it[InventarioTable.fechaVenta] = vendido.fechaVenta
            it[InventarioTable.vendidoEn] = vendido.vendidoEn?.amountInCop
            it[InventarioTable.canalVenta] = vendido.canalVenta
            it[InventarioTable.estado] = vendido.estado.etiqueta
        }
        InventarioTable.select { InventarioTable.key eq key.value }.single().toItem()
    }

    override fun anularVenta(key: ProductKey, usuario: String?, origen: String): InventarioItem? =
        DatabaseFactory.tx(origen, usuario) {
            val actual = InventarioTable.select { InventarioTable.key eq key.value }.singleOrNull()?.toItem()
                ?: return@tx null
            val reabierto = actual.anularVenta()   // falla si no estaba vendido

            // El trigger borra la venta y las métricas y limpia fecha_venta / vendido_en / canal_venta.
            InventarioTable.update({ InventarioTable.key eq key.value }) {
                it[InventarioTable.estado] = reabierto.estado.etiqueta
            }
            InventarioTable.select { InventarioTable.key eq key.value }.single().toItem()
        }

    override fun venderUnidades(
        key: ProductKey,
        quantity: Int,
        eventId: String?,
        origen: String,
        precioUnit: Money,
        reservaId: String?,
        orderId: String?,
        lineId: String?
    ): Boolean = DatabaseFactory.tx(origen, "system") {
        try {
            val jsonRes = exec(
                "SELECT tienda.vender_unidades(?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    TextColumnType() to key.value,
                    IntegerColumnType() to quantity,
                    TextColumnType() to (eventId ?: ""),
                    TextColumnType() to origen,
                    LongColumnType() to precioUnit.amountInCop,
                    TextColumnType() to (reservaId ?: ""),
                    TextColumnType() to (orderId ?: ""),
                    TextColumnType() to (lineId ?: "")
                )
            ) { rs ->
                if (rs.next()) rs.getString(1) else null
            }
            jsonRes != null && jsonRes.contains("SUCCESS")
        } catch (e: Exception) {
            System.err.println("Error en vender_unidades SQL: ${e.message}")
            false
        }
    }

    override fun devolverUnidades(orderId: String, lineId: String?, quantity: Int): Boolean =
        DatabaseFactory.tx("shopify", "system") {
            try {
                val jsonRes = exec(
                    "SELECT tienda.devolver_unidades(?, ?, ?)",
                    listOf(
                        TextColumnType() to orderId,
                        TextColumnType() to (lineId ?: ""),
                        IntegerColumnType() to quantity
                    )
                ) { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
                jsonRes != null && jsonRes.contains("SUCCESS")
            } catch (e: Exception) {
                System.err.println("Error en devolver_unidades SQL: ${e.message}")
                false
            }
        }

    /**
     * Escribe todas las columnas editables. No toca la Key, ni costo_total ni precio_minimo
     * (los calcula la base con triggers).
     */
    private fun UpdateBuilder<*>.escribir(item: InventarioItem) {
        this[InventarioTable.item] = item.item
        this[InventarioTable.tipo] = item.tipo
        this[InventarioTable.link] = item.link
        this[InventarioTable.fotos] = item.fotos.joinToString("\n")
        this[InventarioTable.estado] = item.estado.etiqueta
        this[InventarioTable.fechaCompra] = item.fechaCompra
        this[InventarioTable.fechaPublicacion] = item.fechaPublicacion
        this[InventarioTable.fechaVenta] = item.fechaVenta
        this[InventarioTable.precioPublicado] = item.precioPublicado?.amountInCop
        this[InventarioTable.precioCompra] = item.precioCompra.amountInCop
        this[InventarioTable.vendidoEn] = item.vendidoEn?.amountInCop
        this[InventarioTable.canalVenta] = item.canalVenta
        this[InventarioTable.requiereReparacion] = item.requiereReparacion
        this[InventarioTable.pctAntonio] = item.reparte.pctAntonio
        this[InventarioTable.pctJavier] = item.reparte.pctJavier
        this[InventarioTable.descripcion] = item.descripcion
        this[InventarioTable.marca] = item.marca
        this[InventarioTable.condicion] = item.condicion
        this[InventarioTable.cantidad] = item.cantidad
        this[InventarioTable.ubicacion] = item.ubicacion
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
        costoTotal = Money.of(this[InventarioTable.costoTotal]),
        descripcion = this[InventarioTable.descripcion],
        tipo = this[InventarioTable.tipo],
        marca = this[InventarioTable.marca],
        condicion = this[InventarioTable.condicion],
        cantidad = this[InventarioTable.cantidad],
        ubicacion = this[InventarioTable.ubicacion],
        requiereReparacion = this[InventarioTable.requiereReparacion],
        reparte = ReparteSocios(this[InventarioTable.pctAntonio], this[InventarioTable.pctJavier]),
        canalVenta = this[InventarioTable.canalVenta]
    )
}
