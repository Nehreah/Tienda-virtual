package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.ReparacionesRenderer
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.model.Reparacion
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.ports.ReparacionRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ReparacionesRoutes")

private const val SIN_BASE_DE_DATOS =
    "<section class=\"module-panel\"><h2>Reparaciones</h2><p class=\"module-error\" role=\"alert\">" +
        "El módulo de reparaciones requiere conexión a PostgreSQL y la aplicación está en modo solo memoria. " +
        "Revisa DATABASE_URL y los logs de arranque.</p></section>"

fun Route.reparacionesRoutes(
    dashboardGuard: DashboardGuard,
    inventarioRepo: InventarioRepository?,
    reparacionRepo: ReparacionRepository?
) {
    /** Vuelve a pintar el panel completo con datos frescos y, opcionalmente, un mensaje o un error (mismo patrón que Socios). */
    suspend fun ApplicationCall.panel(
        inventario: InventarioRepository,
        reparaciones: ReparacionRepository,
        mensaje: String? = null,
        error: String? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        borrador: Reparacion? = null
    ) {
        val html = try {
            val items = inventario.findAll()
            val repairs = reparaciones.findAll().associateBy { it.key.value }
            ReparacionesRenderer.render(items, repairs, error, mensaje, borrador)
        } catch (e: Exception) {
            logger.error("No se pudo cargar reparaciones: ${e.message}", e)
            respondFragmento(
                ReparacionesRenderer.render(emptyList(), emptyMap(), error ?: e.mensajeLegible()),
                HttpStatusCode.InternalServerError
            )
            return
        }
        respondFragmento(html, status)
    }

    get("/ui/reparaciones") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        if (inventarioRepo == null || reparacionRepo == null) {
            call.respondFragmento(SIN_BASE_DE_DATOS, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        call.panel(inventarioRepo, reparacionRepo)
    }

    post("/api/reparaciones/{key}") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (inventarioRepo == null || reparacionRepo == null) {
            call.respondFragmento(SIN_BASE_DE_DATOS, HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val keyRaw = call.parameters["key"]?.trim().orEmpty()
        val params = call.receiveParameters()
        val precio = params["precioReparacion"]?.trim()?.toLongOrNull()
        if (keyRaw.isBlank() || precio == null || precio < 0L) {
            call.panel(
                inventarioRepo, reparacionRepo,
                error = "Clave y precio de reparación válido (COP, entero no negativo) son obligatorios",
                status = HttpStatusCode.BadRequest
            )
            return@post
        }
        val key = ProductKey(keyRaw)
        if (inventarioRepo.findByKey(key) == null) {
            call.panel(inventarioRepo, reparacionRepo, error = "No existe el artículo $keyRaw", status = HttpStatusCode.NotFound)
            return@post
        }
        val details = params["detalles"]?.trim()?.takeIf { it.isNotEmpty() }
        if (details != null && details.length > 1000) {
            call.panel(
                inventarioRepo, reparacionRepo,
                error = "Los detalles no pueden superar 1000 caracteres",
                status = HttpStatusCode.BadRequest
            )
            return@post
        }
        try {
            reparacionRepo.guardar(Reparacion(key, Money.of(precio), details), usuario = "dashboard", origen = "dashboard")
        } catch (e: Exception) {
            // Reglas de la base (p. ej. artículo ya vendido) llegan como excepción SQL: se muestra su mensaje, no un JSON crudo.
            logger.warn("No se pudo guardar la reparación de $keyRaw: ${e.message}")
            call.panel(
                inventarioRepo, reparacionRepo,
                error = "No se pudo guardar la reparación de $keyRaw: ${e.mensajeLegible()}",
                status = HttpStatusCode.Conflict,
                borrador = Reparacion(key, Money.of(precio), details)
            )
            return@post
        }
        call.panel(
            inventarioRepo, reparacionRepo,
            mensaje = "Reparación de $keyRaw guardada. La base recalculó el costo total y el precio mínimo."
        )
    }
}
