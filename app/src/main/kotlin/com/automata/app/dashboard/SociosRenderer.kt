package com.automata.app.dashboard

import com.automata.commerce.model.CuentaSocio
import com.automata.commerce.model.Liquidacion
import com.automata.commerce.model.Socio
import com.automata.commerce.service.ImpactoSocio
import com.automata.shared.SignedCop
import java.time.LocalDate

/**
 * Módulo de socios (v11): lista, alta, retiro/archivo, aportes y liquidaciones.
 *
 * Reescrito sin Tailwind (el dashboard no lo carga) y sin JavaScript en línea ni onclick (la CSP los bloquea):
 * el formulario de alta usa <details>, los modales se cargan con htmx en #socios-modal y se cierran con
 * data-modal-close (dashboard.js). Todo texto variable pasa por [DashboardRenderer.escapeHtml].
 */
object SociosRenderer {

    private fun e(texto: String?): String = DashboardRenderer.escapeHtml(texto.orEmpty())

    private fun cop(valor: SignedCop): String =
        if (valor.isNegative) """<span class="ui-neg">${e(valor.format())}</span>""" else e(valor.format())

    /**
     * Contenido de #socios-panel.
     * [cuentas] = null significa que no hay base de datos: se muestra solo la lista de socios, sin aportes ni liquidaciones.
     */
    fun renderVistaSocios(
        sociosActivos: List<Socio>,
        sociosArchivados: List<Socio>,
        mostrarArchivados: Boolean = false,
        error: String? = null,
        mensaje: String? = null,
        cuentas: List<CuentaSocio>? = null,
        liquidaciones: List<Liquidacion> = emptyList()
    ): String = buildString {
        val porSocio = cuentas?.associateBy { it.socioId }.orEmpty()
        val conCuentas = cuentas != null
        val totalArchivados = sociosArchivados.size
        val sufijoArchivados = if (totalArchivados > 0) " · $totalArchivados archivado(s)" else ""

        append("""<div class="ui-stack">""")
        append("""<section class="module-panel">""")
        append(
            """<header class="module-heading"><div><h2>Socios</h2>""" +
                """<p>${sociosActivos.size} socio(s) activo(s)$sufijoArchivados. """ +
                """Los porcentajes de inversión se asignan por artículo en el catálogo.</p></div></header>"""
        )
        if (!mensaje.isNullOrBlank()) append("""<p class="ui-ok" role="status">${e(mensaje)}</p>""")
        if (!error.isNullOrBlank()) append("""<p class="module-error" role="alert">${e(error)}</p>""")

        val claseChip = if (mostrarArchivados) " active" else ""
        append("""<div class="ui-toolbar"><details class="ui-details"><summary class="btn btn-sm btn-primary">+ Agregar socio</summary>""")
        append(
            """<form class="ui-card" style="margin-top:10px" hx-post="/api/socios" hx-target="#socios-panel" autocomplete="off">""" +
                """<div class="ui-field"><label for="socio-nombre">Nombre del socio *</label>""" +
                """<input id="socio-nombre" type="text" name="nombre" required maxlength="60"></div>""" +
                """<div class="ui-actions end"><button type="submit" class="btn btn-sm btn-primary">Guardar</button></div></form>"""
        )
        append("</details>")
        append(
            """<button type="button" class="chip$claseChip" aria-pressed="$mostrarArchivados" """ +
                """hx-get="/api/socios?archivados=${!mostrarArchivados}" hx-target="#socios-panel">Ver archivados</button></div>"""
        )
        append("""<div id="socios-modal"></div>""")

        if (sociosActivos.isEmpty() && sociosArchivados.isEmpty()) {
            append(
                """<p class="module-empty">Todavía no hay socios. Agrega al primero para repartir la inversión de los artículos """ +
                    """y poder vender con reparto completo.</p>"""
            )
        } else {
            val anchoMinimo = if (conCuentas) 940 else 520
            append("""<div class="table-wrap inventory-table-wrap"><table class="module-table inventory-table" style="min-width:${anchoMinimo}px">""")
            append("""<thead class="inventory-table-head"><tr><th>ID</th><th>Socio</th><th>Estado</th>""")
            if (conCuentas) {
                append(
                    """<th class="inventory-number">Aporte en stock</th><th class="inventory-number">Aporte vendido</th>""" +
                        """<th class="inventory-number">Ganancia</th><th class="inventory-number">Liquidado</th>""" +
                        """<th class="inventory-number">Saldo por liquidar</th>"""
                )
            }
            append("""<th class="inventory-number">Acciones</th></tr></thead><tbody>""")
            sociosActivos.forEach { append(renderFila(it, porSocio[it.id], conCuentas)) }
            if (mostrarArchivados) sociosArchivados.forEach { append(renderFila(it, porSocio[it.id], conCuentas)) }
            append("</tbody>")
            if (conCuentas) {
                val visibles = sociosActivos + (if (mostrarArchivados) sociosArchivados else emptyList())
                val cs = visibles.mapNotNull { porSocio[it.id] }
                val enStock = cs.fold(SignedCop.ZERO) { acc, c -> acc + c.aporteEnStock }
                val vendido = cs.fold(SignedCop.ZERO) { acc, c -> acc + c.aporteVendido }
                val ganancia = cs.fold(SignedCop.ZERO) { acc, c -> acc + c.ganancia }
                val liquidado = cs.fold(SignedCop.ZERO) { acc, c -> acc + c.liquidado }
                val saldo = cs.fold(SignedCop.ZERO) { acc, c -> acc + c.saldoPorLiquidar }
                append(
                    """<tfoot><tr><th colspan="3">Total</th>""" +
                        """<th class="ui-num">${cop(enStock)}</th>""" +
                        """<th class="ui-num">${cop(vendido)}</th>""" +
                        """<th class="ui-num">${cop(ganancia)}</th>""" +
                        """<th class="ui-num">${cop(liquidado)}</th>""" +
                        """<th class="ui-num">${cop(saldo)}</th><th></th></tr></tfoot>"""
                )
            }
            append("</table></div>")
            if (conCuentas) {
                append(
                    """<p class="module-footnote">Aporte = parte del costo que corresponde a cada socio según su % de inversión (en stock o ya vendido). """ +
                        """Lo vendido (capital recuperado + ganancia) pertenece al socio; el saldo por liquidar es lo vendido menos lo ya pagado. """ +
                        """Si una venta se anula después de liquidarla, el saldo queda negativo.</p>"""
                )
            }
        }
        append("</section>")

        if (conCuentas) {
            append("""<section class="module-panel"><header class="module-heading"><div><h2>Liquidaciones</h2>""")
            append("""<p>Pagos entregados a los socios. No se editan ni se borran: se anulan con motivo.</p></div>""")
            append("""<span class="module-count">${liquidaciones.count { it.vigente }} vigente(s)</span></header>""")
            if (liquidaciones.isEmpty()) {
                append("""<p class="module-empty">Aún no se ha registrado ninguna liquidación.</p>""")
            } else {
                append(
                    """<div class="table-wrap"><table class="module-table"><thead><tr><th>Fecha</th><th>Socio</th>""" +
                        """<th class="inventory-number">Monto</th><th>Nota</th><th>Estado</th><th class="inventory-number">Acción</th></tr></thead><tbody>"""
                )
                liquidaciones.forEach { append(renderFilaLiquidacion(it)) }
                append("</tbody></table></div>")
            }
            append("</section>")
        }
        append("</div>")
    }

