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
    val isEscalatedToHuman: Boolean = false,
    /** true cuando la contraoferta ya no puede bajar más con el bot (se agotó la escalera). */
    val isFinalCounter: Boolean = false
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
 *
 * Contraoferta sin revelar el piso (v5):
 *  - Una oferta >= PM se acepta a la cifra ofrecida (nunca por encima del precio publicado).
 *  - Una oferta < PM NO se rechaza diciendo "nuestro mejor precio es el PM". Se responde con una contraoferta
 *    escalonada (cada intento baja un poco, siempre por encima del PM) y el PM jamás aparece en el texto.
 *  - Los precios de contraoferta se redondean HACIA ARRIBA a miles de pesos: no dejan rastro de la cifra exacta del PM.
 */
class NegotiationEngine {

    /**
     * Evalúa una oferta enviada por un cliente en el chat.
     *
     * @param previousCounterOffers cuántas contraofertas se le han hecho ya al cliente por este producto
     *        (la conversación lo recuerda). Con 0 se hace la primera contraoferta.
     */
    fun evaluateOffer(
        product: CommercialProduct,
        offeredAmount: Money,
        previousCounterOffers: Int = 0
    ): NegotiationDecision {
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

        // 2. Por debajo del PM: contraoferta escalonada que NO revela el piso.
        if (offeredAmount < pm) {
            return counterOffer(pp, pm, previousCounterOffers)
        }

        // 3. Escalera de concesiones (L1 a L4) entre PP y PM: aquí offeredAmount >= PM garantizado.
        val l1Price = calculateLevelPrice(pp, pm, 0.25)
        val l2Price = calculateLevelPrice(pp, pm, 0.50)
        val l3Price = calculateLevelPrice(pp, pm, 0.75)

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
            else -> NegotiationDecision(
                accepted = true,
                level = NegotiationLevel.L4_FLOOR_LIMIT,
                agreedPrice = offeredAmount,
                counterOffer = null,
                // No se dice "es nuestro límite": eso confirmaría que la cifra está pegada al piso.
                message = "Aceptamos tu propuesta de ${offeredAmount.formatFormattedCop()} para cerrar la venta."
            )
        }
    }

    /**
     * Contraoferta: pasos del 25 %, 50 % y 75 % de la distancia PP→PM. El último paso queda SIEMPRE por encima
     * del PM (a 25 % de la distancia), de modo que el cliente nunca recibe la cifra del piso.
     * Agotados los pasos se repite la última contraoferta (marcada como final).
     */
    private fun counterOffer(pp: Money, pm: Money, previousCounterOffers: Int): NegotiationDecision {
        val step = previousCounterOffers.coerceAtLeast(0)
        val isFinal = step >= COUNTER_FACTORS.size
        val factor = COUNTER_FACTORS[step.coerceAtMost(COUNTER_FACTORS.size - 1)]

        val raw = calculateLevelPrice(pp, pm, factor)
        // Redondeo hacia ARRIBA a miles (nunca baja del PM) y tope en el precio publicado.
        val rounded = ((raw.amountInCop + 999) / 1000) * 1000
        // maxOf con el PM: garantía extra por si los datos traen PP < PM (el precio ofrecido jamás baja del PM).
        val price = Money.of(maxOf(pm.amountInCop, minOf(rounded, pp.amountInCop)))
        val formatted = price.formatFormattedCop()

        val text = when {
            isFinal ->
                "Ese es el precio más bajo que puedo ofrecerte por ahora: $formatted. " +
                    "¿Lo tomamos? Si prefieres, puedo comunicarte con un asesor."
            step == 0 ->
                "Gracias por tu propuesta. Esa cifra está un poco baja para nosotros, pero puedo dejártelo en $formatted. ¿Te sirve?"
            step == 1 ->
                "Puedo mejorarlo un poco más: $formatted. ¿Lo tomamos?"
            else ->
                "Hago un último esfuerzo y te lo dejo en $formatted. ¿Te animas?"
        }
        return NegotiationDecision(
            accepted = false,
            level = NegotiationLevel.REJECTED_BELOW_PM,
            agreedPrice = null,
            counterOffer = price,
            message = text,
            isFinalCounter = isFinal
        )
    }

    private fun calculateLevelPrice(pp: Money, pm: Money, factor: Double): Money {
        val diff = pp.amountInCop - pm.amountInCop
        val discount = (diff * factor).toLong()
        val calculated = pp.amountInCop - discount
        return Money.of(max(calculated, pm.amountInCop))
    }

    private companion object {
        /** Fracción de la distancia PP→PM que se concede en cada contraoferta (siempre < 1.0: el PM no se alcanza). */
        val COUNTER_FACTORS = doubleArrayOf(0.25, 0.50, 0.75)
    }
}
