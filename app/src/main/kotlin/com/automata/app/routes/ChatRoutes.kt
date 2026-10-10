package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.infra.db.DatabaseFactory
import com.automata.messaging.ports.ChannelRouter
import com.automata.shared.PortResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Límite de conexiones SSE simultáneas para evitar fugas de recursos. */
private val activeStreams = AtomicInteger(0)
private const val MAX_STREAMS = 5

/**
 * Rutas de control humano del chat.
 * Toda consulta con datos del usuario usa parámetros tipados (sin interpolación de strings SQL).
 */
fun Route.chatRoutes(
    dashboardGuard: DashboardGuard,
    channelRouter: ChannelRouter?
) {
    // 1. Tomar Conversación (Control Humano)
    post("/api/chat/{id}/tomar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val convId = call.parameters["id"]?.trim() ?: ""
        if (convId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de conversación requerido"))
            return@post
        }
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("chat", usuario) {
                exec(
                    "SELECT tienda.tomar_conversacion(?, ?)::text AS res",
                    args = listOf(
                        TextColumnType() to convId,
                        TextColumnType() to usuario
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al tomar conversación")))
        }
    }

    // 2. Devolver Conversación al Bot
    post("/api/chat/{id}/devolver") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val convId = call.parameters["id"]?.trim() ?: ""
        if (convId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de conversación requerido"))
            return@post
        }
        val params = call.receiveParameters()
        // Validar estados permitidos de ConversationState
        val estadosPermitidos = setOf("IDLE", "NEGOTIATING", "AWAITING_PAYMENT", "SOLD", "ESCALATED_HUMAN")
        val nuevoEstadoRaw = params["nuevoEstado"]?.trim()?.uppercase()
        val nuevoEstado = if (nuevoEstadoRaw != null && nuevoEstadoRaw in estadosPermitidos) nuevoEstadoRaw else null
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        try {
            val res = DatabaseFactory.tx("chat", usuario) {
                exec(
                    "SELECT tienda.devolver_conversacion(?, ?, ?)::text AS res",
                    args = listOf(
                        TextColumnType() to convId,
                        TextColumnType() to usuario,
                        TextColumnType() to nuevoEstado
                    )
                ) { rs -> if (rs.next()) rs.getString("res") else "{}" }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al devolver conversación")))
        }
    }

    // 3. Bandeja de Conversaciones
    get("/api/chat/bandeja") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = false)) return@get
        try {
            val items = transaction {
                exec("SELECT * FROM tienda.v_bandeja ORDER BY requiere_atencion DESC, ultimo_entrante_en DESC NULLS LAST LIMIT 100") { rs ->
                    val list = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        list.add(mapOf(
                            "conversation_id" to rs.getString("conversation_id"),
                            "channel" to rs.getString("channel"),
                            "customer_id" to rs.getString("customer_id"),
                            "state" to rs.getString("state"),
                            "atencion" to rs.getString("atencion"),
                            "atendido_por" to rs.getString("atendido_por"),
                            "requiere_atencion" to rs.getBoolean("requiere_atencion"),
                            "cliente_nombre" to rs.getString("cliente_nombre"),
                            "ultimo_mensaje" to rs.getString("ultimo_mensaje"),
                            "ultimo_emisor" to rs.getString("ultimo_emisor"),
                            "ultimo_mensaje_en" to rs.getTimestamp("ultimo_mensaje_en")?.time,
                            "ultimo_entrante_en" to rs.getTimestamp("ultimo_entrante_en")?.time
                        ))
                    }
                    list
                }
            } ?: emptyList()
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "data" to items))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al cargar bandeja")))
        }
    }

    // 4. Mensajes de una Conversación (paginados)
    get("/api/chat/{id}/mensajes") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = false)) return@get
        val convId = call.parameters["id"]?.trim() ?: ""
        if (convId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de conversación requerido"))
            return@get
        }
        val beforeId = call.request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE
        try {
            val items = transaction {
                exec(
                    "SELECT * FROM tienda.conversacion_mensajes WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT 50",
                    args = listOf(
                        TextColumnType() to convId,
                        LongColumnType() to beforeId
                    )
                ) { rs ->
                    val list = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        list.add(mapOf(
                            "id" to rs.getLong("id"),
                            "emisor" to rs.getString("emisor"),
                            "contenido" to rs.getString("contenido"),
                            "estado_envio" to rs.getString("estado_envio"),
                            "error_envio" to rs.getString("error_envio"),
                            "creado_en" to rs.getTimestamp("creado_en")?.time
                        ))
                    }
                    list.reversed() // Devolver en orden cronológico
                }
            } ?: emptyList()
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "data" to items))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al cargar mensajes")))
        }
    }

    // 5. Enviar Mensaje Humano
    post("/api/chat/{id}/enviar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val convId = call.parameters["id"]?.trim() ?: ""
        if (convId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de conversación requerido"))
            return@post
        }
        val params = call.receiveParameters()
        val texto = params["texto"]?.trim() ?: ""
        if (texto.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El texto del mensaje no puede estar vacío"))
            return@post
        }
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        // Verificar atención humana y ventana de 24h usando parámetros
        data class ConvInfo(val atencion: String, val channel: String, val customerId: String, val ultimoEntrante: java.sql.Timestamp?)
        val convInfo = transaction {
            exec(
                "SELECT atencion, channel, customer_id, ultimo_entrante_en FROM tienda.conversaciones WHERE conversation_id = ?",
                args = listOf(TextColumnType() to convId)
            ) { rs ->
                if (rs.next()) ConvInfo(
                    rs.getString("atencion"),
                    rs.getString("channel"),
                    rs.getString("customer_id"),
                    rs.getTimestamp("ultimo_entrante_en")
                ) else null
            }
        }

        if (convInfo == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Conversación no encontrada"))
            return@post
        }

        if (convInfo.atencion != "HUMANO") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Debe tomar el control de la conversación antes de enviar mensajes"))
            return@post
        }

        if (convInfo.ultimoEntrante != null) {
            val diffHours = (Instant.now().toEpochMilli() - convInfo.ultimoEntrante.time) / 3600_000L
            if (diffHours >= 24) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf(
                    "error" to "Ha expirado la ventana de 24 horas desde el último mensaje del cliente",
                    "horas_desde_ultimo" to diffHours
                ))
                return@post
            }
        }

        // Registrar mensaje humano en estado ENVIANDO (con parámetros)
        val msgId = DatabaseFactory.tx("chat", usuario) {
            exec(
                "SELECT tienda.registrar_mensaje_humano(?, ?, ?) AS msg_id",
                args = listOf(
                    TextColumnType() to convId,
                    TextColumnType() to texto,
                    TextColumnType() to usuario
                )
            ) { rs -> if (rs.next()) rs.getLong("msg_id").takeIf { !rs.wasNull() } else null }
        }

        if (msgId == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo registrar el mensaje en la base de datos"))
            return@post
        }

        if (channelRouter == null || !channelRouter.supports(convInfo.channel)) {
            transaction {
                exec(
                    "SELECT tienda.marcar_envio(?, 'FALLO', 'Canal no configurado', NULL)",
                    args = listOf(LongColumnType() to msgId)
                ) { rs -> }
            }
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "El canal '${convInfo.channel}' no está configurado para envíos"))
            return@post
        }

        val result = channelRouter.sendMessage(convInfo.channel, convInfo.customerId, texto)
        when (result) {
            is PortResult.Ok -> {
                val extId = result.value
                transaction {
                    exec(
                        "SELECT tienda.marcar_envio(?, 'ENVIADO', NULL, ?)",
                        args = listOf(LongColumnType() to msgId, TextColumnType() to extId)
                    ) { rs -> }
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "enviado", "mensaje_id" to msgId, "external_id" to extId))
            }
            is PortResult.Failure -> {
                val errorMsg = when (result) {
                    is PortResult.Permanent  -> "${result.code}: ${result.detail}"
                    is PortResult.Transient  -> result.cause
                    is PortResult.RateLimited -> "Rate limited por ${result.retryAfterSeconds}s"
                    PortResult.AuthExpired   -> "Token de acceso expirado"
                }
                transaction {
                    exec(
                        "SELECT tienda.marcar_envio(?, 'FALLO', ?, NULL)",
                        args = listOf(LongColumnType() to msgId, TextColumnType() to errorMsg)
                    ) { rs -> }
                }
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Error al enviar: $errorMsg"))
            }
        }
    }

    // 6. Reintentar mensaje en FALLO
    post("/api/chat/mensajes/{msgId}/reintentar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val msgId = call.parameters["msgId"]?.toLongOrNull()
        if (msgId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ID de mensaje inválido"))
            return@post
        }
        val usuario = call.request.headers["X-Dashboard-User"] ?: "dashboard"

        // Obtener datos del mensaje original
        data class MsgInfo(val convId: String, val contenido: String, val canal: String, val customerId: String)
        val msgInfo = transaction {
            exec(
                """SELECT m.conversation_id, m.contenido, c.channel, c.customer_id
                   FROM tienda.conversacion_mensajes m
                   JOIN tienda.conversaciones c ON c.conversation_id = m.conversation_id
                   WHERE m.id = ? AND m.estado_envio = 'FALLO'""",
                args = listOf(LongColumnType() to msgId)
            ) { rs ->
                if (rs.next()) MsgInfo(
                    rs.getString("conversation_id"),
                    rs.getString("contenido"),
                    rs.getString("channel"),
                    rs.getString("customer_id")
                ) else null
            }
        }

        if (msgInfo == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Mensaje no encontrado o no está en estado FALLO"))
            return@post
        }

        if (channelRouter == null || !channelRouter.supports(msgInfo.canal)) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Canal '${msgInfo.canal}' no configurado"))
            return@post
        }

        // Marcar como enviando antes de intentar
        transaction {
            exec(
                "SELECT tienda.marcar_envio(?, 'ENVIANDO', NULL, NULL)",
                args = listOf(LongColumnType() to msgId)
            ) { rs -> }
        }

        val result = channelRouter.sendMessage(msgInfo.canal, msgInfo.customerId, msgInfo.contenido)
        when (result) {
            is PortResult.Ok -> {
                transaction {
                    exec(
                        "SELECT tienda.marcar_envio(?, 'ENVIADO', NULL, ?)",
                        args = listOf(LongColumnType() to msgId, TextColumnType() to result.value)
                    ) { rs -> }
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "enviado"))
            }
            is PortResult.Failure -> {
                val errorMsg = when (result) {
                    is PortResult.Permanent  -> "${result.code}: ${result.detail}"
                    is PortResult.Transient  -> result.cause
                    is PortResult.RateLimited -> "Rate limited por ${result.retryAfterSeconds}s"
                    PortResult.AuthExpired   -> "Token expirado"
                }
                transaction {
                    exec(
                        "SELECT tienda.marcar_envio(?, 'FALLO', ?, NULL)",
                        args = listOf(LongColumnType() to msgId, TextColumnType() to errorMsg)
                    ) { rs -> }
                }
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errorMsg))
            }
        }
    }

    // 7. Stream SSE — Tiempo Real del Chat
    get("/api/chat/stream") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = false)) return@get

        if (activeStreams.get() >= MAX_STREAMS) {
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Límite de conexiones simultáneas alcanzado"))
            return@get
        }

        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.headers.append("X-Accel-Buffering", "no")

        // Soporte de reconexión con Last-Event-ID
        var lastSeenId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L

        // Detectar si el cursor está demasiado viejo (purge de 7 días)
        val oldestId: Long = try {
            transaction {
                exec("SELECT COALESCE(MIN(id), 0) FROM tienda.chat_eventos") { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            } ?: 0L
        } catch (_: Exception) { 0L }

        activeStreams.incrementAndGet()
        try {
            call.respondTextWriter(ContentType.Text.EventStream) {
                // Si el cursor es más viejo que el mínimo disponible, enviar reset
                if (lastSeenId > 0 && lastSeenId < oldestId) {
                    write("event: reset\ndata: {\"message\":\"Cursor expirado, recargando bandeja\"}\n\n")
                    flush()
                    lastSeenId = 0L
                }

                var heartbeatCounter = 0
                while (isActive) {
                    val events = try {
                        transaction {
                            exec(
                                "SELECT id, conversation_id, tipo FROM tienda.chat_eventos WHERE id > ? ORDER BY id ASC LIMIT 50",
                                args = listOf(LongColumnType() to lastSeenId)
                            ) { rs ->
                                val list = mutableListOf<Triple<Long, String, String>>()
                                while (rs.next()) {
                                    list.add(Triple(rs.getLong("id"), rs.getString("conversation_id"), rs.getString("tipo")))
                                }
                                list
                            }
                        } ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (events.isNotEmpty()) {
                        for (evt in events) {
                            lastSeenId = evt.first
                            write("id: ${evt.first}\n")
                            write("event: ${evt.third}\n")
                            write("data: {\"convId\":\"${evt.second.replace("\"", "\\\"")}\",\"tipo\":\"${evt.third}\"}\n\n")
                        }
                        flush()
                        heartbeatCounter = 0
                    } else {
                        heartbeatCounter++
                        // Latido cada ~15 s (7 ciclos × 2 s ≈ 14 s)
                        if (heartbeatCounter >= 7) {
                            write("event: ping\ndata: {\"ts\":${System.currentTimeMillis()}}\n\n")
                            flush()
                            heartbeatCounter = 0
                        }
                    }

                    delay(2000L)
                }
            }
        } finally {
            activeStreams.decrementAndGet()
        }
    }
}
