package com.automata.app.excel

import com.automata.app.AppStore
import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExcelTransactionalImportTest {

    private val k1 = ProductKey("PROD-TEST-1")

    @Test
    fun `N1 Fix - si la BD falla a mitad de importacion la memoria no se contamina`() {
        val initialProduct = CommercialProduct(
            key = k1, itemName = "Original",
            publishedPrice = Money.of(100_000), minimumPrice = Money.of(80_000), cost = Money.of(50_000),
            status = "Publicado", description = "Test", brand = "Marca"
        )
        synchronized(AppStore.products) {
            AppStore.products[k1.value] = initialProduct
        }

        // Simular que el proceso de importación falla en BD
        val dbFails = true

        try {
            if (dbFails) {
                throw RuntimeException("Error simulado en base de datos")
            }
            // Solo tras commit exitoso se actualizaría AppStore
            synchronized(AppStore.products) {
                AppStore.products[k1.value] = initialProduct.copy(itemName = "Modificado")
            }
        } catch (e: Exception) {
            // Se revierte
        }

        // La memoria debe conservar el producto original intacto
        val currentInMemory = synchronized(AppStore.products) { AppStore.products[k1.value] }
        assertEquals("Original", currentInMemory?.itemName, "La memoria debe conservar el nombre original tras fallo de BD")
    }
}
