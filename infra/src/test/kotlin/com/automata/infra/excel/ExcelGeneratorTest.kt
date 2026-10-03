package com.automata.infra.excel

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExcelGeneratorTest {

    @Test
    fun `exportToBytes genera un xlsx valido y parseable`() {
        val items = listOf(
            InventarioItem(
                key = ProductKey("TEST-001"),
                item = "Producto de Prueba",
                precioPublicado = Money.of(100000),
                precioMinimo = Money.of(80000),
                precioCompra = Money.of(50000),
                costoTotal = Money.of(50000),
                estado = EstadoItem.PUBLICADO,
                descripcion = "Descripción de prueba"
            )
        )

        val bytes = ExcelGenerator.exportToBytes(items)
        assertTrue(bytes.isNotEmpty(), "El Excel generado no debe estar vacío")

        val parser = ExcelV2Parser()
        val result = parser.parseExcel(bytes.inputStream())
        assertTrue(result.errors.isEmpty(), "No deben haber errores al parsear el Excel generado: ${result.errors}")
        assertTrue(result.products.isNotEmpty(), "Debe haber al menos un producto parseado")
    }
}
