package com.automata.app.dashboard

import com.automata.infra.config.AppEnv
import java.security.MessageDigest
import java.util.Base64

// Autenticacion del tablero y de las rutas /api/...
//
// Hay dos formas de entrar, con las mismas credenciales:
//  - Formulario /login: deja una cookie de sesion firmada (ver SessionManager). Es lo que usa el navegador.
//  - HTTP Basic (cabecera Authorization): para curl y scripts. Sigue funcionando igual que antes.
//
// Hay dos roles:
//  - ADMIN (DASHBOARD_USER / DASHBOARD_PASSWORD): puede todo.
//  - LECTURA (DASHBOARD_VIEWER_USER / DASHBOARD_VIEWER_PASSWORD, opcional): solo ve; cualquier ruta que modifique datos
//    responde 403. Pensado para socios o contadores que solo necesitan consultar.
//
// - Si [password] esta vacio, esta clase deja pasar todo como ADMIN. Eso SOLO ocurre en APP_ENV=local:
//   [DashboardSecurity.plan] impide crear una instancia asi en produccion y, en local sin clave,
//   obliga a escuchar unicamente en 127.0.0.1.
//
// Los webhooks y la vitrina publica NO pasan por aqui: se protegen con sus propias firmas o son publicos por diseno.
// Usalo siempre detras de HTTPS.

/** Qué puede hacer una persona dentro del tablero. */
enum class DashboardRole { ADMIN, LECTURA }

/** Quién es y qué rol tiene. */
data class DashboardPrincipal(val user: String, val role: DashboardRole)

class DashboardAuth(
    private val user: String,
    private val password: String,
    private val viewerUser: String = "",
    private val viewerPassword: String = ""
) {
    val enabled: Boolean get() = password.isNotBlank()

    /** true si hay un segundo usuario de solo lectura configurado. */
    val hasViewer: Boolean get() = enabled && viewerUser.isNotBlank() && viewerPassword.isNotBlank()

    /** Misma configuración más un usuario de solo lectura. */
    fun withViewer(viewerUser: String, viewerPassword: String): DashboardAuth =
        DashboardAuth(user, password, viewerUser, viewerPassword)

    fun isAuthorized(authorizationHeader: String?): Boolean = authenticate(authorizationHeader) != null

    /** Quién es el dueño de la cabecera Basic, o null si no trae credenciales válidas. Sin clave configurada: ADMIN local. */
    fun authenticate(authorizationHeader: String?): DashboardPrincipal? {
        if (!enabled) return DashboardPrincipal(user.ifBlank { "local" }, DashboardRole.ADMIN)
        val header = authorizationHeader ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null

        val decoded = try {
            String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val separator = decoded.indexOf(':')
        if (separator < 0) return null
        return verify(decoded.substring(0, separator), decoded.substring(separator + 1))
    }

    /** Valida usuario y clave (del formulario de login o de Basic). null si no coinciden con ningún usuario. */
    fun verify(givenUser: String, givenPassword: String): DashboardPrincipal? {
        // Comparacion en tiempo constante; se evaluan ambas (and, no &&) para no filtrar cual fallo.
        val adminOk = sameBytes(givenUser, user) and sameBytes(givenPassword, password)
        if (adminOk) return DashboardPrincipal(user, DashboardRole.ADMIN)
        if (hasViewer) {
            val viewerOk = sameBytes(givenUser, viewerUser) and sameBytes(givenPassword, viewerPassword)
            if (viewerOk) return DashboardPrincipal(viewerUser, DashboardRole.LECTURA)
        }
        return null
    }

    /** ¿Sigue existiendo esta persona con este rol en la configuración actual? Invalida cookies de usuarios retirados. */
    fun recognizes(principal: DashboardPrincipal): Boolean = when (principal.role) {
        DashboardRole.ADMIN -> sameBytes(principal.user, user)
        DashboardRole.LECTURA -> hasViewer && sameBytes(principal.user, viewerUser)
    }

    private fun sameBytes(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
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

    /**
     * Valida el usuario de solo lectura opcional. Devuelve null si no hay ninguno configurado.
     * - Usuario y clave deben venir juntos (uno solo es un error de configuración).
     * - Debe ser distinto del administrador (si no, no se sabría qué rol darle).
     * - En producción su clave también exige [MIN_PASSWORD_LENGTH] caracteres.
     */
    fun viewerCredentials(env: AppEnv, adminUser: String, viewerUser: String, viewerPassword: String): Pair<String, String>? {
        if (viewerUser.isBlank() && viewerPassword.isBlank()) return null
        if (viewerUser.isBlank() || viewerPassword.isBlank()) {
            throw IllegalStateException(
                "DASHBOARD_VIEWER_USER y DASHBOARD_VIEWER_PASSWORD deben definirse juntos (o ninguno de los dos)."
            )
        }
        if (viewerUser == adminUser) {
            throw IllegalStateException("DASHBOARD_VIEWER_USER debe ser distinto de DASHBOARD_USER.")
        }
        if (env == AppEnv.PRODUCTION && viewerPassword.length < MIN_PASSWORD_LENGTH) {
            throw IllegalStateException(
                "APP_ENV=production: DASHBOARD_VIEWER_PASSWORD tiene menos de $MIN_PASSWORD_LENGTH caracteres."
            )
        }
        return viewerUser to viewerPassword
    }

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
