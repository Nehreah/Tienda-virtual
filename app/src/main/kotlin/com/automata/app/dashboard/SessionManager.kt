package com.automata.app.dashboard

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sesión del tablero en una cookie FIRMADA (HMAC-SHA256), sin estado en el servidor.
 *
 * La cookie dice quién es la persona, qué rol tiene y cuándo vence, y lleva una firma que solo este servidor puede
 * calcular: si alguien cambia el rol o la fecha, la firma ya no coincide y la cookie se rechaza. No contiene la clave.
 *
 * - [ttlSeconds]: duración de la sesión (por defecto 8 horas, una jornada de trabajo).
 * - Si no se da [secret], se genera uno aleatorio al arrancar: al reiniciar la app todas las sesiones se cierran.
 *   Con SESSION_SECRET fijo las sesiones sobreviven a un reinicio; cambiarlo cierra todas de golpe.
 * - [clock] (en segundos) se puede inyectar para probar el vencimiento sin esperar.
 */
class SessionManager(
    secret: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val key = SecretKeySpec(secret, HMAC)

    /** Crea el valor de la cookie para [principal]. */
    fun issue(principal: DashboardPrincipal): String {
        val expires = clock() + ttlSeconds
        // El usuario va al final: puede contener cualquier carácter, incluido el separador.
        val payload = "${principal.role.name}\n$expires\n${principal.user}"
        return encode(payload.toByteArray(Charsets.UTF_8)) + "." + encode(sign(payload))
    }

    /** Devuelve quién es, o null si la cookie falta, está mal formada, fue alterada o ya venció. */
    fun verify(token: String?): DashboardPrincipal? {
        if (token.isNullOrBlank() || token.length > MAX_TOKEN_LENGTH) return null
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.length - 1) return null

        val payloadBytes = decode(token.substring(0, dot)) ?: return null
        val givenSignature = decode(token.substring(dot + 1)) ?: return null
        val payload = String(payloadBytes, Charsets.UTF_8)

        // Comparación en tiempo constante.
        if (!MessageDigest.isEqual(sign(payload), givenSignature)) return null

        val parts = payload.split('\n', limit = 3)
        if (parts.size != 3) return null
        val role = DashboardRole.values().firstOrNull { it.name == parts[0] } ?: return null
        val expires = parts[1].toLongOrNull() ?: return null
        if (expires <= clock()) return null
        if (parts[2].isEmpty()) return null
        return DashboardPrincipal(parts[2], role)
    }

    private fun sign(payload: String): ByteArray =
        Mac.getInstance(HMAC).also { it.init(key) }.doFinal(payload.toByteArray(Charsets.UTF_8))

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val COOKIE_NAME = "tv_session"
        const val DEFAULT_TTL_SECONDS = 8 * 60 * 60L
        private const val HMAC = "HmacSHA256"
        private const val MAX_TOKEN_LENGTH = 1024

        /** Deriva la clave de firma de un texto cualquiera (SESSION_SECRET) con SHA-256, sea corto o largo. */
        fun fromSecret(secret: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS): SessionManager =
            SessionManager(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)), ttlSeconds)
    }
}
