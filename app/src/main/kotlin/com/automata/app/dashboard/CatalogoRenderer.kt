package com.automata.app.dashboard

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * CRUD visual del catálogo (v11): listado, alta, edición, archivado con motivo, restauración y eliminación.
 *
 * - Solo CSS local (clases ui-* e inventory-* de DashboardRenderer); no depende de Tailwind.
 * - Sin JavaScript en línea: todo se hace con atributos hx-* (htmx oficial) y data-* (dashboard.js).
 * - Todo texto que viene de la base o de la persona pasa por [DashboardRenderer.escapeHtml].
 * - El precio mínimo (PM) se muestra aquí porque el dashboard es privado; nunca sale a canales públicos.
 */
object CatalogoRenderer {

    enum class Filtro(val param: String, val etiqueta: String) {
        ACTIVOS("activos", "Activos"),
        ARCHIVADOS("archivados", "Archivados"),
        TODOS("todos", "Todos");

        companion object {
            fun desde(valor: String?): Filtro = entries.firstOrNull { it.param.equals(valor?.trim(), ignoreCase = true) } ?: ACTIVOS
        }
    }

    /** Estados que se pueden elegir a mano. "Vendido" se registra desde Ventas y "Inactivo" con Archivar. */
    val ESTADOS_EDITABLES: List<EstadoItem> =
        listOf(EstadoItem.COMPRADO, EstadoItem.PUBLICADO, EstadoItem.NEGOCIACION, EstadoItem.OTRO)

    private val CONDICIONES_SUGERIDAS = listOf("Nuevo", "Usado", "Reacondicionado")

    fun esArchivado(item: InventarioItem): Boolean = item.estado == EstadoItem.INACTIVO || item.archivadoEn != null

    private fun e(texto: String?): String = DashboardRenderer.escapeHtml(texto.orEmpty())

    private fun enc(valor: String): String = URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20")

    fun renderSinBaseDeDatos(): String =
        """<section class="module-panel"><header class="module-heading"><div><h2>Artículos</h2>""" +
            """<p>La gestión de artículos necesita la base de datos. Configura DATABASE_URL y reinicia la aplicación.</p>""" +
            """</div></header></section>"""

    /**
     * Contenido del panel #catalogo-panel. [formularioHtml] se pinta dentro de #catalogo-form-slot
     * (por ejemplo, el formulario abierto cuando hubo un error de validación).
     */
    fun renderPanel(
        items: List<InventarioItem>,
        filtro: Filtro = Filtro.ACTIVOS,
        mensaje: String? = null,
        error: String? = null,
        formularioHtml: String = ""
    ): String = buildString {
        val visibles = items.filter {
            when (filtro) {
                Filtro.ACTIVOS -> !esArchivado(it)
                Filtro.ARCHIVADOS -> esArchivado(it)
                Filtro.TODOS -> true
            }
        }
        val qs = "?filtro=${filtro.param}"

        append("""<section class="module-panel">""")
        append(
            """<header class="module-heading"><div><h2>Artículos</h2>""" +
                """<p>Crea, edita, archiva o restaura artículos. El costo total y el precio mínimo (costo total × 1,5) los calcula """ +
                """PostgreSQL; el precio mínimo es interno y no se publica en ningún canal.</p></div>""" +
                """<span class="module-count">${visibles.size} de ${items.size}</span></header>"""
        )
        if (!mensaje.isNullOrBlank()) append("""<p class="ui-ok" role="status">${e(mensaje)}</p>""")
        if (!error.isNullOrBlank()) append("""<p class="module-error" role="alert">${e(error)}</p>""")

        append("""<div class="ui-toolbar"><div class="ui-toolbar-group" role="group" aria-label="Filtro de artículos">""")
        Filtro.entries.forEach { f ->
            val activo = f == filtro
            append(
                """<button type="button" class="chip${if (activo) " active" else ""}" aria-pressed="${activo}" """ +
                    """hx-get="/ui/catalogo?filtro=${f.param}" hx-target="#catalogo-panel">${e(f.etiqueta)}</button>"""
            )
        }
        append("</div>")
        append(
            """<button type="button" class="btn btn-sm btn-primary" hx-get="/ui/catalogo/nuevo$qs" """ +
                """hx-target="#catalogo-form-slot">+ Nuevo artículo</button></div>"""
        )

        append("""<div id="catalogo-form-slot">$formularioHtml</div>""")
        append("""<div id="catalogo-modal"></div>""")

        if (visibles.isEmpty()) {
            append("""<p class="module-empty">No hay artículos en esta vista.</p>""")
        } else {
            append("""<div class="table-wrap inventory-table-wrap"><table class="module-table inventory-table" style="min-width:980px">""")
            append(
                """<thead class="inventory-table-head"><tr><th>Key</th><th>Artículo</th><th>Estado</th>""" +
                    """<th class="inventory-number">Cant.</th><th class="inventory-number">Compra</th>""" +
                    """<th class="inventory-number">Costo total</th><th class="inventory-number">Precio mín.</th>""" +
                    """<th class="inventory-number">Precio pub.</th><th class="inventory-number">Acciones</th></tr></thead><tbody>"""
            )
            visibles.forEach { append(renderFila(it, qs)) }
            append("</tbody></table></div>")
        }
        append("</section>")
    }

