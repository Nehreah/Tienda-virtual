package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.ventasRoutes(
    dashboardGuard: DashboardGuard
) {
    // 1. Registrar Venta Manual
    post("/api/ventas") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val params = call.receiveParameters()
        val key = params["key"]?.trim() ?: ""
        val cantidad = params["cantidad"]?.toIntOrNull() ?: 1
        val precioUnitario = params["precioUnitario"]?.toLongOrNull() ?: 0L
        val canal = params["canal"]?.trim() ?: "presencial"
        val canalDetalle = params["canalDetalle"]?.trim()
        val orderId = params["orderId"]?.trim()
        val idempotencyKey = params["idempotencyKey"]?.trim() ?: "MAN-${java.util.UUID.randomUUID()}"
        val estadoLogistico = params["estadoLogistico"]?.trim() ?: "SIN_ENVIO"
        val nota = params["nota"]?.trim()
        val usuario = "dashboard"

        if (key.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "La clave del producto es obligatoria"))
            return@post
        }
        if (canal == "shopify" && orderId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El canal Shopify requiere el número de pedido (order_id)"))
            return@post
        }
        if (canal == "otro" && canalDetalle.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El canal 'Otro' requiere especificar el detalle del canal"))
            return@post
        }

        try {
            val resultJson = transaction {
                val conn = exec("SELECT tienda.registrar_venta_manual(" +
                        "'$key', $cantidad, $precioUnitario, '$canal', " +
                        "${canalDetalle?.let { "'$it'" } ?: "NULL"}, NULL, " +
                        "${orderId?.let { "'$it'" } ?: "NULL"}, '$idempotencyKey', " +
                        "'$estadoLogistico', NULL, NULL, ${nota?.let { "'$it'" } ?: "NULL"}, '$usuario')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
                conn ?: "{}"
            }

            if (resultJson.contains("SUCCESS") || resultJson.contains("ALREADY_PROCESSED")) {
                call.respond(HttpStatusCode.Created, mapOf("status" to "ok", "raw" to resultJson))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("status" to "error", "raw" to resultJson))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al registrar venta")))
        }
    }

    // 2. Actualizar Estado Logístico
    post("/api/ventas/{id}/logistica") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val ventaId = call.parameters["id"]?.toLongOrNull()
        if (ventaId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de venta inválido"))
            return@post
        }
        val params = call.receiveParameters()
        val estado = params["estadoLogistico"]?.trim() ?: "SIN_ENVIO"
        val guia = params["guiaEnvio"]?.trim()
        val transportadora = params["transportadora"]?.trim()
        val nota = params["nota"]?.trim()

        try {
            val res = transaction {
                exec("SELECT tienda.actualizar_logistica_venta($ventaId, '$estado', " +
                        "${guia?.let { "'$it'" } ?: "NULL"}, ${transportadora?.let { "'$it'" } ?: "NULL"}, " +
                        "${nota?.let { "'$it'" } ?: "NULL"}, 'dashboard')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al actualizar logística")))
        }
    }

    // 3. Asignar Reparto de Socios a Venta
    post("/api/ventas/{id}/reparto") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val ventaId = call.parameters["id"]?.toLongOrNull()
        if (ventaId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de venta inválido"))
            return@post
        }
        val bodyJson = call.receiveText()
        if (bodyJson.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El JSON de porcentajes de socios es obligatorio"))
            return@post
        }

        try {
            val res = transaction {
                exec("SELECT tienda.asignar_reparto_venta($ventaId, '$bodyJson'::jsonb)::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al asignar reparto")))
        }
    }

    // 4. Anular Venta Linea
    post("/api/ventas/{id}/anular") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val ventaId = call.parameters["id"]?.toLongOrNull()
        if (ventaId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de venta inválido"))
            return@post
        }
        val params = call.receiveParameters()
        val motivo = params["motivo"]?.trim() ?: "anulada desde dashboard"

        try {
            val res = transaction {
                exec("SELECT tienda.anular_venta_linea($ventaId, NULL, NULL, NULL, NULL, '$motivo', 'dashboard')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al anular venta")))
        }
    }

    // 5. Reintentar aviso de outbox
    post("/api/sync/{eventId}/reintentar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val eventId = call.parameters["eventId"]?.trim() ?: ""
        try {
            val res = transaction {
                exec("SELECT tienda.reintentar_outbox('$eventId')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al reintentar outbox")))
        }
    }

    // 6. Marcar acción manual como hecha en outbox
    post("/api/sync/{eventId}/manual-hecho") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val eventId = call.parameters["eventId"]?.trim() ?: ""
        try {
            val res = transaction {
                exec("SELECT tienda.resolver_accion_manual('$eventId', 'dashboard')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al resolver acción manual")))
        }
    }
}
