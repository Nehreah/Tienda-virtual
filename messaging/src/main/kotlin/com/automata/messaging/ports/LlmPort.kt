package com.automata.messaging.ports

import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.serialization.Serializable

@Serializable
enum class CustomerIntentType {
    QUERY_PRODUCT_INFO,
    MAKE_OFFER,
    REQUEST_RESERVATION,
    SEND_PAYMENT_PROOF,
    REQUEST_HUMAN_AGENT,
    OTHER
}

@Serializable
data class ParsedCustomerIntent(
    val intentType: CustomerIntentType,
    val extractedProductKey: ProductKey? = null,
    val offeredPrice: Money? = null,
    val rawText: String
)

/**
 * Puerto de Integración con LLM (Gemini).
 * Regla Principio 3 (v5.3): El LLM interpreta el lenguaje natural; el dominio decide precios y stock.
 */
interface LlmPort {
    suspend fun parseIntent(userMessage: String, availableProducts: List<ProductKey>): ParsedCustomerIntent
}
