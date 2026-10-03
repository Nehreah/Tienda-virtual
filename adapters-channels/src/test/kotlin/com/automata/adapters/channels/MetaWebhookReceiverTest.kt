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

    @Test
    fun `instagram channel is decided by object field, not by id format`() {
        val payload = """
        {
          "object": "instagram",
          "entry": [{
            "id": "17841400000000",
            "messaging": [{
              "sender": { "id": "IGSID_1" },
              "message": { "mid": "mid.ig.1", "text": "Hola desde IG" }
            }]
          }]
        }
        """.trimIndent()

        val events = receiver.parseInboundPayload(payload)
        assertEquals(1, events.size)
        assertEquals("instagram", events[0].channel)
    }

    @Test
    fun `page object is parsed as messenger`() {
        val payload = """
        {
          "object": "page",
          "entry": [{
            "id": "ig_looks_like_instagram_but_is_a_page",
            "messaging": [{
              "sender": { "id": "PSID_1" },
              "message": { "mid": "mid.fb.1", "text": "Hola desde Messenger" }
            }]
          }]
        }
        """.trimIndent()

        val events = receiver.parseInboundPayload(payload)
        assertEquals(1, events.size)
        assertEquals("messenger", events[0].channel)
    }

    @Test
    fun `events without message id are discarded because they cannot be deduplicated`() {
        val payload = """
        {
          "object": "page",
          "entry": [{
            "messaging": [{
              "sender": { "id": "PSID_1" },
              "message": { "text": "sin mid" }
            }]
          }]
        }
        """.trimIndent()

        assertTrue(receiver.parseInboundPayload(payload).isEmpty())
    }

    private fun sign(body: ByteArray, secret: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `valid signature is accepted`() {
        val body = """{"object":"page","entry":[]}""".toByteArray()
        assertTrue(receiver.verifySignature(body, sign(body, "app_secret"), "app_secret"))
    }

    @Test
    fun `tampered body or wrong secret is rejected`() {
        val body = """{"object":"page","entry":[]}""".toByteArray()
        val header = sign(body, "app_secret")
        assertFalse(receiver.verifySignature("""{"object":"page","entry":[1]}""".toByteArray(), header, "app_secret"))
        assertFalse(receiver.verifySignature(body, header, "otro_secret"))
    }

    @Test
    fun `missing malformed or unconfigured signature is rejected`() {
        val body = "{}".toByteArray()
        assertFalse(receiver.verifySignature(body, null, "app_secret"))
        assertFalse(receiver.verifySignature(body, "", "app_secret"))
        assertFalse(receiver.verifySignature(body, "md5=abc", "app_secret"))
        // SecretKeySpec lanza IllegalArgumentException con clave vacía; verifySignature
        // ya guarda appSecret.isBlank() antes de llamar al MAC, así que false es lo esperado.
        assertFalse(receiver.verifySignature(body, "sha256=cualquiercosa", ""))
    }

    @Test
    fun `echo messages sent by the page itself are ignored`() {
        val payload = """
        {
          "object": "page",
          "entry": [{
            "messaging": [
              { "sender": { "id": "PAGE" }, "message": { "mid": "mid.echo", "is_echo": true, "text": "respuesta del bot" } },
              { "sender": { "id": "PSID_1" }, "message": { "mid": "mid.real", "text": "mensaje del cliente" } }
            ]
          }]
        }
        """.trimIndent()

        val events = receiver.parseInboundPayload(payload)
        assertEquals(1, events.size)
        assertEquals("mid.real", events[0].messageId)
    }

    // ---------- Fase 1.2: firma siempre obligatoria y token no vacío ----------

    @Test
    fun `authenticate accepts a valid signature`() {
        val body = """{"object":"page","entry":[]}""".toByteArray()
        assertEquals(
            MetaWebhookReceiver.SignatureResult.VALID,
            receiver.authenticate(body, sign(body, "app_secret"), "app_secret")
        )
    }

    @Test
    fun `authenticate without signature header is invalid`() {
        val body = "{}".toByteArray()
        assertEquals(MetaWebhookReceiver.SignatureResult.INVALID, receiver.authenticate(body, null, "app_secret"))
        assertEquals(MetaWebhookReceiver.SignatureResult.INVALID, receiver.authenticate(body, "", "app_secret"))
    }

    @Test
    fun `authenticate with a bad signature is invalid`() {
        val body = "{}".toByteArray()
        assertEquals(
            MetaWebhookReceiver.SignatureResult.INVALID,
            receiver.authenticate(body, sign(body, "otro_secret"), "app_secret")
        )
        assertEquals(
            MetaWebhookReceiver.SignatureResult.INVALID,
            receiver.authenticate("{\"x\":1}".toByteArray(), sign(body, "app_secret"), "app_secret")
        )
    }

    @Test
    fun `authenticate with an empty app secret reports the webhook as not configured even if a signature is sent`() {
        val body = "{}".toByteArray()
        assertEquals(MetaWebhookReceiver.SignatureResult.SECRET_NOT_CONFIGURED, receiver.authenticate(body, "sha256=cualquiercosa", ""))
        assertEquals(MetaWebhookReceiver.SignatureResult.SECRET_NOT_CONFIGURED, receiver.authenticate(body, null, "   "))
    }

    @Test
    fun `empty verify token on either side is rejected`() {
        // Token configurado vacío y Meta envía token vacío: antes coincidían.
        val unconfigured = MetaWebhookReceiver("")
        assertNull(unconfigured.verifyWebhookChallenge("subscribe", "", "challenge"))
        assertNull(unconfigured.verifyWebhookChallenge("subscribe", null, "challenge"))
        assertNull(unconfigured.verifyWebhookChallenge("subscribe", "cualquiera", "challenge"))
        // Token configurado y Meta envía vacío o nulo.
        assertNull(receiver.verifyWebhookChallenge("subscribe", "", "challenge"))
        assertNull(receiver.verifyWebhookChallenge("subscribe", null, "challenge"))
        assertNull(receiver.verifyWebhookChallenge("subscribe", "   ", "challenge"))
    }

    @Test
    fun `challenge requires subscribe mode`() {
        assertNull(receiver.verifyWebhookChallenge("unsubscribe", verifyToken, "challenge"))
        assertNull(receiver.verifyWebhookChallenge(null, verifyToken, "challenge"))
    }
}
