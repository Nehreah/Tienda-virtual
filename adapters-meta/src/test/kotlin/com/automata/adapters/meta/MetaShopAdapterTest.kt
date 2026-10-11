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

    private fun producto(images: List<String> = emptyList(), attributes: Map<String, String> = emptyMap()) = PublicProductDto(
        key = ProductKey("SKU-1"),
        title = "Test",
        description = "Desc",
        publishedPrice = Money.of(100000),
        availabilityStatus = "in stock",
        brand = "Brand",
        condition = "new",
        gtinOrEan = null,
        mpn = null,
        images = images,
        attributes = attributes
    )

    @Test
    fun `v14 sin credenciales el adaptador falla con CONFIG_ERROR y no intenta red`() = runBlocking {
        val adapter = MetaShopAdapter("", "")

        val pubRes = adapter.publishProduct(producto(), 1)
        assertTrue(pubRes is PortResult.Permanent)
        assertEquals("CONFIG_ERROR", (pubRes as PortResult.Permanent).code)

        val stockRes = adapter.setStock(ProductKey("SKU-1"), "SKU-1", 1, 1L)
        assertTrue(stockRes is PortResult.Permanent)
        assertEquals("CONFIG_ERROR", (stockRes as PortResult.Permanent).code)

        assertTrue(adapter.fetchRemoteProducts().isEmpty())
    }

    @Test
    fun `v14 publicar sin foto o sin enlace se rechaza antes de llamar a Meta`() = runBlocking {
        val adapter = MetaShopAdapter("cat123", "token123")

        val sinFoto = adapter.publishListing(producto(attributes = mapOf("link" to "https://x.co/p")), 1)
        assertTrue(sinFoto is PortResult.Permanent)
        assertEquals("META_CAMPO_FALTANTE", (sinFoto as PortResult.Permanent).code)

        val sinEnlace = adapter.publishListing(producto(images = listOf("https://x.co/a.jpg")), 1)
        assertTrue(sinEnlace is PortResult.Permanent)
        assertEquals("META_CAMPO_FALTANTE", (sinEnlace as PortResult.Permanent).code)
    }
}
