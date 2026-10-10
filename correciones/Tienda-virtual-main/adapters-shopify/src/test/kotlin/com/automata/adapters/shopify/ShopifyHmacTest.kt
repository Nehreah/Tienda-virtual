package com.automata.adapters.shopify

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ShopifyHmacTest {

    private val secret = "test_secret_key_12345"
    private val payload = """{"id": 123456789, "line_items": [{"sku": "PROD-001", "quantity": 1, "price": "120000"}]}"""

    @Test
    fun `valid hmac signature passes verification`() {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val validHmac = Base64.getEncoder().encodeToString(mac.doFinal(bytes))

        val isValid = ShopifyHmacVerifier.verifyHmac(bytes, validHmac, secret)
        assertTrue(isValid, "Firma HMAC válida debe ser aceptada")
    }

    @Test
    fun `invalid hmac signature fails verification`() {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val isValid = ShopifyHmacVerifier.verifyHmac(bytes, "invalid_hmac_signature", secret)
        assertFalse(isValid, "Firma HMAC inválida debe ser rechazada")
    }
}
