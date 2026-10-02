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

    // Shopify Config
    val shopifyShopDomain: String get() = get("SHOPIFY_SHOP_DOMAIN", "tienda-demo.myshopify.com")
    val shopifyAccessToken: String get() = get("SHOPIFY_ACCESS_TOKEN", "shpat_demo123456789")
    val shopifyWebhookSecret: String get() = get("SHOPIFY_WEBHOOK_SECRET", "secret_webhook_123")

    // Meta Catalog & Shop Config
    val metaCatalogId: String get() = get("META_CATALOG_ID", "meta_cat_123")
    val metaAccessToken: String get() = get("META_ACCESS_TOKEN", "meta_token_123")
    val metaCountryCode: String get() = get("META_COUNTRY_CODE", "CO")
    val metaWebhookVerifyToken: String get() = get("META_WEBHOOK_VERIFY_TOKEN", "verify_token_123")

    // WhatsApp Cloud API Config
    val whatsappPhoneNumberId: String get() = get("WHATSAPP_PHONE_NUMBER_ID", "100123456789")
    val whatsappAccessToken: String get() = get("WHATSAPP_ACCESS_TOKEN", "EAAG_whatsapp_token_123")

    // Instagram & Messenger Config
    val metaPageId: String get() = get("META_PAGE_ID", "page_123456789")

    // Google Gemini LLM API Key
    val geminiApiKey: String get() = get("GEMINI_API_KEY", "")
}
