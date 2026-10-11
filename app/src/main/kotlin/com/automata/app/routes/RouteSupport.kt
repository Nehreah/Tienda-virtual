package com.automata.app.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.sql.SQLException
import java.util.UUID

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

/**
 * Responde un error capturado en una ruta SIN filtrar detalles internos y sin perder la causa real.
 *
 * - Cancelación de la corrutina: se vuelve a lanzar (nunca se responde, es el cliente que cerró la conexión).
 * - Dato inválido ([IllegalArgumentException]): 400 con el mensaje, que escribió el propio código y es para la persona.
 * - Regla de negocio ([IllegalStateException]) o regla de la base (RAISE EXCEPTION de un trigger, SQLState P0001, o una
 *   restricción de integridad, SQLState 23xxx): 409 con la primera línea del mensaje.
 * - Cualquier otra cosa (caída de la base, error de programación): 500 con un mensaje genérico y un código corto.
 *   El detalle completo con su traza queda en el registro bajo ese mismo código, para encontrarlo sin exponerlo al cliente.
 *
 * [contexto] completa la frase "No se pudo ...": por ejemplo "registrar la venta".
 */
suspend fun ApplicationCall.respondError(logger: Logger, contexto: String, e: Throwable) {
    if (e is CancellationException) throw e

    val sql = generateSequence<Throwable>(e) { it.cause?.takeIf { c -> c !== it } }
        .filterIsInstance<SQLException>()
        .firstOrNull()
    val reglaDeLaBase = sql != null && (sql.sqlState == "P0001" || sql.sqlState?.startsWith("23") == true)

    when {
        e is IllegalArgumentException -> {
            logger.warn("No se pudo $contexto (dato inválido): ${e.message}")
            respond(HttpStatusCode.BadRequest, mapOf("error" to e.mensajeLegible()))
        }
        e is IllegalStateException || reglaDeLaBase -> {
            logger.warn("No se pudo $contexto (regla de negocio): ${e.mensajeLegible()}")
            respond(HttpStatusCode.Conflict, mapOf("error" to e.mensajeLegible()))
        }
        else -> {
            val codigo = UUID.randomUUID().toString().take(8)
            logger.error("No se pudo $contexto (código $codigo): ${e.message}", e)
            respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "No se pudo $contexto. Código de error: $codigo (consúltalo en los registros del servidor).")
            )
        }
    }
}
