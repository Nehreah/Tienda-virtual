package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.SignedCop
import java.time.LocalDate

/**
 * Venta ya calculada (tienda.ventas). SOLO LECTURA: la base la crea y la actualiza sola cuando
 * el artículo pasa a estado Vendido (o cuando editas fecha_venta / vendido_en / canal_venta de uno
 * ya vendido) y la borra si el artículo deja de estar Vendido. Ver db/migracion_fase2.sql.
 *
 * tiempoDias   = días desde la COMPRA hasta la venta (vacío si no hay fecha de compra)
 * diasPublicado = días desde la PUBLICACIÓN hasta la venta (vacío si no hubo fecha de publicación)
 */
data class Venta(
    val key: ProductKey,
    val fechaVenta: LocalDate,
    val vendidoEn: Money,
    val canal: String?,
    val ingresos: Money,
    val costoTotal: Money,          // copia congelada del costo al momento de vender
    val gananciaNeta: SignedCop,    // puede ser negativa (pérdida)
    val margenPct: Double?,         // vacío si vendidoEn = 0
    val tiempoDias: Int?,
    val diasPublicado: Int?,
    val periodo: Periodo?
)

/** Ingresos y ganancias de cada socio por venta (tienda.metricas, 1 a 1 con ventas). */
data class Metrica(
    val key: ProductKey,
    val ingresosAntonio: Money,
    val gananciasAntonio: SignedCop,
    val ingresosJavier: Money,
    val gananciasJavier: SignedCop
)
