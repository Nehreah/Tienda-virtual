package com.automata.infra.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

object ExcelGenerator {
    fun generateSampleWorkbook(outputFile: File) {
        val workbook = XSSFWorkbook()

        // 1. Hoja Inventario
        val invSheet = workbook.createSheet("Inventario")
        val invHeaders = listOf(
            "Key", "Item name", "PP", "PM", "Costo", "Ganancia", "ROI/Margen",
            "Estado", "Link", "Fotos", "Descripción AB", "Cantidad disponible"
        )
        val hRow = invSheet.createRow(0)
        invHeaders.forEachIndexed { i, h -> hRow.createCell(i).setCellValue(h) }

        val sampleData = listOf(
            listOf("PROD-001", "Audífonos Bluetooth Pro ANC", "$120.000", "$95.000", "$70.000", "$50.000", "41%", "Activo", "https://images.unsplash.com/photo-1505740420928-5e560c06d30e", "https://images.unsplash.com/photo-1505740420928-5e560c06d30e", "Audífonos inalámbricos con cancelación activa de ruido.", "20"),
            listOf("PROD-002", "Reloj Inteligente Sport GPS", "$250.000", "$210.000", "$150.000", "$100.000", "40%", "Activo", "https://images.unsplash.com/photo-1523275335684-37898b6baf30", "https://images.unsplash.com/photo-1523275335684-37898b6baf30", "Smartwatch con monitor cardíaco y resistencia 5 ATM.", "15"),
            listOf("PROD-003", "Cámara de Acción 4K Ultra", "$450.000", "$390.000", "$300.000", "$150.000", "33%", "Activo", "https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f", "https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f", "Cámara deportiva 4K Sumergible con estabilización.", "10")
        )

        sampleData.forEachIndexed { rIdx, rowData ->
            val row = invSheet.createRow(rIdx + 1)
            rowData.forEachIndexed { cIdx, valStr -> row.createCell(cIdx).setCellValue(valStr) }
        }

        // 2. Hoja Publicacion
        val pubSheet = workbook.createSheet("Publicacion")
        val pubHeaders = listOf("Key", "Marca", "Condición", "SKU interno", "GTIN / EAN", "MPN", "Categoría interna", "Atributos", "Garantía (meses)", "Foto 1")
        val pubHRow = pubSheet.createRow(0)
        pubHeaders.forEachIndexed { i, h -> pubHRow.createCell(i).setCellValue(h) }

        val pubSampleData = listOf(
            listOf("PROD-001", "AudioTech", "Nuevo", "SKU-AT001", "7701234567890", "AT-BTPRO", "Electrónica", "color:negro;conexion:bluetooth", "12", "https://images.unsplash.com/photo-1505740420928-5e560c06d30e"),
            listOf("PROD-002", "TechWear", "Nuevo", "SKU-TW002", "7709876543210", "TW-WATCH", "Deportes", "color:plata;pantalla:amoled", "12", "https://images.unsplash.com/photo-1523275335684-37898b6baf30"),
            listOf("PROD-003", "CamPro", "Nuevo", "SKU-CP003", "7705554443322", "CP-4KULT", "Fotografía", "resolucion:4k;fps:60", "24", "https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f")
        )

        pubSampleData.forEachIndexed { rIdx, rowData ->
            val row = pubSheet.createRow(rIdx + 1)
            rowData.forEachIndexed { cIdx, valStr -> row.createCell(cIdx).setCellValue(valStr) }
        }

        // 3. Hoja Canales
        val canSheet = workbook.createSheet("Canales")
        val canHeaders = listOf("Key", "Canal", "Publicar", "Título para el canal", "Categoría del canal", "Precio del canal")
        val canHRow = canSheet.createRow(0)
        canHeaders.forEachIndexed { i, h -> canHRow.createCell(i).setCellValue(h) }

        val canSampleData = listOf(
            listOf("PROD-001", "shopify", "SI", "Audífonos Bluetooth Pro ANC AudioTech", "Audio", "120000"),
            listOf("PROD-001", "meta_catalogo", "SI", "Audífonos Bluetooth Pro ANC", "Audio", "120000"),
            listOf("PROD-002", "shopify", "SI", "Smartwatch TechWear Sport GPS 5ATM", "Relojes", "250000"),
            listOf("PROD-002", "meta_catalogo", "SI", "Reloj Inteligente Sport GPS", "Relojes", "250000"),
            listOf("PROD-003", "shopify", "SI", "Cámara 4K CamPro Ultra Sumergible", "Cámaras", "450000"),
            listOf("PROD-003", "meta_catalogo", "SI", "Cámara de Acción 4K Ultra", "Cámaras", "450000")
        )

        canSampleData.forEachIndexed { rIdx, rowData ->
            val row = canSheet.createRow(rIdx + 1)
            rowData.forEachIndexed { cIdx, valStr -> row.createCell(cIdx).setCellValue(valStr) }
        }

        // 4. Hoja Config
        val cfgSheet = workbook.createSheet("Config")
        val cfgHeaders = listOf("Condiciones", "Estados", "Canales")
        val cfgHRow = cfgSheet.createRow(0)
        cfgHeaders.forEachIndexed { i, h -> cfgHRow.createCell(i).setCellValue(h) }
        cfgSheet.createRow(1).apply {
            createCell(0).setCellValue("Nuevo")
            createCell(1).setCellValue("Activo")
            createCell(2).setCellValue("shopify")
        }
        cfgSheet.createRow(2).apply {
            createCell(0).setCellValue("Reacondicionado")
            createCell(1).setCellValue("Inactivo")
            createCell(2).setCellValue("meta_catalogo")
        }

        FileOutputStream(outputFile).use { fos ->
            workbook.write(fos)
        }
        workbook.close()
    }
}
