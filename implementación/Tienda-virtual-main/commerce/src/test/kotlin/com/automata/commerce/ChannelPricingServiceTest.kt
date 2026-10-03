package com.automata.commerce

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.service.ChannelPricingService
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChannelPricingServiceTest {

    private val pricingService = ChannelPricingService()

    private val sampleProduct = CommercialProduct(
        key = ProductKey("PROD-001"),
        itemName = "Licuadora Oster",
        publishedPrice = Money.of(1000000), // PP = $1.000.000
        minimumPrice = Money.of(850000),   // PM = $850.000
        cost = Money.of(600000),
        status = "Activo",
        description = "600W",
        brand = "Oster"
    )

    @Test
    fun `calculate channel price with commission includes commission without lowering net below PM`() {
        val commission = 0.13 // 13% commission
        val channelPrice = pricingService.calculateChannelPrice(sampleProduct, commissionPercentage = commission)

        // PP = 1.000.000 / (1 - 0.13) = 1.149.426 COP
        assertTrue(channelPrice.amountInCop >= 1149425L)

        // Verificación de Frontera: El ingreso neto tras comisión no debe caer por debajo de PM (850.000)
        val netRevenue = channelPrice.amountInCop * (1.0 - commission)
        assertTrue(netRevenue >= 850000.0, "El ingreso neto tras comisión no debe ser menor al PM")
    }
}
