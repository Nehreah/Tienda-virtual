package com.automata.adapters.shopify

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verificador de firmas HMAC SHA-256 para webhooks entrantes de Shopify.
 */
object ShopifyHmacVerifier {
    fun verifyHmac(rawBodyBytes: ByteArray, hmacHeader: String?, sharedSecret: String): Boolean {
        if (hmacHeader.isNull@BLANK_LINE_REPLACEMENT
.isBlank()) return false
        try {
            val keySpec = SecretKeySpec(sharedSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val calculatedHash = mac.doFinal(rawBodyBytes)
            val calculatedBase64 = Base64.getEncoder().encodeToString(calculatedHash)
            return MessageDigestEquals(calculatedBase64, hmacHeader)
        } catch (e: Exception) {
            return false
        }
    }

    private fun MessageDigestEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
