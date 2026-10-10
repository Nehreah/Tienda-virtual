package com.automata.commerce

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommercialProductActiveTest {

    private fun product(status: String) = CommercialProduct(
        key = ProductKey("P-1"), itemName = "Producto",
        publishedPrice = Money.of(100_000), minimumPrice = Money.of(80_000), cost = Money.of(50_000),
        status = status, description = "d", brand = "b"
    )

    @Test
    fun `solo el estado Inactivo retira el producto de la venta`() {
        assertEquals(false, product("Inactivo").isActive)
        assertEquals(false, product("  inactivo ").isActive)
        assertEquals(false, product("INACTIVO").isActive)
        assertEquals(true, product("Activo").isActive)
        assertEquals(true, product("Publicado").isActive)
        assertEquals(true, product("").isActive)
    }
}
