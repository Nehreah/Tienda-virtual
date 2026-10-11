package com.automata.app.dashboard

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Reparacion

/** Renderiza el módulo de reparaciones sin depender de clases Tailwind/CDN. */
object ReparacionesRenderer {
    fun render(items: List<InventarioItem>, reparaciones: Map<String, Reparacion>, error: String? = null, mensaje: String? = null, borrador: Reparacion? = null): String = buildString {
        append("<section class=\"module-panel\"><header class=\"module-heading\"><div><h2>Reparaciones</h2><p>Registra el costo y los detalles de reparación. El costo total y el precio mínimo los recalcula PostgreSQL.</p></div><span class=\"module-count\">${items.count { it.requiereReparacion }} con reparación</span></header>")
        if (!error.isNullOrBlank()) append("<p class=\"module-error\" role=\"alert\">${DashboardRenderer.escapeHtml(error)}</p>")
        if (!mensaje.isNullOrBlank()) append("<p class=\"ui-ok\" role=\"status\">${DashboardRenderer.escapeHtml(mensaje)}</p>")
        if (items.isEmpty()) {
            append("<p class=\"module-empty\">No hay artículos cargados en la base de datos.</p>")
        } else {
            append("<div class=\"table-wrap\"><table class=\"module-table\"><thead><tr><th>Clave</th><th>Artículo</th><th>Compra</th><th>Requiere reparación</th><th>Precio reparación (COP)</th><th>Detalles</th><th>Acción</th></tr></thead><tbody>")
            items.forEach { item ->
                val rep = if (borrador != null && borrador.key == item.key) borrador else reparaciones[item.key.value]
                val urlKey = java.net.URLEncoder.encode(item.key.value, Charsets.UTF_8).replace("+", "%20")
                append("<tr><td><code>${DashboardRenderer.escapeHtml(item.key.value)}</code></td><td>${DashboardRenderer.escapeHtml(item.item)}</td><td>${item.precioCompra.amountInCop}</td><td>${if (item.requiereReparacion) "Sí" else "No"}</td><td colspan=\"3\"><form class=\"repair-form\" hx-post=\"/api/reparaciones/${DashboardRenderer.escapeHtml(urlKey)}\" hx-target=\"#reparaciones-panel\" autocomplete=\"off\"><label class=\"sr-only\" for=\"repair-price-${DashboardRenderer.escapeHtml(item.key.value)}\">Precio de reparación</label><input id=\"repair-price-${DashboardRenderer.escapeHtml(item.key.value)}\" name=\"precioReparacion\" type=\"number\" min=\"0\" step=\"1\" required value=\"${rep?.precioReparacion?.amountInCop ?: 0}\"/><label class=\"sr-only\" for=\"repair-details-${DashboardRenderer.escapeHtml(item.key.value)}\">Detalles de reparación</label><input id=\"repair-details-${DashboardRenderer.escapeHtml(item.key.value)}\" name=\"detalles\" maxlength=\"1000\" value=\"${DashboardRenderer.escapeHtml(rep?.detalles.orEmpty())}\" placeholder=\"Detalles (opcional)\"/><button class=\"btn btn-primary btn-sm\" type=\"submit\">Guardar</button></form></td></tr>")
            }
            append("</tbody></table></div>")
        }
        append("<p class=\"module-footnote\">El sistema no permite usar esta pantalla para cambiar precios de venta ni stock. Los triggers de la base de datos son responsables de recalcular el costo total y el precio mínimo.</p></section>")
    }
}
