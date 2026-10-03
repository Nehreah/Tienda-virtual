package com.automata.infra.excel

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.service.FinancialMath
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.OutputStream

object ExcelGenerator {

    fun generateWorkbookFromItems(items: List<InventarioItem>, outputStream: OutputStream) {
        val workbook = XSSFWorkbook()

        // 1. Hoja Inventario
        val invSheet = workbook.createSheet("Inventario")
        val invHeaders = listOf(
            "Key", "Item name", "PP", "PM", "Costo", "Ganancia", "ROI/Margen",
            "Estado", "Link", "Fotos", "Descripción AB", "Fecha Compra", "Fecha Venta", "Vendido En"
        )
        val hRow = invSheet.createRow(0)
        invHeaders.forEachIndexed { i, h -> hRow.createCell(i).setCellValue(h) }

        items.forEachIndexed { rIdx, item ->
            val row = invSheet.createRow(rIdx + 1)
            val ppStr = item.precioPublicado?.formatFormattedCop() ?: ""
            val pmStr = item.precioMinimo?.formatFormattedCop() ?: ""
            val costoStr = item.costoTotal.formatFormattedCop()

            val ganancia = if (item.estaVendido && item.vendidoEn != null) {
                FinancialMath.gananciaNeta(item.vendidoEn!!, item.costoTotal)
            } else if (item.precioPublicado != null) {
                FinancialMath.gananciaNeta(item.precioPublicado!!, item.costoTotal)
            } else null

            val gananciaStr = ganancia?.format() ?: ""
            val roi = ganancia?.let { FinancialMath.roi(it, item.costoTotal) }
            val roiStr = roi?.let { "%.1f%%".format(it * 100) } ?: ""

            row.createCell(0).setCellValue(item.key.value)
            row.createCell(1).setCellValue(item.item)
            row.createCell(2).setCellValue(ppStr)
            row.createCell(3).setCellValue(pmStr)
            row.createCell(4).setCellValue(costoStr)
            row.createCell(5).setCellValue(gananciaStr)
            row.createCell(6).setCellValue(roiStr)
            row.createCell(7).setCellValue(item.estado.etiqueta)
            row.createCell(8).setCellValue(item.link ?: "")
            row.createCell(9).setCellValue(item.fotos.joinToString("\n"))
            row.createCell(10).setCellValue(item.descripcion ?: "")
            row.createCell(11).setCellValue(item.fechaCompra?.toString() ?: "")
            row.createCell(12).setCellValue(item.fechaVenta?.toString() ?: "")
            row.createCell(13).setCellValue(item.vendidoEn?.formatFormattedCop() ?: "")
        }

        // 2. Hoja Config
        val cfgSheet = workbook.createSheet("Config")
        val cfgHeaders = listOf("Condiciones", "Estados", "Canales")
        val cfgHRow = cfgSheet.createRow(0)
        cfgHeaders.forEachIndexed { i, h -> cfgHRow.createCell(i).setCellValue(h) }
        cfgSheet.createRow(1).apply {
            createCell(0).setCellValue("Nuevo")
            createCell(1).setCellValue("Comprado")
            createCell(2).setCellValue("shopify")
        }
        cfgSheet.createRow(2).apply {
            createCell(0).setCellValue("Reacondicionado")
            createCell(1).setCellValue("Publicado")
            createCell(2).setCellValue("meta_catalogo")
        }

        workbook.write(outputStream)
        workbook.close()
    }

    fun exportToBytes(items: List<InventarioItem>): ByteArray {
        val baos = ByteArrayOutputStream()
        generateWorkbookFromItems(items, baos)
        return baos.toByteArray()
    }

}

