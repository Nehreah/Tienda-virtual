package com.automata.app.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
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

/** Tamaño máximo de un cuerpo de webhook (Meta / Shopify). */
const val MAX_WEBHOOK_BYTES = 1_048_576

/** Tamaño máximo de un archivo .xlsx subido al tablero. */
const val MAX_EXCEL_BYTES = 5_242_880

/**
 * Lee un flujo SIN pasar de [max] bytes: se detiene en cuanto lo supera, en vez de cargarlo entero en memoria
 * y comprobar el tamaño después. Devuelve null si el contenido supera el límite.
 */
fun java.io.InputStream.readBytesLimited(max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * Lee el cuerpo de la petición con tope de [max] bytes. Rechaza de inmediato si Content-Length ya lo supera y,
 * para cuerpos chunked (sin Content-Length), corta la lectura al pasar el límite. Devuelve null si excede.
 */
suspend fun ApplicationCall.receiveBytesLimited(max: Int): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > max) return null
    val channel = receiveChannel()
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val n = channel.readAvailable(buffer, 0, buffer.size)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
