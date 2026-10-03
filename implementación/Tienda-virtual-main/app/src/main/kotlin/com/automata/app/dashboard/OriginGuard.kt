package com.automata.app.dashboard

/**
 * Defensa mínima contra CSRF para los POST del tablero (fase 1.4).
 *
 * Un navegador siempre envía la cabecera Origin (y normalmente Referer) en un POST. Si ninguna coincide con el host
 * propio, la petición viene de otra página y se rechaza aunque lleve credenciales válidas (el navegador reenvía
 * automáticamente las credenciales Basic guardadas).
 *
 * Reglas (falla cerrado):
 * - Si hay Origin, manda Origin. Si no, se usa Referer. Si no hay ninguno, se rechaza.
 * - Origin "null", sin http/https, o con usuario@ en el host, se rechaza.
 * - Se compara host y puerto (puertos 80 y 443 se consideran "sin puerto"). No se compara el esquema, porque detrás
 *   de un proxy con TLS la app ve http aunque el navegador use https.
 */
object OriginGuard {

    private data class Authority(val host: String, val port: Int)

    fun isSameOrigin(origin: String?, referer: String?, host: String?): Boolean {
        val expected = parseAuthority(host ?: return false) ?: return false
        val source = when {
            !origin.isNullOrBlank() -> origin
            !referer.isNullOrBlank() -> referer
            else -> return false
        }
        val actual = authorityOfUrl(source)?.let { parseAuthority(it) } ?: return false
        return actual == expected
    }

    private fun authorityOfUrl(url: String): String? {
        val t = url.trim()
        val schemeEnd = t.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = t.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = t.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) rest else rest.substring(0, end)
    }

    private fun parseAuthority(raw: String): Authority? {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '/' || it == '@' || it == '\\' }) return null

        val host: String
        val portText: String?
        if (s.startsWith("[")) { // IPv6 literal: [::1]:8080
            val close = s.indexOf(']')
            if (close < 0) return null
            host = s.substring(0, close + 1)
            val rest = s.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            val colon = s.lastIndexOf(':')
            if (colon >= 0) {
                host = s.substring(0, colon)
                portText = s.substring(colon + 1)
            } else {
                host = s
                portText = null
            }
        }
        if (host.isEmpty()) return null

        val port = if (portText.isNullOrEmpty()) -1 else (portText.toIntOrNull() ?: return null)
        return Authority(host, if (port == 80 || port == 443) -1 else port)
    }
}
