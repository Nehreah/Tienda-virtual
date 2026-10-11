package com.automata.app.routes

import com.automata.app.dashboard.CatalogoRenderer
import com.automata.app.dashboard.CatalogoRenderer.Filtro
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.ports.ReservationRepository
import com.automata.commerce.service.ArticuloLifecycleService
import com.automata.shared.Money
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * CRUD visual del catálogo (v11). Devuelve fragmentos HTML para htmx; la lógica de negocio sigue en
 * [ArticuloLifecycleService] y las reglas de precios/estados en la base (triggers).
 *
 * Todas las rutas que modifican datos exigen credenciales y Origin propio (requireDashboardAuth mutating = true).
 * Los errores de validación devuelven 400 (409 si la Key ya existe) con el panel completo y el mensaje: dashboard.js
 * deja pasar esas respuestas text/html para que el mensaje se vea dentro del panel.
 */
fun Route.catalogoUiRoutes(
    dashboardGuard: DashboardGuard,
    articuloService: ArticuloLifecycleService?,
    inventarioRepo: InventarioRepository?,
    reservationRepo: ReservationRepository?
) {
    route("/ui/catalogo") {

        get {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val repo = inventarioRepo ?: return@get call.sinBaseDeDatos()
            call.panel(repo, call.filtro())
        }

        get("/nuevo") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            if (inventarioRepo == null) return@get call.sinBaseDeDatos()
            call.respondFragmento(CatalogoRenderer.renderFormulario(null, call.filtro()))
        }

        post {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val repo = inventarioRepo ?: return@post call.sinBaseDeDatos()
            val servicio = articuloService ?: return@post call.sinBaseDeDatos()
            val filtro = call.filtro()
            val params = call.receiveParameters()

            when (val lectura = leerArticulo(params, existente = null)) {
                is Lectura.Error -> call.panel(
                    repo, filtro, status = HttpStatusCode.BadRequest,
                    formulario = CatalogoRenderer.renderFormulario(null, filtro, valoresDe(params), lectura.mensaje)
                )
                is Lectura.Ok -> try {
                    val creado = servicio.crear(lectura.item, usuario = "dashboard")
                    sincronizarProductoEnMemoria(creado)
                    call.panel(repo, filtro, mensaje = "Artículo ${creado.key.value} creado.")
                } catch (e: Exception) {
                    val estado = if (e is IllegalArgumentException) HttpStatusCode.Conflict else HttpStatusCode.BadRequest
                    call.panel(
                        repo, filtro, status = estado,
                        formulario = CatalogoRenderer.renderFormulario(null, filtro, valoresDe(params), e.mensajeLegible())
                    )
                }
            }
        }

        get("/{key}/editar") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val repo = inventarioRepo ?: return@get call.sinBaseDeDatos()
            val servicio = articuloService ?: return@get call.sinBaseDeDatos()
            val filtro = call.filtro()
            val key = call.keyDeRuta() ?: return@get call.panel(repo, filtro, error = "Key de artículo requerida.", status = HttpStatusCode.BadRequest)
            val item = servicio.buscar(key)
                ?: return@get call.panel(repo, filtro, error = "No existe el artículo ${key.value}.", status = HttpStatusCode.NotFound)
            if (item.estaVendido || CatalogoRenderer.esArchivado(item)) {
                return@get call.panel(
                    repo, filtro, status = HttpStatusCode.Conflict,
                    error = "${key.value} está vendido o archivado y no se puede editar. Restáuralo primero si está archivado."
                )
            }
            call.respondFragmento(CatalogoRenderer.renderFormulario(item, filtro))
        }

        put("/{key}") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@put
            val repo = inventarioRepo ?: return@put call.sinBaseDeDatos()
            val servicio = articuloService ?: return@put call.sinBaseDeDatos()
            val filtro = call.filtro()
            val key = call.keyDeRuta() ?: return@put call.panel(repo, filtro, error = "Key de artículo requerida.", status = HttpStatusCode.BadRequest)
            val params = call.receiveParameters()
            val existente = servicio.buscar(key)
                ?: return@put call.panel(repo, filtro, error = "No existe el artículo ${key.value}.", status = HttpStatusCode.NotFound)
            if (existente.estaVendido || CatalogoRenderer.esArchivado(existente)) {
                return@put call.panel(
                    repo, filtro, status = HttpStatusCode.Conflict,
                    error = "${key.value} está vendido o archivado y no se puede editar."
                )
            }

            when (val lectura = leerArticulo(params, existente)) {
                is Lectura.Error -> call.panel(
                    repo, filtro, status = HttpStatusCode.BadRequest,
                    formulario = CatalogoRenderer.renderFormulario(existente, filtro, valoresDe(params), lectura.mensaje)
                )
                is Lectura.Ok -> try {
                    val actualizado = servicio.editar(lectura.item, usuario = "dashboard")
                    sincronizarProductoEnMemoria(actualizado)
                    call.panel(repo, filtro, mensaje = "Artículo ${actualizado.key.value} actualizado.")
                } catch (e: Exception) {
                    call.panel(
                        repo, filtro, status = HttpStatusCode.BadRequest,
                        formulario = CatalogoRenderer.renderFormulario(existente, filtro, valoresDe(params), e.mensajeLegible())
                    )
                }
            }
        }

        get("/{key}/archivar") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val repo = inventarioRepo ?: return@get call.sinBaseDeDatos()
            val servicio = articuloService ?: return@get call.sinBaseDeDatos()
            val filtro = call.filtro()
            val key = call.keyDeRuta() ?: return@get call.panel(repo, filtro, error = "Key de artículo requerida.", status = HttpStatusCode.BadRequest)
            val item = servicio.buscar(key)
                ?: return@get call.panel(repo, filtro, error = "No existe el artículo ${key.value}.", status = HttpStatusCode.NotFound)
            if (item.estaVendido) {
                return@get call.panel(repo, filtro, status = HttpStatusCode.Conflict, error = "${key.value} está vendido y no se puede archivar.")
            }
            call.respondFragmento(CatalogoRenderer.renderModalArchivar(item, filtro))
        }

        post("/{key}/archivar") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val repo = inventarioRepo ?: return@post call.sinBaseDeDatos()
            val servicio = articuloService ?: return@post call.sinBaseDeDatos()
            val filtro = call.filtro()
            val key = call.keyDeRuta() ?: return@post call.panel(repo, filtro, error = "Key de artículo requerida.", status = HttpStatusCode.BadRequest)
            val motivo = call.receiveParameters()["motivo"]?.trim().orEmpty()
            if (motivo.isEmpty()) {
                return@post call.panel(repo, filtro, status = HttpStatusCode.BadRequest, error = "El motivo es obligatorio para archivar un artículo.")
            }
            if (motivo.length > 300) {
                return@post call.panel(repo, filtro, status = HttpStatusCode.BadRequest, error = "El motivo no puede superar 300 caracteres.")
            }
            try {
                servicio.archivar(key = key, motivo = motivo, usuario = "dashboard", reservationRepo = reservationRepo)
                marcarEstadoEnMemoria(key, EstadoItem.INACTIVO.etiqueta)
                call.panel(repo, filtro, mensaje = "Artículo ${key.value} archivado.")
            } catch (e: IllegalArgumentException) {
                call.panel(repo, filtro, status = HttpStatusCode.NotFound, error = e.mensajeLegible())
            } catch (e: Exception) {
                call.panel(repo, filtro, status = HttpStatusCode.Conflict, error = e.mensajeLegible())
            }
        }

        post("/{key}/restaurar") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val repo = inventarioRepo ?: return@post call.sinBaseDeDatos()
            val servicio = articuloService ?: return@post call.sinBaseDeDatos()
            val filtro = call.filtro()
            val key = call.keyDeRuta() ?: return@post call.panel(repo, filtro, error = "Key de artículo requerida.", status = HttpStatusCode.BadRequest)
            try {
                servicio.restaurar(key, usuario = "dashboard")
                marcarEstadoEnMemoria(key, EstadoItem.PUBLICADO.etiqueta)
                call.panel(repo, filtro, mensaje = "Artículo ${key.value} restaurado.")
            } catch (e: IllegalArgumentException) {
                call.panel(repo, filtro, status = HttpStatusCode.NotFound, error = e.mensajeLegible())
            } catch (e: Exception) {
                call.panel(repo, filtro, status = HttpStatusCode.Conflict, error = e.mensajeLegible())
            }
        }

        delete("/{key}") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@delete
            val repo = inventarioRepo ?: return@delete call.sinBaseDeDatos()
            val servicio = articuloService ?: return@delete call.sinBaseDeDatos()
            val filtro = call.filtro()
            val key = call.keyDeRuta() ?: return@delete call.panel(repo, filtro, error = "Key de artículo requerida.", status = HttpStatusCode.BadRequest)
            try {
                if (servicio.eliminar(key, usuario = "dashboard")) {
                    quitarDeMemoria(key)
                    call.panel(repo, filtro, mensaje = "Artículo ${key.value} eliminado.")
                } else {
                    call.panel(repo, filtro, status = HttpStatusCode.NotFound, error = "No existe el artículo ${key.value}.")
                }
            } catch (e: IllegalArgumentException) {
                call.panel(repo, filtro, status = HttpStatusCode.NotFound, error = e.mensajeLegible())
            } catch (e: Exception) {
                call.panel(repo, filtro, status = HttpStatusCode.Conflict, error = e.mensajeLegible())
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Utilidades de la ruta
// ------------------------------------------------------------------------------------------------

private fun ApplicationCall.filtro(): Filtro = Filtro.desde(request.queryParameters["filtro"])

private suspend fun ApplicationCall.sinBaseDeDatos() {
    respondFragmento(CatalogoRenderer.renderSinBaseDeDatos(), HttpStatusCode.ServiceUnavailable)
}

private suspend fun ApplicationCall.panel(
    repo: InventarioRepository,
    filtro: Filtro,
    mensaje: String? = null,
    error: String? = null,
    formulario: String = "",
    status: HttpStatusCode = HttpStatusCode.OK
) {
    respondFragmento(CatalogoRenderer.renderPanel(repo.findAll(), filtro, mensaje, error, formulario), status)
}

private fun valoresDe(params: Parameters): Map<String, String> =
    params.entries().associate { it.key to it.value.firstOrNull().orEmpty() }

/** Resultado de leer el formulario: el artículo listo para guardar o el mensaje de lo que está mal. */
internal sealed interface Lectura {
    data class Ok(val item: InventarioItem) : Lectura
    data class Error(val mensaje: String) : Lectura
}

private fun fechaOpcional(raw: String?, etiqueta: String): Pair<LocalDate?, String?> {
    val texto = raw?.trim().orEmpty()
    if (texto.isEmpty()) return null to null
    return try {
        LocalDate.parse(texto) to null
    } catch (e: DateTimeParseException) {
        null to "La $etiqueta no es una fecha válida (usa el formato AAAA-MM-DD)."
    }
}

/**
 * Convierte el formulario en un [InventarioItem]. En alta valida la Key con [ProductKey.validar]; en edición conserva
 * la del artículo existente. NO calcula costo total ni precio mínimo: los calcula la base.
 */
internal fun leerArticulo(params: Parameters, existente: InventarioItem?): Lectura {
    val key: ProductKey = if (existente != null) {
        existente.key
    } else {
        ProductKey.validar(params["key"])?.let { return Lectura.Error(it) }
        ProductKey(params["key"].orEmpty().trim())
    }

    val nombre = params["item"]?.trim().orEmpty()
    if (nombre.isEmpty()) return Lectura.Error("El nombre del artículo es obligatorio.")
    if (nombre.length > 200) return Lectura.Error("El nombre no puede superar 200 caracteres.")

    fun opcional(nombreCampo: String, etiqueta: String, max: Int): Pair<String?, String?> {
        val v = params[nombreCampo]?.trim()?.takeIf { it.isNotEmpty() }
        return if (v != null && v.length > max) null to "$etiqueta no puede superar $max caracteres." else v to null
    }
    val (tipo, e1) = opcional("tipo", "El tipo", 60)
    val (marca, e2) = opcional("marca", "La marca", 60)
    val (condicion, e3) = opcional("condicion", "La condición", 40)
    val (ubicacion, e4) = opcional("ubicacion", "La ubicación", 80)
    val (descripcion, e5) = opcional("descripcion", "La descripción", 2000)
    val (link, e6) = opcional("link", "El enlace", 500)
    listOf(e1, e2, e3, e4, e5, e6).firstOrNull { it != null }?.let { return Lectura.Error(it) }
    if (link != null && !(link.startsWith("http://") || link.startsWith("https://"))) {
        return Lectura.Error("El enlace debe empezar por http:// o https://.")
    }

    val precioCompra = parseCop(params["precioCompra"])
        ?: return Lectura.Error("El precio de compra debe ser un número entero de pesos, sin decimales.")
    val precioPublicadoRaw = params["precioPublicado"]?.trim().orEmpty()
    val precioPublicado = if (precioPublicadoRaw.isEmpty()) null else
        parseCop(precioPublicadoRaw) ?: return Lectura.Error("El precio publicado debe ser un número entero de pesos, sin decimales.")

    val cantidad = params["cantidad"]?.trim()?.toIntOrNull()
    if (cantidad == null || cantidad < 0) return Lectura.Error("La cantidad debe ser un número entero igual o mayor que cero.")

    val estado = EstadoItem.desdeEtiqueta(params["estado"].orEmpty())
    if (estado == null || estado !in CatalogoRenderer.ESTADOS_EDITABLES) {
        return Lectura.Error("Estado no válido. Para vender usa Ventas; para retirar un artículo usa Archivar.")
    }

    val (fechaCompra, errCompra) = fechaOpcional(params["fechaCompra"], "fecha de compra")
    if (errCompra != null) return Lectura.Error(errCompra)
    val (fechaPublicacion, errPub) = fechaOpcional(params["fechaPublicacion"], "fecha de publicación")
    if (errPub != null) return Lectura.Error(errPub)

    return try {
        val base = existente ?: InventarioItem(key = key, item = nombre)
        Lectura.Ok(
            base.copy(
                item = nombre,
                tipo = tipo,
                marca = marca,
                condicion = condicion,
                ubicacion = ubicacion,
                descripcion = descripcion,
                link = link,
                estado = estado,
                // El precio mínimo lo recalcula la base con el costo nuevo; el viejo no debe bloquear la validación PM <= PP.
                precioMinimo = null,
                precioCompra = Money.of(precioCompra),
                precioPublicado = precioPublicado?.let { Money.of(it) },
                cantidad = cantidad,
                fechaCompra = fechaCompra,
                fechaPublicacion = fechaPublicacion
            )
        )
    } catch (e: IllegalArgumentException) {
        Lectura.Error(e.message ?: "Los datos del artículo no son válidos.")
    }
}
