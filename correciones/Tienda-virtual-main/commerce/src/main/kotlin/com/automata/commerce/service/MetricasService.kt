package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.model.Socio
import com.automata.commerce.model.Venta
import com.automata.commerce.model.VentaReparto
import com.automata.shared.ProductKey
import java.math.BigDecimal
import java.time.LocalDate

data class FiltroMetricas(
    val fechaInicio: LocalDate? = null,
    val fechaFin: LocalDate? = null,
    val tipo: String? = null
)

data class MetricasGenerales(
    val ingresos: Long,
    val costoVendido: Long,
    val gananciaNeta: Long,
    val margen: Double?,
    val roi: Double?,
    val ticketPromedio: Long,
    val gananciaPromedio: Long,
    val totalVendidos: Int,
    val totalStock: Int,
    val totalPublicados: Int,
    val tasaRotacion: Double,
    val diasPromedioVenta: Int?,
    val diasMedianaVenta: Int?,
    val ventasConPerdida: Int,
    val montoPerdido: Long,
    val capitalInmovilizado: Long,
    val valorStockPublishedPrice: Long,
    val gananciaPotencial: Long,
    val descuentoPromedio: Double?,
    val porcentajeSobrePm: Double?
)

data class MetricaSocioItem(
    val socio: Socio,
    val participacionPct: Double,
    val inversionTotal: Long,
    val capitalInmovilizado: Long,
    val ingresos: Long,
    val gananciaNeta: Long,
    val montoPerdido: Long,
    val roi: Double?,
    val margen: Double?,
    val articulosParticipados: Int,
    val articulosVendidos: Int,
    val diasPromedioRetorno: Int?,
    val mejorArticulo: String?,
    val peorArticulo: String?
)

data class MetricasSociosResumen(
    val sociosMetrics: List<MetricaSocioItem>,
    val totalCuadra: Boolean
)

data class RankingItem(
    val key: String,
    val tipo: String,
    val modelo: String,
    val valor: String,
    val metricaSecundaria: String? = null
)

data class DatosMetricas(
    val productos: List<CommercialProduct>,
    val inventarios: List<OperationalInventory>,
    val ventas: List<Venta>,
    val repartosVenta: List<VentaReparto>,
    val inversionesStock: Map<ProductKey, Map<Long, BigDecimal>>,
    val socios: List<Socio>,
    val hoy: LocalDate = LocalDate.now(),
    val filtro: FiltroMetricas = FiltroMetricas()
)

/**
 * Servicio de Dominio para el cálculo de Métricas Generales, Métricas por Socio y Rankings.
 */
class MetricasService {

