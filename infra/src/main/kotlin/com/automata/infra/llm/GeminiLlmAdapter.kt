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

    override suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>): ParsedCustomerIntent {
        val normalized = userMessage.lowercase().trim()

        // Si se encuentra una cifra de dinero en el mensaje, se interpreta como propuesta de precio
        val moneyRegex = Regex("""(?:\$|cop)?\s*(\d{1,3}(?:\.\d{3})+|\d+)""", RegexOption.IGNORE_CASE)
        val match = moneyRegex.find(normalized)

        val offeredMoney = if (match != null) {
            val rawNum = match.groupValues[1].replace(".", "")
            val cop = rawNum.toLongOrNull()
            if (cop != null && cop > 1000) Money.of(cop) else null
        } else null

        // Detectar si menciona un producto específico
        val matchedProductKey = availableProducts.find { key ->
            normalized.contains(key.value.lowercase())
        }

        val intentType = when {
            normalized.contains("humano") || normalized.contains("asesor") || normalized.contains("queja") ->
                CustomerIntentType.REQUEST_HUMAN_AGENT
            normalized.contains("comprobante") || normalized.contains("nequi") || normalized.contains("transferencia") ->
                CustomerIntentType.SEND_PAYMENT_PROOF
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
}
