package com.automata.infra.llm

import com.automata.messaging.ports.CustomerIntentType
import com.automata.messaging.ports.LlmPort
import com.automata.messaging.ports.ParsedCustomerIntent
import com.automata.shared.Money
import com.automata.shared.ProductKey

/**
 * Adaptador de IA Gemini para parsear la intención del cliente sin tomar decisiones financieras.
 * Aplica el Principio 3 (v5.3): El LLM interpreta la intención; el código en Kotlin decide precios y stock.
 */
class GeminiLlmAdapter(
    private val apiKey: String = ""
) : LlmPort {

    private val moneyRegex = Regex("""(?:\$|cop)?\s*(\d{1,3}(?:[.,]\d{3})+|\d+)""", RegexOption.IGNORE_CASE)
    private val thousandsSuffixRegex = Regex("""(\d{1,3})\s*(?:mil|k)\b""")
    private val wordSplitRegex = Regex("""[^\p{L}]+""")

    /** Palabras que, SOLAS, forman un "sí" a una contraoferta. Cualquier otra palabra invalida la aceptación. */
    private val acceptCore = setOf(
        "acepto", "aceptado", "ok", "okay", "okey", "vale", "dale", "listo", "trato", "hecho", "perfecto",
        "bueno", "claro", "si", "sí", "una", "sirve", "llevo", "tomo", "quiero", "compro", "bien", "va"
    )
    private val acceptFiller = setOf(
        "de", "esta", "está", "me", "lo", "entonces", "ya", "gracias", "por", "favor", "señor", "señora", "muy"
    )

    override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>): ParsedCustomerIntent {
        val normalized = userMessage.lowercase().trim()

        // Se quitan los códigos de producto antes de buscar la cifra: "PROD-001" no es un precio,
        // y find() solo miraba la PRIMERA cifra del mensaje (el código), por lo que nunca detectaba la oferta.
        var withoutKeys = normalized
        availableProducts.forEach { withoutKeys = withoutKeys.replace(it.value.lowercase(), " ") }
        // "110 mil" / "110k" -> 110000 (forma muy común de ofertar en Colombia).
        withoutKeys = withoutKeys.replace(thousandsSuffixRegex) { m -> (m.groupValues[1].toLong() * 1000).toString() }

        // Primera cifra > 1000 (acepta "110000", "110.000" y "110,000"); si hay una, se interpreta como propuesta de precio.
        val offeredMoney = moneyRegex.findAll(withoutKeys)
            .mapNotNull { it.groupValues[1].replace(".", "").replace(",", "").toLongOrNull() }
            .firstOrNull { it > 1000 }
            ?.let { Money.of(it) }

        // Detectar si menciona un producto específico
        val matchedProductKey = availableProducts.find { key ->
            normalized.contains(key.value.lowercase())
        }

        val intentType = when {
            normalized.contains("humano") || normalized.contains("asesor") || normalized.contains("queja") ->
                CustomerIntentType.REQUEST_HUMAN_AGENT
            normalized.contains("comprobante") || normalized.contains("nequi") || normalized.contains("transferencia") ->
                CustomerIntentType.SEND_PAYMENT_PROOF
            looksLikeAcceptance(normalized) -> CustomerIntentType.ACCEPT_COUNTER_OFFER
            offeredMoney != null || normalized.contains("descuento") || normalized.contains("cuanto me lo deja") || normalized.contains("ofrezco") ->
                CustomerIntentType.MAKE_OFFER
            normalized.contains("reserva") || normalized.contains("apárteme") || normalized.contains("guardeme") ->
                CustomerIntentType.REQUEST_RESERVATION
            else -> CustomerIntentType.QUERY_PRODUCT_INFO
        }

        return ParsedCustomerIntent(
            intentType = intentType,
            extractedProductKey = matchedProductKey,
            offeredPrice = offeredMoney,
            rawText = userMessage
        )
    }

    /**
     * Mensaje corto, sin cifras ni pregunta, formado SOLO por palabras de aceptación ("ok dale", "sí, lo tomo", "listo gracias").
     * "si me lo envían a Bogotá" no cuenta (trae palabras ajenas). El pipeline solo lo toma en cuenta si había una
     * contraoferta pendiente; si no, se trata como una consulta cualquiera.
     */
    private fun looksLikeAcceptance(normalized: String): Boolean {
        if (normalized.any { it.isDigit() } || normalized.contains('?')) return false
        val words = normalized.split(wordSplitRegex).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 5) return false
        if (!words.all { it in acceptCore || it in acceptFiller }) return false
        return words.any { it in acceptCore }
    }
}
