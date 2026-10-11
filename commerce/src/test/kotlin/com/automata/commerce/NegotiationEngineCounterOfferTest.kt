package com.automata.commerce

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.service.NegotiationEngine
import com.automata.commerce.service.NegotiationLevel
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regresión v14: el motor nunca debe contraofertar un precio MENOR que el que el cliente ya ofreció.
 *
 * Antes del arreglo, con PP=120.000 y PM=95.000, una oferta de 105.000 en el paso 2 (15 % de descuento,
 * contraoferta ~102.000) recibía una contraoferta más barata que su propia oferta.
 */
class NegotiationEngineCounterOfferTest {

    private val engine = NegotiationEngine(random = java.util.Random(42L))

    private val product = CommercialProduct(
        key = ProductKey("PROD-001"),
        itemName = "Audífonos Bluetooth",
        publishedPrice = Money.of(120000), // PP
        minimumPrice = Money.of(95000),    // PM
        cost = Money.of(70000),
        status = "Activo",
        description = "Prueba",
        brand = "AudioBrand"
    )

    private val customers = listOf("", "cliente-a", "cliente-b", "573001112233")

    @Test
    fun `a counter offer is never lower than the customer's own offer`() {
        // Ofertas entre el PM (95.000) y justo debajo del umbral L2 (108.000): rechazadas por L1/L2 y, antes del
        // arreglo, contraofertadas con precios posiblemente más bajos que la oferta.
        for (customer in customers) {
            for (step in 0..2) {
                for (offerCop in 95_000L..107_999L step 500L) {
                    val offered = Money.of(offerCop)
                    val d = engine.evaluateOffer(product, offered, step, customer)
                    if (d.accepted) {
                        assertEquals(offered, d.agreedPrice, "Se acepta exactamente la oferta del cliente")
                        assertTrue(d.agreedPrice!! >= product.minimumPrice, "VIOLACION: aceptado bajo el PM")
                    } else {
                        val counter = d.counterOffer
                        assertNotNull(counter, "Sin aceptación debe haber contraoferta (cliente=$customer paso=$step oferta=$offerCop)")
                        assertTrue(
                            counter!! > offered,
                            "Contraoferta ${counter.amountInCop} <= oferta del cliente $offerCop (cliente=$customer paso=$step)"
                        )
                        assertTrue(counter >= product.minimumPrice, "Contraoferta bajo el PM")
                        assertTrue(counter <= product.publishedPrice, "Contraoferta sobre el PP")
                    }
                }
            }
        }
    }

    @Test
    fun `an offer clearly above the step 2 counter is accepted at its own price`() {
        // Paso 2: contraoferta ~102.000 (15 % de descuento, ±3 % de variación). 107.000 siempre la supera.
        for (customer in customers) {
            val offered = Money.of(107_000)
            val d = engine.evaluateOffer(product, offered, previousCounterOffers = 2, customerId = customer)
            assertTrue(d.accepted, "107.000 supera cualquier contraoferta del paso 2 (cliente=$customer)")
            assertEquals(NegotiationLevel.L2_MODERATE_DISCOUNT, d.level)
            assertEquals(offered, d.agreedPrice)
            assertNull(d.counterOffer)
            assertFalse(d.isEscalatedToHuman)
        }
    }

    @Test
    fun `an offer below PM is still countered and never accepted`() {
        for (customer in customers) {
            for (step in 0..2) {
                val d = engine.evaluateOffer(product, Money.of(94_999), step, customer)
                assertFalse(d.accepted, "Oferta bajo el PM no se acepta (paso=$step)")
                assertNotNull(d.counterOffer)
                assertTrue(d.counterOffer!! >= product.minimumPrice)
            }
        }
    }
}
