package com.automata.commerce.model

import com.automata.shared.ProductKey
import java.math.BigDecimal

enum class Socio { ANTONIO, JAVIER }

/**
 * Capa TABLA INVERSIÓN (tienda.inversion, 1 a 1 con Inventario).
 * La inversión de cada socio es un PORCENTAJE (0 a 100) del producto.
 * Regla: ambos en 0 (aún sin repartir) o la suma exactamente 100.
 */
data class Inversion(
    val key: ProductKey,
    val pctAntonio: BigDecimal = BigDecimal.ZERO,
    val pctJavier: BigDecimal = BigDecimal.ZERO
) {
    init {
        val cien = BigDecimal(100)
        require(pctAntonio.signum() >= 0 && pctAntonio <= cien) { "El % de Antonio debe estar entre 0 y 100 ($key)" }
        require(pctJavier.signum() >= 0 && pctJavier <= cien) { "El % de Javier debe estar entre 0 y 100 ($key)" }
        val suma = pctAntonio + pctJavier
        require(suma.signum() == 0 || suma.compareTo(cien) == 0) {
            "Los porcentajes de Antonio y Javier deben sumar 100 (o ambos 0) en $key; suman $suma"
        }
    }

    /** true si ya se definió el reparto entre socios. */
    val definida: Boolean get() = (pctAntonio + pctJavier).signum() > 0

    fun porcentaje(socio: Socio): BigDecimal = if (socio == Socio.ANTONIO) pctAntonio else pctJavier
}
