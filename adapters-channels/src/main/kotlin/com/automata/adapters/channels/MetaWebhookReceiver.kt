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

    fun verifyWebhookChallenge(mode: String?, token: String?, challenge: String?): String? {
        if (mode == "subscribe" && token == verifyToken) {
            return challenge
        }
        return null
    }

    fun parseInboundPayload(rawJsonBody: String): List<InboundMessageEvent> {
        val events = mutableListOf<InboundMessageEvent>()
        try {
            val json = Json.parseToJsonElement(rawJsonBody).jsonObject
            val entries = json["entry"]?.jsonArray ?: return emptyList()

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
                            val msgId = msg["id"]?.jsonPrimitive?.content ?: "msg_id"
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
                    val isInstagram = entry.containsKey("id") && entry["id"]?.jsonPrimitive?.content?.startsWith("ig_") == true
                    val channel = if (isInstagram) "instagram" else "messenger"

                    for (mElement in messaging) {
                        val mObj = mElement.jsonObject
                        val senderId = mObj["sender"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: continue
                        val message = mObj["message"]?.jsonObject ?: continue
                        val msgId = message["mid"]?.jsonPrimitive?.content ?: "mid_id"
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
