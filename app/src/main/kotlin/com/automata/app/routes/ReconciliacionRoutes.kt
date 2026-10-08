package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.service.ReconcileCatalogUseCase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.reconciliacionRoutes(
    dashboardGuard: DashboardGuard,
    reconcileUseCase: ReconcileCatalogUseCase
) {
    post("/api/reconciliacion/ejecutar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val localProducts = synchronized(AppStore.products) { AppStore.products.values.toList() }
        val report = reconcileUseCase.execute(localProducts)
        AppStore.lastReconciliationReport = report
        call.respond(HttpStatusCode.OK, report)
    }

    get("/api/reconciliacion/reporte") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        val report = AppStore.lastReconciliationReport
        if (report != null) {
            call.respond(HttpStatusCode.OK, report)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("message" to "No se ha ejecutado ninguna reconciliación aún"))
        }
    }
}
