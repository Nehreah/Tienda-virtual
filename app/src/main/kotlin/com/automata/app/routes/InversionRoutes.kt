package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.InventarioRenderer
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.ports.InversionRepository
import com.automata.commerce.ports.SocioRepository
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal

fun Route.inversionRoutes(
    dashboardGuard: DashboardGuard,
    inversionRepo: InversionRepository,
    inventarioRepo: InventarioRepository,
    socioRepo: SocioRepository
) {
    route("/api/inventario") {

        get("/tabla") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val items = inventarioRepo.findAll()
            val socios = socioRepo.listar(incluirArchivados = false)
            val html = InventarioRenderer.renderTablaInventarioConSocios(items, socios)
            call.respondText(html, ContentType.Text.Html)
        }

        put("/{key}/inversion/{socioId}") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@put
            val keyStr = call.parameters["key"] ?: ""
            val socioId = call.parameters["socioId"]?.toLongOrNull()
            val params = call.receiveParameters()
            val pctStr = params["pct"] ?: "0"

            if (socioId == null) {
                call.respond(HttpStatusCode.BadRequest, "Socio ID inválido")
                return@put
            }

            val pct = pctStr.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val key = ProductKey(keyStr)

            try {
                inversionRepo.fijarPorcentaje(key, socioId, pct)
                val items = inventarioRepo.findAll()
                val socios = socioRepo.listar(incluirArchivados = false)
                val html = InventarioRenderer.renderTablaInventarioConSocios(items, socios)
                call.respondText(html, ContentType.Text.Html)
            } catch (e: Exception) {
                val items = inventarioRepo.findAll()
                val socios = socioRepo.listar(incluirArchivados = false)
                val html = InventarioRenderer.renderTablaInventarioConSocios(items, socios, errorMsg = e.message ?: "Error fijando porcentaje")
                call.respondText(html, ContentType.Text.Html)
            }
        }

        post("/{key}/inversion/repartir-igual") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val keyStr = call.parameters["key"] ?: ""
            val key = ProductKey(keyStr)
            val socios = socioRepo.listar(incluirArchivados = false)
            val sociosIds = socios.map { it.id }

            try {
                inversionRepo.repartirPartesIguales(key, sociosIds)
                val items = inventarioRepo.findAll()
                val html = InventarioRenderer.renderTablaInventarioConSocios(items, socios)
                call.respondText(html, ContentType.Text.Html)
            } catch (e: Exception) {
                val items = inventarioRepo.findAll()
                val html = InventarioRenderer.renderTablaInventarioConSocios(items, socios, errorMsg = e.message ?: "Error repartiendo en partes iguales")
                call.respondText(html, ContentType.Text.Html)
            }
        }
    }
}
