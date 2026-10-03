package com.automata.infra.config

import java.io.File
import java.util.Properties

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

    fun get(key: String, defaultValue: String = ""): String {
        val envVal = System.getenv(key)
        if (!envVal.isNullOrBlank()) return envVal

        val propVal = localProps.getProperty(key)
        if (!propVal.isNullOrBlank()) return propVal

        return defaultValue
    }

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

    // Meta Catalog & Shop Config
    val metaCatalogId: String get() = get("META_CATALOG_ID")
    val metaAccessToken: String get() = get("META_ACCESS_TOKEN")
    val metaCountryCode: String get() = get("META_COUNTRY_CODE", "CO")
    val metaWebhookVerifyToken: String get() = get("META_WEBHOOK_VERIFY_TOKEN")

    // Versión de la Graph API de Meta (WhatsApp, Instagram, Messenger). Centralizada aquí para
    // poder actualizarla sin tocar los adaptadores. Verifica la vigente en developers.facebook.com/docs/graph-api/changelog
    // App Secret de la app de Meta: firma de los webhooks (X-Hub-Signature-256). Sin valor por defecto.
    val metaAppSecret: String get() = get("META_APP_SECRET")

    val metaGraphApiVersion: String get() = get("META_GRAPH_API_VERSION", "v26.0")

    // WhatsApp Cloud API Config
    val whatsappPhoneNumberId: String get() = get("WHATSAPP_PHONE_NUMBER_ID")
    val whatsappAccessToken: String get() = get("WHATSAPP_ACCESS_TOKEN")

    // Instagram & Messenger Config
    val metaPageId: String get() = get("META_PAGE_ID")

    // Texto que el bot envía al cliente tras reservar (cómo pagar). Configúralo con tus datos reales.
    val paymentInstructions: String get() = get("PAYMENT_INSTRUCTIONS", "Un asesor te enviará los datos de pago en breve.")

    // Acceso al tablero y a /api/*: HTTP Basic. Sin DASHBOARD_PASSWORD la protección queda desactivada (solo para uso local).
    val dashboardUser: String get() = get("DASHBOARD_USER", "admin")
    val dashboardPassword: String get() = get("DASHBOARD_PASSWORD")

    // Endurecimiento opcional (por defecto NO cambia nada del comportamiento actual).
    // REQUIRE_META_SIGNATURE=true: /webhooks/meta rechaza todo si META_APP_SECRET está vacío.
    val requireMetaSignature: Boolean get() = get("REQUIRE_META_SIGNATURE", "false").trim().equals("true", ignoreCase = true)

    // CORS_ALLOWED_HOST=mi-dominio.com: solo ese origen puede llamar a la API desde un navegador (vacío = cualquiera, como hasta ahora).
    val corsAllowedHost: String get() = get("CORS_ALLOWED_HOST")

    // Google Gemini LLM API Key
    val geminiApiKey: String get() = get("GEMINI_API_KEY", "")
}
