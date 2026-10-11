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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.LocalDate

class ExposedInventarioRepository : InventarioRepository {
    private val logger = LoggerFactory.getLogger(ExposedInventarioRepository::class.java)

    override fun findAll(): List<InventarioItem> = transaction {
        val items = InventarioTable.selectAll().orderBy(InventarioTable.key).toList()
        val inversions = InventarioInversionTable.selectAll().toList().groupBy { ProductKey(it[InventarioInversionTable.key]) }
        items.map { row ->
            val key = ProductKey(row[InventarioTable.key])
            val invMap = inversions[key]?.associate { it[InventarioInversionTable.socioId] to it[InventarioInversionTable.pct] } ?: emptyMap()
            row.toItem(ReparteSocios(invMap))
        }
    }

    override fun findByKey(key: ProductKey): InventarioItem? = transaction {
        val row = InventarioTable.select { InventarioTable.key eq key.value }.singleOrNull() ?: return@transaction null
        val invMap = InventarioInversionTable.select { InventarioInversionTable.key eq key.value }
            .associate { it[InventarioInversionTable.socioId] to it[InventarioInversionTable.pct] }
        row.toItem(ReparteSocios(invMap))
    }

    override fun upsert(item: InventarioItem, usuario: String?, origen: String): InventarioItem =
        DatabaseFactory.tx(origen, usuario) {
            val estadoActual = InventarioTable.select { InventarioTable.key eq item.key.value }
                .singleOrNull()
                ?.let { EstadoItem.desdeEtiqueta(it[InventarioTable.estado]) }

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
            findByKey(item.key) ?: error("No se pudo releer el artículo ${item.key} después de guardarlo.")
        }

    override fun marcarVendido(
        key: ProductKey,
        vendidoEn: Money,
        fecha: LocalDate,
        canal: String?,
        usuario: String?,
        origen: String
    ): InventarioItem? = DatabaseFactory.tx(origen, usuario) {
        // Candado de fila (SELECT ... FOR UPDATE): si dos confirmaciones del mismo producto llegan a la vez, la segunda
        // espera a que termine la primera y entonces lee "Vendido" (registrarVenta lanza IllegalStateException) en vez
        // de pisar vendido_en / fecha_venta / canal_venta de la venta ya registrada.
        if (InventarioTable.select { InventarioTable.key eq key.value }.forUpdate().firstOrNull() == null) return@tx null
        val actual = findByKey(key) ?: return@tx null
        val vendido = actual.registrarVenta(vendidoEn, fecha, canal)

        InventarioTable.update({ InventarioTable.key eq key.value }) {
            it[InventarioTable.fechaVenta] = vendido.fechaVenta
            it[InventarioTable.vendidoEn] = vendido.vendidoEn?.amountInCop
            it[InventarioTable.canalVenta] = vendido.canalVenta
            it[InventarioTable.estado] = vendido.estado.etiqueta
        }
        findByKey(key)
    }

    override fun anularVenta(key: ProductKey, usuario: String?, origen: String): InventarioItem? =
        DatabaseFactory.tx(origen, usuario) {
            val actual = findByKey(key) ?: return@tx null
            val reabierto = actual.anularVenta()

            InventarioTable.update({ InventarioTable.key eq key.value }) {
                it[InventarioTable.estado] = reabierto.estado.etiqueta
            }
            findByKey(key)
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
            logger.error("Error en vender_unidades SQL: ${e.message}", e)
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
                logger.error("Error en devolver_unidades SQL: ${e.message}", e)
                false
            }
        }

    override fun archivar(
        key: ProductKey,
        usuario: String?,
        motivo: String,
        origen: String
    ): InventarioItem? = DatabaseFactory.tx(origen, usuario) {
        val actual = findByKey(key) ?: return@tx null
        check(actual.estado != EstadoItem.VENDIDO) { "No se puede archivar el producto $key porque está Vendido." }
        InventarioTable.update({ InventarioTable.key eq key.value }) {
            it[InventarioTable.estado] = EstadoItem.INACTIVO.etiqueta
            it[InventarioTable.archivadoEn] = java.time.Instant.now()
            it[InventarioTable.archivadoPor] = usuario
            it[InventarioTable.motivoArchivo] = motivo
        }
        findByKey(key)
    }

    override fun restaurar(
        key: ProductKey,
        usuario: String?,
        origen: String
    ): InventarioItem? = DatabaseFactory.tx(origen, usuario) {
        val actual = findByKey(key) ?: return@tx null
        InventarioTable.update({ InventarioTable.key eq key.value }) {
            it[InventarioTable.estado] = EstadoItem.PUBLICADO.etiqueta
            it[InventarioTable.archivadoEn] = null
            it[InventarioTable.archivadoPor] = null
            it[InventarioTable.motivoArchivo] = null
        }
        findByKey(key)
    }

    override fun eliminar(
        key: ProductKey,
        usuario: String?,
        origen: String
    ): Boolean = DatabaseFactory.tx(origen, usuario) {
        val count = InventarioTable.deleteWhere { InventarioTable.key eq key.value }
        count > 0
    }

    override fun hasHistoryOrReferences(key: ProductKey): Boolean = transaction {
        val inVentas = VentasTable.select { VentasTable.key eq key.value }.count() > 0
        val inReservas = ReservasTable.select { ReservasTable.productKey eq key.value }.count() > 0
        val inConversaciones = ConversacionesTable.select { ConversacionesTable.activeProductKey eq key.value }.count() > 0
        inVentas || inReservas || inConversaciones
    }

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
        this[InventarioTable.descripcion] = item.descripcion
        this[InventarioTable.marca] = item.marca
        this[InventarioTable.condicion] = item.condicion
        this[InventarioTable.cantidad] = item.cantidad
        this[InventarioTable.ubicacion] = item.ubicacion
        this[InventarioTable.archivadoEn] = item.archivadoEn
        this[InventarioTable.archivadoPor] = item.archivadoPor
        this[InventarioTable.motivoArchivo] = item.motivoArchivo
    }

    private fun ResultRow.toItem(reparte: ReparteSocios = ReparteSocios.SIN_DEFINIR) = InventarioItem(
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
        reparte = reparte,
        canalVenta = this[InventarioTable.canalVenta],
        archivadoEn = this[InventarioTable.archivadoEn],
        archivadoPor = this[InventarioTable.archivadoPor],
        motivoArchivo = this[InventarioTable.motivoArchivo]
    )
}
