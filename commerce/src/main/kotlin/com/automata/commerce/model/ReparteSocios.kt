package com.automata.commerce.model

import com.automata.commerce.service.FinancialMath
import java.math.BigDecimal
import java.time.Instant

/**
 * Entidad Socio dinámico.
 */
data class Socio(
    val id: Long,
    val nombre: String,
    val activo: Boolean = true,
    val archivadoEn: Instant? = null
)

/**
 * Reparto de la inversión entre socios dinámicos (tienda.inventario_inversion).
 *
 * Reglas:
 *  - Cada porcentaje individual está entre 0 y 100.
 *  - La suma de porcentajes no puede superar 100 %.
 *  - Para VENDER un artículo el reparto debe ser exactamente 100 % con al menos 1 socio.
 */
data class ReparteSocios(
    val porSocio: Map<Long, BigDecimal> = emptyMap()
) {
    val suma: BigDecimal
    val sinAsignar: BigDecimal
    val definida: Boolean

    init {
        val cien = BigDecimal(100)
        porSocio.forEach { (socioId, pct) ->
            require(pct.signum() >= 0 && pct <= cien) {
                "El % del socio $socioId debe estar entre 0 y 100 (recibido: $pct)"
            }
        }
        val total = porSocio.values.fold(BigDecimal.ZERO) { acc, b -> acc + b }
        require(total <= cien) {
            "La suma de porcentajes de inversión no puede superar 100 % (suman $total %)"
        }
        suma = total
        sinAsignar = (cien - total).max(BigDecimal.ZERO)
        definida = total.compareTo(cien) == 0 && porSocio.isNotEmpty()
    }

    fun porcentaje(socioId: Long): BigDecimal = porSocio[socioId] ?: BigDecimal.ZERO

    /** Reparte [valor] entre los socios según la proporción asignada (método del mayor residuo). */
    fun repartir(valor: Long): Map<Long, Long>? = FinancialMath.repartir(valor, porSocio)

    companion object {
        val SIN_DEFINIR = ReparteSocios()
    }
}
