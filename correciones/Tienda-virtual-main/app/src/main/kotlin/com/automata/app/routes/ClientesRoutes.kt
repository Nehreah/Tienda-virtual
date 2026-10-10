package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.service.ClienteService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.clientesRoutes(
    dashboardGuard: DashboardGuard,
    clienteService: ClienteService
) {
    get("/api/clientes") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        val clientes = clienteService.findAll()
        call.respond(HttpStatusCode.OK, clientes)
    }

    post("/api/clientes/{id}/anonimizar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de cliente requerido"))
            return@post
        }
        try {
            val anonimizado = clienteService.anonimizar(id)
            call.respond(HttpStatusCode.OK, anonimizado)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Cliente no encontrado")))
        }
    }
}
