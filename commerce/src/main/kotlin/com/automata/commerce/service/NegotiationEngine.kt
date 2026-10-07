package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import kotlin.math.max
import kotlin.random.Random

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
    REJECTED_BELOW_PM,
    ESCALATED_HUMAN
}

/**
 * Motor Comercial de Negociación.
 * Garantiza que NUNCA se acepte ni se ofrezca un precio neto por debajo del PM.
 *
 * Protecciones anti-filtrado del PM (Fase 3):
 *  3.1 Variación aleatoria ±5% en cada contraoferta (semilla inyectable para tests).
 *  3.2 Límite de MAX_COUNTER_OFFERS contraofertas; al superarlo escala a humano.
 *  3.3 L3/L4 NUNCA aceptan al valor exacto ofrecido; siempre responden con contraoferta.
 *  3.4 isFinalCounter se marca cuando se agota la escalera (el pipeline puede expirar).
 */
class NegotiationEngine(
    /** Inyectable para tests deterministas. Por defecto usa el generador global. */
    private val random: Random = Random.Default
) {

    /**
     * Evalúa una oferta enviada por un cliente en el chat.
     *
     * @param previousCounterOffers cuántas contraofertas se le han hecho ya al cliente por este producto.
     */
    fun evaluateOffer(
        product: CommercialProduct,
        offeredAmount: Money,
        previousCounterOffers: Int = 0
    ): NegotiationDecision {
        val pp = product.publishedPrice
        val pm = product.minimumPrice

        // Fase 3.2: si ya se superó el límite de contraofertas, escalar a humano.
        if (previousCounterOffers >= MAX_COUNTER_OFFERS) {
            return NegotiationDecision(
                accepted = false,
                level    = NegotiationLevel.ESCALATED_HUMAN,
                agreedPrice  = null,
                counterOffer = null,
                message = "Hemos llegado al límite de lo que puedo ofrecerte. " +
                          "Un asesor se pondrá en contacto contigo para encontrar la mejor solución.",
                isEscalatedToHuman = true,
                isFinalCounter = true
            )
        }

        // L0: oferta >= precio publicado
        if (offeredAmount >= pp) {
            return NegotiationDecision(
                accepted     = true,
                level        = NegotiationLevel.L0_PUBLISHED_PRICE,
                agreedPrice  = pp,
                counterOffer = null,
                message = "¡Excelente! Aceptamos tu oferta de ${pp.formatFormattedCop()}. Podemos proceder con el pago."
            )
        }

        // Si PM >= PP no es posible otorgar descuentos (Hallazgo A13)
        if (pm >= pp) {
            if (offeredAmount >= pp) {
                return NegotiationDecision(
                    accepted     = true,
                    level        = NegotiationLevel.L0_PUBLISHED_PRICE,
                    agreedPrice  = pp,
                    counterOffer = null,
                    message = "¡Excelente! Aceptamos tu oferta de ${pp.formatFormattedCop()}. Podemos proceder con el pago."
                )
            } else {
                return NegotiationDecision(
                    accepted     = false,
                    level        = NegotiationLevel.REJECTED_BELOW_PM,
                    agreedPrice  = null,
                    counterOffer = pp,
                    message      = "El precio de este producto es de ${pp.formatFormattedCop()} y no tiene margen de descuento.",
                    isFinalCounter = true
                )
            }
        }

        // Por debajo del PM: contraoferta escalonada sin revelar el piso.
        if (offeredAmount < pm) {
            return counterOffer(pp, pm, previousCounterOffers)
        }

        // Entre PP y PM: calcular niveles.
        val l1Price = calculateLevelPrice(pp, pm, 0.25)
        val l2Price = calculateLevelPrice(pp, pm, 0.50)
        val l3Price = calculateLevelPrice(pp, pm, 0.75)

        return when {
            offeredAmount >= l1Price -> NegotiationDecision(
                accepted     = true,
                level        = NegotiationLevel.L1_LIGHT_DISCOUNT,
                agreedPrice  = offeredAmount,
                counterOffer = null,
                message = "¡Trato hecho! Aceptamos tu propuesta por ${offeredAmount.formatFormattedCop()}."
            )
            offeredAmount >= l2Price -> NegotiationDecision(
                accepted     = true,
                level        = NegotiationLevel.L2_MODERATE_DISCOUNT,
                agreedPrice  = offeredAmount,
                counterOffer = null,
                message = "Aceptamos tu oferta especial de ${offeredAmount.formatFormattedCop()} por hoy."
            )
            // Fase 3.3: L3 y L4 NUNCA aceptan al valor exacto; responden siempre con contraoferta
            // para que el cliente no pueda deducir el PM haciendo ofertas muy cercanas.
            offeredAmount >= l3Price -> {
                val counter = addRandomVariation(l2Price, pm, pp)
                NegotiationDecision(
                    accepted     = false,
                    level        = NegotiationLevel.L3_HIGH_DISCOUNT,
                    agreedPrice  = null,
                    counterOffer = counter,
                    message = "Muy buen intento, pero no puedo dejarlo en eso. " +
                              "Te propongo ${counter.formatFormattedCop()} para cerrar. ¿Lo tomamos?"
                )
            }
            else -> {
                val counter = addRandomVariation(l3Price, pm, pp)
                NegotiationDecision(
                    accepted     = false,
                    level        = NegotiationLevel.L4_FLOOR_LIMIT,
                    agreedPrice  = null,
                    counterOffer = counter,
                    message = "Ese precio no me es posible, pero te puedo ofrecer " +
                              "${counter.formatFormattedCop()} para cerrar hoy."
                )
            }
        }
    }

    /**
     * Contraoferta escalonada: cada paso baja un poco más pero SIEMPRE queda por encima del PM.
     * Fase 3.1: se añade variación aleatoria ±5% a cada paso para dificultar la deducción del PM.
     */
    private fun counterOffer(pp: Money, pm: Money, previousCounterOffers: Int): NegotiationDecision {
        val step = previousCounterOffers.coerceAtLeast(0)
        val isFinal = step >= COUNTER_FACTORS.size
        val factor = COUNTER_FACTORS[step.coerceAtMost(COUNTER_FACTORS.size - 1)]

        val raw = calculateLevelPrice(pp, pm, factor)
        val price = addRandomVariation(raw, pm, pp)
        val formatted = price.formatFormattedCop()

        val text = when {
            isFinal -> "Ese es el precio más bajo que puedo ofrecerte por ahora: $formatted. " +
                       "¿Lo tomamos? Si prefieres, puedo comunicarte con un asesor."
            step == 0 -> "Gracias por tu propuesta. Esa cifra está un poco baja para nosotros, " +
                         "pero puedo dejártelo en $formatted. ¿Te sirve?"
            step == 1 -> "Puedo mejorarlo un poco más: $formatted. ¿Lo tomamos?"
            else -> "Hago un último esfuerzo y te lo dejo en $formatted. ¿Te animas?"
        }
        return NegotiationDecision(
            accepted     = false,
            level        = NegotiationLevel.REJECTED_BELOW_PM,
            agreedPrice  = null,
            counterOffer = price,
            message      = text,
            isFinalCounter = isFinal
        )
    }

    /**
     * Fase 3.1: variación aleatoria ±5% de la distancia entre `raw` y el PM.
     * El resultado siempre está en [pm+1, pp] (no revela el PM exacto).
     */
    private fun addRandomVariation(raw: Money, pm: Money, pp: Money): Money {
        if (pm >= pp) return pp
        val spread = raw.amountInCop - pm.amountInCop
        val jitter = if (spread > 0) (random.nextDouble() * 0.10 - 0.05) * spread else 0.0
        val adjusted = raw.amountInCop + jitter.toLong()
        // Redondeo HACIA ARRIBA a miles: nunca revela la cifra exacta del PM.
        val rounded = ((adjusted + 999) / 1000) * 1000
        val clamped = maxOf(pm.amountInCop + 1, minOf(rounded, pp.amountInCop))
        return Money.of(minOf(clamped, pp.amountInCop))
    }

    private fun calculateLevelPrice(pp: Money, pm: Money, factor: Double): Money {
        val diff = pp.amountInCop - pm.amountInCop
        val discount = (diff * factor).toLong()
        val calculated = pp.amountInCop - discount
        return Money.of(max(calculated, pm.amountInCop))
    }

    private companion object {
        /** Fracción de la distancia PP→PM concedida en cada contraoferta (siempre < 1.0). */
        val COUNTER_FACTORS = doubleArrayOf(0.25, 0.50, 0.75)

        /** Fase 3.2: tras este número de contraofertas se escala a humano. */
        const val MAX_COUNTER_OFFERS = 4
    }
}

