package com.automata.app.dashboard

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Socio
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object InventarioRenderer {

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    fun renderTablaInventarioConSocios(
        items: List<InventarioItem>,
        socios: List<Socio>,
        errorMsg: String? = null
    ): String = buildString {
        append("""
            <div class="inventory-stack">
                <div class="inventory-toolbar">
                    <div>
                        <h2 class="inventory-title">Inventario — Inversión por Socio</h2>
                        <p class="inventory-subtitle">${items.size} artículo(s) | ${socios.size} socio(s) activo(s)</p>
                    </div>
                </div>
        """.trimIndent())

        if (!errorMsg.isNullOrBlank()) {
            append("""
                <div class="module-error">
                    ${DashboardRenderer.escapeHtml(errorMsg)}
                </div>
            """.trimIndent())
        }

        append("""
            <div class="table-wrap inventory-table-wrap">
                <table class="module-table inventory-table">
                    <thead class="inventory-table-head">
                        <tr>
                            <th class="">Key</th>
                            <th class="">Producto</th>
                            <th class="">Estado</th>
                            <th class="inventory-number">Costo Total</th>
        """.trimIndent())

        socios.forEach { socio ->
            append("""<th class="inventory-center inventory-investor-head">% ${DashboardRenderer.escapeHtml(socio.nombre)}</th>""")
        }

        append("""
                            <th class="inventory-center font-bold">Suma %</th>
                            <th class="inventory-number">Acciones</th>
                        </tr>
                    </thead>
                    <tbody class="">
        """.trimIndent())

        items.forEach { item ->
            val esVendido = item.estaVendido
            val suma = item.reparte.suma
            append("""
                <tr class="" id="row-${DashboardRenderer.escapeHtml(item.key.value)}">
                    <td class="inventory-key">${DashboardRenderer.escapeHtml(item.key.value)}</td>
                    <td class="inventory-product">${DashboardRenderer.escapeHtml(item.item)}</td>
                    <td class="">
                        <span class="inventory-state ${if (esVendido) "inventory-state-sold" else "inventory-state-active"}">
                            ${item.estado.etiqueta}
                        </span>
                    </td>
                    <td class="inventory-number inventory-money">${item.costoTotal.formatFormattedCop()}</td>
            """.trimIndent())

            socios.forEach { socio ->
                val pct = item.reparte.porcentaje(socio.id)
                val valStr = if (pct.signum() > 0) pct.stripTrailingZeros().toPlainString() else ""
                append("""
                    <td class="inventory-center inventory-investor-cell">
                        <input type="number" min="0" max="100" step="0.01" value="$valStr"
                               ${if (esVendido) "disabled" else ""}
                               hx-put="/api/inventario/${encodePathSegment(item.key.value)}/inversion/${socio.id}"
                               hx-target="#inventario-inversion-panel"
                               hx-trigger="change"
                               name="pct"
                               class="inventory-percent ${if (esVendido) "inventory-percent-disabled" else ""}"/>
                    </td>
                """.trimIndent())
            }

            append("""
                    <td class="inventory-center">
                        <span class="inventory-sum-badge ${when { suma.compareTo(BigDecimal(100)) == 0 -> "inventory-sum-ok"; suma.signum() == 0 -> "inventory-sum-empty"; else -> "inventory-sum-warning" }}">${suma.stripTrailingZeros().toPlainString()}%</span>
                    </td>
                    <td class="inventory-number">
                        ${if (!esVendido && socios.isNotEmpty()) """
                            <button hx-post="/api/inventario/${encodePathSegment(item.key.value)}/inversion/repartir-igual"
                                    hx-target="#inventario-inversion-panel"
                                    class="inventory-action-button">Partes Iguales</button>
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
