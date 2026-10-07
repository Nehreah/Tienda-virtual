package com.automata.infra.config

import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Entorno de ejecución (variable APP_ENV).
 * - LOCAL (por defecto): desarrollo en tu propia máquina. El servidor escucha solo en 127.0.0.1.
 * - PRODUCTION: servidor expuesto. Exige clave del tablero y escucha en todas las interfaces.
 * Un valor desconocido (por ejemplo un error de tipeo como "prodution") NO se interpreta como local: la app no arranca.
 */
enum class AppEnv {
    LOCAL, PRODUCTION;

    companion object {
        fun parse(raw: String?): AppEnv = when (raw?.trim()?.lowercase().orEmpty()) {
            "", "local" -> LOCAL
            "production" -> PRODUCTION
            else -> throw IllegalStateException(
                "APP_ENV='${raw?.trim()}' no es válido. Los valores permitidos son 'local' (por defecto) y 'production'."
            )
        }
    }
}

/**
 * Detecta valores de ejemplo copiados de local.properties.example (por ejemplo "verify_token_secret_123",
 * "shpat_xxx..." o "change_me"). AppConfig los trata como si la variable estuviera vacía.
 */
object ConfigPlaceholders {
    private val EXACT = setOf(
        "change_me", "changeme", "cambiar_esta_clave", "cambiar_clave",
        "verify_token_secret_123", "meta_cat_123456", "page_1234567890", "1001234567890",
        "tu-tienda.myshopify.com", "postgres.id_del_proyecto"
    )
    private val PREFIXES = listOf("shpat_xxx", "shpss_xxx", "eaagxxx", "aizasyxxx")
    private val FRAGMENTS = listOf("id_del_proyecto", "aws-0-region.", "tu-dominio.com", "3xx xxx xxxx")
    // Cinco "x" seguidas no aparecen en un secreto real (hex o base62) en la práctica; en los ejemplos hay 20 o más.
    private val RUN_OF_X = Regex("x{5,}", RegexOption.IGNORE_CASE)

    fun isPlaceholder(value: String): Boolean {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return false
        return v in EXACT ||
            (v.startsWith("<") && v.endsWith(">")) ||   // "<tu token aquí>"
            PREFIXES.any { v.startsWith(it) } ||
            FRAGMENTS.any { v.contains(it) } ||
            RUN_OF_X.containsMatchIn(v)
    }
}

/**
 * Carga segura de configuración y secretos.
 */
object AppConfig {

    private val localProps = Properties()

    init {
        val propsFile = File("local.properties")
        if (propsFile.exists()) {
            try {
                propsFile.inputStream().use { localProps.load(it) }
            } catch (e: Exception) {
                System.err.println("Advertencia: No se pudo cargar local.properties: ${e.message}")
            }
        }
    }

    private val warnedPlaceholders: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Valor tal cual está en el entorno o en local.properties ("" si no existe). La variable de entorno gana. */
    private fun rawValue(key: String): String {
        val sysProp = System.getProperty(key)
        if (!sysProp.isNullOrBlank()) return sysProp
        val envVal = System.getenv(key)
        if (!envVal.isNullOrBlank()) return envVal
        val propVal = localProps.getProperty(key)
        if (!propVal.isNullOrBlank()) return propVal
        return ""
    }

    fun get(key: String, defaultValue: String = ""): String {
        val raw = rawValue(key)
        if (raw.isBlank()) return defaultValue
        if (ConfigPlaceholders.isPlaceholder(raw)) {
            // Solo se escribe el NOMBRE de la variable, nunca su valor.
            if (warnedPlaceholders.add(key)) {
                System.err.println("ADVERTENCIA: $key contiene un valor de ejemplo (placeholder); se ignora y esa integración queda sin configurar.")
            }
            return defaultValue
        }
        return raw
    }

    /** true si la variable existe pero trae un valor de ejemplo sin reemplazar (el tablero lo explica así). */
    fun hasPlaceholder(key: String): Boolean {
        val raw = rawValue(key)
        return raw.isNotBlank() && ConfigPlaceholders.isPlaceholder(raw)
    }

    /** Entorno de ejecución. Lanza IllegalStateException si APP_ENV tiene un valor desconocido. */
    val appEnv: AppEnv get() = AppEnv.parse(get("APP_ENV"))

