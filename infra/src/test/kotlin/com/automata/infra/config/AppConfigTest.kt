package com.automata.infra.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppConfigTest {

    // ---------- APP_ENV ----------

    @Test
    fun `app env defaults to local when missing or blank`() {
        assertEquals(AppEnv.LOCAL, AppEnv.parse(null))
        assertEquals(AppEnv.LOCAL, AppEnv.parse(""))
        assertEquals(AppEnv.LOCAL, AppEnv.parse("   "))
        assertEquals(AppEnv.LOCAL, AppEnv.parse("local"))
    }

    @Test
    fun `production is recognized ignoring case and spaces`() {
        assertEquals(AppEnv.PRODUCTION, AppEnv.parse("production"))
        assertEquals(AppEnv.PRODUCTION, AppEnv.parse("  Production "))
    }

    @Test
    fun `an unknown app env does not silently become local`() {
        assertThrows(IllegalStateException::class.java) { AppEnv.parse("prodution") }
        assertThrows(IllegalStateException::class.java) { AppEnv.parse("prod") }
    }

    // ---------- Placeholders de local.properties.example ----------

    @Test
    fun `every value of the example file is detected as placeholder`() {
        val fromExample = listOf(
            "jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres?sslmode=require",
            "postgres.ID_DEL_PROYECTO",
            "cambiar_esta_clave",
            "tu-tienda.myshopify.com",
            "shpat_xxxxxxxxxxxxxxxxxxxxxxxx",
            "shpss_xxxxxxxxxxxxxxxxxxxxxxx",
            "meta_cat_123456",
            "EAAGxxxxxxxxxxxxxxxxxxxxxxx",
            "verify_token_secret_123",
            "1001234567890",
            "page_1234567890",
            "AIzaSyxxxxxxxxxxxxxxxxxxxxxxx",
            "change_me",
            "shpat_xxx",
            "Paga por Nequi al 3XX XXX XXXX a nombre de TU NOMBRE.",
            "<token de la app de Shopify>"
        )
        fromExample.forEach { assertTrue(ConfigPlaceholders.isPlaceholder(it), "debería ser placeholder: $it") }
    }

    @Test
    fun `real looking secrets are not placeholders`() {
        val real = listOf(
            "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",
            "EAAGm0PX4ZCpsBAKZB8h2oUq1dZCfJ3nL9",
            "k3Jd9sLq0VbN7xYtR2mHgW5uPzAe8CfQ",
            "AIzaSyD3f4k3RealLookingKeyValue1234567",
            "3q9fX1mB7sV0uK2eYtR8nLhPzWc4JdAo",
            "Pago por Nequi al 3001234567 a nombre de Ana Pérez",
            "mi-tienda.myshopify.com",
            "8080"
        )
        real.forEach { assertFalse(ConfigPlaceholders.isPlaceholder(it), "NO debería ser placeholder: $it") }
    }

    @Test
    fun `blank values are not placeholders, they are just empty`() {
        assertFalse(ConfigPlaceholders.isPlaceholder(""))
        assertFalse(ConfigPlaceholders.isPlaceholder("   "))
    }
}
