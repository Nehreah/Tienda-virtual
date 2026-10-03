package com.automata.adapters.shopify

import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.ProductSoldEvent
import kotlinx.serialization.json.*

/**
 * Procesador de Webhooks entrantes de Shopify (`orders/create`, `inventory/update`).
 */
class ShopifyWebhookHandler(
    private val webhookSecret: String
) {
    data class WebhookProcessResult(
        val success: Boolean,
        val eventType: String,
        val emittedEvent: ProductSoldEvent? = null,
        val message: String
    )

    fun handleWebhook(
        topicHeader: String?,
        hmacHeader: String?,
        rawBodyBytes: ByteArray
    ): WebhookProcessResult {
        if (!ShopifyHmacVerifier.verifyHmac(rawBodyBytes, hmacHeader, webhookSecret)) {
            return WebhookProcessResult(
                success = false,
                eventType = topicHeader ?: "unknown",
                message = "Firma HMAC inválida en Webhook de Shopify"
            )
        }

        val bodyString = rawBodyBytes.toString(Charsets.UTF_8)
        val json = try {
            Json.parseToJsonElement(bodyString).jsonObject
        } catch (e: Exception) {
            return WebhookProcessResult(false, topicHeader ?: "unknown", message = "JSON malformado")
        }

        return when (topicHeader) {
            "orders/create" -> processOrderCreated(json)
            "inventory/update" -> processInventoryUpdated(json)
            else -> WebhookProcessResult(true, topicHeader ?: "unknown", message = "Topic no suscrito ignorado")
        }
    }

    private fun processOrderCreated(json: JsonObject): WebhookProcessResult {
        val orderId = json["id"]?.jsonPrimitive?.content ?: "UNKNOWN_ORDER"
        val lineItems = json["line_items"]?.jsonArray

        if (lineItems.isNullOrEmpty()) {
            return WebhookProcessResult(true, "orders/create", message = "Orden sin ítems")
        }

        val firstItem = lineItems.first().jsonObject
        val sku = firstItem["sku"]?.jsonPrimitive?.content ?: "UNKNOWN_SKU"
        val qty = firstItem["quantity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val priceStr = firstItem["price"]?.jsonPrimitive?.content ?: "0"
        val priceCop = (priceStr.toDoubleOrNull() ?: 0.0).toLong()

        val soldEvent = ProductSoldEvent(
            eventId = "shopify_order_$orderId",
            orderId = orderId,
            channel = "shopify",
            productKey = ProductKey(sku),
            quantity = qty,
            soldPrice = Money.of(priceCop)
        )

        return WebhookProcessResult(
            success = true,
            eventType = "orders/create",
            emittedEvent = soldEvent,
            message = "Orden de Shopify procesada para $sku x$qty"
        )
    }

    private fun processInventoryUpdated(json: JsonObject): WebhookProcessResult {
        val inventoryItemId = json["inventory_item_id"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val available = json["available"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return WebhookProcessResult(
            success = true,
            eventType = "inventory/update",
            message = "Inventario de Shopify actualizado externamente a $available para ítem $inventoryItemId"
        )
    }
}
