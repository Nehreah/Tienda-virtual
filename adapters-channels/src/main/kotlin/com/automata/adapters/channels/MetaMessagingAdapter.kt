package com.automata.adapters.channels

import com.automata.messaging.ports.MessagingPort
import com.automata.shared.PortResult
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Adaptador de Producción para Instagram Direct Messages & Facebook Messenger (Send API v18.0+).
 */
class MetaMessagingAdapter(
    override val channelName: String, // "instagram" | "messenger"
    private val pageOrIgAccountId: String,
    private val accessToken: String,
    private val apiVersion: String = "v18.0",
    private val httpClient: HttpClient = HttpClient()
) : MessagingPort {

    private val sendApiEndpoint: String
        get() = "https://graph.facebook.com/$apiVersion/$pageOrIgAccountId/messages"

    override suspend fun sendMessage(recipientId: String, text: String): PortResult<String> {
        if (pageOrIgAccountId.isBlank() || accessToken.isBlank()) {
            return PortResult.Permanent("META_MSG_CONFIG_ERROR", "Credenciales de $channelName Send API no configuradas")
        }

        val payload = buildJsonObject {
            putJsonObject("recipient") {
                put("id", recipientId)
            }
            putJsonObject("message") {
                put("text", text)
            }
        }.toString()

        return sendApiPost(payload)
    }

    override suspend fun sendProductCard(
        recipientId: String,
        productTitle: String,
        priceFormatted: String,
        imageUrl: String
    ): PortResult<String> {
        val payload = buildJsonObject {
            putJsonObject("recipient") {
                put("id", recipientId)
            }
            putJsonObject("message") {
                putJsonObject("attachment") {
                    put("type", "template")
                    putJsonObject("payload") {
                        put("template_type", "generic")
                        putJsonArray("elements") {
                            add(buildJsonObject {
                                put("title", productTitle)
                                put("subtitle", "Precio: $priceFormatted")
                                put("image_url", imageUrl)
                                putJsonArray("buttons") {
                                    add(buildJsonObject {
                                        put("type", "postback")
                                        put("title", "Comprar / Reservar")
                                        put("payload", "BUY_PRODUCT")
                                    })
                                }
                            })
                        }
                    }
                }
            }
        }.toString()

        return sendApiPost(payload)
    }

    private suspend fun sendApiPost(jsonPayload: String): PortResult<String> {
        return try {
            val response = httpClient.post(sendApiEndpoint) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(jsonPayload)
            }

            if (response.status.isSuccess()) {
                val resJson = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val messageId = resJson["message_id"]?.jsonPrimitive?.content ?: "mid_ok"
                PortResult.Ok(messageId)
            } else if (response.status == HttpStatusCode.TooManyRequests) {
                PortResult.RateLimited(retryAfterSeconds = 5)
            } else if (response.status == HttpStatusCode.Unauthorized) {
                PortResult.AuthExpired
            } else {
                PortResult.Transient("Error HTTP Meta Send API ${response.status.value}: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            PortResult.Transient("Error de red conectando con Meta Send API ($channelName): ${e.message}")
        }
    }
}
