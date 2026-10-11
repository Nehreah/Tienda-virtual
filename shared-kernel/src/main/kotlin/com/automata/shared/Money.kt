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

    /**
     * Resta montos. Money nunca es negativo: si [other] es mayor que este monto lanza IllegalArgumentException con un
     * mensaje claro. Si el resultado PUEDE ser negativo (ganancia, pérdida) usa [signedDiff].
     */
    operator fun minus(other: Money): Money {
        require(amountInCop >= other.amountInCop) {
            "No se puede restar ${other.formatCop()} de ${formatCop()}: Money no admite resultados negativos (usa signedDiff)."
        }
        return Money(amountInCop - other.amountInCop)
    }

    /** Diferencia CON signo (este monto menos [other]); puede ser negativa. */
    fun signedDiff(other: Money): SignedCop = SignedCop(amountInCop - other.amountInCop)

    /** "$1.234.567" (separador de miles con punto, igual que SignedCop.format). */
    fun formatCop(): String = "$${String.format(java.util.Locale.US, "%,d", amountInCop).replace(',', '.')}"

    @Deprecated("Nombre redundante; usa formatCop().", ReplaceWith("formatCop()"))
    fun formatFormattedCop(): String = formatCop()

    companion object {
        val ZERO = Money(0)
        fun of(cop: Long): Money = Money(cop)
    }
}
