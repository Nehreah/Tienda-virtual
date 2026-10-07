package com.automata.adapters.meta

import com.automata.commerce.model.PublicProductDto
import com.automata.shared.Money
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetaShopAdapterTest {

    @Test
    fun `T56 stub adapter returns NO_IMPLEMENTADO`() = runBlocking {
        val adapter = MetaShopAdapter("cat123", "token123")
        val product = PublicProductDto(
            key = ProductKey("SKU-1"),
            title = "Test",
            description = "Desc",
            publishedPrice = Money.of(100000),
            availabilityStatus = "in stock",
            brand = "Brand",
            condition = "new",
            gtinOrEan = null,
            mpn = null,
            images = emptyList()
        )

        val pubRes = adapter.publishProduct(product, 1)
        assertTrue(pubRes is PortResult.Permanent)
        assertEquals("NO_IMPLEMENTADO", (pubRes as PortResult.Permanent).code)

        val stockRes = adapter.setStock(ProductKey("SKU-1"), "ext-1", 1, 1L)
        assertTrue(stockRes is PortResult.Permanent)
        assertEquals("NO_IMPLEMENTADO", (stockRes as PortResult.Permanent).code)
    }
}
