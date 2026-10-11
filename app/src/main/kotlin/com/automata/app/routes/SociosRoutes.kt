package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.SociosRenderer
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.commerce.ports.LiquidacionRepository
import com.automata.commerce.ports.SocioRepository
import com.automata.commerce.service.SocioService
import com.automata.commerce.service.TipoRetiro
import com.automata.shared.Money
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Socios, aportes y liquidaciones (v11). [liquidacionRepo] es null cuando no hay base de datos: en ese caso solo se
 * muestra la lista de socios y las rutas de liquidación responden 503.
 */
fun Route.sociosRoutes(
    dashboardGuard: DashboardGuard,
    socioService: SocioService,
    socioRepo: SocioRepository,
    liquidacionRepo: LiquidacionRepository? = null
) {
    /** Vuelve a pintar el panel completo con datos frescos y, opcionalmente, un mensaje o un error. */
    suspend fun ApplicationCall.panel(
        mostrarArchivados: Boolean = false,
        mensaje: String? = null,
        error: String? = null,
        status: HttpStatusCode = HttpStatusCode.OK
    ) {
        val activos = socioRepo.listar(incluirArchivados = false)
        val archivados = if (mostrarArchivados) socioRepo.listar(incluirArchivados = true).filter { !it.activo } else emptyList()
        // Los archivados también pueden tener saldo por liquidar: se cuentan aunque la lista no los muestre.
        val html = try {
            val cuentas = liquidacionRepo?.cuentas()
            val liquidaciones = liquidacionRepo?.listar(limite = 100).orEmpty()
            SociosRenderer.renderVistaSocios(activos, archivados, mostrarArchivados, error, mensaje, cuentas, liquidaciones)
        } catch (e: Exception) {
            // Si la vista de cuentas falla (por ejemplo, falta aplicar V005), la lista de socios sigue funcionando.
            val aviso = "No se pudieron cargar aportes y liquidaciones: ${e.mensajeLegible()}"
            SociosRenderer.renderVistaSocios(activos, archivados, mostrarArchivados, error ?: aviso, mensaje)
        }
        respondFragmento(html, status)
    }

    suspend fun ApplicationCall.sinLiquidaciones() {
        respondFragmento(
            """<div class="ui-modal-backdrop"><div class="ui-modal"><h3>No disponible</h3>""" +
                """<p class="ui-hint">Las liquidaciones necesitan la base de datos.</p>""" +
                """<div class="ui-actions end"><button type="button" class="btn btn-sm" data-modal-close>Cerrar</button></div></div></div>""",
            HttpStatusCode.ServiceUnavailable
        )
    }

    route("/api/socios") {

        get {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            val incluirArchivados = call.request.queryParameters["archivados"]?.toBoolean() ?: false
            call.panel(mostrarArchivados = incluirArchivados)
        }

        post {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val nombre = call.receiveParameters()["nombre"] ?: ""
            try {
                val socio = socioService.crear(nombre)
                call.panel(mensaje = "Socio ${socio.nombre} agregado.")
            } catch (e: Exception) {
                call.panel(error = e.mensajeLegible(), status = HttpStatusCode.BadRequest)
            }
        }

        put("/{id}") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@put
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de socio inválido"))
                return@put
            }
            val nombre = call.receiveParameters()["nombre"]?.trim().orEmpty()
            try {
                val socio = socioService.renombrar(id, nombre)
                call.respond(HttpStatusCode.OK, mapOf("id" to socio.id, "nombre" to socio.nombre, "estado" to if (socio.activo) "activo" else "archivado"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Nombre inválido")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "No se pudo renombrar el socio")))
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
            call.respondFragmento(SociosRenderer.renderModalImpacto(impacto, socio.nombre))
        }

        delete("/{id}") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@delete
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID inválido")
                return@delete
            }
            try {
                val resultado = socioService.retirar(id)
                val texto = if (resultado == TipoRetiro.ARCHIVADO) "Socio archivado: se conserva su historial." else "Socio eliminado."
                call.panel(mensaje = texto)
            } catch (e: Exception) {
                call.panel(error = e.mensajeLegible(), status = HttpStatusCode.Conflict)
            }
        }

        post("/{id}/reactivar") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "ID inválido")
                return@post
            }
            try {
                val socio = socioService.reactivar(id)
                call.panel(mensaje = "Socio ${socio.nombre} reactivado.")
            } catch (e: Exception) {
                call.panel(error = e.mensajeLegible(), status = HttpStatusCode.BadRequest)
            }
        }

        // ---- Liquidaciones de un socio ----

        get("/{id}/liquidar") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            if (liquidacionRepo == null) return@get call.sinLiquidaciones()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.panel(error = "ID de socio inválido.", status = HttpStatusCode.BadRequest)
            val socio = socioRepo.buscarPorId(id)
                ?: return@get call.panel(error = "Socio no encontrado.", status = HttpStatusCode.NotFound)
            val cuenta = liquidacionRepo.cuentas().firstOrNull { it.socioId == id }
            call.respondFragmento(SociosRenderer.renderModalLiquidar(socio, cuenta, LocalDate.now()))
        }

        post("/{id}/liquidaciones") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            if (liquidacionRepo == null) return@post call.sinLiquidaciones()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.panel(error = "ID de socio inválido.", status = HttpStatusCode.BadRequest)
            val params = call.receiveParameters()

            val monto = parseCop(params["monto"])
            if (monto == null || monto <= 0) {
                return@post call.panel(error = "El monto debe ser un número entero de pesos mayor que cero.", status = HttpStatusCode.BadRequest)
            }
            val fechaRaw = params["fecha"]?.trim().orEmpty()
            val fecha = if (fechaRaw.isEmpty()) null else try {
                LocalDate.parse(fechaRaw)
            } catch (e: DateTimeParseException) {
                return@post call.panel(error = "La fecha no es válida (usa AAAA-MM-DD).", status = HttpStatusCode.BadRequest)
            }
            val nota = params["nota"]?.trim()?.takeIf { it.isNotEmpty() }
            if (nota != null && nota.length > 500) {
                return@post call.panel(error = "La nota no puede superar 500 caracteres.", status = HttpStatusCode.BadRequest)
            }

            try {
                liquidacionRepo.registrar(id, Money.of(monto), fecha, nota, usuario = "dashboard")
                call.panel(mensaje = "Liquidación de ${Money.of(monto).formatFormattedCop()} registrada.")
            } catch (e: Exception) {
                call.panel(error = e.mensajeLegible(), status = HttpStatusCode.BadRequest)
            }
        }
    }

    route("/api/liquidaciones") {

        get("/{id}/anular") {
            if (!call.requireDashboardAuth(dashboardGuard)) return@get
            if (liquidacionRepo == null) return@get call.sinLiquidaciones()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.panel(error = "ID de liquidación inválido.", status = HttpStatusCode.BadRequest)
            call.respondFragmento(SociosRenderer.renderModalAnularLiquidacion(id))
        }

        post("/{id}/anular") {
            if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
            if (liquidacionRepo == null) return@post call.sinLiquidaciones()
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.panel(error = "ID de liquidación inválido.", status = HttpStatusCode.BadRequest)
            val motivo = call.receiveParameters()["motivo"]?.trim().orEmpty()
            if (motivo.isEmpty()) {
                return@post call.panel(error = "El motivo es obligatorio para anular una liquidación.", status = HttpStatusCode.BadRequest)
            }
            if (motivo.length > 300) {
                return@post call.panel(error = "El motivo no puede superar 300 caracteres.", status = HttpStatusCode.BadRequest)
            }
            try {
                liquidacionRepo.anular(id, motivo, usuario = "dashboard")
                call.panel(mensaje = "Liquidación #$id anulada.")
            } catch (e: Exception) {
                call.panel(error = e.mensajeLegible(), status = HttpStatusCode.BadRequest)
            }
        }
    }
}