    fun calcularGenerales(datos: DatosMetricas): MetricasGenerales {
        val ventasFiltradas = filtrarVentas(datos.ventas, datos.filtro)
        val productosMap = datos.productos.associateBy { it.key }

        val ingresos = ventasFiltradas.sumOf { it.vendidoEn.amountInCop }
        val costoVendido = ventasFiltradas.sumOf { it.costoTotal.amountInCop }
        val gananciaNeta = ingresos - costoVendido
        val totalVendidos = ventasFiltradas.size

        val margen = if (ingresos > 0) gananciaNeta.toDouble() / ingresos else null
        val roi = if (costoVendido > 0) gananciaNeta.toDouble() / costoVendido else null

        val ticketPromedio = if (totalVendidos > 0) ingresos / totalVendidos else 0L
        val gananciaPromedio = if (totalVendidos > 0) gananciaNeta / totalVendidos else 0L

        val stockItems = datos.productos.filter { p ->
            val inv = datos.inventarios.find { it.productKey == p.key }
            p.status == "Activo" && (inv?.isAvailable == true)
        }
        val totalStock = stockItems.size
        val totalPublicados = stockItems.count { it.status == "Activo" }
        val tasaRotacion = if (totalVendidos + totalStock > 0) totalVendidos.toDouble() / (totalVendidos + totalStock) else 0.0

        val diasVentas = ventasFiltradas.mapNotNull { it.tiempoDias }.sorted()

        val diasPromedio = if (diasVentas.isNotEmpty()) diasVentas.average().toInt() else null
        val diasMediana = if (diasVentas.isNotEmpty()) diasVentas[diasVentas.size / 2] else null

        val ventasPerdida = ventasFiltradas.filter { (it.vendidoEn.amountInCop - it.costoTotal.amountInCop) < 0 }
        val ventasConPerdida = ventasPerdida.size
        val montoPerdido = ventasPerdida.sumOf { kotlin.math.abs(it.vendidoEn.amountInCop - it.costoTotal.amountInCop) }

        val capitalInmovilizado = stockItems.sumOf { it.cost.amountInCop }
        val valorStockPublishedPrice = stockItems.sumOf { it.publishedPrice.amountInCop }
        val gananciaPotencial = valorStockPublishedPrice - capitalInmovilizado

        val descuentos = ventasFiltradas.mapNotNull { v ->
            val pp = v.precioPublicado?.amountInCop ?: productosMap[v.key]?.publishedPrice?.amountInCop
            if (pp != null && pp > 0) (pp - v.vendidoEn.amountInCop).toDouble() / pp else null
        }
        val descuentoPromedio = if (descuentos.isNotEmpty()) descuentos.average() else null

        val sobrePmCount = ventasFiltradas.count { v ->
            val pm = productosMap[v.key]?.minimumPrice?.amountInCop
            pm != null && v.vendidoEn.amountInCop >= pm
        }
        val porcentajeSobrePm = if (totalVendidos > 0) sobrePmCount.toDouble() / totalVendidos else null

        return MetricasGenerales(
            ingresos = ingresos,
            costoVendido = costoVendido,
            gananciaNeta = gananciaNeta,
            margen = margen,
            roi = roi,
            ticketPromedio = ticketPromedio,
            gananciaPromedio = gananciaPromedio,
            totalVendidos = totalVendidos,
            totalStock = totalStock,
            totalPublicados = totalPublicados,
            tasaRotacion = tasaRotacion,
            diasPromedioVenta = diasPromedio,
            diasMedianaVenta = diasMediana,
            ventasConPerdida = ventasConPerdida,
            montoPerdido = montoPerdido,
            capitalInmovilizado = capitalInmovilizado,
            valorStockPublishedPrice = valorStockPublishedPrice,
            gananciaPotencial = gananciaPotencial,
            descuentoPromedio = descuentoPromedio,
            porcentajeSobrePm = porcentajeSobrePm
        )
    }

