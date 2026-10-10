package com.automata.shared

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Valor monetario en pesos colombianos (COP) CON SIGNO.
 *
 * Money (>= 0) sigue siendo el tipo para precios y costos. SignedCop es solo para
 * resultados que pueden ser negativos: ganancia neta, ganancia por día, ganancia por socio.
 */
@Serializable
data class SignedCop(val amount: Long) : Comparable<SignedCop> {

    override fun compareTo(other: SignedCop): Int = amount.compareTo(other.amount)

    operator fun plus(other: SignedCop): SignedCop = SignedCop(amount + other.amount)
    operator fun minus(other: SignedCop): SignedCop = SignedCop(amount - other.amount)
    operator fun unaryMinus(): SignedCop = SignedCop(-amount)

    val isNegative: Boolean get() = amount < 0

    /** "$1.234.567" o "-$1.234.567" (separador de miles con punto, igual que Money). */
    fun format(): String {
        val digits = "%,d".format(Locale.US, amount.absoluteValue).replace(',', '.')
        return if (amount < 0) "-\$$digits" else "\$$digits"
    }

    companion object {
        val ZERO = SignedCop(0)
        fun of(cop: Long): SignedCop = SignedCop(cop)
        fun from(money: Money): SignedCop = SignedCop(money.amountInCop)
    }
}
