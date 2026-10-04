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
