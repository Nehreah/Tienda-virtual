package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.SociosRenderer
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.ports.SocioRepository
import com.automata.commerce.service.SocioService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.sociosRoutes(
    dashboardGuard: DashboardGuard,
    socioService: SocioService,
    socioRepo: SocioRepository
) {
    route("/api/socios") {

        get {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val incluirArchivados = call.request.queryParameters["archivados"]?.toBoolean() ?: false
            val activos = socioRepo.listar(incluirArchivados = false)
            val archivados = if (incluirArchivados) socioRepo.listar(incluirArchivados = true).filter { !it.activo } else emptyList()
            val html = SociosRenderer.renderVistaSocios(activos, archivados, mostrarArchivados = incluirArchivados)
            call.respondText(html, ContentType.Text.Html)
        }

        post {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val params = call.receiveParameters()
            val nombre = params["nombre"] ?: ""

            try {
                socioService.crear(nombre)
                val activos = socioRepo.listar(incluirArchivados = false)
                val html = SociosRenderer.renderVistaSocios(activos, emptyList())
                call.respondText(html, ContentType.Text.Html)
            } catch (e: Exception) {
                val activos = socioRepo.listar(incluirArchivados = false)
                val html = SociosRenderer.renderVistaSocios(activos, emptyList(), error = e.message ?: "Error creando socio")
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.BadRequest)
            }
        }

        get("/{id}/impacto") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID de socio inválido")
                return@get
            }
            val socio = socioRepo.buscarPorId(id)
            if (socio == null) {
                call.respond(HttpStatusCode.NotFound, "Socio no encontrado")
                return@get
            }
            val impacto = socioService.evaluarImpacto(id)
            val html = SociosRenderer.renderModalImpacto(impacto, socio.nombre)
            call.respondText(html, ContentType.Text.Html)
        }

        delete("/{id}") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@delete
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID inválido")
                return@delete
            }
            socioService.retirar(id)
            val activos = socioRepo.listar(incluirArchivados = false)
            val html = SociosRenderer.renderVistaSocios(activos, emptyList())
            call.respondText(html, ContentType.Text.Html)
        }

        post("/{id}/reactivar") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID inválido")
                return@post
            }
            try {
                socioService.reactivar(id)
                val activos = socioRepo.listar(incluirArchivados = false)
                val html = SociosRenderer.renderVistaSocios(activos, emptyList())
                call.respondText(html, ContentType.Text.Html)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Error reactivando socio")
            }
        }
    }
}
