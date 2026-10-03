package com.automata.infra.db

import com.automata.infra.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

/** Valores permitidos para "origen" del cambio (constantes: nunca texto que venga del usuario). */
object Origen {
    const val DASHBOARD = "dashboard"
    const val SHOPIFY = "shopify"
    const val IMPORT = "import"
}

/**
 * Conexión a Supabase (PostgreSQL) con pool Hikari + Exposed.
 * [tx] abre una transacción y le dice a la base quién y desde dónde cambia los datos;
 * los triggers del esquema "tienda" lo guardan en tienda.historial.
 */
object DatabaseFactory {

    fun connect(
        url: String = AppConfig.databaseUrl,
        user: String = AppConfig.databaseUser,
        password: String = AppConfig.databasePassword
    ) {
        check(url.isNotBlank()) {
            "Falta DATABASE_URL. Configúrala en local.properties o como variable de entorno (ver local.properties.example)."
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
        Database.connect(
            HikariDataSource(cfg),
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
