package com.automata.adapters.channels

import com.automata.messaging.ports.MessagingPort
import com.automata.shared.PortResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Adaptador de Producción para WhatsApp Business Cloud API (Graph API).
 * Maneja el envío de mensajes de texto, tarjetas interactivas de producto y plantillas de 24h.
 */
class WhatsAppMessagingAdapter(
    private val phoneNumberId: String,
    private val accessToken: String,
    private val apiVersion: String,
    private val httpClient: HttpClient = HttpClient()
) : MessagingPort {

    override val channelName: String = "whatsapp"

    private val apiEndpoint: String
        get() = "https://graph.facebook.com/$apiVersion/$phoneNumberId/messages"

    override suspend fun sendMessage(recipientId: String, text: String): PortResult<String> {
        if (phoneNumberId.isBlank() || accessToken.isBlank()) {
            return PortResult.Permanent("WHATSAPP_CONFIG_ERROR", "Credenciales de WhatsApp Cloud API no configuradas")
        }

        val payload = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipientId)
            put("type", "text")
            putJsonObject("text") {
                put("body", text)
            }
        }.toString()

        return sendGraphApiPost(payload)
    }

    override suspend fun sendProductCard(
        recipientId: String,
        productTitle: String,
        priceFormatted: String,
        imageUrl: String
    ): PortResult<String> {
        val text = "*$productTitle*\nPrecio: $priceFormatted\n\n¿Te gustaría reservarlo o tienes alguna pregunta?"
        return sendMessage(recipientId, text)
    }

    /**
     * Envía un mensaje de catálogo interactivo oficial de WhatsApp Business.
     */
    suspend fun sendInteractiveCatalogMessage(
        recipientId: String,
        catalogId: String,
        productRetailerId: String,
        bodyText: String
    ): PortResult<String> {
        val payload = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipientId)
            put("type", "interactive")
            putJsonObject("interactive") {
                put("type", "product")
                putJsonObject("body") {
                    put("text", bodyText)
                }
                putJsonObject("action") {
                    put("catalog_id", catalogId)
                    put("product_retailer_id", productRetailerId)
                }
            }
        }.toString()

        return sendGraphApiPost(payload)
    }

    private suspend fun sendGraphApiPost(jsonPayload: String): PortResult<String> {
        return try {
            val response = httpClient.post(apiEndpoint) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(jsonPayload)
            }

            if (response.status.isSuccess()) {
                val resJson = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val messageId = resJson["messages"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "wamid_ok"
                PortResult.Ok(messageId)
            } else if (response.status == HttpStatusCode.TooManyRequests) {
                PortResult.RateLimited(retryAfterSeconds = 5)
            } else if (response.status == HttpStatusCode.Unauthorized) {
                PortResult.AuthExpired
            } else {
                PortResult.Transient("Error HTTP WhatsApp API ${response.status.value}: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            PortResult.Transient("Error de red enviando mensaje a WhatsApp: ${e.message}")
        }
    }
}
