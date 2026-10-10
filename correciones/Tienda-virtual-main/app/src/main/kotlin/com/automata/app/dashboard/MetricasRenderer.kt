package com.automata.app.dashboard

import com.automata.commerce.service.MetricasGenerales
import com.automata.commerce.service.MetricasSociosResumen
import com.automata.commerce.service.RankingItem
import com.automata.shared.Money

object MetricasRenderer {

    fun renderVistaMetricas(
        generales: MetricasGenerales,
        sociosResumen: MetricasSociosResumen,
        masVendidos: List<RankingItem>,
        masRapidos: List<RankingItem>,
        pestanaActiva: String = "generales"
    ): String = buildString {
        append("""
            <div class="space-y-6">
                <!-- Pestañas -->
                <div class="border-b border-gray-200">
                    <nav class="-mb-px flex space-x-8 text-sm font-medium">
                        <button hx-get="/api/metricas?p=generales" hx-target="#main-content"
                                class="py-4 px-1 border-b-2 ${if (pestanaActiva == "generales") "border-indigo-500 text-indigo-600 font-bold" else "border-transparent text-gray-500 hover:text-gray-700"}">
                            Generales
                        </button>
                        <button hx-get="/api/metricas?p=socios" hx-target="#main-content"
                                class="py-4 px-1 border-b-2 ${if (pestanaActiva == "socios") "border-indigo-500 text-indigo-600 font-bold" else "border-transparent text-gray-500 hover:text-gray-700"}">
                            Por Socio
                        </button>
                        <button hx-get="/api/metricas?p=rankings" hx-target="#main-content"
                                class="py-4 px-1 border-b-2 ${if (pestanaActiva == "rankings") "border-indigo-500 text-indigo-600 font-bold" else "border-transparent text-gray-500 hover:text-gray-700"}">
                            Rankings
                        </button>
                    </nav>
                </div>
        """.trimIndent())

        when (pestanaActiva) {
            "socios" -> append(renderPestanaSocios(sociosResumen))
            "rankings" -> append(renderPestanaRankings(masVendidos, masRapidos))
            else -> append(renderPestanaGenerales(generales))
        }

        append("</div>")
    }

