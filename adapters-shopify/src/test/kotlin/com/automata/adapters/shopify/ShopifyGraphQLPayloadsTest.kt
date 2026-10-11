package com.automata.adapters.shopify

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShopifyGraphQLPayloadsTest {

    @Test
    fun `la creacion pide el ID de inventario de la primera variante`() {
        val q = ShopifyGraphQLPayloads.buildProductCreateMutation(
            com.automata.commerce.model.PublicProductDto(
                key = com.automata.shared.ProductKey("PROD-001"), title = "A", description = "B",
                publishedPrice = com.automata.shared.Money.of(1000), availabilityStatus = "in stock", brand = "C",
                condition = "nuevo", gtinOrEan = null, mpn = null, images = emptyList()
            )
        )
        assertTrue(q.contains("inventoryItem"))
    }

    @Test
    fun `el cambio de precio usa productVariantsBulkUpdate con el ID de variante`() {
        val p = ShopifyGraphQLPayloads.buildVariantPriceUpdatePayload("gid://shopify/Product/1", "gid://shopify/ProductVariant/2", "150000")
        assertTrue(p.query.contains("productVariantsBulkUpdate"))
        val v = p.variables!!["variants"]!!.jsonArray.first().jsonObject
        assertEquals("gid://shopify/ProductVariant/2", v["id"]!!.jsonPrimitive.content)
        assertEquals("150000", v["price"]!!.jsonPrimitive.content)
    }

    @Test
    fun `la pagina de catalogo envia el cursor solo cuando existe`() {
        assertFalse(ShopifyGraphQLPayloads.buildProductsPagePayload(null).variables!!.containsKey("cursor"))
        assertEquals("abc", ShopifyGraphQLPayloads.buildProductsPagePayload("abc").variables!!["cursor"]!!.jsonPrimitive.content)
    }
}
