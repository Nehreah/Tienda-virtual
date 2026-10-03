package com.automata.adapters.channels

import com.automata.messaging.ports.CustomerIntentType
import com.automata.shared.ProductKey
import kotlinx.serialization.json.*

/**
 * Receptor de Webhooks oficial para WhatsApp Business, Instagram DM y Facebook Messenger.
 * Procesa la verificación inicial hub.challenge y extrae mensajes entrantes.
 */
class MetaWebhookReceiver(
    private val verifyToken: String
) {
    data class InboundMessageEvent(
        val channel: String, // "whatsapp", "instagram", "messenger"
        val senderId: String,
        val messageId: String,
        val text: String,
        val timestamp: Long
    )

    /** Resultado de autenticar un POST de Meta. Application lo traduce a 200 / 503 / 401. */
    enum class SignatureResult {
        VALID,
        /** No hay META_APP_SECRET: el webhook está deshabilitado (HTTP 503). */
        SECRET_NOT_CONFIGURED,
        /** Falta la firma, está mal formada o no coincide (HTTP 401). */
        INVALID
    }

    /**
     * Verificación inicial de Meta (GET). Falla cerrado: un token vacío, de cualquiera de los dos lados,
     * nunca se acepta (antes, token == "" coincidía con un verifyToken vacío).
     */
    fun verifyWebhookChallenge(mode: String?, token: String?, challenge: String?): String? {
        if (verifyToken.isBlank() || token.isNullOrBlank()) return null
        if (mode != "subscribe") return null
        val tokensMatch = java.security.MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            verifyToken.toByteArray(Charsets.UTF_8)
        )
        return if (tokensMatch) challenge else null
    }

    /**
     * La firma de Meta es siempre obligatoria. Sin App Secret el webhook no funciona (503);
     * con App Secret, toda petición sin X-Hub-Signature-256 válida se rechaza (401).
     */
    fun authenticate(rawBody: ByteArray, signatureHeader: String?, appSecret: String): SignatureResult = when {
        appSecret.isBlank() -> SignatureResult.SECRET_NOT_CONFIGURED
        verifySignature(rawBody, signatureHeader, appSecret) -> SignatureResult.VALID
        else -> SignatureResult.INVALID
    }

    /**
     * Verifica la cabecera X-Hub-Signature-256 ("sha256=<hex>") = HMAC-SHA256 del cuerpo CRUDO
     * con el App Secret. Comparación en tiempo constante. Falla cerrado si falta algo.
     */
    fun verifySignature(rawBody: ByteArray, signatureHeader: String?, appSecret: String): Boolean {
        if (appSecret.isBlank() || signatureHeader.isNullOrBlank()) return false
        val prefix = "sha256="
        if (!signatureHeader.startsWith(prefix)) return false
        val provided = signatureHeader.removePrefix(prefix).trim().lowercase()
        return try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(rawBody).joinToString("") { "%02x".format(it) }
            java.security.MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                provided.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            false
        }
    }

    fun parseInboundPayload(rawJsonBody: String): List<InboundMessageEvent> {
        val events = mutableListOf<InboundMessageEvent>()
        try {
            val json = Json.parseToJsonElement(rawJsonBody).jsonObject
            val entries = json["entry"]?.jsonArray ?: return emptyList()
            // Meta indica la plataforma en el campo raíz "object":
            // "whatsapp_business_account" | "instagram" | "page" (Messenger).
            val platformObject = json["object"]?.jsonPrimitive?.content

            for (entryElement in entries) {
                val entry = entryElement.jsonObject
                val changes = entry["changes"]?.jsonArray

                // 1. WhatsApp Cloud API Format
                if (changes != null) {
                    for (changeElement in changes) {
                        val value = changeElement.jsonObject["value"]?.jsonObject ?: continue
                        val messages = value["messages"]?.jsonArray ?: continue

                        for (msgElement in messages) {
                            val msg = msgElement.jsonObject
                            val from = msg["from"]?.jsonPrimitive?.content ?: continue
                            // Sin id no se puede deduplicar: se descarta el evento.
                            val msgId = msg["id"]?.jsonPrimitive?.content ?: continue
                            val text = msg["text"]?.jsonObject?.get("body")?.jsonPrimitive?.content
                                ?: msg["interactive"]?.jsonObject?.get("button_reply")?.jsonObject?.get("title")?.jsonPrimitive?.content
                                ?: ""

                            events.add(InboundMessageEvent("whatsapp", from, msgId, text, System.currentTimeMillis()))
                        }
                    }
                }

                // 2. Instagram & Messenger Format
                val messaging = entry["messaging"]?.jsonArray
                if (messaging != null) {
                    // El canal lo define el campo "object" del webhook, no el formato del id.
                    val channel = if (platformObject == "instagram") "instagram" else "messenger"

                    for (mElement in messaging) {
                        val mObj = mElement.jsonObject
                        val senderId = mObj["sender"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: continue
                        val message = mObj["message"]?.jsonObject ?: continue
                        // Los "ecos" son copias de lo que la propia página envió: no son mensajes del cliente.
                        if (message["is_echo"]?.jsonPrimitive?.booleanOrNull == true) continue
                        // Sin mid no se puede deduplicar: se descarta el evento.
                        val msgId = message["mid"]?.jsonPrimitive?.content ?: continue
                        val text = message["text"]?.jsonPrimitive?.content ?: ""

                        events.add(InboundMessageEvent(channel, senderId, msgId, text, System.currentTimeMillis()))
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error parseando webhook de Meta: ${e.message}")
        }
        return events
    }
}
