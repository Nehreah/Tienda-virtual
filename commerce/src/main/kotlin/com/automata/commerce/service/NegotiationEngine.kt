package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import kotlin.math.max

/**
 * Resultado de una evaluación de negociación.
 */
data class NegotiationDecision(
    val accepted: Boolean,
    val level: NegotiationLevel,
    val agreedPrice: Money?,
    val counterOffer: Money?,
    val message: String,
    val isEscalatedToHuman: Boolean = false
)

enum class NegotiationLevel {
    L0_PUBLISHED_PRICE,
    L1_LIGHT_DISCOUNT,
    L2_MODERATE_DISCOUNT,
    L3_HIGH_DISCOUNT,
    L4_FLOOR_LIMIT,
    REJECTED_BELOW_PM
}

/**
 * Motor Comercial de Negociación (Plan Maestro v5.0 / v5.3 Secciones 06 y 09).
 * Garantiza con invarianza absoluta que NUNCA se acepte ni se ofrezca un precio neto por debajo del PM.
 */
class NegotiationEngine {

    /**
     * Evalúa una oferta enviada por un cliente en el chat.
     */
    fun evaluateOffer(product: CommercialProduct, offeredAmount: Money): NegotiationDecision {
        val pp = product.publishedPrice
        val pm = product.minimumPrice

        // 1. Si la oferta es mayor o igual al precio publicado (L0)
        if (offeredAmount >= pp) {
            return NegotiationDecision(
                accepted = true,
                level = NegotiationLevel.L0_PUBLISHED_PRICE,
                agreedPrice = pp,
                counterOffer = null,
                message = "¡Excelente! Aceptamos tu oferta de ${pp.formatFormattedCop()}. Podemos proceder con el pago."
            )
        }

        // 2. Si la oferta está por debajo del Precio Mínimo (PM), RECHAZO AUTOMÁTICO o contraoferta L4.
        if (offeredAmount < pm) {
            val l4Price = calculateL4Price(pp, pm)
            return NegotiationDecision(
                accepted = false,
                level = NegotiationLevel.REJECTED_BELOW_PM,
                agreedPrice = null,
                counterOffer = l4Price,
                message = "Lo sentimos, no podemos aceptar esa cantidad. Nuestro mejor precio disponible es ${l4Price.formatFormattedCop()}."
            )
        }

        // 3. Escalera de concesiones (L1 a L4) entre PP y PM
        val l1Price = calculateLevelPrice(pp, pm, 0.25)
        val l2Price = calculateLevelPrice(pp, pm, 0.50)
        val l3Price = calculateLevelPrice(pp, pm, 0.75)
        val l4Price = calculateL4Price(pp, pm)

        return when {
            offeredAmount >= l1Price -> NegotiationDecision(
                accepted = true,
                level = NegotiationLevel.L1_LIGHT_DISCOUNT,
                agreedPrice = offeredAmount,
                counterOffer = null,
                message = "¡Trato hecho! Aceptamos tu propuesta por ${offeredAmount.formatFormattedCop()}."
            )
            offeredAmount >= l2Price -> NegotiationDecision(
                accepted = true,
                level = NegotiationLevel.L2_MODERATE_DISCOUNT,
                agreedPrice = offeredAmount,
                counterOffer = null,
                message = "Aceptamos tu oferta especial de ${offeredAmount.formatFormattedCop()} por hoy."
            )
            offeredAmount >= l3Price -> NegotiationDecision(
                accepted = true,
                level = NegotiationLevel.L3_HIGH_DISCOUNT,
                agreedPrice = offeredAmount,
                counterOffer = null,
                message = "Es un gran descuento, pero aceptamos los ${offeredAmount.formatFormattedCop()} para cerrar la venta ya."
            )
            offeredAmount >= l4Price -> NegotiationDecision(
                accepted = true,
                level = NegotiationLevel.L4_FLOOR_LIMIT,
                agreedPrice = offeredAmount,
                counterOffer = null,
                message = "Confirmado, tu propuesta de ${offeredAmount.formatFormattedCop()} es nuestro límite pero la aceptamos."
            )
            else -> NegotiationDecision(
                accepted = false,
                level = NegotiationLevel.REJECTED_BELOW_PM,
                agreedPrice = null,
                counterOffer = l4Price,
                message = "No alcanzamos ese valor. Te podemos dejar el producto en ${l4Price.formatFormattedCop()}."
            )
        }
    }

    private fun calculateLevelPrice(pp: Money, pm: Money, factor: Double): Money {
        val diff = pp.amountInCop - pm.amountInCop
        val discount = (diff * factor).toLong()
        val calculated = pp.amountInCop - discount
        return Money.of(max(calculated, pm.amountInCop))
    }

    private fun calculateL4Price(pp: Money, pm: Money): Money {
        // L4 es el piso comercial que nunca cae por debajo de PM
        return pm
    }
}
