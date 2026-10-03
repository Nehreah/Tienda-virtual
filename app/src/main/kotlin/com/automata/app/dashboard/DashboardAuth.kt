package com.automata.app.dashboard

import com.automata.infra.config.AppEnv
import java.security.MessageDigest
import java.util.Base64

// Autenticacion HTTP Basic para el tablero y las rutas /api/...
//
// - Si [password] esta vacio, esta clase deja pasar todo. Eso SOLO ocurre en APP_ENV=local:
//   [DashboardSecurity.plan] impide crear una instancia asi en produccion y, en local sin clave,
//   obliga a escuchar unicamente en 127.0.0.1.
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

/**
 * Política de arranque del tablero: falla cerrado por defecto.
 *
 * - PRODUCTION: DASHBOARD_USER y DASHBOARD_PASSWORD (mínimo [MIN_PASSWORD_LENGTH] caracteres) son obligatorios;
 *   si faltan, [plan] lanza IllegalStateException y la app no arranca. Escucha en todas las interfaces.
 * - LOCAL: escucha SIEMPRE solo en 127.0.0.1 (nunca en 0.0.0.0), con o sin clave; sin clave se agrega un aviso.
 */
object DashboardSecurity {
    const val MIN_PASSWORD_LENGTH = 12
    const val LOOPBACK_HOST = "127.0.0.1"
    const val ALL_INTERFACES_HOST = "0.0.0.0"

    data class Plan(val host: String, val auth: DashboardAuth, val warnings: List<String>)

    fun plan(env: AppEnv, user: String, password: String): Plan {
        val warnings = mutableListOf<String>()

        if (env == AppEnv.PRODUCTION) {
            if (user.isBlank()) {
                throw IllegalStateException(
                    "APP_ENV=production: DASHBOARD_USER no puede estar vacío."
                )
            }
            if (password.isBlank() || password.length < MIN_PASSWORD_LENGTH) {
                throw IllegalStateException(
                    "APP_ENV=production: DASHBOARD_PASSWORD está vacía o tiene menos de $MIN_PASSWORD_LENGTH caracteres. " +
                        "Genera una con: openssl rand -base64 24  y guárdala en local.properties o como variable de entorno. " +
                        "Detalles en docs/MANUAL_CLAVE_TABLERO.md."
                )
            }
            return Plan(ALL_INTERFACES_HOST, DashboardAuth(user, password), warnings)
        }

        // LOCAL
        if (password.isBlank()) {
            warnings += "DASHBOARD_PASSWORD vacío (APP_ENV=local): el tablero no pide clave y por eso solo escucha en $LOOPBACK_HOST. " +
                "Defínela antes de pasar a producción."
        } else if (password.length < MIN_PASSWORD_LENGTH) {
            warnings += "DASHBOARD_PASSWORD tiene menos de $MIN_PASSWORD_LENGTH caracteres; en APP_ENV=production la app no arrancaría así."
        }
        return Plan(LOOPBACK_HOST, DashboardAuth(user, password), warnings)
    }
}
