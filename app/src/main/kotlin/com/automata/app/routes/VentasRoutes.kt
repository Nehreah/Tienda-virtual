package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.infra.db.DatabaseFactory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.transactions.transaction

/** Canales de venta permitidos (lista cerrada para evitar inyección). */
private val CANALES_VALIDOS = setOf("presencial", "whatsapp", "instagram", "messenger", "shopify", "otro")

/** Estados logísticos permitidos (lista cerrada). */
private val ESTADOS_LOGISTICOS_VALIDOS = setOf(
    "SIN_ENVIO", "PREPARANDO", "EN_CAMINO", "ENTREGADO", "DEVUELTO", "CANCELADO"
)

/**
 * Extrae el valor del campo "status" del JSON devuelto por las funciones SQL.
 * Usa regex simple para no depender de kotlinx.serialization en este módulo.
 */
private fun parseResultStatus(json: String): String {
    val match = """"status"\s*:\s*"([^"]+)"""".toRegex().find(json)
    return match?.groupValues?.getOrNull(1) ?: if (json.contains("SUCCESS")) "SUCCESS" else "ERROR"
}

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
        val canalRaw = params["canal"]?.trim()?.lowercase() ?: "presencial"
        val canal = if (canalRaw in CANALES_VALIDOS) canalRaw else "presencial"
        val canalDetalle = params["canalDetalle"]?.trim()
        val orderId = params["orderId"]?.trim()
        val idempotencyKey = params["idempotencyKey"]?.trim() ?: "MAN-${java.util.UUID.randomUUID()}"
        val estadoLogisticoRaw = params["estadoLogistico"]?.trim()?.uppercase() ?: "SIN_ENVIO"
        val estadoLogistico = if (estadoLogisticoRaw in ESTADOS_LOGISTICOS_VALIDOS) estadoLogisticoRaw else "SIN_ENVIO"
        val nota = params["nota"]?.trim()
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

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
            val resultJson = DatabaseFactory.tx("ventas", usuario) {
                exec(
                    "SELECT tienda.registrar_venta_manual(?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL, NULL, ?, ?)::text AS res",
                    args = listOf(
                        TextColumnType() to key,
                        org.jetbrains.exposed.sql.IntegerColumnType() to cantidad,
                        LongColumnType() to precioUnitario,
                        TextColumnType() to canal,
                        TextColumnType() to canalDetalle,
                        TextColumnType() to orderId,
                        TextColumnType() to idempotencyKey,
                        TextColumnType() to estadoLogistico,
                        TextColumnType() to nota,
                        TextColumnType() to usuario
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            } ?: "{}"

            val status = parseResultStatus(resultJson)
            when (status) {
                "SUCCESS"           -> call.respond(HttpStatusCode.Created, mapOf("status" to "ok", "resultado" to status))
                "ALREADY_PROCESSED" -> call.respond(HttpStatusCode.OK, mapOf("status" to "ya_procesado", "resultado" to status))
                "SIN_STOCK"         -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sin stock disponible para el producto"))
                "FECHA_INVALIDA"    -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Fecha de venta inválida"))
                "PRECIO_INVALIDO"   -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El precio no puede ser menor al precio mínimo"))
                else                -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Error al registrar venta: $status", "raw" to resultJson))
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
        val estadoRaw = params["estadoLogistico"]?.trim()?.uppercase() ?: "SIN_ENVIO"
        val estado = if (estadoRaw in ESTADOS_LOGISTICOS_VALIDOS) estadoRaw else {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Estado logístico inválido: $estadoRaw"))
            return@post
        }
        val guia = params["guiaEnvio"]?.trim()
        val transportadora = params["transportadora"]?.trim()
        val nota = params["nota"]?.trim()
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("ventas", usuario) {
                exec(
                    "SELECT tienda.actualizar_logistica_venta(?, ?, ?, ?, ?, ?)::text AS res",
                    args = listOf(
                        LongColumnType() to ventaId,
                        TextColumnType() to estado,
                        TextColumnType() to guia,
                        TextColumnType() to transportadora,
                        TextColumnType() to nota,
                        TextColumnType() to usuario
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            val status = parseResultStatus(res ?: "{}")
            if (status == "SUCCESS") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Error al actualizar logística: $status"))
            }
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
        // Validar estructura básica de JSON antes de enviarlo a la BD
        val jsonTrimmed = bodyJson.trim()
        if (!jsonTrimmed.startsWith("{") || !jsonTrimmed.endsWith("}")) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "JSON de reparto inválido: debe ser un objeto {\"socioId\": porcentaje}"))
            return@post
        }
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("ventas", usuario) {
                exec(
                    "SELECT tienda.asignar_reparto_venta(?, ?::jsonb)::text AS res",
                    args = listOf(
                        LongColumnType() to ventaId,
                        TextColumnType() to bodyJson
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            val status = parseResultStatus(res ?: "{}")
            if (status == "SUCCESS") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Error al asignar reparto: $status"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al asignar reparto")))
        }
    }

    // 4. Anular Venta
    post("/api/ventas/{id}/anular") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val ventaId = call.parameters["id"]?.toLongOrNull()
        if (ventaId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de venta inválido"))
            return@post
        }
        val params = call.receiveParameters()
        val motivo = params["motivo"]?.trim()?.takeIf { it.isNotBlank() } ?: "Anulada desde dashboard"
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("ventas", usuario) {
                exec(
                    "SELECT tienda.anular_venta_linea(?, NULL, NULL, NULL, NULL, ?, ?)::text AS res",
                    args = listOf(
                        LongColumnType() to ventaId,
                        TextColumnType() to motivo,
                        TextColumnType() to usuario
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            val status = parseResultStatus(res ?: "{}")
            if (status == "SUCCESS") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            } else {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Error al anular venta: $status"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al anular venta")))
        }
    }

    // 5. Reintentar aviso de outbox
    post("/api/sync/{eventId}/reintentar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val eventId = call.parameters["eventId"]?.trim() ?: ""
        if (eventId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de evento requerido"))
            return@post
        }
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("sync", usuario) {
                exec(
                    "SELECT tienda.reintentar_outbox(?)::text AS res",
                    args = listOf(TextColumnType() to eventId)
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            val status = parseResultStatus(res ?: "{}")
            call.respond(HttpStatusCode.OK, mapOf("status" to status.lowercase()))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al reintentar outbox")))
        }
    }

    // 6. Marcar acción manual como hecha
    post("/api/sync/{eventId}/manual-hecho") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val eventId = call.parameters["eventId"]?.trim() ?: ""
        if (eventId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de evento requerido"))
            return@post
        }
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("sync", usuario) {
                exec(
                    "SELECT tienda.resolver_accion_manual(?, ?)::text AS res",
                    args = listOf(
                        TextColumnType() to eventId,
                        TextColumnType() to usuario
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            val status = parseResultStatus(res ?: "{}")
            call.respond(HttpStatusCode.OK, mapOf("status" to status.lowercase()))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al resolver acción manual")))
        }
    }
}
