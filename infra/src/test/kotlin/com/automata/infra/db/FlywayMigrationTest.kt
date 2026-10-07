package com.automata.infra.db

import com.automata.infra.config.AppConfig
import com.automata.infra.config.AppEnv
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FlywayMigrationTest {

    @Test
    fun `T55 url without sslmode is rejected in production`() {
        val originalEnv = System.getProperty("APP_ENV")
        try {
            System.setProperty("APP_ENV", "production")
            val urlWithoutSsl = "jdbc:postgresql://localhost:5432/tienda"
            val ex = assertThrows(IllegalStateException::class.java) {
                DatabaseFactory.connect(
                    url = urlWithoutSsl,
                    user = "postgres",
                    password = "password",
                    runMigrations = false
                )
            }
            assertTrue(ex.message!!.contains("M11"))
        } finally {
            if (originalEnv != null) {
                System.setProperty("APP_ENV", originalEnv)
            } else {
                System.clearProperty("APP_ENV")
            }
        }
    }
}
