package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.service.StockDriftDetector
import com.automata.infra.db.ExposedUnresolvedEventStore
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.eventosDerivaRoutes(
    dashboardGuard: DashboardGuard,
    unresolvedEventStore: ExposedUnresolvedEventStore?,
    stockDriftDetector: StockDriftDetector
) {
    get("/api/eventos-sin-resolver") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        if (unresolvedEventStore == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@get
        }
        val pendingEvents = unresolvedEventStore.findPendingEvents()
        call.respond(HttpStatusCode.OK, pendingEvents)
    }

    post("/api/eventos-sin-resolver/{id}/resolver") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (unresolvedEventStore == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de evento invalido"))
            return@post
        }
        val resolved = unresolvedEventStore.resolveEvent(id)
        if (resolved) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "RESUELTO", "id" to id))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Evento $id no encontrado"))
        }
    }

    get("/api/deriva/reporte") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        val localProducts = synchronized(AppStore.products) { AppStore.products.values.toList() }
        val report = stockDriftDetector.detectDrift(localProducts, emptyMap())
        call.respond(HttpStatusCode.OK, report)
    }
}