    private fun renderFila(item: InventarioItem, qs: String): String = buildString {
        val keyEnc = enc(item.key.value)
        val archivado = esArchivado(item)
        val detalle = listOfNotNull(item.tipo?.takeIf { it.isNotBlank() }, item.marca?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val claseEstado = when {
            item.estaVendido -> "bad"
            archivado -> ""
            item.estado == EstadoItem.PUBLICADO -> "ok"
            item.estado == EstadoItem.NEGOCIACION -> "warn"
            else -> ""
        }
        val claseFila = if (archivado) " class=\"ui-row-off\"" else ""
        val subtitulo = if (detalle.isNotBlank()) "<span class=\"ui-sub\">${e(detalle)}</span>" else ""
        append("""<tr id="art-${e(item.key.value)}"$claseFila>""")
        append("""<td class="inventory-key">${e(item.key.value)}</td>""")
        append("""<td class="inventory-product">${e(item.item)}$subtitulo</td>""")
        append("""<td><span class="ui-state $claseEstado">${e(item.estado.etiqueta)}</span></td>""")
        append("""<td class="ui-num">${item.cantidad}</td>""")
        append("""<td class="ui-num">${item.precioCompra.formatFormattedCop()}</td>""")
        append("""<td class="ui-num">${item.costoTotal.formatFormattedCop()}</td>""")
        append("""<td class="ui-num">${item.precioMinimo?.formatFormattedCop() ?: "—"}</td>""")
        append("""<td class="ui-num">${item.precioPublicado?.formatFormattedCop() ?: "—"}</td>""")
        append("""<td class="inventory-number"><div class="ui-actions" style="justify-content:flex-end">""")
        when {
            item.estaVendido ->
                append("""<span class="ui-muted ui-small">Vendido: bloqueado</span>""")
            archivado -> {
                append(
                    """<button type="button" class="ui-link-btn" hx-post="/ui/catalogo/$keyEnc/restaurar$qs" """ +
                        """hx-target="#catalogo-panel">Restaurar</button>"""
                )
                append(botonEliminar(item, keyEnc, qs))
            }
            else -> {
                append(
                    """<button type="button" class="ui-link-btn" hx-get="/ui/catalogo/$keyEnc/editar$qs" """ +
                        """hx-target="#catalogo-form-slot">Editar</button>"""
                )
                if (item.estado == EstadoItem.PUBLICADO || item.estado == EstadoItem.NEGOCIACION) {
                    append(botonPublicar(item, keyEnc, qs, "shopify", "Shopify"))
                    append(botonPublicar(item, keyEnc, qs, "meta_catalogo", "Meta"))
                }
                append(
                    """<button type="button" class="ui-link-btn" hx-get="/ui/catalogo/$keyEnc/archivar$qs" """ +
                        """hx-target="#catalogo-modal">Archivar</button>"""
                )
                append(botonEliminar(item, keyEnc, qs))
            }
        }
        append("</div></td></tr>")
    }

    /** v14: publica el artículo en un canal. Con CHANNEL_WRITE_ENABLED=false solo hace un ensayo (no escribe nada). */
    private fun botonPublicar(item: InventarioItem, keyEnc: String, qs: String, canal: String, etiqueta: String): String =
        """<button type="button" class="ui-link-btn" title="Publicar en $etiqueta" hx-post="/ui/catalogo/$keyEnc/publicar$qs&canal=$canal" """ +
            """hx-target="#catalogo-panel" hx-confirm="${e("¿Publicar ${item.key.value} en $etiqueta?")}">↗ $etiqueta</button>"""

    private fun botonEliminar(item: InventarioItem, keyEnc: String, qs: String): String =
        """<button type="button" class="ui-link-btn danger" hx-delete="/ui/catalogo/$keyEnc$qs" """ +
            """hx-target="#catalogo-panel" hx-confirm="${e("¿Eliminar definitivamente ${item.key.value}? Solo se puede si no tiene ventas, reservas ni conversaciones.")}">""" +
            """Eliminar</button>"""

    /**
     * Formulario de alta ([item] = null) o de edición. [valores] repite lo que la persona escribió cuando hubo un
     * error de validación, para que no pierda lo digitado.
     */
    fun renderFormulario(
        item: InventarioItem?,
        filtro: Filtro,
        valores: Map<String, String> = emptyMap(),
        error: String? = null
    ): String = buildString {
        val edita = item != null
        val qs = "?filtro=${filtro.param}"

        fun v(nombre: String, porDefecto: String): String = valores[nombre] ?: porDefecto
        fun campo(
            nombre: String, etiqueta: String, valor: String, tipo: String = "text",
            requerido: Boolean = false, extra: String = "", ayuda: String? = null, ancho: Boolean = false
        ) {
            val id = "art-f-$nombre"
            append("""<div class="ui-field${if (ancho) " wide" else ""}"><label for="$id">${e(etiqueta)}${if (requerido) " *" else ""}</label>""")
            append("""<input id="$id" name="$nombre" type="$tipo" value="${e(valor)}"${if (requerido) " required" else ""} $extra>""")
            if (ayuda != null) append("<small>${e(ayuda)}</small>")
            append("</div>")
        }

        val titulo = if (item != null) "Editar ${item.key.value}" else "Nuevo artículo"
        val accion = if (item != null) "hx-put=\"/ui/catalogo/${enc(item.key.value)}$qs\"" else "hx-post=\"/ui/catalogo$qs\""

        append("""<form class="ui-card" $accion hx-target="#catalogo-panel" autocomplete="off">""")
        append("""<h3>${e(titulo)}</h3>""")
        append(
            """<p class="ui-hint">Los campos con * son obligatorios. El precio mínimo se calcula solo: (precio de compra + reparación) × 1,5. """ +
                """Un artículo Publicado o en Negociación debe tener precio publicado igual o mayor que el mínimo.</p>"""
        )
        if (!error.isNullOrBlank()) append("""<p class="module-error" role="alert">${e(error)}</p>""")

        append("""<div class="ui-grid">""")
        if (item != null) {
            campo("key", "Key", item.key.value, extra = "readonly", ayuda = "La Key no se cambia: es el identificador del artículo.")
        } else {
            campo(
                "key", "Key", v("key", ""), requerido = true,
                extra = "maxlength=\"64\" pattern=\"[\\p{L}\\p{N}][\\p{L}\\p{N}._\\-]*\" placeholder=\"PROD-001\" autocapitalize=\"characters\"",
                ayuda = "Letras, números, guion (-), guion bajo (_) y punto (.), sin espacios. Máx. 64."
            )
        }
        campo("item", "Nombre del artículo", v("item", item?.item.orEmpty()), requerido = true, extra = "maxlength=\"200\"", ancho = true)
        campo("tipo", "Tipo", v("tipo", item?.tipo.orEmpty()), extra = "maxlength=\"60\"")
        campo("marca", "Marca", v("marca", item?.marca.orEmpty()), extra = "maxlength=\"60\"")
        campo(
            "condicion", "Condición", v("condicion", item?.condicion.orEmpty()),
            extra = "maxlength=\"40\" list=\"art-condiciones\""
        )
        campo("ubicacion", "Ubicación", v("ubicacion", item?.ubicacion.orEmpty()), extra = "maxlength=\"80\"")
        campo(
            "precioCompra", "Precio de compra (COP)", v("precioCompra", item?.precioCompra?.amountInCop?.toString() ?: "0"),
            tipo = "number", requerido = true, extra = "min=\"0\" step=\"1\" inputmode=\"numeric\""
        )
        campo(
            "precioPublicado", "Precio publicado (COP)", v("precioPublicado", item?.precioPublicado?.amountInCop?.toString().orEmpty()),
            tipo = "number", extra = "min=\"0\" step=\"1\" inputmode=\"numeric\""
        )
        campo(
            "cantidad", "Cantidad disponible", v("cantidad", item?.cantidad?.toString() ?: "1"),
            tipo = "number", requerido = true, extra = "min=\"0\" step=\"1\" inputmode=\"numeric\""
        )

        val estadoActual = v("estado", item?.estado?.etiqueta ?: EstadoItem.COMPRADO.etiqueta)
        append("""<div class="ui-field"><label for="art-f-estado">Estado *</label><select id="art-f-estado" name="estado" required>""")
        ESTADOS_EDITABLES.forEach { est ->
            val sel = EstadoItem.desdeEtiqueta(estadoActual) == est
            append("""<option value="${e(est.etiqueta)}"${if (sel) " selected" else ""}>${e(est.etiqueta)}</option>""")
        }
        append("""</select><small>Para vender usa Ventas; para retirar usa Archivar.</small></div>""")

        campo("fechaCompra", "Fecha de compra", v("fechaCompra", item?.fechaCompra?.toString().orEmpty()), tipo = "date")
        campo("fechaPublicacion", "Fecha de publicación", v("fechaPublicacion", item?.fechaPublicacion?.toString().orEmpty()), tipo = "date")
        campo("link", "Enlace de la publicación", v("link", item?.link.orEmpty()), tipo = "url", extra = "maxlength=\"500\" placeholder=\"https://…\"", ancho = true)

        append("""<div class="ui-field wide"><label for="art-f-descripcion">Descripción</label>""")
        append("""<textarea id="art-f-descripcion" name="descripcion" maxlength="2000">${e(v("descripcion", item?.descripcion.orEmpty()))}</textarea></div>""")
        append("</div>")

        val opcionesCondicion = CONDICIONES_SUGERIDAS.joinToString("") { "<option value=\"${e(it)}\">" }
        append("""<datalist id="art-condiciones">$opcionesCondicion</datalist>""")
        append(
            """<div class="ui-actions end"><button type="button" class="btn btn-sm" data-clear="#catalogo-form-slot">Cancelar</button>""" +
                """<button type="submit" class="btn btn-sm btn-primary">${if (edita) "Guardar cambios" else "Crear artículo"}</button></div>"""
        )
        append("</form>")
    }

    /** Ventana para archivar con motivo obligatorio. Se carga en #catalogo-modal. */
    fun renderModalArchivar(item: InventarioItem, filtro: Filtro): String {
        val keyEnc = enc(item.key.value)
        return """<div class="ui-modal-backdrop"><div class="ui-modal" role="dialog" aria-modal="true" aria-labelledby="archivar-titulo">""" +
            """<h3 id="archivar-titulo">Archivar artículo</h3>""" +
            """<p class="ui-hint"><strong>${e(item.key.value)}</strong> — ${e(item.item)}</p>""" +
            """<div class="ui-notice"><p>El artículo pasa a Inactivo y deja de publicarse. No se borra su historial y se puede restaurar.</p></div>""" +
            """<form hx-post="/ui/catalogo/$keyEnc/archivar?filtro=${filtro.param}" hx-target="#catalogo-panel">""" +
            """<div class="ui-field"><label for="archivar-motivo">Motivo *</label>""" +
            """<textarea id="archivar-motivo" name="motivo" required maxlength="300" placeholder="Ej.: se dañó, se devolvió al proveedor…"></textarea></div>""" +
            """<div class="ui-actions end"><button type="button" class="btn btn-sm" data-modal-close>Cancelar</button>""" +
            """<button type="submit" class="btn btn-sm btn-danger">Archivar</button></div></form></div></div>"""
    }
}
