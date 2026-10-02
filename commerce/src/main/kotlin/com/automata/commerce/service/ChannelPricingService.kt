package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import kotlin.math.ceil
import kotlin.math.max

/**
 * Servicio de políticas de precios por canal (Plan Maestro v5.3 Sección 09).
 * Permite ajustar los precios según comisiones de canal (ej. Shopify, Mercado Libre)
 * garantizando que el ingreso neto tras comisión NUNCA quede por debajo de PM.
 */
class ChannelPricingService {

    enum class PricingPolicy {
        MISMO_NETO, // Precio Canal = PP / (1 - comisión)
        PISO        // Precio Canal = max(PP, PM / (1 - comisión))
    }

    /**
     * Calcula el precio público ajustado para un canal específico.
     * @param product Producto comercial
     * @param commissionPercentage Porcentaje de comisión del canal (ej. 0.13 para 13%)
     * @param policy Política de precio a aplicar
     */
    fun calculateChannelPrice(
        product: CommercialProduct,
        commissionPercentage: Double = 0.0,
        policy: PricingPolicy = PricingPolicy.MISMO_NETO
    ): Money {
        require(commissionPercentage in 0.0..0.9) { "Comisión inválida: $commissionPercentage" }
        if (commissionPercentage == 0.0) return product.publishedPrice

        val pp = product.publishedPrice.amountInCop.toDouble()
        val pm = product.minimumPrice.amountInCop.toDouble()

        val calculatedCop = when (policy) {
            PricingPolicy.MISMO_NETO -> ceil(pp / (1.0 - commissionPercentage)).toLong()
            PricingPolicy.PISO -> {
                val minFloorWithCommission = ceil(pm / (1.0 - commissionPercentage)).toLong()
                max(pp.toLong(), minFloorWithCommission)
            }
        }

        val channelPrice = Money.of(calculatedCop)

        // Verificación de Frontera: El ingreso neto (Precio Canal * (1 - comisión)) debe ser >= PM
        val netRevenue = channelPrice.amountInCop * (1.0 - commissionPercentage)
        check(netRevenue >= product.minimumPrice.amountInCop - 1) {
            "Falla de seguridad financiera: El precio calculado para el canal ($channelPrice) deja el neto ($netRevenue) por debajo de PM (${product.minimumPrice})"
        }

        return channelPrice
    }
}
