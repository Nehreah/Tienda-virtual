package com.automata.adapters.shopify

import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.ProductSoldEvent
import kotlinx.serialization.json.*

/**
 * Procesador de Webhooks entrantes de Shopify (orders/create, orders/cancelled, inventory/update).
 * Fase 2.5: procesa TODOS los line_items de un pedido, no solo el primero.
 * Los eventos de cancelacion llevan quantity negativo (devolver stock).
 */
class ShopifyWebhookHandler(
    private val webhookSecret: String
) {
    data class WebhookProcessResult(
        val success: Boolean,
        val eventType: String,
        val emittedEvents: List<ProductSoldEvent> = emptyList(),
        val message: String
    ) {
        val emittedEvent: ProductSoldEvent? get() = emittedEvents.firstOrNull()
    }

    fun handleWebhook(
        topicHeader: String?,
        hmacHeader: String?,
        rawBodyBytes: ByteArray
    ): WebhookProcessResult {
        if (!ShopifyHmacVerifier.verifyHmac(rawBodyBytes, hmacHeader, webhookSecret)) {
            return WebhookProcessResult(
                success = false,
                eventType = topicHeader ?: "unknown",
                message = "Firma HMAC invalida en Webhook de Shopify"
            )
        }

        val bodyString = rawBodyBytes.toString(Charsets.UTF_8)
        val json = try {
            Json.parseToJsonElement(bodyString).jsonObject
        } catch (e: Exception) {
            return WebhookProcessResult(false, topicHeader ?: "unknown", message = "JSON malformado")
        }

        return when (topicHeader) {
            "orders/create"    -> processOrderCreated(json)
            "orders/cancelled" -> processOrderCancelled(json)
            "inventory/update" -> processInventoryUpdated(json)
            else -> WebhookProcessResult(true, topicHeader ?: "unknown", message = "Topic no suscrito ignorado")
        }
    }

    private fun processOrderCreated(json: JsonObject): WebhookProcessResult {
        val orderId = json["id"]?.jsonPrimitive?.content ?: "UNKNOWN_ORDER"
        val lineItems = json["line_items"]?.jsonArray

        if (lineItems.isNullOrEmpty()) {
            return WebhookProcessResult(true, "orders/create", message = "Orden sin items")
        }

        val events = lineItems.mapIndexedNotNull { idx, element ->
            val item = element.jsonObject
            val sku = item["sku"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val qty = item["quantity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val priceCop = (item["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0).toLong()
            ProductSoldEvent(
                eventId    = "shopify_order_${orderId}_item_$idx",
                orderId    = orderId,
                channel    = "shopify",
                productKey = ProductKey(sku),
                quantity   = qty,
                soldPrice  = Money.of(priceCop)
            )
        }

        return WebhookProcessResult(
            success       = true,
            eventType     = "orders/create",
            emittedEvents = events,
            message       = "Orden $orderId: ${events.size} producto(s) procesado(s)"
        )
    }

    private fun processOrderCancelled(json: JsonObject): WebhookProcessResult {
        val orderId = json["id"]?.jsonPrimitive?.content ?: "UNKNOWN_ORDER"
        val lineItems = json["line_items"]?.jsonArray

        if (lineItems.isNullOrEmpty()) {
            return WebhookProcessResult(true, "orders/cancelled", message = "Cancelacion sin items")
        }

        val events = lineItems.mapIndexedNotNull { idx, element ->
            val item = element.jsonObject
            val sku = item["sku"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val qty = item["quantity"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            ProductSoldEvent(
                eventId    = "shopify_cancel_${orderId}_item_$idx",
                orderId    = orderId,
                channel    = "shopify",
                productKey = ProductKey(sku),
                quantity   = -qty,
                soldPrice  = Money.of(0L)
            )
        }

        return WebhookProcessResult(
            success       = true,
            eventType     = "orders/cancelled",
            emittedEvents = events,
            message       = "Cancelacion $orderId: ${events.size} producto(s) devuelto(s)"
        )
    }

    private fun processInventoryUpdated(json: JsonObject): WebhookProcessResult {
        val inventoryItemId = json["inventory_item_id"]?.jsonPrimitive?.content ?: "UNKNOWN"
        val available = json["available"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return WebhookProcessResult(
            success   = true,
            eventType = "inventory/update",
            message   = "Inventario de Shopify actualizado externamente a $available para item $inventoryItemId"
        )
    }
}
