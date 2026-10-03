package com.automata.shared

import kotlinx.serialization.Serializable

/**
 * Value object que representa montos monetarios en pesos colombianos (COP).
 * Se almacena en Long (unidad mínima en pesos sin decimales) para evitar imprecisiones de coma flotante.
 */
@Serializable
data class Money(val amountInCop: Long) : Comparable<Money> {
    init {
        require(amountInCop >= 0) { "El monto monetario no puede ser negativo: $amountInCop" }
    }

    override fun compareTo(other: Money): Int = amountInCop.compareTo(other.amountInCop)

    operator fun plus(other: Money): Money = Money(amountInCop + other.amountInCop)
    operator fun minus(other: Money): Money = Money(amountInCop - other.amountInCop)
    
    fun formatFormattedCop(): String = "$${String.format(java.util.Locale.US, "%,d", amountInCop).replace(',', '.')}"

    companion object {
        val ZERO = Money(0)
        fun of(cop: Long): Money = Money(cop)
    }
}
