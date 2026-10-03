package com.automata.commerce.service

import com.automata.commerce.model.Periodo
import com.automata.commerce.model.ReparteSocios
import com.automata.shared.Money
import com.automata.shared.SignedCop
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Único lugar donde viven las fórmulas de Métricas. La base guarda datos; no guarda resultados.
 * Todo lo que no se pueda calcular devuelve null (se muestra vacío), nunca un valor inventado.
 */
object FinancialMath {

    /** ROI mínimo por defecto sobre el COSTO TOTAL (tienda.config: roi_minimo_pct = 50). */
    const val ROI_MINIMO_PCT_DEFECTO = 50

    /**
     * Precio mínimo = costo total × (1 + ROI mínimo), redondeado HACIA ARRIBA al peso.
     * Se calcula sobre el costo TOTAL (compra + reparación), no solo sobre la compra: así el ROI real
     * nunca queda por debajo del mínimo en artículos con reparación.
     * Ej.: costo 20.000 -> 30.000 · costo 25.000 -> 37.500 · costo 333 -> 500.
     * Es el espejo de tienda.fn_precio_minimo() en la base.
     */
    fun precioMinimo(costoTotal: Money, roiMinimoPct: Int = ROI_MINIMO_PCT_DEFECTO): Money {
        require(roiMinimoPct >= 0) { "El ROI mínimo no puede ser negativo: $roiMinimoPct" }
        val numerador = costoTotal.amountInCop * (100L + roiMinimoPct)
        return Money.of((numerador + 99) / 100)   // división entera con redondeo hacia arriba (valores >= 0)
    }

    /** Periodo de venta según los días desde la compra: corto < 15, medio 15 a 29, largo 30 o más. */
    fun periodo(dias: Int): Periodo = Periodo.desdeDias(dias.coerceAtLeast(0))

    /** Días desde la PUBLICACIÓN hasta la venta (o hoy si sigue en stock). Vacío sin fecha de publicación. */
    fun diasPublicado(publicacion: LocalDate?, venta: LocalDate?, hoy: LocalDate): Int? =
        publicacion?.let { ChronoUnit.DAYS.between(it, venta ?: hoy).toInt().coerceAtLeast(0) }

    /** Ganancia neta = Vendido en − Costo (Compra + Reparación). Puede ser negativa. */
    fun gananciaNeta(vendidoEn: Money, costo: Money): SignedCop =
        SignedCop(vendidoEn.amountInCop - costo.amountInCop)

    /** ROI = ganancia / costo. Vacío si el costo es 0. */
    fun roi(ganancia: SignedCop, costo: Money): Double? =
        if (costo.amountInCop == 0L) null else ganancia.amount.toDouble() / costo.amountInCop

    /** Margen = ganancia / vendido en. Vacío si "Vendido en" es 0. */
    fun margen(ganancia: SignedCop, vendidoEn: Money): Double? =
        if (vendidoEn.amountInCop == 0L) null else ganancia.amount.toDouble() / vendidoEn.amountInCop

    /** Días desde la COMPRA = (fecha de venta, o hoy si sigue en stock) − fecha de compra. Vacío sin fecha de compra. */
    fun dias(compra: LocalDate?, venta: LocalDate?, hoy: LocalDate): Int? =
        compra?.let { ChronoUnit.DAYS.between(it, venta ?: hoy).toInt().coerceAtLeast(0) }

    /** Ganancia por día = ganancia / días, con un mínimo de 1 día (venta el mismo día cuenta como 1). */
    fun gananciaPorDia(ganancia: SignedCop, dias: Int): SignedCop =
        SignedCop(Math.round(ganancia.amount.toDouble() / dias.coerceAtLeast(1)))

    /**
     * Texto legible de los días (columna "Tiempo"). Convención: 1 mes = 30 días.
     * Ej.: 0 -> "0 días", 1 -> "1 día", 20 -> "20 días", 63 -> "2 meses 3 días".
     */
    fun tiempoLegible(dias: Int): String {
        val d = dias.coerceAtLeast(0)
        val meses = d / 30
        val resto = d % 30
        fun dia(n: Int) = if (n == 1) "1 día" else "$n días"
        fun mes(n: Int) = if (n == 1) "1 mes" else "$n meses"
        return when {
            meses == 0 -> dia(resto)
            resto == 0 -> mes(meses)
            else -> "${mes(meses)} ${dia(resto)}"
        }
    }

    /**
     * Reparte [valor] entre Antonio y Javier según sus porcentajes de inversión.
     * Antonio = valor × pctAntonio / (pctAntonio + pctJavier), redondeado al peso;
     * Javier = valor − Antonio, así la suma siempre cuadra exacto.
     * Devuelve null si aún no hay reparto definido (ambos porcentajes en 0).
     *
     * Sirve igual para el Retorno (valor = Vendido en) y para la Ganancia (valor = ganancia neta, puede ser negativa).
     */
    fun repartir(valor: Long, pctAntonio: BigDecimal, pctJavier: BigDecimal): Pair<Long, Long>? {
        val total = pctAntonio + pctJavier
        if (total.signum() <= 0) return null
        val antonio = BigDecimal.valueOf(valor)
            .multiply(pctAntonio)
            .divide(total, 0, RoundingMode.HALF_UP)
            .toLong()
        return antonio to (valor - antonio)
    }

    fun repartir(valor: Long, reparte: ReparteSocios): Pair<Long, Long>? =
        repartir(valor, reparte.pctAntonio, reparte.pctJavier)
}
