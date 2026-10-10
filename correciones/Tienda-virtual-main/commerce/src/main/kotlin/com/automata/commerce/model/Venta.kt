package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.SignedCop
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Venta ya calculada (tienda.ventas). SOLO LECTURA: la base la crea y la actualiza sola.
 */
data class Venta(
    val ventaId: Long? = null,
    val key: ProductKey,
    val cantidad: Int = 1,
    val precioUnitario: Money? = null,
    val costoUnitario: Money? = null,
    val fechaVenta: LocalDate,
    val vendidoEn: Money,
    val canal: String?,
    val origen: String? = null,
    val dealId: String? = null,
    val orderId: String? = null,
    val lineId: String? = null,
    val eventId: String? = null,
    val reservaId: String? = null,
    val clienteId: String? = null,
    val ingresos: Money,
    val costoTotal: Money,          // copia congelada del costo al momento de vender
    val gananciaNeta: SignedCop,    // puede ser negativa (pérdida)
    val margenPct: Double?,         // vacío si vendidoEn = 0
    val tiempoDias: Int?,
    val diasPublicado: Int?,
    val periodo: Periodo?,
    val precioPublicado: Money? = null,
    val anuladaEn: Instant? = null,
    val anuladaMotivo: String? = null
)

/** Ingresos, inversión y ganancias de cada socio por venta (tienda.venta_reparto). */
data class VentaReparto(
    val ventaId: Long? = null,
    val key: ProductKey,
    val socioId: Long,
    val pct: BigDecimal,
    val inversion: Long,
    val ingresos: Long,
    val ganancia: Long
)
