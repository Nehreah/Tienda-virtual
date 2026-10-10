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

fun Route.reparacionesRoutes(
    dashboardGuard: DashboardGuard,
    inventarioRepo: InventarioRepository?,
    reparacionRepo: ReparacionRepository?
) {
    get("/ui/reparaciones") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        if (inventarioRepo == null || reparacionRepo == null) {
            call.respondText("<section class=\"module-panel\"><h2>Reparaciones</h2><p>El módulo requiere una conexión a PostgreSQL.</p></section>", ContentType.Text.Html, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        try {
            val items = inventarioRepo.findAll()
            val repairs = items.filter { it.requiereReparacion }.mapNotNull { item -> reparacionRepo.find(item.key)?.let { item.key.value to it } }.toMap()
            call.respondText(ReparacionesRenderer.render(items, repairs), ContentType.Text.Html)
        } catch (e: Exception) {
            call.respondText(ReparacionesRenderer.render(emptyList(), emptyMap(), e.message ?: "No se pudo cargar reparaciones"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
        }
    }

    post("/api/reparaciones/{key}") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (inventarioRepo == null || reparacionRepo == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "El módulo requiere una conexión a PostgreSQL"))
            return@post
        }
        val keyRaw = call.parameters["key"]?.trim().orEmpty()
        val params = call.receiveParameters()
        val precioRaw = params["precioReparacion"]
        val precio = precioRaw?.toLongOrNull()
        if (keyRaw.isBlank() || precio == null || precio < 0L) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Clave y precio de reparación válido (COP, entero no negativo) son obligatorios"))
            return@post
        }
        val key = ProductKey(keyRaw)
        val item = inventarioRepo.findByKey(key)
        if (item == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe el artículo $keyRaw"))
            return@post
        }
        val details = params["detalles"]?.trim()?.takeIf { it.isNotEmpty() }
        if (details != null && details.length > 1000) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Los detalles no pueden superar 1000 caracteres"))
            return@post
        }
        try {
            reparacionRepo.guardar(Reparacion(key, Money.of(precio), details), usuario = "dashboard", origen = "dashboard")
            call.respondRedirect("/", permanent = false)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Datos de reparación inválidos")))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "No se pudo guardar la reparación")))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo guardar la reparación"))
        }
    }
}
