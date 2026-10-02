package com.automata.infra.db

import org.jetbrains.exposed.sql.Table

/**
 * Esquema relacional PostgreSQL/H2 para el Contexto Comercio (`com.*`).
 */
object ComProductsTable : Table("com_products") {
    val key = varchar("key", 64)
    val itemName = varchar("item_name", 255)
    val publishedPriceCop = long("published_price_cop")
    val minimumPriceCop = long("minimum_price_cop") // DATO SENSIBLE INTERNO
    val costCop = long("cost_cop")                 // DATO SENSIBLE INTERNO
    val status = varchar("status", 32)
    val description = text("description")
    val brand = varchar("brand", 128)
    val condition = varchar("condition", 32)
    val gtinEan = varchar("gtin_ean", 64).nullable()
    val mpn = varchar("mpn", 64).nullable()
    val masterQuantity = integer("master_quantity")
    val updatedAt = varchar("updated_at", 64)

    override val primaryKey = PrimaryKey(key)
}

object ComInventoryTable : Table("com_inventory") {
    val productKey = varchar("product_key", 64)
    val stockMaestro = integer("stock_maestro")
    val reservasActivas = integer("reservas_activas")
    val unidadesVendidas = integer("unidades_vendidas")
    val version = long("version")

    override val primaryKey = PrimaryKey(productKey)
}

object ComReservationsTable : Table("com_reservations") {
    val reservationId = varchar("reservation_id", 64)
    val conversationId = varchar("conversation_id", 64)
    val productKey = varchar("product_key", 64)
    val quantity = integer("quantity")
    val createdAt = varchar("created_at", 64)
    val expiresAt = varchar("expires_at", 64)
    val status = varchar("status", 32)

    override val primaryKey = PrimaryKey(reservationId)
}

object ComExternalMappingsTable : Table("com_external_mappings") {
    val productKey = varchar("product_key", 64)
    val channel = varchar("channel", 64) // "shopify", "meta_catalogo"
    val externalId = varchar("external_id", 128)
    val publishedStatus = varchar("published_status", 32)
    val lastSyncedAt = varchar("last_synced_at", 64)

    override val primaryKey = PrimaryKey(productKey, channel)
}

object ComOutboxTable : Table("com_outbox") {
    val eventId = varchar("event_id", 128)
    val eventType = varchar("event_type", 128)
    val payloadJson = text("payload_json")
    val status = varchar("status", 32) // "PENDING", "PROCESSED", "FAILED"
    val createdAt = varchar("created_at", 64)
    val retryCount = integer("retry_count")

    override val primaryKey = PrimaryKey(eventId)
}

/**
 * Esquema relacional PostgreSQL/H2 para el Contexto Mensajería (`msg.*`).
 */
object MsgConversationsTable : Table("msg_conversations") {
    val conversationId = varchar("conversation_id", 64)
    val customerPhone = varchar("customer_phone", 64)
    val channel = varchar("channel", 32)
    val activeProductKey = varchar("active_product_key", 64).nullable()
    val state = varchar("state", 32)
    val historyJson = text("history_json")
    val updatedAt = varchar("updated_at", 64)

    override val primaryKey = PrimaryKey(conversationId)
}
