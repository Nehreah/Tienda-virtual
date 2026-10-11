package com.automata.app.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.sql.SQLException

/** Responde un fragmento HTML (para htmx). */
suspend fun ApplicationCall.respondFragmento(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(html, ContentType.Text.Html, status)
}

/**
 * Mensaje que se le puede mostrar a la persona: si la excepción viene de PostgreSQL (RAISE EXCEPTION de un trigger o
 * función) se usa solo la primera línea del mensaje del servidor, sin "ERROR:" ni la traza de la consulta;
 * en cualquier otro caso, el mensaje de la propia excepción.
 */
fun Throwable.mensajeLegible(): String {
    val sql = generateSequence<Throwable>(this) { it.cause?.takeIf { c -> c !== it } }
        .filterIsInstance<SQLException>()
        .firstOrNull()
    val bruto = (sql?.message ?: this.message).orEmpty()
    val primera = bruto.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return primera.removePrefix("ERROR:").trim().ifBlank { "Ocurrió un error inesperado." }
}

/**
 * Pesos colombianos escritos por una persona: "120000", "$120.000" o "1.200.000". Sin decimales ni negativos.
 * Devuelve null si el texto no es un monto válido.
 */
fun parseCop(raw: String?): Long? {
    val texto = raw?.trim()?.removePrefix("$")?.trim().orEmpty()
    if (texto.isEmpty()) return null
    val conMiles = Regex("^\\d{1,3}(\\.\\d{3})+$")
    val limpio = if (conMiles.matches(texto)) texto.replace(".", "") else texto
    return if (limpio.all { it in '0'..'9' }) limpio.toLongOrNull() else null
}
