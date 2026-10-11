package com.automata.app.dashboard

import com.automata.commerce.service.MetricasGenerales
import com.automata.commerce.service.MetricasSociosResumen
import com.automata.commerce.service.RankingItem
import com.automata.shared.SignedCop

/**
 * Métricas financieras (generales, por socio y rankings).
 *
 * Solo usa CSS local (card, kpis, module-table, chips y ui-* de [DashboardRenderer]) y no lleva JavaScript en línea:
 * no depende de Tailwind ni de CDN (la CSP los bloquea). Se carga como panel dentro de la subpágina Métricas
 * (#metricas-financieras-panel, ver dashboard.js) y sus pestañas son htmx que reemplazan ese mismo panel.
 * Todo texto variable pasa por [DashboardRenderer.escapeHtml].
 */
object MetricasRenderer {

    private const val PANEL = "#metricas-financieras-panel"

    private fun e(texto: String?): String = DashboardRenderer.escapeHtml(texto.orEmpty())

    private fun pct(valor: Double?, vacio: String = "N/A"): String =
        valor?.let { "%.1f%%".format(it * 100) } ?: vacio

    /** Formato COP con signo: ganancia neta y ganancia potencial pueden ser negativas (Money lanza excepción con negativos). */
    private fun cop(valor: Long): String = SignedCop.of(valor).format()

    fun renderVistaMetricas(
        generales: MetricasGenerales,
        sociosResumen: MetricasSociosResumen,
        masVendidos: List<RankingItem>,
        masRapidos: List<RankingItem>,
        pestanaActiva: String = "generales"
    ): String = buildString {
        append("""<div class="ui-stack">""")
        append(renderPestanas(pestanaActiva))

        when (pestanaActiva) {
            "socios" -> append(renderPestanaSocios(sociosResumen))
            "rankings" -> append(renderPestanaRankings(masVendidos, masRapidos))
            else -> append(renderPestanaGenerales(generales))
        }

        append("</div>")
    }

    private fun renderPestanas(activa: String): String = buildString {
        append("""<div class="chips" role="group" aria-label="Secciones de métricas">""")
        listOf("generales" to "Generales", "socios" to "Por Socio", "rankings" to "Rankings").forEach { (id, titulo) ->
            val seleccionada = activa == id
            append(
                """<button type="button" class="chip${if (seleccionada) " active" else ""}" aria-pressed="$seleccionada" """ +
                    """hx-get="/api/metricas?p=$id" hx-target="$PANEL">$titulo</button>"""
            )
        }
        append("</div>")
    }

    /** Fila etiqueta + valor de una tabla de resumen. */
    private fun fila(etiqueta: String, valor: String, claseValor: String = ""): String =
        """<tr><td>${e(etiqueta)}</td><td class="ui-num $claseValor">${e(valor)}</td></tr>"""

    private fun tarjetaResumen(titulo: String, filas: String): String =
        """<div class="card card-pad"><h3>${e(titulo)}</h3><div class="table-wrap">""" +
            """<table class="module-table"><tbody>$filas</tbody></table></div></div>"""

    private fun renderPestanaGenerales(g: MetricasGenerales): String = buildString {
        val claseGanancia = if (g.gananciaNeta >= 0) "v-ok" else "ui-neg"

        append("""<div class="kpis">""")
        append("""<div class="card kpi"><p class="label">Ingresos totales</p><p class="value">${e(cop(g.ingresos))}</p></div>""")
        append("""<div class="card kpi"><p class="label">Ganancia neta</p><p class="value $claseGanancia">${e(cop(g.gananciaNeta))}</p></div>""")
        append("""<div class="card kpi"><p class="label">ROI global</p><p class="value v-primary">${e(pct(g.roi))}</p></div>""")
        append("""<div class="card kpi"><p class="label">Total vendidos</p><p class="value">${g.totalVendidos} uds</p></div>""")
        append("</div>")

        append("""<div class="cols-2">""")
        append(
            tarjetaResumen(
                "Capital e inventario",
                fila("Capital inmovilizado (costo del stock)", cop(g.capitalInmovilizado)) +
                    fila("Valor del stock a precio publicado", cop(g.valorStockPublishedPrice)) +
                    fila("Ganancia potencial del stock", cop(g.gananciaPotencial), "v-ok")
            )
        )
        append(
            tarjetaResumen(
                "Velocidad y pérdidas",
                fila("Días promedio de venta", "${g.diasPromedioVenta ?: "N/A"} días") +
                    fila("Ventas con pérdida", "${g.ventasConPerdida} (monto: ${cop(g.montoPerdido)})", "ui-neg") +
                    fila("Descuento promedio vs precio publicado", pct(g.descuentoPromedio, vacio = "0%"))
            )
        )
        append("</div>")
    }

