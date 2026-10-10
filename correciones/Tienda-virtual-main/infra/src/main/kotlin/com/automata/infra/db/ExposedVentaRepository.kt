package com.automata.infra.db

import com.automata.commerce.model.Periodo
import com.automata.commerce.model.Venta
import com.automata.commerce.model.VentaReparto
import com.automata.commerce.ports.VentaRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.SignedCop
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Solo lectura: ventas y repartos las escribe la base con triggers. */
class ExposedVentaRepository : VentaRepository {

    override fun findByKey(key: ProductKey): Venta? = transaction {
        VentasTable.select { VentasTable.key eq key.value }
            .orderBy(VentasTable.fechaVenta to SortOrder.DESC, VentasTable.ventaId to SortOrder.DESC)
            .firstOrNull()?.toVenta()
    }

    override fun findAllByKey(key: ProductKey): List<Venta> = transaction {
        VentasTable.select { VentasTable.key eq key.value }
            .orderBy(VentasTable.fechaVenta to SortOrder.DESC, VentasTable.ventaId to SortOrder.DESC)
            .map { it.toVenta() }
    }

    override fun findAll(): List<Venta> = transaction {
        VentasTable.selectAll()
            .orderBy(VentasTable.fechaVenta to SortOrder.DESC, VentasTable.ventaId to SortOrder.DESC)
            .map { it.toVenta() }
    }

    override fun repartosDe(key: ProductKey): List<VentaReparto> = transaction {
        VentaRepartoTable.select { VentaRepartoTable.key eq key.value }
            .map { it.toVentaReparto() }
    }

    override fun repartos(): List<VentaReparto> = transaction {
        VentaRepartoTable.selectAll().map { it.toVentaReparto() }
    }

    private fun ResultRow.toVenta() = Venta(
        ventaId = this[VentasTable.ventaId],
        key = ProductKey(this[VentasTable.key]),
        cantidad = this[VentasTable.cantidad],
        precioUnitario = Money.of(this[VentasTable.precioUnitario]),
        costoUnitario = Money.of(this[VentasTable.costoUnitario]),
        fechaVenta = this[VentasTable.fechaVenta],
        vendidoEn = Money.of(this[VentasTable.vendidoEn]),
        canal = this[VentasTable.canal],
        origen = this[VentasTable.origen],
        dealId = this[VentasTable.dealId],
        orderId = this[VentasTable.orderId],
        lineId = this[VentasTable.lineId],
        eventId = this[VentasTable.eventId],
        reservaId = this[VentasTable.reservaId],
        clienteId = this[VentasTable.clienteId],
        ingresos = Money.of(this[VentasTable.ingresos]),
        costoTotal = Money.of(this[VentasTable.costoTotal]),
        gananciaNeta = SignedCop(this[VentasTable.gananciaNeta]),
        margenPct = this[VentasTable.margenPct]?.toDouble(),
        tiempoDias = this[VentasTable.tiempoDias],
        diasPublicado = this[VentasTable.diasPublicado],
        periodo = this[VentasTable.periodo]?.let { Periodo.desdeEtiqueta(it) },
        precioPublicado = this[VentasTable.precioPublicado]?.let { Money.of(it) },
        anuladaEn = this[VentasTable.anuladaEn],
        anuladaMotivo = this[VentasTable.anuladaMotivo]
    )

    private fun ResultRow.toVentaReparto() = VentaReparto(
        ventaId = this[VentaRepartoTable.ventaId],
        key = ProductKey(this[VentaRepartoTable.key]),
        socioId = this[VentaRepartoTable.socioId],
        pct = this[VentaRepartoTable.pct],
        inversion = this[VentaRepartoTable.inversion],
        ingresos = this[VentaRepartoTable.ingresos],
        ganancia = this[VentaRepartoTable.ganancia]
    )
}
