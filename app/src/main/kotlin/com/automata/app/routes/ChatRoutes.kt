package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.messaging.ports.ChannelRouter
import com.automata.shared.PortResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

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
        val usuario = "dashboard"

        try {
            val res = transaction {
                exec("SELECT tienda.tomar_conversacion('$convId', '$usuario')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
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
        val nuevoEstado = params["nuevoEstado"]?.trim() ?: "IDLE"
        val usuario = "dashboard"

        try {
            val res = transaction {
                exec("SELECT tienda.devolver_conversacion('$convId', '$usuario', '$nuevoEstado')::text AS res") { rs ->
                    if (rs.next()) rs.getString("res") else "{}"
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok", "result" to res))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error al devolver conversación")))
        }
    }

    // 2.1 Obtener Bandeja
    get("/api/chat/bandeja") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = false)) return@get
        try {
            val items = transaction {
                exec("SELECT * FROM tienda.v_bandeja ORDER BY ultimo_entrante_en DESC NULLS LAST LIMIT 100") { rs ->
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

    // 2.2 Obtener Mensajes de una conversación
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
                exec("SELECT * FROM tienda.conversacion_mensajes WHERE conversation_id = '$convId' AND id < $beforeId ORDER BY id DESC LIMIT 50") { rs ->
                    val list = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        list.add(mapOf(
                            "id" to rs.getLong("id"),
                            "emisor" to rs.getString("emisor"),
                            "contenido" to rs.getString("contenido"),
                            "estado_envio" to rs.getString("estado_envio"),
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

    // 3. Enviar Mensaje Humano
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

        val usuario = "dashboard"

        // Verificar atención humana y ventana de 24h
        val (atencion, channel, customerId, ultimoEntrante) = transaction {
            exec("SELECT atencion, channel, customer_id, ultimo_entrante_en FROM tienda.conversaciones WHERE conversation_id = '$convId'") { rs ->
                if (rs.next()) {
                    Tuple4(
                        rs.getString("atencion"),
                        rs.getString("channel"),
                        rs.getString("customer_id"),
                        rs.getTimestamp("ultimo_entrante_en")
                    )
                } else Tuple4("BOT", "", "", null)
            } ?: Tuple4("BOT", "", "", null)
        }

        if (atencion != "HUMANO") {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Debe tomar el control de la conversación antes de enviar mensajes humanos"))
            return@post
        }

        if (ultimoEntrante != null) {
            val diffHours = (Instant.now().toEpochMilli() - ultimoEntrante.time) / 3600_000L
            if (diffHours >= 24) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "Ha expirado la ventana de 24 horas desde el último mensaje del cliente"))
                return@post
            }
        }

        // Registrar mensaje humano en estado ENVIANDO
        val msgId = transaction {
            exec("SELECT tienda.registrar_mensaje_humano('$convId', '$texto', '$usuario') AS msg_id") { rs ->
                if (rs.next()) rs.getLong("msg_id") else null
            }
        }

        if (msgId == null) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudo registrar el mensaje humano en la base de datos"))
            return@post
        }

        if (channelRouter == null || !channelRouter.supports(channel)) {
            transaction {
                exec("SELECT tienda.marcar_envio($msgId, 'FALLO', 'Canal no configurado o soportado', NULL)")
            }
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "El canal '$channel' no está configurado para envíos"))
            return@post
        }

        val result = channelRouter.sendMessage(channel, customerId, texto)
        when (result) {
            is PortResult.Ok -> {
                val extId = result.value
                transaction {
                    exec("SELECT tienda.marcar_envio($msgId, 'ENVIADO', NULL, '$extId')")
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "enviado", "mensaje_id" to msgId, "external_id" to extId))
            }
            is PortResult.Failure -> {
                val errorMsg = when (result) {
                    is PortResult.Permanent -> "${result.code}: ${result.detail}"
                    is PortResult.Transient -> result.cause
                    is PortResult.RateLimited -> "Rate limited for ${result.retryAfterSeconds}s"
                    PortResult.AuthExpired -> "Auth expired"
                }
                transaction {
                    exec("SELECT tienda.marcar_envio($msgId, 'FALLO', '$errorMsg', NULL)")
                }
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Error al enviar mensaje por canal: $errorMsg"))
            }
        }
    }

    // 4. Stream SSE para Tiempo Real del Chat
    get("/api/chat/stream") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = false)) return@get
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.headers.append("X-Accel-Buffering", "no")

        var lastSeenId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L

        call.respondTextWriter(ContentType.Text.EventStream) {
            while (true) {
                val events = try {
                    transaction {
                        exec("SELECT id, conversation_id, tipo FROM tienda.chat_eventos WHERE id > $lastSeenId ORDER BY id ASC LIMIT 50") { rs ->
                            val list = mutableListOf<Triple<Long, String, String>>()
                            while (rs.next()) {
                                list.add(Triple(rs.getLong("id"), rs.getString("conversation_id"), rs.getString("tipo")))
                            }
                            list
                        }
                    } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }

                if (events.isNotEmpty()) {
                    for (evt in events) {
                        lastSeenId = evt.first
                        write("id: ${evt.first}\n")
                        write("event: ${evt.third}\n")
                        write("data: {\"convId\":\"${evt.second}\",\"tipo\":\"${evt.third}\"}\n\n")
                        flush()
                    }
                } else {
                    write("data: ping\n\n")
                    flush()
                }

                delay(2000L)
            }
        }
    }
}

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
