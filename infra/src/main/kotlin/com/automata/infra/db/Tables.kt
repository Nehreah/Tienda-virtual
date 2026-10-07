package com.automata.infra.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.postgresql.util.PGobject

/**
 * Tablas Exposed del esquema "tienda" (ver db/migracion_fase2.sql).
 * Las columnas que maneja la base (created_at, updated_at) no se declaran: la aplicación no las escribe.
 * Las marcadas databaseGenerated() las calcula un trigger: la aplicación solo las lee.
 */

/** jsonb leído como String (solo lectura; el historial lo escriben los triggers). */
class JsonbTextColumnType : ColumnType() {
    override fun sqlType(): String = "JSONB"
    override fun valueFromDB(value: Any): Any = when (value) {
        is PGobject -> value.value ?: ""
        else -> value.toString()
    }
}

fun Table.jsonbText(name: String): Column<String> = registerColumn<String>(name, JsonbTextColumnType())

/** Idempotencia de webhooks (ver db/migracion_fase3_idempotencia.sql). procesado_en lo pone la base. */
object EventosProcesadosTable : Table("tienda.eventos_procesados") {
    val canal = text("canal")
    val eventoExternoId = text("evento_externo_id")
    val estado = text("estado").default("DONE")
    val actualizadoEn = timestamp("actualizado_en").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())

    override val primaryKey = PrimaryKey(canal, eventoExternoId)
}

object InventarioTable : Table("tienda.inventario") {
    val key = text("key")
    val item = text("item")
    val tipo = text("tipo").nullable()                          // antes tienda.clasificacion.categoria
    val link = text("link").nullable()
    val fotos = text("fotos")                                   // URLs separadas por salto de línea
    val estado = text("estado")
    val fechaCompra = date("fecha_compra").nullable()
    val fechaPublicacion = date("fecha_publicacion").nullable()
    val fechaVenta = date("fecha_venta").nullable()
    val precioPublicado = long("precio_publicado").nullable()
    val precioMinimo = long("precio_minimo").nullable().databaseGenerated()   // trigger: costo_total × 1,5 (hacia arriba)
    val precioCompra = long("precio_compra")
    val vendidoEn = long("vendido_en").nullable()
    val canalVenta = text("canal_venta").nullable()             // antes tienda.canal_venta
    val requiereReparacion = bool("requiere_reparacion")
    val pctAntonio = decimal("pct_antonio", 5, 2)               // antes tienda.inversion
    val pctJavier = decimal("pct_javier", 5, 2)
    val costoTotal = long("costo_total").databaseGenerated()    // antes costo_compra_reparacion; lo mantiene el trigger
    val descripcion = text("descripcion").nullable()
    val marca = text("marca").nullable()
    val condicion = text("condicion").nullable()
    val cantidad = integer("cantidad").default(1)
    val ubicacion = text("ubicacion").nullable()
    val archivadoEn = timestamp("archivado_en").nullable()
    val archivadoPor = text("archivado_por").nullable()
    val motivoArchivo = text("motivo_archivo").nullable()

    override val primaryKey = PrimaryKey(key)
}

object ReparacionesTable : Table("tienda.reparaciones") {
    val key = text("key")
    val precioReparacion = long("precio_reparacion")
    val detalles = text("detalles").nullable()

    override val primaryKey = PrimaryKey(key)
}

/** Solo lectura: la llena el trigger de Inventario. */
object VentasTable : Table("tienda.ventas") {
    val key = text("key")
    val fechaVenta = date("fecha_venta")
    val vendidoEn = long("vendido_en")
    val canal = text("canal").nullable()
    val ingresos = long("ingresos")
    val costoTotal = long("costo_total")
    val gananciaNeta = long("ganancia_neta")
    val margenPct = decimal("margen_pct", 7, 2).nullable()
    val tiempoDias = integer("tiempo_dias").nullable()
    val diasPublicado = integer("dias_publicado").nullable()
    val periodo = text("periodo").nullable()

    override val primaryKey = PrimaryKey(key)
}

/** Solo lectura: la llena el trigger de Inventario. */
object MetricasTable : Table("tienda.metricas") {
    val key = text("key")
    val ingresosAntonio = long("ingresos_antonio")
    val gananciasAntonio = long("ganancias_antonio")
    val ingresosJavier = long("ingresos_javier")
    val gananciasJavier = long("ganancias_javier")

