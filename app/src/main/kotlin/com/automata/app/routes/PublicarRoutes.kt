package com.automata.app.routes

import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.app.workers.ChannelPublisher
import com.automata.app.workers.PublicarResultado
import com.automata.infra.db.PublicacionesTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * v14 · Publicación en canales (Fase 5 del plan v6).
 *   POST /api/articulos/{key}/publicar?canal=shopify|meta_catalogo   publica (o hace ENSAYO si CHANNEL_WRITE_ENABLED=false)
 *   GET  /api/articulos/{key}/publicaciones                          en qué canales está y con qué IDs / último stock enviado
 */
fun Route.publicarRoutes(
    dashboardGuard: DashboardGuard,
    publisher: ChannelPublisher?
) {
    post("/api/articulos/{key}/publicar") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        if (publisher == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@post
        }
        val key = call.keyDeRuta() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key de producto requerida"))
            return@post
        }
        val canal = call.request.queryParameters["canal"].orEmpty()
        when (val r = publisher.publicar(key, canal)) {
            is PublicarResultado.Rechazado ->
                call.respondText(
                    buildJsonObject { put("error", r.mensaje) }.toString(),
                    ContentType.Application.Json, HttpStatusCode.fromValue(r.status)
                )
            is PublicarResultado.Ensayo -> call.respondJson(buildJsonObject {
                put("modo", "ENSAYO")
                put("mensaje", "CHANNEL_WRITE_ENABLED=false: no se escribió nada en ${r.canal}. Esto es lo que se enviaría.")
                put("canal", r.canal)
                put("stock", r.stock)
                put("datos", buildJsonObject { r.datos.forEach { (k, v) -> put(k, v) } })
                put("avisos", buildJsonArray { r.avisos.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
            is PublicarResultado.Publicado -> call.respondJson(buildJsonObject {
                put("modo", "PUBLICADO")
                put("canal", r.canal)
                put("externalId", r.externalId)
                r.externalInventoryId?.let { put("externalInventoryId", it) }
                put("stock", r.stock)
                put("stockSincronizado", r.stockSincronizado)
                r.aviso?.let { put("aviso", it) }
            })
        }
    }

    get("/api/articulos/{key}/publicaciones") {
        if (!call.requireDashboardAuth(dashboardGuard)) return@get
        if (publisher == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Base de datos no configurada"))
            return@get
        }
        val key = call.keyDeRuta() ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Key de producto requerida"))
            return@get
        }
        val filas = transaction {
            PublicacionesTable.select { PublicacionesTable.sku eq key.value }.map { row ->
                buildJsonObject {
                    put("canal", row[PublicacionesTable.canal])
                    put("externalId", row[PublicacionesTable.externalId])
                    row[PublicacionesTable.externalInventoryId]?.let { put("externalInventoryId", it) }
                    put("publicado", row[PublicacionesTable.publicado])
                    put("stockPublicado", row[PublicacionesTable.stockPublicado])
                    put("ultimaSincronizacion", row[PublicacionesTable.ultimaSincronizacion].toString())
                }
            }
        }
        call.respondJson(buildJsonObject {
            put("key", key.value)
            put("publicaciones", buildJsonArray { filas.forEach { add(it) } })
        })
    }
}

private suspend fun ApplicationCall.respondJson(body: JsonObject) =
    respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
