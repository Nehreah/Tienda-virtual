package com.automata.adapters.shopify

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verificador de firmas HMAC SHA-256 para webhooks entrantes de Shopify.
 */
object ShopifyHmacVerifier {
    fun verifyHmac(rawBodyBytes: ByteArray, hmacHeader: String?, sharedSecret: String): Boolean {
        if (hmacHeader.isNullOrBlank()) return false
        try {
            val keySpec = SecretKeySpec(sharedSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val calculatedHash = mac.doFinal(rawBodyBytes)
            val calculatedBase64 = Base64.getEncoder().encodeToString(calculatedHash)
            return MessageDigest.isEqual(
                calculatedBase64.toByteArray(Charsets.UTF_8),
                hmacHeader.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            return false
        }
    }
}
