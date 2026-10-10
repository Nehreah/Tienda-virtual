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
                descripcion = "Descripción de prueba",
                marca = "MarcaTest",
                tipo = "CategoríaTest",
                condicion = "Usado",
                cantidad = 3,
                ubicacion = "Estante A"
            )
        )

        val bytes = ExcelGenerator.exportToBytes(items)
        assertTrue(bytes.isNotEmpty(), "El Excel generado no debe estar vacío")

        val parser = ExcelV2Parser()
        val result = parser.parseExcel(bytes.inputStream())
        assertTrue(result.errors.isEmpty(), "No deben haber errores al parsear el Excel generado: ${result.errors}")
        assertTrue(result.products.isNotEmpty(), "Debe haber al menos un producto parseado")

        val p = result.products[0]
        org.junit.jupiter.api.Assertions.assertEquals("MarcaTest", p.brand)
        org.junit.jupiter.api.Assertions.assertEquals("CategoríaTest", p.category)
        org.junit.jupiter.api.Assertions.assertEquals("Usado", p.condition)
        org.junit.jupiter.api.Assertions.assertEquals(3, p.masterQuantity)
        org.junit.jupiter.api.Assertions.assertEquals("Estante A", p.location)
    }

    @Test
    fun `actualiza el archivo NEGOCIO xlsx en la raiz del proyecto con las 18 columnas`() {
        val sampleItems = listOf(
            InventarioItem(
                key = ProductKey("PROD-001"),
                item = "Audífonos Bluetooth Pro",
                precioPublicado = Money.of(120000),
                precioMinimo = Money.of(95000),
                precioCompra = Money.of(70000),
                costoTotal = Money.of(70000),
                estado = EstadoItem.PUBLICADO,
                marca = "AudioBrand",
                tipo = "Accesorios",
                condicion = "Nuevo",
                cantidad = 5,
                ubicacion = "Bodega Principal A-1",
                descripcion = "Audífonos inalámbricos de alta fidelidad con cancelación de ruido",
                fechaCompra = java.time.LocalDate.of(2026, 1, 15)
            ),
            InventarioItem(
                key = ProductKey("PROD-002"),
                item = "Smartwatch Sport",
                precioPublicado = Money.of(250000),
                precioMinimo = Money.of(200000),
                precioCompra = Money.of(150000),
                costoTotal = Money.of(150000),
                estado = EstadoItem.PUBLICADO,
                marca = "TechTime",
                tipo = "Relojes",
                condicion = "Reacondicionado",
                cantidad = 3,
                ubicacion = "Estante B-3",
                descripcion = "Reloj inteligente con monitor cardíaco y GPS",
                fechaCompra = java.time.LocalDate.of(2026, 2, 1)
            )
        )

        val targetFile = java.io.File("NEGOCIO.xlsx")
        targetFile.outputStream().use { os ->
            ExcelGenerator.generateWorkbookFromItems(sampleItems, os)
        }
        assertTrue(targetFile.exists() && targetFile.length() > 0)
    }
}
