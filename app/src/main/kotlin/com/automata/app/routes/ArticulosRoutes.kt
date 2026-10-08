package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.ports.ReservationRepository
import com.automata.commerce.service.ArticuloLifecycleService
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.articulosRoutes(
    dashboardGuard: DashboardGuard,
    articuloService: ArticuloLifecycleService?,
    reservationRepo: ReservationRepository?
) {
    post("/api/articulos/{key}/archivar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val key = ProductKey(call.parameters["key"] ?: "")
        val params = call.receiveParameters()
        val motivo = params["motivo"]?.trim() ?: ""
        if (motivo.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El campo 'motivo' es obligatorio para archivar un artículo"))
            return@post
        }
        try {
            articuloService.archivar(
                key = key,
                motivo = motivo,
                usuario = "dashboard",
                reservationRepo = reservationRepo
            )
            synchronized(AppStore.products) {
                AppStore.products[key.value]?.let {
                    AppStore.products[key.value] = it.copy(status = "Inactivo")
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "archivado", "key" to key.value, "motivo" to motivo))
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "No se pudo archivar")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Artículo no encontrado")))
        }
    }

    post("/api/articulos/{key}/restaurar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val key = ProductKey(call.parameters["key"] ?: "")
        try {
            articuloService.restaurar(key, usuario = "dashboard")
            synchronized(AppStore.products) {
                AppStore.products[key.value]?.let {
                    AppStore.products[key.value] = it.copy(status = "Publicado")
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "restaurado", "key" to key.value))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Artículo no encontrado")))
        }
    }

    delete("/api/articulos/{key}") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@delete
        if (articuloService == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@delete
        }
        val key = ProductKey(call.parameters["key"] ?: "")
        try {
            val deleted = articuloService.eliminar(key, usuario = "dashboard")
            if (deleted) {
                synchronized(AppStore.products) { AppStore.products.remove(key.value) }
                synchronized(AppStore.inventory) { AppStore.inventory.remove(key.value) }
                call.respond(HttpStatusCode.OK, mapOf("status" to "eliminado", "key" to key.value))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe el artículo ${key.value}"))
            }
        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "No se puede eliminar")))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Artículo no encontrado")))
        }
    }
}
