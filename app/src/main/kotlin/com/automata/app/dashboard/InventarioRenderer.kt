package com.automata.app.dashboard

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Socio
import java.math.BigDecimal

object InventarioRenderer {

    fun renderTablaInventarioConSocios(
        items: List<InventarioItem>,
        socios: List<Socio>,
        errorMsg: String? = null
    ): String = buildString {
        append("""
            <div class="space-y-4">
                <div class="flex justify-between items-center bg-white p-4 rounded-lg shadow-sm border border-gray-200">
                    <div>
                        <h2 class="text-lg font-bold text-gray-800">Inventario — Inversión por Socio</h2>
                        <p class="text-xs text-gray-500">${items.size} artículo(s) | ${socios.size} socio(s) activo(s)</p>
                    </div>
                </div>
        """.trimIndent())

        if (!errorMsg.isNullOrBlank()) {
            append("""
                <div class="bg-red-50 border-l-4 border-red-500 p-3 text-red-700 text-xs rounded">
                    ${DashboardRenderer.escapeHtml(errorMsg)}
                </div>
            """.trimIndent())
        }

        append("""
            <div class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200 text-xs">
                    <thead class="bg-gray-50 text-gray-600 uppercase font-semibold">
                        <tr>
                            <th class="px-4 py-3 text-left">Key</th>
                            <th class="px-4 py-3 text-left">Producto</th>
                            <th class="px-4 py-3 text-left">Estado</th>
                            <th class="px-4 py-3 text-right">Costo Total</th>
        """.trimIndent())

        socios.forEach { socio ->
            append("""<th class="px-4 py-3 text-center bg-indigo-50 text-indigo-900">% ${DashboardRenderer.escapeHtml(socio.nombre)}</th>""")
        }

        append("""
                            <th class="px-4 py-3 text-center font-bold">Suma %</th>
                            <th class="px-4 py-3 text-right">Acciones</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-200">
        """.trimIndent())

        items.forEach { item ->
            val esVendido = item.estaVendido
            val suma = item.reparte.suma
            val sumaBg = when {
                suma.compareTo(BigDecimal(100)) == 0 -> "bg-green-100 text-green-800"
                suma.signum() == 0 -> "bg-gray-100 text-gray-500"
                else -> "bg-amber-100 text-amber-800"
            }

            append("""
                <tr class="hover:bg-gray-50" id="row-${item.key.value}">
                    <td class="px-4 py-3 font-mono font-bold text-gray-700">${item.key.value}</td>
                    <td class="px-4 py-3 font-medium text-gray-900">${DashboardRenderer.escapeHtml(item.item)}</td>
                    <td class="px-4 py-3">
                        <span class="px-2 py-0.5 text-xs rounded ${if (esVendido) "bg-red-100 text-red-800" else "bg-blue-100 text-blue-800"}">
                            ${item.estado.etiqueta}
                        </span>
                    </td>
                    <td class="px-4 py-3 text-right font-mono">${item.costoTotal.formatFormattedCop()}</td>
            """.trimIndent())

            socios.forEach { socio ->
                val pct = item.reparte.porcentaje(socio.id)
                val valStr = if (pct.signum() > 0) pct.stripTrailingZeros().toPlainString() else ""
                append("""
                    <td class="px-3 py-2 text-center bg-indigo-50/50">
                        <input type="number" min="0" max="100" step="0.01" value="$valStr"
                               ${if (esVendido) "disabled" else ""}
                               hx-put="/api/inventario/${item.key.value}/inversion/${socio.id}"
                               hx-target="#main-content"
                               name="pct"
                               class="w-16 text-center border border-gray-300 rounded px-1 py-0.5 text-xs focus:ring-1 focus:ring-indigo-500 ${if (esVendido) "bg-gray-100 text-gray-400" else ""}"/>
                    </td>
                """.trimIndent())
            }

            append("""
                    <td class="px-4 py-3 text-center">
                        <span class="px-2 py-1 rounded font-bold text-xs $sumaBg">${suma.stripTrailingZeros().toPlainString()}%</span>
                    </td>
                    <td class="px-4 py-3 text-right space-x-1">
                        ${if (!esVendido && socios.isNotEmpty()) """
                            <button hx-post="/api/inventario/${item.key.value}/inversion/repartir-igual"
                                    hx-target="#main-content"
                                    class="text-indigo-600 hover:text-indigo-900 text-xs font-medium">Partes Iguales</button>
                        """ else ""}
                    </td>
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
}