    private fun renderFila(socio: Socio, cuenta: CuentaSocio?, conCuentas: Boolean): String = buildString {
        val activo = socio.activo
        val claseFila = if (activo) "" else " class=\"ui-row-off\""
        val claseEstado = if (activo) "ok" else ""
        val textoEstado = if (activo) "Activo" else "Archivado"
        append("<tr$claseFila>")
        append("""<td class="ui-mono ui-muted">#${socio.id}</td>""")
        append("""<td class="inventory-product">${e(socio.nombre)}</td>""")
        append("""<td><span class="ui-state $claseEstado">$textoEstado</span></td>""")
        if (conCuentas) {
            if (cuenta == null) {
                append("""<td class="ui-num" colspan="5">—</td>""")
            } else {
                append("""<td class="ui-num">${cop(cuenta.aporteEnStock)}</td>""")
                append("""<td class="ui-num">${cop(cuenta.aporteVendido)}</td>""")
                append("""<td class="ui-num">${cop(cuenta.ganancia)}</td>""")
                append("""<td class="ui-num">${cop(cuenta.liquidado)}</td>""")
                append("""<td class="ui-num"><strong>${cop(cuenta.saldoPorLiquidar)}</strong></td>""")
            }
        }
        append("""<td class="inventory-number"><div class="ui-actions" style="justify-content:flex-end">""")
        if (conCuentas && cuenta != null && cuenta.saldoPorLiquidar.amount > 0) {
            append(
                """<button type="button" class="ui-link-btn" hx-get="/api/socios/${socio.id}/liquidar" """ +
                    """hx-target="#socios-modal">Liquidar</button>"""
            )
        }
        if (activo) {
            append(
                """<button type="button" class="ui-link-btn danger" hx-get="/api/socios/${socio.id}/impacto" """ +
                    """hx-target="#socios-modal">Retirar</button>"""
            )
        } else {
            append(
                """<button type="button" class="ui-link-btn" hx-post="/api/socios/${socio.id}/reactivar" """ +
                    """hx-target="#socios-panel">Reactivar</button>"""
            )
        }
        append("</div></td></tr>")
    }

