package com.automata.app

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppStoreCatalogTest {

    private fun product(key: String, status: String) = CommercialProduct(
        key = ProductKey(key), itemName = "Producto $key",
        publishedPrice = Money.of(100_000), minimumPrice = Money.of(80_000), cost = Money.of(50_000),
        status = status, description = "d", brand = "b"
    )

    @BeforeEach
    @AfterEach
    fun clean() {
        synchronized(AppStore.products) { AppStore.products.clear() }
    }

    private fun load(vararg products: CommercialProduct) {
        synchronized(AppStore.products) { products.forEach { AppStore.products[it.key.value] = it } }
    }

    @Test
    fun `el catalogo del bot no incluye productos inactivos pero el interprete los reconoce`() {
        load(product("ACT-1", "Activo"), product("INA-1", "Inactivo"))

        assertEquals(listOf(ProductKey("ACT-1")), AppStoreCatalog.productKeys())
        assertEquals(setOf(ProductKey("ACT-1"), ProductKey("INA-1")), AppStoreCatalog.knownKeys().toSet())
        assertNotNull(AppStoreCatalog.product(ProductKey("INA-1")))
    }

    @Test
    fun `la vitrina publica no muestra productos inactivos ni inexistentes`() {
        load(product("ACT-1", "Activo"), product("INA-1", "Inactivo"), product("INA-2", " INACTIVO "))

        assertNotNull(AppStoreCatalog.publicProduct("ACT-1"))
        assertNull(AppStoreCatalog.publicProduct("INA-1"))
        assertNull(AppStoreCatalog.publicProduct("INA-2"))
        assertNull(AppStoreCatalog.publicProduct("NO-EXISTE"))
    }
}
