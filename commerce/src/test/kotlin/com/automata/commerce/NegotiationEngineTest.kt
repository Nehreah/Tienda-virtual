package com.automata.commerce

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.service.NegotiationEngine
import com.automata.commerce.service.NegotiationLevel
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NegotiationEngineTest {

    private val engine = NegotiationEngine()

    private val sampleProduct = CommercialProduct(
        key = ProductKey("PROD-001"),
        itemName = "Audífonos Bluetooth",
        publishedPrice = Money.of(120000), // PP
        minimumPrice = Money.of(95000),   // PM
        cost = Money.of(70000),
        status = "Activo",
        description = "Prueba",
        brand = "AudioBrand"
    )

    @Test
    fun `offer above PP is accepted at L0`() {
        val decision = engine.evaluateOffer(sampleProduct, Money.of(130000))
        assertTrue(decision.accepted)
        assertEquals(NegotiationLevel.L0_PUBLISHED_PRICE, decision.level)
        assertEquals(Money.of(120000), decision.agreedPrice)
    }

    @Test
    fun `offer below PM is rejected with counter offer at L4`() {
        val decision = engine.evaluateOffer(sampleProduct, Money.of(80000))
        assertFalse(decision.accepted)
        assertEquals(NegotiationLevel.REJECTED_BELOW_PM, decision.level)
        assertNull(decision.agreedPrice)
        assertEquals(Money.of(95000), decision.counterOffer) // Counter offer == PM
    }

    @Test
    fun `invariant test agreed price must never fall below PM`() {
        val testOffers = listOf(50000L, 90000L, 95000L, 100000L, 110000L, 115000L, 120000L, 150000L)

        for (offerCop in testOffers) {
            val decision = engine.evaluateOffer(sampleProduct, Money.of(offerCop))
            if (decision.accepted) {
                assertNotNull(decision.agreedPrice)
                assertTrue(
                    decision.agreedPrice!! >= sampleProduct.minimumPrice,
                    "VIOLACIÓN DE INVARIANZA: Precio acordado (${decision.agreedPrice}) es menor que PM (${sampleProduct.minimumPrice})"
                )
            }
        }
    }
}
