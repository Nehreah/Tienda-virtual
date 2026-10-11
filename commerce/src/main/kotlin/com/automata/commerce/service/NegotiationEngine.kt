package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import java.security.SecureRandom
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
    REJECTED_BELOW_PM,
    ESCALATED_HUMAN
}

/**
 * Motor Comercial de Negociación.
 * Garantiza que NUNCA se acepte ni se ofrezca un precio neto por debajo del PM.
 *
 * Protecciones anti-filtrado del PM:
 *  - Contraofertas basadas en tramos de descuento sobre el Precio Publicado (PP) y redondeadas a miles.
 *  - El PM no se usa para calcular fracciones de distancia, evitando que el cliente despeje el piso.
 *  - Uso de SecureRandom y variaciones estables por cliente/conversación para evitar promediados.
 *  - Límite de MAX_COUNTER_OFFERS contraofertas; al superarlo escala a humano.
 */
class NegotiationEngine(
    private val random: java.util.Random = SecureRandom()
) {

    /**
     * Evalúa una oferta enviada por un cliente en el chat.
     *
     * @param previousCounterOffers cuántas contraofertas se le han hecho ya al cliente por este producto.
     * @param customerId identificador opcional del cliente/conversación para estabilizar el jitter.
     */
    fun evaluateOffer(
        product: CommercialProduct,
        offeredAmount: Money,
        previousCounterOffers: Int = 0,
        customerId: String = ""
    ): NegotiationDecision {
        val pp = product.publishedPrice
        val pm = product.minimumPrice

        // Límite de contraofertas -> Escalar a humano
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

        // Si PM >= PP no es posible otorgar descuentos.
        if (pm >= pp) {
            return NegotiationDecision(
                accepted     = false,
                level        = NegotiationLevel.REJECTED_BELOW_PM,
                agreedPrice  = null,
                counterOffer = pp,
                message      = "El precio de este producto es de ${pp.formatFormattedCop()} y no tiene margen de descuento.",
                isFinalCounter = true
            )
        }

        // Tramos de descuento relativos al Precio Publicado (PP)
        val l1Price = calculateDiscountPrice(pp, pm, 0.05) // 5% dto sobre PP
        val l2Price = calculateDiscountPrice(pp, pm, 0.10) // 10% dto sobre PP

        if (offeredAmount >= l1Price) {
            return NegotiationDecision(
                accepted     = true,
                level        = NegotiationLevel.L1_LIGHT_DISCOUNT,
                agreedPrice  = offeredAmount,
                counterOffer = null,
                message = "¡Trato hecho! Aceptamos tu propuesta por ${offeredAmount.formatFormattedCop()}."
            )
        }

        if (offeredAmount >= l2Price) {
            return NegotiationDecision(
                accepted     = true,
                level        = NegotiationLevel.L2_MODERATE_DISCOUNT,
                agreedPrice  = offeredAmount,
                counterOffer = null,
                message = "Aceptamos tu oferta especial de ${offeredAmount.formatFormattedCop()} por hoy."
            )
        }

        // Cualquier oferta por debajo del umbral de aceptación (sea < PM o < L2) recibe la contraoferta del paso
        val counter = counterOffer(product, previousCounterOffers, customerId)

        // Nunca contraofertar POR DEBAJO de lo que el cliente ya ofreció (v14): en los pasos avanzados (15 % de
        // descuento, o el jitter de ±3 % en el paso 1) la contraoferta puede quedar más barata que la oferta del
        // cliente. Como la contraoferta ya es >= PM (la fija calculateDiscountPrice/applyJitter), si el cliente
        // ofreció igual o más, se acepta su oferta en vez de regalarle margen. La comprobación contra PM es
        // redundante a propósito: el invariante "nunca aceptar bajo el PM" no debe depender de otra función.
        val counterPrice = counter.counterOffer
        if (counterPrice != null && offeredAmount >= counterPrice && offeredAmount >= pm) {
            return NegotiationDecision(
                accepted     = true,
                level        = NegotiationLevel.L2_MODERATE_DISCOUNT,
                agreedPrice  = offeredAmount,
                counterOffer = null,
                message = "Aceptamos tu oferta especial de ${offeredAmount.formatFormattedCop()} por hoy."
            )
        }
        return counter
    }

    private fun counterOffer(product: CommercialProduct, previousCounterOffers: Int, customerId: String): NegotiationDecision {
        val pp = product.publishedPrice
        val pm = product.minimumPrice
        val step = previousCounterOffers.coerceAtLeast(0)
        val isFinal = step >= DISCOUNTS_PP.size - 1
        val discountPercent = DISCOUNTS_PP[step.coerceAtMost(DISCOUNTS_PP.size - 1)]

        val raw = calculateDiscountPrice(pp, pm, discountPercent)
        val price = applyJitter(raw, pm, pp, customerId, step)
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
     * Aplica variación estable por conversación/cliente (jitter determinista si hay customerId)
     * o aleatoria con SecureRandom. El resultado se redondea a miles de COP y nunca es menor a PM.
     */
    private fun applyJitter(raw: Money, pm: Money, pp: Money, customerId: String, step: Int): Money {
        if (pm >= pp) return pp
        val baseAmount = raw.amountInCop
        val maxJitter = (baseAmount * 0.03).toLong() // ±3% sobre el precio de la contraoferta
        val seed = if (customerId.isNotBlank()) (customerId + step).hashCode().toLong() else random.nextLong()
        val range = maxJitter * 2 + 1
        val deterministicJitter = if (maxJitter > 0) Math.floorMod(seed, range) - maxJitter else 0L

        val adjusted = baseAmount + deterministicJitter
        // Redondeo a miles COP
        val rounded = maxOf(((adjusted + 500) / 1000) * 1000, pm.amountInCop)
        val clamped = maxOf(pm.amountInCop, minOf(rounded, pp.amountInCop))
        return Money.of(clamped)
    }

    private fun calculateDiscountPrice(pp: Money, pm: Money, discountPercent: Double): Money {
        val discount = (pp.amountInCop * discountPercent).toLong()
        val calculated = pp.amountInCop - discount
        // Redondeo a miles COP
        val rounded = ((calculated + 500) / 1000) * 1000
        return Money.of(max(rounded, pm.amountInCop))
    }

    private companion object {
        /** Pasos de descuento porcentual sobre el Precio Publicado (PP). */
        val DISCOUNTS_PP = doubleArrayOf(0.05, 0.10, 0.15)

        /** Tras este número de contraofertas se escala a humano. */
        const val MAX_COUNTER_OFFERS = 3
    }
}