    override val primaryKey = PrimaryKey(key)
}

object HistorialTable : Table("tienda.historial") {
    val id = long("id")
    val tabla = text("tabla")
    val key = text("key")
    val operacion = text("operacion")
    val cambios = jsonbText("cambios")
    val origen = text("origen")
    val usuario = text("usuario").nullable()
    val creadoEn = timestamp("creado_en")

    override val primaryKey = PrimaryKey(id)
}

object ConversacionesTable : Table("tienda.conversaciones") {
    val conversationId = text("conversation_id")
    val customerId = text("customer_id")
    val channel = text("channel")
    val state = text("state")
    val activeProductKey = text("active_product_key").nullable()
    val agreedPriceCop = long("agreed_price_cop").nullable()
    val counterOfferCount = integer("counter_offer_count").default(0)
    val pendingCounterCop = long("pending_counter_cop").nullable()
    val pendingCounterAt = timestamp("pending_counter_at").nullable()
    val currentReservationId = text("current_reservation_id").nullable()
    val mensajes = jsonbText("mensajes").nullable()
    val lastUpdated = timestamp("last_updated")

    override val primaryKey = PrimaryKey(conversationId)
}

object ReservasTable : Table("tienda.reservas") {
    val reservationId = text("reservation_id")
    val conversationId = text("conversation_id")
    val productKey = text("product_key")
    val quantity = integer("quantity").default(1)
    val status = text("status").default("ACTIVE")
    val expiresAt = timestamp("expires_at")
    val orderRef = text("order_ref").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(reservationId)
}

object OutboxTable : Table("tienda.outbox") {
    val eventId = text("event_id")
    val tipo = text("tipo").default("stock_changed")
    val sku = text("sku")
    val channel = text("canal")
    val recipientId = text("recipient_id").nullable()
    val version = long("version").default(1L)
    val stockObjetivo = integer("stock_objetivo").default(0)
    val payloadJson = text("payload_json").nullable()
    val status = text("estado").default("PENDING")
    val attempts = integer("intentos").default(0)
    val proximaEjecucion = timestamp("proxima_ejecucion").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val createdAt = timestamp("creado_en").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val lastAttemptAt = timestamp("actualizado_en").nullable()
    val errorMessage = text("ultimo_error").nullable()

    override val primaryKey = PrimaryKey(eventId)
}

object EventosEntrantesTable : Table("tienda.eventos_entrantes") {
    val id = text("id")
    val canal = text("canal")
    val eventoExternoId = text("evento_externo_id")
    val payload = jsonbText("payload")
    val estado = text("estado").default("RECIBIDO")
    val intentos = integer("intentos").default(0)
    val proximaEjecucion = timestamp("proxima_ejecucion").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val errorMsg = text("error_msg").nullable()
    val creadoEn = timestamp("creado_en").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val procesadoEn = timestamp("procesado_en").nullable()

    override val primaryKey = PrimaryKey(id)
}

object PublicacionesTable : Table("tienda.publicaciones") {
    val sku = text("sku")
    val canal = text("canal")
    val externalId = text("external_id")
    val publicado = bool("publicado").default(true)
    val stockPublicado = integer("stock_publicado").default(0)
    val ultimaSincronizacion = timestamp("ultima_sincronizacion").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())

    override val primaryKey = PrimaryKey(sku, canal)
}

object ImportacionesTable : Table("tienda.importaciones") {
    val id = text("id")
    val usuario = text("usuario")
    val fecha = timestamp("fecha").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val hashArchivo = text("hash_archivo")
    val estado = text("estado").default("PENDING")
    val conteos = jsonbText("conteos").nullable()
    val errores = jsonbText("errores").nullable()

    override val primaryKey = PrimaryKey(id)
}

object EventosSinResolverTable : Table("tienda.eventos_sin_resolver") {
    val id = long("id").autoIncrement()
    val canal = text("canal")
    val tipo = text("tipo")
    val referencia = text("referencia").nullable()
    val sku = text("sku").nullable()
    val motivo = text("motivo")
    val payload = jsonbText("payload")
    val estado = text("estado").default("PENDIENTE")
    val creadoEn = timestamp("creado_en").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp())
    val resueltoEn = timestamp("resuelto_en").nullable()

    override val primaryKey = PrimaryKey(id)
}


