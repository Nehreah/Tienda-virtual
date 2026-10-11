package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.SignedCop
import java.time.Instant
import java.time.LocalDate

/**
 * Pago que el negocio le entrega a un socio (tienda.liquidacion). No se edita ni se borra: se anula con motivo.
 */
data class Liquidacion(
    val id: Long,
    val socioId: Long,
    val socioNombre: String,
    val fecha: LocalDate,
    val monto: Money,
    val nota: String? = null,
    val creadoPor: String? = null,
    val creadoEn: Instant? = null,
    val anuladaEn: Instant? = null,
    val anuladaMotivo: String? = null
) {
    val vigente: Boolean get() = anuladaEn == null
}

/**
 * Estado de cuenta de un socio (vista tienda.v_socio_cuenta).
 *
 *  - [aporteEnStock]: capital del socio en artículos aún no vendidos (costo por unidad x unidades x su %).
 *  - [aporteVendido]: capital que le tocó en ventas vigentes.
 *  - [ingresos]: lo que le correspondió de las ventas vigentes (capital recuperado + ganancia).
 *  - [ganancia]: parte de [ingresos] que es ganancia (puede ser negativa).
 *  - [liquidado]: suma de liquidaciones vigentes ya pagadas.
 *  - [saldoPorLiquidar] = [ingresos] - [liquidado]; negativo si se anuló una venta ya liquidada.
 */
data class CuentaSocio(
    val socioId: Long,
    val nombre: String,
    val activo: Boolean,
    val aporteEnStock: SignedCop,
    val aporteVendido: SignedCop,
    val ingresos: SignedCop,
    val ganancia: SignedCop,
    val ventas: Int,
    val liquidado: SignedCop,
    val liquidaciones: Int,
    val saldoPorLiquidar: SignedCop
) {
    /** Capital total aportado: lo que sigue en stock más lo ya vendido. */
    val aporteTotal: SignedCop get() = aporteEnStock + aporteVendido
}