    val port: Int get() = get("PORT", "8080").toIntOrNull() ?: 8080

    // Base de datos (Supabase / PostgreSQL). Sin valores por defecto: si falta, la app debe fallar claro.
    val databaseUrl: String get() = get("DATABASE_URL")
    val databaseUser: String get() = get("DATABASE_USER")
    val databasePassword: String get() = get("DATABASE_PASSWORD")
    val databaseConfigured: Boolean get() = databaseUrl.isNotBlank()

    // Shopify Config
    val shopifyShopDomain: String get() = get("SHOPIFY_SHOP_DOMAIN")
    val shopifyAccessToken: String get() = get("SHOPIFY_ACCESS_TOKEN")
    val shopifyWebhookSecret: String get() = get("SHOPIFY_WEBHOOK_SECRET")
    /** ID de la ubicación de inventario en Shopify (debe configurarse vía SHOPIFY_LOCATION_ID). */
    val shopifyLocationId: String get() = get("SHOPIFY_LOCATION_ID")
    /** Versión de Shopify API (configurable vía SHOPIFY_API_VERSION). */
    val shopifyApiVersion: String get() = get("SHOPIFY_API_VERSION", "2024-10")

    // Meta Catalog & Shop Config
    val metaCatalogId: String get() = get("META_CATALOG_ID")
    val metaAccessToken: String get() = get("META_ACCESS_TOKEN")
    val metaCountryCode: String get() = get("META_COUNTRY_CODE", "CO")
    val metaWebhookVerifyToken: String get() = get("META_WEBHOOK_VERIFY_TOKEN")

    // Versión de la Graph API de Meta (WhatsApp, Instagram, Messenger). Centralizada aquí para
    // poder actualizarla sin tocar los adaptadores. Verifica la vigente en developers.facebook.com/docs/graph-api/changelog
    // App Secret de la app de Meta: firma de los webhooks (X-Hub-Signature-256). Sin valor por defecto.
    // Es OBLIGATORIO: sin él, POST /webhooks/meta responde 503 (ya no existe modo "sin firma").
    val metaAppSecret: String get() = get("META_APP_SECRET")

    val metaGraphApiVersion: String get() = get("META_GRAPH_API_VERSION", "v26.0")

    // WhatsApp Cloud API Config
    val whatsappPhoneNumberId: String get() = get("WHATSAPP_PHONE_NUMBER_ID")
    val whatsappAccessToken: String get() = get("WHATSAPP_ACCESS_TOKEN")

    // Instagram & Messenger Config
    val metaPageId: String get() = get("META_PAGE_ID")

    // Texto que el bot envía al cliente tras reservar (cómo pagar). Configúralo con tus datos reales.
    val paymentInstructions: String get() = get("PAYMENT_INSTRUCTIONS", "Un asesor te enviará los datos de pago en breve.")

    // Acceso al tablero y a /api/*: HTTP Basic.
    // APP_ENV=production exige DASHBOARD_PASSWORD de 12+ caracteres (si no, la app no arranca).
    // APP_ENV=local sin clave: el servidor escucha solo en 127.0.0.1. Ver docs/MANUAL_CLAVE_TABLERO.md.
    val dashboardUser: String get() = get("DASHBOARD_USER", "admin")
    val dashboardPassword: String get() = get("DASHBOARD_PASSWORD")

    // CORS_ALLOWED_HOST=mi-dominio.com: solo ese origen puede llamar a la API desde un navegador.
    // Vacío = CORS NO se instala (solo mismo origen). El tablero no necesita CORS.
    val corsAllowedHost: String get() = get("CORS_ALLOWED_HOST")

    // TRUST_PROXY=true: la app corre detrás de un proxy inverso de confianza.
    val trustProxyHeaders: Boolean get() = get("TRUST_PROXY", "false").trim().equals("true", ignoreCase = true)
    /** D11: Número de hops de proxy para extraer la IP real desde X-Forwarded-For. */
    val trustProxyHops: Int get() = get("TRUST_PROXY_HOPS", "1").toIntOrNull() ?: 1

    // Google Gemini LLM API Key
    val geminiApiKey: String get() = get("GEMINI_API_KEY", "")
}
