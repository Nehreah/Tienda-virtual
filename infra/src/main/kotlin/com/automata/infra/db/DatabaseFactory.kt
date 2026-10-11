package com.automata.infra.db

import com.automata.infra.config.AppConfig
import com.automata.infra.config.AppEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

/** Valores permitidos para "origen" del cambio (constantes: nunca texto que venga del usuario). */
object Origen {
    const val DASHBOARD = "dashboard"
    const val SHOPIFY = "shopify"
    const val IMPORT = "import"
}

/**
 * Conexión a Supabase (PostgreSQL) con pool Hikari + Flyway + Exposed.
 * [tx] abre una transacción y le dice a la base quién y desde dónde cambia los datos;
 * los triggers del esquema "tienda" lo guardan en tienda.historial.
 */
object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun connect(
        url: String = AppConfig.databaseUrl,
        user: String = AppConfig.databaseUser,
        password: String = AppConfig.databasePassword,
        runMigrations: Boolean = true
    ) {
        check(url.isNotBlank()) {
            "Falta DATABASE_URL. Configúrala en local.properties o como variable de entorno (ver local.properties.example)."
        }

        // M11: En producción sslmode=require es obligatorio, salvo cuando la base está en la red interna del propio
        // servidor (contenedor "db" de docker-compose o localhost): ahí el tráfico nunca sale a Internet.
        if (AppConfig.appEnv == AppEnv.PRODUCTION) {
            val host = Regex("^jdbc:postgresql://([^:/?]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()
            val redInterna = host in setOf("db", "postgres", "localhost", "127.0.0.1")
            check(redInterna || url.contains("sslmode=require", ignoreCase = true) || url.contains("ssl=true", ignoreCase = true)) {
                "M11: Conexión a la base de datos rechazada. En producción sslmode=require es OBLIGATORIO en la URL " +
                    "(solo se permite sin SSL si el host es 'db', 'postgres' o 'localhost', es decir, la red interna)."
            }
        }

        val cfg = HikariConfig().apply {
            poolName = "tienda-pool"
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }

        val dataSource = HikariDataSource(cfg)

        // M2: Ejecutor de Migraciones Flyway
        if (runMigrations) {
            try {
                val flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migrations")
                    .schemas("tienda")
                    .baselineOnMigrate(true)
                    .load()
                val migrationResult = flyway.migrate()
                logger.info("Flyway: ${migrationResult.migrationsExecuted} migraciones aplicadas correctamente en esquema 'tienda'.")
            } catch (e: Exception) {
                throw IllegalStateException("M2: Error crítico ejecutando migraciones de base de datos con Flyway: ${e.message}", e)
            }
        }

        Database.connect(
            dataSource,
            databaseConfig = DatabaseConfig { defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED }
        )
    }

    /**
     * Ejecuta [block] en una transacción de escritura. Fija app.origen y app.usuario solo para
     * esta transacción (set_config(..., true)); el trigger de historial los lee.
     */
    fun <T> tx(origen: String, usuario: String?, block: Transaction.() -> T): T = transaction {
        exec(
            "select set_config('app.origen', ?, true), set_config('app.usuario', ?, true)",
            listOf(TextColumnType() to origen, TextColumnType() to (usuario ?: ""))
        ) { }
        block()
    }
}
