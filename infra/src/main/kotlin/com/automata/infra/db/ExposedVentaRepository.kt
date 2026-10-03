package com.automata.infra.db

import com.automata.commerce.model.Metrica
import com.automata.commerce.model.Periodo
import com.automata.commerce.model.Venta
import com.automata.commerce.ports.VentaRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.SignedCop
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Solo lectura: ventas y métricas las escribe la base con triggers. */
class ExposedVentaRepository : VentaRepository {

    override fun findByKey(key: ProductKey): Venta? = transaction {
        VentasTable.select { VentasTable.key eq key.value }.singleOrNull()?.toVenta()
    }

    override fun findAll(): List<Venta> = transaction {
        VentasTable.selectAll()
            .orderBy(VentasTable.fechaVenta to SortOrder.DESC)
            .map { it.toVenta() }
    }

    override fun metricaDe(key: ProductKey): Metrica? = transaction {
        MetricasTable.select { MetricasTable.key eq key.value }.singleOrNull()?.let {
            Metrica(
                key = key,
                ingresosAntonio = Money.of(it[MetricasTable.ingresosAntonio]),
                gananciasAntonio = SignedCop(it[MetricasTable.gananciasAntonio]),
                ingresosJavier = Money.of(it[MetricasTable.ingresosJavier]),
                gananciasJavier = SignedCop(it[MetricasTable.gananciasJavier])
            )
        }
    }

    private fun ResultRow.toVenta() = Venta(
        key = ProductKey(this[VentasTable.key]),
        fechaVenta = this[VentasTable.fechaVenta],
        vendidoEn = Money.of(this[VentasTable.vendidoEn]),
        canal = this[VentasTable.canal],
        ingresos = Money.of(this[VentasTable.ingresos]),
        costoTotal = Money.of(this[VentasTable.costoTotal]),
        gananciaNeta = SignedCop(this[VentasTable.gananciaNeta]),
        margenPct = this[VentasTable.margenPct]?.toDouble(),
        tiempoDias = this[VentasTable.tiempoDias],
        diasPublicado = this[VentasTable.diasPublicado],
        periodo = this[VentasTable.periodo]?.let { Periodo.desdeEtiqueta(it) }
    )
}