    private fun renderFilaLiquidacion(l: Liquidacion): String = buildString {
        val claseFila = if (l.vigente) "" else " class=\"ui-row-off\""
        append("<tr$claseFila>")
        append("""<td class="ui-mono">${e(l.fecha.toString())}</td>""")
        append("""<td>${e(l.socioNombre)}</td>""")
        append("""<td class="ui-num">${e(l.monto.formatFormattedCop())}</td>""")
        append("""<td>${e(l.nota)}</td>""")
        if (l.vigente) {
            append("""<td><span class="ui-state ok">Vigente</span></td>""")
            append(
                """<td class="inventory-number"><button type="button" class="ui-link-btn danger" """ +
                    """hx-get="/api/liquidaciones/${l.id}/anular" hx-target="#socios-modal">Anular</button></td>"""
            )
        } else {
            append("""<td><span class="ui-state bad">Anulada</span><span class="ui-sub">${e(l.anuladaMotivo)}</span></td>""")
            append("<td></td>")
        }
        append("</tr>")
    }

    /** Ventana de confirmación para retirar un socio. Se carga en #socios-modal. */
    fun renderModalImpacto(impacto: ImpactoSocio, nombre: String): String {
        val consecuencia =
            if (impacto.esArchivable) "Se archivará para conservar el historial de ventas y liquidaciones."
            else "No tiene ventas ni stock: se eliminará definitivamente."
        return """<div class="ui-modal-backdrop"><div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="retiro-titulo">""" +
            """<h3 id="retiro-titulo">Confirmar retiro de socio</h3>""" +
            """<p class="ui-hint">Socio: <strong>${e(nombre)}</strong></p>""" +
            """<div class="ui-notice"><p>Participa en <strong>${impacto.articulosEnStock}</strong> artículo(s) sin vender.</p>""" +
            """<p>Participó en <strong>${impacto.ventasParticipadas}</strong> venta(s) histórica(s).</p>""" +
            """<p><strong>$consecuencia</strong></p></div>""" +
            """<div class="ui-actions end"><button type="button" class="btn btn-sm" data-modal-close>Cancelar</button>""" +
            """<button type="button" class="btn btn-sm btn-danger" hx-delete="/api/socios/${impacto.id}" hx-target="#socios-panel">Confirmar retiro</button></div>""" +
            """</div></div>"""
    }

    /** Ventana para registrar un pago al socio. [cuenta] da el saldo sugerido y el máximo permitido. */
    fun renderModalLiquidar(socio: Socio, cuenta: CuentaSocio?, hoy: LocalDate): String {
        val saldo = cuenta?.saldoPorLiquidar?.amount ?: 0L
        val saldoTexto = if (cuenta != null) cop(cuenta.saldoPorLiquidar) else "—"
        val valorInicial = if (saldo > 0) saldo.toString() else ""
        return """<div class="ui-modal-backdrop"><div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="liq-titulo">""" +
            """<h3 id="liq-titulo">Liquidar a ${e(socio.nombre)}</h3>""" +
            """<p class="ui-hint">Saldo por liquidar: <strong>$saldoTexto</strong>. No se puede pagar más que el saldo.</p>""" +
            """<form hx-post="/api/socios/${socio.id}/liquidaciones" hx-target="#socios-panel" autocomplete="off">""" +
            """<div class="ui-grid">""" +
            """<div class="ui-field"><label for="liq-monto">Monto (COP) *</label>""" +
            """<input id="liq-monto" name="monto" type="number" min="1" step="1" inputmode="numeric" required value="$valorInicial"></div>""" +
            """<div class="ui-field"><label for="liq-fecha">Fecha *</label>""" +
            """<input id="liq-fecha" name="fecha" type="date" required value="$hoy"></div>""" +
            """<div class="ui-field wide"><label for="liq-nota">Nota</label>""" +
            """<input id="liq-nota" name="nota" type="text" maxlength="500" placeholder="Ej.: transferencia, efectivo, referencia…"></div>""" +
            """</div>""" +
            """<div class="ui-actions end"><button type="button" class="btn btn-sm" data-modal-close>Cancelar</button>""" +
            """<button type="submit" class="btn btn-sm btn-primary">Registrar pago</button></div></form></div></div>"""
    }

    /** Ventana para anular una liquidación con motivo obligatorio. */
    fun renderModalAnularLiquidacion(id: Long): String =
        """<div class="ui-modal-backdrop"><div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="anular-liq-titulo">""" +
            """<h3 id="anular-liq-titulo">Anular liquidación #$id</h3>""" +
            """<div class="ui-notice"><p>El dinero vuelve al saldo por liquidar del socio. La liquidación queda en el historial como anulada.</p></div>""" +
            """<form hx-post="/api/liquidaciones/$id/anular" hx-target="#socios-panel">""" +
            """<div class="ui-field"><label for="anular-liq-motivo">Motivo *</label>""" +
            """<textarea id="anular-liq-motivo" name="motivo" required maxlength="300" placeholder="Ej.: se registró por error"></textarea></div>""" +
            """<div class="ui-actions end"><button type="button" class="btn btn-sm" data-modal-close>Cancelar</button>""" +
            """<button type="submit" class="btn btn-sm btn-danger">Anular</button></div></form></div></div>"""
}
