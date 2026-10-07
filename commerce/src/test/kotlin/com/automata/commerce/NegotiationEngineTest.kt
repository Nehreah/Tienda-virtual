package com.automata.commerce

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.service.NegotiationEngine
import com.automata.commerce.service.NegotiationLevel
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NegotiationEngineTest {

    // Fase 3.1: Random fijo para tests deterministas.
    private val engine = NegotiationEngine(random = Random(seed = 42L))

    private val sampleProduct = CommercialProduct(
        key = ProductKey("PROD-001"),
        itemName = "Audífonos Bluetooth",
        publishedPrice = Money.of(120000), // PP
        minimumPrice = Money.of(95000),    // PM
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
        assertTrue(decision.counterOffer!! > sampleProduct.minimumPrice)
        assertTrue(decision.counterOffer!! <= sampleProduct.publishedPrice)
        assertFalse(decision.message.contains("95.000"), "El texto no debe nombrar el piso")
    }

    @Test
    fun `counter offers for steps 0 to 3 have valid counter and never exceed PM`() {
        // Con MAX_COUNTER_OFFERS = 4, pasos 0-3 producen contraoferta; paso 4+ escala a humano.
        for (step in 0..3) {
            val d = engine.evaluateOffer(sampleProduct, Money.of(50000), previousCounterOffers = step)
            assertFalse(d.accepted, "Paso $step no deberia aceptar")
            assertNotNull(d.counterOffer, "Paso $step deberia tener contraoferta")
            assertTrue(d.counterOffer!! > sampleProduct.minimumPrice, "Contraoferta $step en o por debajo del PM")
            assertTrue(d.counterOffer!! <= sampleProduct.publishedPrice, "Contraoferta $step por encima del PP")
            assertFalse(d.message.contains("95.000"), "El texto del paso $step revela el PM")
            assertFalse(d.isEscalatedToHuman, "Paso $step no deberia escalar a humano")
        }
        // Paso 4: escala a humano (Fase 3.2)
        val escalated = engine.evaluateOffer(sampleProduct, Money.of(50000), previousCounterOffers = 4)
        assertTrue(escalated.isEscalatedToHuman, "Paso 4 debe escalar a humano")
        assertNull(escalated.counterOffer, "Al escalar no hay contraoferta")
    }

    @Test
    fun `offer exactly at PM falls into L4 and is NOT auto-accepted (Fase 3 3)`() {
        // Fase 3.3: L4 nunca acepta al valor exacto para no revelar el PM por biseccion.
        val decision = engine.evaluateOffer(sampleProduct, Money.of(95000))
        assertFalse(decision.accepted, "Oferta exacta en PM no se acepta en L4 (Fase 3.3)")
        assertNotNull(decision.counterOffer)
        assertTrue(decision.counterOffer!! > sampleProduct.minimumPrice)
        assertFalse(decision.message.contains("95.000"), "No debe revelar el PM exacto")
    }

    @Test
    fun `offer clearly above l2 threshold is accepted at L2`() {
        // l2Price = PP - 50% * (PP - PM) = 120000 - 12500 = 107500
        val decision = engine.evaluateOffer(sampleProduct, Money.of(108000))
        assertTrue(decision.accepted)
        assertEquals(NegotiationLevel.L2_MODERATE_DISCOUNT, decision.level)
        assertEquals(Money.of(108000), decision.agreedPrice)
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
        val testOffers = listOf(50000L, 90000L, 100000L, 110000L, 115000L, 120000L, 150000L)

        for (offerCop in testOffers) {
            val decision = engine.evaluateOffer(sampleProduct, Money.of(offerCop))
            if (decision.accepted) {
                assertNotNull(decision.agreedPrice)
                assertTrue(
                    decision.agreedPrice!! >= sampleProduct.minimumPrice,
                    "VIOLACION: Precio acordado menor que PM"
                )
            }
        }
    }

    @Test
    fun `invariant test counter offers are never below PM for steps 0 to 3`() {
        // Solo pasos 0-3; paso 4+ escala a humano sin contraoferta (Fase 3.2).
        for (offerCop in listOf(0L, 1000L, 50000L, 94999L)) {
            for (step in 0..3) {
                val d = engine.evaluateOffer(sampleProduct, Money.of(offerCop), step)
                assertFalse(d.accepted)
                assertFalse(d.isEscalatedToHuman, "Paso $step no deberia escalar")
                assertNotNull(d.counterOffer, "Paso $step deberia tener contraoferta")
                assertTrue(
                    d.counterOffer!! >= sampleProduct.minimumPrice,
                    "Contraoferta paso $step por debajo del PM"
                )
            }
        }
    }

    @Test
    fun `escalation happens exactly at MAX_COUNTER_OFFERS (Fase 3 2)`() {
        val d = engine.evaluateOffer(sampleProduct, Money.of(50000), previousCounterOffers = 4)
        assertTrue(d.isEscalatedToHuman)
        assertEquals(NegotiationLevel.ESCALATED_HUMAN, d.level)
        assertFalse(d.accepted)
        assertNull(d.counterOffer)
        assertTrue(d.isFinalCounter)
    }

    @Test
    fun `T44 property test counter offer is always greater than PM if PM less than PP and never exceeds PP`() {
        // PM = PP
        val equalPrices = sampleProduct.copy(publishedPrice = Money.of(100000), minimumPrice = Money.of(100000))
        val dEqual = engine.evaluateOffer(equalPrices, Money.of(80000))
        assertFalse(dEqual.accepted)
        assertEquals(Money.of(100000), dEqual.counterOffer)

        // PM > PP
        val pmGreater = sampleProduct.copy(publishedPrice = Money.of(100000), minimumPrice = Money.of(110000))
        val dGreater = engine.evaluateOffer(pmGreater, Money.of(80000))
        assertFalse(dGreater.accepted)
        assertEquals(Money.of(100000), dGreater.counterOffer)
    }
}
