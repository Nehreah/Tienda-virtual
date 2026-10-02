package com.automata.infra.excel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ExcelGeneratorTest {

    @Test
    fun `generate sample NEGOCIO xlsx file in root directory`() {
        val rootFile = File("../NEGOCIO.xlsx")
        ExcelGenerator.generateSampleWorkbook(rootFile)
        assertTrue(rootFile.exists(), "El archivo NEGOCIO.xlsx debe haberse generado")

        // Parsear para verificar la integridad del Excel generado
        val parser = ExcelV2Parser()
        val result = parser.parseExcel(rootFile.inputStream())
        assertTrue(result.errors.isEmpty(), "No deben haber errores al parsear el Excel generado")
        assertTrue(result.products.isNotEmpty(), "Debe haber productos parseados del Excel")
    }
}
