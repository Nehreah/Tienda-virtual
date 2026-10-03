package com.automata.infra.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.postgresql.util.PGobject

/**
 * Tablas Exposed del esquema "tienda" (ver schema.sql).
 * Las columnas que maneja la base (created_at, updated_at) no se declaran: la aplicación no las escribe.
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

object InventarioTable : Table("tienda.inventario") {
    val key = text("key")
    val item = text("item")
    val link = text("link").nullable()
    val fotos = text("fotos")                       // URLs separadas por salto de línea
    val estado = text("estado")
    val fechaCompra = date("fecha_compra").nullable()
    val fechaPublicacion = date("fecha_publicacion").nullable()
    val fechaVenta = date("fecha_venta").nullable()
    val precioPublicado = long("precio_publicado").nullable()
    val precioMinimo = long("precio_minimo").nullable()
    val precioCompra = long("precio_compra")
    val vendidoEn = long("vendido_en").nullable()
    val costoCompraReparacion = long("costo_compra_reparacion").databaseGenerated()   // lo mantiene el trigger
    val descripcion = text("descripcion").nullable()

    override val primaryKey = PrimaryKey(key)
}

object RevisionTable : Table("tienda.revision") {
    val key = text("key")
    val reparacion = long("reparacion")
    val detalles = text("detalles").nullable()

    override val primaryKey = PrimaryKey(key)
}

object InversionTable : Table("tienda.inversion") {
    val key = text("key")
    val pctAntonio = decimal("pct_antonio", 5, 2)
    val pctJavier = decimal("pct_javier", 5, 2)

    override val primaryKey = PrimaryKey(key)
}

object ClasificacionTable : Table("tienda.clasificacion") {
    val key = text("key")
    val categoria = text("categoria")

    override val primaryKey = PrimaryKey(key)
}

object CanalVentaTable : Table("tienda.canal_venta") {
    val key = text("key")
    val canal = text("canal")

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