    private fun renderPestanaGenerales(g: MetricasGenerales): String = buildString {
        append("""
            <div class="space-y-6">
                <!-- Tarjetas resumen -->
                <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
                    <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200">
                        <p class="text-xs text-gray-500 font-medium uppercase">Ingresos Totales</p>
                        <p class="text-2xl font-bold text-gray-900 mt-1">${Money.of(g.ingresos).formatFormattedCop()}</p>
                    </div>
                    <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200">
                        <p class="text-xs text-gray-500 font-medium uppercase">Ganancia Neta</p>
                        <p class="text-2xl font-bold ${if (g.gananciaNeta >= 0) "text-green-600" else "text-red-600"} mt-1">
                            ${Money.of(g.gananciaNeta).formatFormattedCop()}
                        </p>
                    </div>
                    <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200">
                        <p class="text-xs text-gray-500 font-medium uppercase">ROI Global</p>
                        <p class="text-2xl font-bold text-indigo-600 mt-1">
                            ${g.roi?.let { "%.1f%%".format(it * 100) } ?: "N/A"}
                        </p>
                    </div>
                    <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200">
                        <p class="text-xs text-gray-500 font-medium uppercase">Total Vendidos</p>
                        <p class="text-2xl font-bold text-gray-900 mt-1">${g.totalVendidos} uds</p>
                    </div>
                </div>

                <!-- Detalle de Capital y Velocidad -->
                <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                    <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200 space-y-3">
                        <h3 class="font-bold text-gray-800 text-sm border-b pb-2">Capital e Inventario</h3>
                        <div class="flex justify-between text-xs">
                            <span class="text-gray-600">Capital Inmovilizado (Costo Stock):</span>
                            <span class="font-bold font-mono">${Money.of(g.capitalInmovilizado).formatFormattedCop()}</span>
                        </div>
                        <div class="flex justify-between text-xs">
                            <span class="text-gray-600">Valor Stock a Precio Publicado:</span>
                            <span class="font-bold font-mono">${Money.of(g.valorStockPublishedPrice).formatFormattedCop()}</span>
                        </div>
                        <div class="flex justify-between text-xs">
                            <span class="text-gray-600">Ganancia Potencial Stock:</span>
                            <span class="font-bold font-mono text-green-600">${Money.of(g.gananciaPotencial).formatFormattedCop()}</span>
                        </div>
                    </div>

                    <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200 space-y-3">
                        <h3 class="font-bold text-gray-800 text-sm border-b pb-2">Velocidad y Pérdidas</h3>
                        <div class="flex justify-between text-xs">
                            <span class="text-gray-600">Días Promedio Venta:</span>
                            <span class="font-bold">${g.diasPromedioVenta ?: "N/A"} días</span>
                        </div>
                        <div class="flex justify-between text-xs">
                            <span class="text-gray-600">Ventas con Pérdida:</span>
                            <span class="font-bold text-red-600">${g.ventasConPerdida} (Monto: ${Money.of(g.montoPerdido).formatFormattedCop()})</span>
                        </div>
                        <div class="flex justify-between text-xs">
                            <span class="text-gray-600">Descuento Promedio vs PP:</span>
                            <span class="font-bold">${g.descuentoPromedio?.let { "%.1f%%".format(it * 100) } ?: "0%"}</span>
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent())
    }

    private fun renderPestanaSocios(resumen: MetricasSociosResumen): String = buildString {
        append("""
            <div class="space-y-4 bg-white p-6 rounded-lg shadow-sm border border-gray-200">
                <div class="flex justify-between items-center mb-2">
                    <h3 class="text-base font-bold text-gray-800">Desglose Financiero por Socio</h3>
                    <span class="text-xs px-2 py-1 rounded font-semibold ${if (resumen.totalCuadra) "bg-green-100 text-green-800" else "bg-amber-100 text-amber-800"}">
                        ${if (resumen.totalCuadra) "✓ Cuadre Financiero Exacto" else "⚠ Revisar Cuadre"}
                    </span>
                </div>

                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200 text-xs">
                        <thead class="bg-gray-50 font-semibold text-gray-600 uppercase">
                            <tr>
                                <th class="px-4 py-3 text-left">Socio</th>
                                <th class="px-4 py-3 text-right">Inversión Total</th>
                                <th class="px-4 py-3 text-right">Capital Stock</th>
                                <th class="px-4 py-3 text-right">Ingresos</th>
                                <th class="px-4 py-3 text-right">Ganancia Neta</th>
                                <th class="px-4 py-3 text-right">ROI</th>
                                <th class="px-4 py-3 text-center">Artículos</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-gray-200">
        """.trimIndent())

        resumen.sociosMetrics.forEach { item ->
            append("""
                <tr class="hover:bg-gray-50">
                    <td class="px-4 py-3 font-semibold text-gray-900">${DashboardRenderer.escapeHtml(item.socio.nombre)}</td>
                    <td class="px-4 py-3 text-right font-mono">${Money.of(item.inversionTotal).formatFormattedCop()}</td>
                    <td class="px-4 py-3 text-right font-mono text-gray-500">${Money.of(item.capitalInmovilizado).formatFormattedCop()}</td>
                    <td class="px-4 py-3 text-right font-mono">${Money.of(item.ingresos).formatFormattedCop()}</td>
                    <td class="px-4 py-3 text-right font-mono font-bold ${if (item.gananciaNeta >= 0) "text-green-600" else "text-red-600"}">
                        ${Money.of(item.gananciaNeta).formatFormattedCop()}
                    </td>
                    <td class="px-4 py-3 text-right font-bold text-indigo-600">
                        ${item.roi?.let { "%.1f%%".format(it * 100) } ?: "N/A"}
                    </td>
                    <td class="px-4 py-3 text-center">${item.articulosVendidos} vend / ${item.articulosParticipados} tot</td>
                </tr>
            """.trimIndent())
        }

        append("""
                        </tbody>
                    </table>
                </div>
            </div>
        """.trimIndent())
    }

    private fun renderPestanaRankings(masVendidos: List<RankingItem>, masRapidos: List<RankingItem>): String = buildString {
        append("""
            <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
                <!-- Lo que más se vende por tipo -->
                <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200">
                    <h3 class="font-bold text-gray-800 text-sm mb-4 border-b pb-2">Más Vendidos por Tipo</h3>
                    <ul class="divide-y divide-gray-100 text-xs">
        """.trimIndent())

        if (masVendidos.isEmpty()) {
            append("""<li class="py-2 text-gray-400">Sin ventas registradas aún.</li>""")
        } else {
            masVendidos.forEach { item ->
                append("""
                    <li class="py-2 flex justify-between">
                        <span class="font-medium text-gray-800">${DashboardRenderer.escapeHtml(item.tipo)}</span>
                        <span class="font-bold text-indigo-600">${item.valor}</span>
                    </li>
                """.trimIndent())
            }
        }

        append("""
                    </ul>
                </div>

                <!-- Más rápidos en venderse -->
                <div class="bg-white p-5 rounded-lg shadow-sm border border-gray-200">
                    <h3 class="font-bold text-gray-800 text-sm mb-4 border-b pb-2">Ventas Más Rápidas</h3>
                    <ul class="divide-y divide-gray-100 text-xs">
        """.trimIndent())

        if (masRapidos.isEmpty()) {
            append("""<li class="py-2 text-gray-400">Sin datos de tiempo de venta.</li>""")
        } else {
            masRapidos.forEach { item ->
                append("""
                    <li class="py-2 flex justify-between">
                        <div>
                            <span class="font-medium text-gray-800 block">${DashboardRenderer.escapeHtml(item.modelo)}</span>
                            <span class="text-gray-400 text-[10px]">${item.metricaSecundaria ?: ""}</span>
                        </div>
                        <span class="font-bold text-green-600">${item.valor}</span>
                    </li>
                """.trimIndent())
            }
        }

        append("""
                    </ul>
                </div>
            </div>
        """.trimIndent())
    }
}