    fun calcularPorSocio(datos: DatosMetricas): MetricasSociosResumen {
        val ventasFiltradas = filtrarVentas(datos.ventas, datos.filtro).associateBy { it.key }
        val repartosMap = datos.repartosVenta.groupBy { it.socioId }
        val productosMap = datos.productos.associateBy { it.key }

        val inversionTotalGlobal = datos.socioInversionTotalGlobal(datos)

        val itemsSocios = datos.socios.map { socio ->
            val repartosSocio = (repartosMap[socio.id] ?: emptyList()).filter { r -> ventasFiltradas.containsKey(r.key) }
            val ingresos = repartosSocio.sumOf { it.ingresos }
            val ganancia = repartosSocio.sumOf { it.ganancia }
            val inversionVendido = repartosSocio.sumOf { it.inversion }

            val stockInversion = datos.productos.filter { p ->
                val inv = datos.inventarios.find { it.productKey == p.key }
                inv?.isAvailable == true
            }.sumOf { p ->
                val pctMap = datos.inversionesStock[p.key] ?: emptyMap()
                val pct = pctMap[socio.id] ?: BigDecimal.ZERO
                (p.cost.amountInCop.toBigDecimal() * pct / BigDecimal(100)).toLong()
            }

            val inversionTotal = inversionVendido + stockInversion
            val participacionPct = if (inversionTotalGlobal > 0) inversionTotal.toDouble() / inversionTotalGlobal else 0.0

            val perdidasList = repartosSocio.filter { it.ganancia < 0 }
            val montoPerdido = perdidasList.sumOf { kotlin.math.abs(it.ganancia) }

            val roi = if (inversionVendido > 0) ganancia.toDouble() / inversionVendido else null
            val margen = if (ingresos > 0) ganancia.toDouble() / ingresos else null

            val articulosVendidos = repartosSocio.size
            val articulosStock = datos.inversionesStock.filter { (key, pctMap) ->
                val inv = datos.inventarios.find { it.productKey == key }
                inv?.isAvailable == true && (pctMap[socio.id] ?: BigDecimal.ZERO) > BigDecimal.ZERO
            }.size
            val articulosParticipados = articulosVendidos + articulosStock

            val mejoresYPeores = repartosSocio.sortedByDescending { it.ganancia }
            val mejorKey = mejoresYPeores.firstOrNull()?.key
            val peorKey = mejoresYPeores.lastOrNull()?.key

            val mejorArticulo = mejorKey?.let { productosMap[it]?.itemName }
            val peorArticulo = peorKey?.let { productosMap[it]?.itemName }

            MetricaSocioItem(
                socio = socio,
                participacionPct = participacionPct,
                inversionTotal = inversionTotal,
                capitalInmovilizado = stockInversion,
                ingresos = ingresos,
                gananciaNeta = ganancia,
                montoPerdido = montoPerdido,
                roi = roi,
                margen = margen,
                articulosParticipados = articulosParticipados,
                articulosVendidos = articulosVendidos,
                diasPromedioRetorno = null,
                mejorArticulo = mejorArticulo,
                peorArticulo = peorArticulo
            )
        }

        val sumaGananciasSocios = itemsSocios.sumOf { it.gananciaNeta }
        val gananciaNetaVentasFiltradas = ventasFiltradas.values.sumOf { it.vendidoEn.amountInCop - it.costoTotal.amountInCop }
        val totalCuadra = datos.repartosVenta.isEmpty() || sumaGananciasSocios == gananciaNetaVentasFiltradas

        return MetricasSociosResumen(
            sociosMetrics = itemsSocios,
            totalCuadra = totalCuadra
        )
    }

    fun masVendidosPorTipo(datos: DatosMetricas): List<RankingItem> {
        val ventasFiltradas = filtrarVentas(datos.ventas, datos.filtro)
        val productosMap = datos.productos.associateBy { it.key }
        val conteo = ventasFiltradas.groupBy { productosMap[it.key]?.brand ?: "Sin Tipo" }
            .mapValues { it.value.size }
            .toList().sortedByDescending { it.second }

        return conteo.map { (tipo, count) ->
            RankingItem(key = tipo, tipo = tipo, modelo = tipo, valor = "$count unidades vendidas")
        }
    }

    fun masRapidos(datos: DatosMetricas): List<RankingItem> {
        val ventasFiltradas = filtrarVentas(datos.ventas, datos.filtro)
        val productosMap = datos.productos.associateBy { it.key }

        return ventasFiltradas.mapNotNull { v ->
            val prod = productosMap[v.key] ?: return@mapNotNull null
            val dias = v.tiempoDias ?: return@mapNotNull null
            Pair(prod, dias)
        }.sortedBy { it.second }.take(10).map { (prod, dias) ->
            RankingItem(
                key = prod.key.value,
                tipo = prod.brand,
                modelo = prod.itemName,
                valor = FinancialMath.tiempoLegible(dias),
                metricaSecundaria = "Key: ${prod.key.value}"
            )
        }
    }

    private fun filtrarVentas(ventas: List<Venta>, filtro: FiltroMetricas): List<Venta> {
        return ventas.filter { v ->
            val inicioOk = filtro.fechaInicio == null || !v.fechaVenta.isBefore(filtro.fechaInicio)
            val finOk = filtro.fechaFin == null || !v.fechaVenta.isAfter(filtro.fechaFin)
            inicioOk && finOk
        }
    }

    private fun DatosMetricas.socioInversionTotalGlobal(datos: DatosMetricas): Long {
        val ventasInversion = datos.ventas.sumOf { it.costoTotal.amountInCop }
        val stockInversion = datos.productos.filter { p ->
            val inv = datos.inventarios.find { it.productKey == p.key }
            inv?.isAvailable == true
        }.sumOf { it.cost.amountInCop }
        return ventasInversion + stockInversion
    }
}
