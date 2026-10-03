package com.automata.app.dashboard

import java.security.MessageDigest
import java.util.Base64

// Autenticacion HTTP Basic opcional para el tablero y las rutas /api/...
//
// - Si [password] esta vacio, la proteccion esta DESACTIVADA y todo pasa
//   (modo local/demo; la app avisa por consola).
// - Si tiene valor, cada peticion debe traer: Authorization: Basic base64(usuario:clave)
//
// Los webhooks y la vitrina publica NO pasan por aqui:
// se protegen con sus propias firmas o son publicos por diseno.
// Usalo siempre detras de HTTPS.
class DashboardAuth(
    private val user: String,
    private val password: String
) {
    val enabled: Boolean get() = password.isNotBlank()

    fun isAuthorized(authorizationHeader: String?): Boolean {
        if (!enabled) return true
        val header = authorizationHeader ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false

        val decoded = try {
            String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val separator = decoded.indexOf(':')
        if (separator < 0) return false

        val givenUser = decoded.substring(0, separator)
        val givenPassword = decoded.substring(separator + 1)

        // Comparacion en tiempo constante; se evaluan ambas para no filtrar cual fallo.
        val userOk = MessageDigest.isEqual(givenUser.toByteArray(Charsets.UTF_8), user.toByteArray(Charsets.UTF_8))
        val passwordOk = MessageDigest.isEqual(givenPassword.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8))
        return userOk && passwordOk
    }
}