    private fun renderPestanaSocios(resumen: MetricasSociosResumen): String = buildString {
        val claseCuadre = if (resumen.totalCuadra) "ok" else "warn"
        val textoCuadre = if (resumen.totalCuadra) "✓ Cuadre financiero exacto" else "⚠ Revisar cuadre"

        append("""<div class="card card-pad">""")
        append("""<div class="ui-toolbar"><h3>Desglose financiero por socio</h3><span class="ui-state $claseCuadre">$textoCuadre</span></div>""")
        append("""<div class="table-wrap"><table class="module-table"><thead><tr>""")
        append("""<th>Socio</th><th class="inventory-number">Inversión total</th><th class="inventory-number">Capital en stock</th>""")
        append("""<th class="inventory-number">Ingresos</th><th class="inventory-number">Ganancia neta</th>""")
        append("""<th class="inventory-number">ROI</th><th class="inventory-center">Artículos</th>""")
        append("""</tr></thead><tbody>""")

        resumen.sociosMetrics.forEach { item ->
            val claseGanancia = if (item.gananciaNeta >= 0) "v-ok" else "ui-neg"
            append("<tr>")
            append("""<td><strong>${e(item.socio.nombre)}</strong></td>""")
            append("""<td class="ui-num">${e(cop(item.inversionTotal))}</td>""")
            append("""<td class="ui-num ui-muted">${e(cop(item.capitalInmovilizado))}</td>""")
            append("""<td class="ui-num">${e(cop(item.ingresos))}</td>""")
            append("""<td class="ui-num $claseGanancia">${e(cop(item.gananciaNeta))}</td>""")
            append("""<td class="ui-num v-primary">${e(pct(item.roi))}</td>""")
            append("""<td class="inventory-center">${item.articulosVendidos} vend / ${item.articulosParticipados} tot</td>""")
            append("</tr>")
        }

        append("</tbody></table></div></div>")
    }

    private fun renderPestanaRankings(masVendidos: List<RankingItem>, masRapidos: List<RankingItem>): String = buildString {
        append("""<div class="cols-2">""")

        // Lo que más se vende por tipo
        append("""<div class="card card-pad"><h3>Más vendidos por tipo</h3><div class="table-wrap">""")
        if (masVendidos.isEmpty()) {
            append("""<p class="module-empty">Sin ventas registradas aún.</p>""")
        } else {
            append("""<table class="module-table"><tbody>""")
            masVendidos.forEach { item ->
                append("""<tr><td><strong>${e(item.tipo)}</strong></td><td class="ui-num v-primary">${e(item.valor)}</td></tr>""")
            }
            append("</tbody></table>")
        }
        append("</div></div>")

        // Más rápidos en venderse
        append("""<div class="card card-pad"><h3>Ventas más rápidas</h3><div class="table-wrap">""")
        if (masRapidos.isEmpty()) {
            append("""<p class="module-empty">Sin datos de tiempo de venta.</p>""")
        } else {
            append("""<table class="module-table"><tbody>""")
            masRapidos.forEach { item ->
                append("<tr><td><strong>${e(item.modelo)}</strong>")
                if (!item.metricaSecundaria.isNullOrBlank()) {
                    append("""<span class="ui-sub">${e(item.metricaSecundaria)}</span>""")
                }
                append("""</td><td class="ui-num v-ok">${e(item.valor)}</td></tr>""")
            }
            append("</tbody></table>")
        }
        append("</div></div>")

        append("</div>")
    }
}
