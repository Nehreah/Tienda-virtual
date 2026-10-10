package com.automata.commerce

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.PublicProductDto
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SecurityFugaTest {

    @Test
    fun `public product dto must never leak PM, cost, profit, or margin`() {
        val commercialProduct = CommercialProduct(
            key = ProductKey("PROD-SECRET-01"),
            itemName = "Camiseta Gamer",
            publishedPrice = Money.of(100000),
            minimumPrice = Money.of(70000), // DATO SENSIBLE
            cost = Money.of(40000),         // DATO SENSIBLE
            status = "Activo",
            description = "100% Algodón",
            brand = "GamerBrand",
            condition = "Nuevo",
            masterQuantity = 10
        )

        val publicDto: PublicProductDto = commercialProduct.toPublicDto()
        val jsonString = Json.encodeToString(publicDto)

        // Verificación de Frontera (Caso de Prueba 4 y Principio 4 de v5.3)
        assertFalse(jsonString.contains("minimumPrice"), "El JSON público contiene la clave minimumPrice")
        assertFalse(jsonString.contains("70000"), "El JSON público contiene el valor del PM (70000)")
        assertFalse(jsonString.contains("cost"), "El JSON público contiene la clave cost")
        assertFalse(jsonString.contains("40000"), "El JSON público contiene el valor del costo (40000)")
        assertFalse(jsonString.contains("profit"), "El JSON público contiene la clave profit")
        assertFalse(jsonString.contains("margin"), "El JSON público contiene la clave margin")
    }
}
