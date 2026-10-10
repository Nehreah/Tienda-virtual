package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StockDriftDetectorTest {

    private val k1 = ProductKey("SKU-100")
    private val k2 = ProductKey("SKU-200")

    private fun sampleProduct(key: ProductKey, stock: Int = 10) = CommercialProduct(
        key = key,
        itemName = "Producto ${key.value}",
        publishedPrice = Money.of(100_000),
        minimumPrice = Money.of(80_000),
        cost = Money.of(50_000),
        status = "Publicado",
        description = "Test",
        brand = "Marca",
        masterQuantity = stock
    )

    @Test
    fun `StockDriftDetector detecta desajustes entre stock local y publicado`() {
        val products = listOf(
            sampleProduct(k1, stock = 10),
            sampleProduct(k2, stock = 5)
        )

        val publishedMap = mapOf(
            k1.value to mapOf("shopify" to 10, "meta_catalogo" to 8), // Meta tiene 8 (-2)
            k2.value to mapOf("shopify" to 5) // Shopify coincide
        )

        val detector = StockDriftDetector()
        val report = detector.detectDrift(products, publishedMap)

        assertEquals(1, report.totalAlerts)
        val alert = report.alerts.first()
        assertEquals(k1.value, alert.sku)
        assertEquals("meta_catalogo", alert.channel)
        assertEquals(10, alert.localStock)
        assertEquals(8, alert.publishedStock)
        assertEquals(-2, alert.driftDelta)
    }
}
