package com.automata.adapters.meta

import com.automata.commerce.model.PublicProductDto
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetaCatalogPayloadsTest {

    private fun producto(condition: String = "nuevo") = PublicProductDto(
        key = ProductKey("PROD-001"), title = "Audífonos", description = "Con cable",
        publishedPrice = Money.of(120_000), availabilityStatus = "in stock", brand = "Sony", condition = condition,
        gtinOrEan = null, mpn = null, images = listOf("https://x.co/a.jpg"),
        attributes = mapOf("link" to "https://x.co/p/PROD-001")
    )

    @Test
    fun `precio con moneda y condicion en el vocabulario de Meta`() {
        assertEquals("120000 COP", MetaCatalogPayloads.formatPrice(120_000))
        assertEquals("new", MetaCatalogPayloads.mapCondition("Nuevo"))
        assertEquals("refurbished", MetaCatalogPayloads.mapCondition("reacondicionado"))
        assertEquals("used", MetaCatalogPayloads.mapCondition("USADO"))
        assertEquals("new", MetaCatalogPayloads.mapCondition("cualquier cosa"))
    }

    @Test
    fun `el stock cero marca el item como agotado`() {
        val d = MetaCatalogPayloads.stockData("PROD-001", 0)
        assertEquals("PROD-001", d["id"]!!.jsonPrimitive.content)
        assertEquals("out of stock", d["availability"]!!.jsonPrimitive.content)
        assertEquals(0, d["inventory"]!!.jsonPrimitive.intOrNull)
        assertEquals("in stock", MetaCatalogPayloads.stockData("PROD-001", 2)["availability"]!!.jsonPrimitive.content)
    }

    @Test
    fun `el item completo trae lo que exige Meta y nada interno`() {
        val d = MetaCatalogPayloads.itemData(producto(), 3)
        listOf("id", "title", "description", "availability", "condition", "price", "link", "image_link", "brand", "inventory")
            .forEach { assertNotNull(d[it], "falta $it") }
        assertEquals("120000 COP", d["price"]!!.jsonPrimitive.content)
        assertNull(d["cost"]); assertNull(d["costo"]); assertNull(d["precio_minimo"])
    }

    @Test
    fun `falta de foto o enlace se detecta antes de enviar`() {
        assertNull(MetaCatalogPayloads.missingRequiredForCreate(producto()))
        assertTrue(MetaCatalogPayloads.missingRequiredForCreate(producto().copy(images = emptyList()))!!.contains("imagen"))
        assertTrue(MetaCatalogPayloads.missingRequiredForCreate(producto().copy(attributes = emptyMap()))!!.contains("enlace"))
    }

    @Test
    fun `el pedido batch lleva method y data`() {
        val b = MetaCatalogPayloads.batch("UPDATE", MetaCatalogPayloads.stockData("PROD-001", 1))
        val first = b.jsonArray.first().jsonObject
        assertEquals("UPDATE", first["method"]!!.jsonPrimitive.content)
        assertEquals("PROD-001", first["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
}
