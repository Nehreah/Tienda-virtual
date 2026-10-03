package com.automata.commerce.model

import com.automata.commerce.service.FinancialMath
import java.math.BigDecimal

enum class Socio { ANTONIO, JAVIER }

/**
 * Reparto de la inversión entre socios. Vive en las columnas pct_antonio / pct_javier de
 * tienda.inventario (reemplaza a la antigua tabla tienda.inversion).
 *
 * Regla: ambos en 0 (aún sin repartir) o la suma exactamente 100.
 * Para poder VENDER un producto el reparto debe estar definido (suma 100).
 */
data class ReparteSocios(
    val pctAntonio: BigDecimal = BigDecimal.ZERO,
    val pctJavier: BigDecimal = BigDecimal.ZERO
) {
    init {
        val cien = BigDecimal(100)
        require(pctAntonio.signum() >= 0 && pctAntonio <= cien) { "El % de Antonio debe estar entre 0 y 100" }
        require(pctJavier.signum() >= 0 && pctJavier <= cien) { "El % de Javier debe estar entre 0 y 100" }
        val suma = pctAntonio + pctJavier
        require(suma.signum() == 0 || suma.compareTo(cien) == 0) {
            "Los porcentajes de Antonio y Javier deben sumar 100 (o ambos 0); suman $suma"
        }
    }

    /** true si ya se definió el reparto entre socios. */
    val definida: Boolean get() = (pctAntonio + pctJavier).signum() > 0

    fun porcentaje(socio: Socio): BigDecimal = if (socio == Socio.ANTONIO) pctAntonio else pctJavier

    /** (Antonio, Javier) con la suma exacta; null si el reparto no está definido. */
    fun repartir(valor: Long): Pair<Long, Long>? = FinancialMath.repartir(valor, pctAntonio, pctJavier)

    companion object {
        val SIN_DEFINIR = ReparteSocios()
    }
}
