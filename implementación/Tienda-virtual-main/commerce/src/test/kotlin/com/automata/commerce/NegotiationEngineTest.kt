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
    fun `offer below PM gets a counter offer that is above PM and never reveals it`() {
        val decision = engine.evaluateOffer(sampleProduct, Money.of(80000))
        assertFalse(decision.accepted)
        assertEquals(NegotiationLevel.REJECTED_BELOW_PM, decision.level)
        assertNull(decision.agreedPrice)
        // PP 120.000 · PM 95.000: primer escalón = 25 % de la distancia -> 113.750 -> redondeado hacia arriba a 114.000
        assertEquals(Money.of(114000), decision.counterOffer)
        assertTrue(decision.counterOffer!! > sampleProduct.minimumPrice)
        assertFalse(decision.message.contains("95.000"), "El texto no debe nombrar el piso")
    }

    @Test
    fun `counter offers go down step by step but never reach or reveal PM`() {
        val counters = (0..6).map { step ->
            val d = engine.evaluateOffer(sampleProduct, Money.of(50000), previousCounterOffers = step)
            assertFalse(d.accepted)
            assertNotNull(d.counterOffer)
            assertTrue(d.counterOffer!! > sampleProduct.minimumPrice, "Contraoferta $step en o por debajo del PM")
            assertTrue(d.counterOffer!! <= sampleProduct.publishedPrice, "Contraoferta $step por encima del precio publicado")
            assertFalse(d.message.contains("95.000"), "El texto del paso $step revela el PM")
            assertEquals(step >= 3, d.isFinalCounter, "Marca de contraoferta final en el paso $step")
            d.counterOffer!!.amountInCop
        }
        assertEquals(listOf(114000L, 108000L, 102000L, 102000L, 102000L, 102000L, 102000L), counters)
        assertEquals(counters.sortedDescending(), counters, "Las contraofertas nunca suben")
    }

    @Test
    fun `an offer exactly at PM is still accepted at the offered price`() {
        val decision = engine.evaluateOffer(sampleProduct, Money.of(95000))
        assertTrue(decision.accepted)
        assertEquals(Money.of(95000), decision.agreedPrice)
        assertFalse(decision.message.contains("límite"), "No debe confirmar que la cifra está pegada al piso")
    }

    @Test
    fun `counter offer is capped at the published price when the gap is tiny`() {
        val tiny = sampleProduct.copy(publishedPrice = Money.of(100000), minimumPrice = Money.of(99900))
        val d = engine.evaluateOffer(tiny, Money.of(50000))
        assertFalse(d.accepted)
        assertTrue(d.counterOffer!! >= tiny.minimumPrice)
        assertTrue(d.counterOffer!! <= tiny.publishedPrice)
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

    @Test
    fun `invariant test counter offers are never below PM for any offer and step`() {
        for (offerCop in listOf(0L, 1000L, 50000L, 94999L)) {
            for (step in 0..5) {
                val d = engine.evaluateOffer(sampleProduct, Money.of(offerCop), step)
                assertFalse(d.accepted)
                assertTrue(d.counterOffer!! >= sampleProduct.minimumPrice)
            }
        }
    }
}
