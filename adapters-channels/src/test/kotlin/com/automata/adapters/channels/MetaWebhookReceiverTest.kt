package com.automata.adapters.channels

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MetaWebhookReceiverTest {

    private val verifyToken = "my_secret_verify_token_123"
    private val receiver = MetaWebhookReceiver(verifyToken)

    @Test
    fun `valid challenge token returns challenge string`() {
        val result = receiver.verifyWebhookChallenge("subscribe", verifyToken, "challenge_code_987")
        assertEquals("challenge_code_987", result)
    }

    @Test
    fun `invalid challenge token returns null`() {
        val result = receiver.verifyWebhookChallenge("subscribe", "wrong_token", "challenge_code_987")
        assertNull(result)
    }

    @Test
    fun `parse whatsapp inbound message payload`() {
        val payload = """
        {
          "entry": [{
            "changes": [{
              "value": {
                "messages": [{
                  "from": "573001234567",
                  "id": "wamid.HBgL...",
                  "text": { "body": "Hola, te ofrezco 95000 por PROD-001" }
                }]
              }
            }]
          }]
        }
        """.trimIndent()

        val events = receiver.parseInboundPayload(payload)
        assertEquals(1, events.size)
        assertEquals("whatsapp", events[0].channel)
        assertEquals("573001234567", events[0].senderId)
        assertEquals("Hola, te ofrezco 95000 por PROD-001", events[0].text)
    }
}
