package com.automata.infra.excel

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.service.FinancialMath
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.OutputStream

object ExcelGenerator {

    /**
     * Fase 4.8: protección contra inyección de fórmulas en Excel (CSV Injection).
     * Si el valor comienza con un carácter que Excel interpreta como fórmula, se antepone un apóstrofe
     * para que la celda se trate como texto literal en todos los procesadores de hojas de cálculo.
     */
    private fun sanitizeCell(value: String): String {
        if (value.isEmpty()) return value
        return when (value[0]) {
            '=', '+', '-', '@', '\t', '\r' -> "'$value"
            else -> value
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun org.apache.poi.ss.usermodel.Cell.setStringSafe(value: String) {
        setCellValue(sanitizeCell(value))
    }

    fun generateWorkbookFromItems(
        items: List<InventarioItem>,
        outputStream: OutputStream,
        socios: List<com.automata.commerce.model.Socio> = emptyList()
    ) {
        val workbook = XSSFWorkbook()

        // 1. Hoja Inventario
        val invSheet = workbook.createSheet("Inventario")
        val baseHeaders = listOf(
            "Key", "Item name", "PP", "PM", "Costo", "Ganancia", "ROI/Margen",
            "Estado", "Link", "Fotos", "Marca", "Tipo", "Condición", "cantidad", "ubicación",
            "Descripción AB", "Fecha Compra", "Fecha Venta"
        )
        val socioHeaders = socios.map { "% ${it.nombre}" }
        val invHeaders = if (socios.isNotEmpty()) baseHeaders + socioHeaders + "% Suma" else baseHeaders

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

            row.createCell(0).setCellValue(sanitizeCell(item.key.value))
            row.createCell(1).setCellValue(sanitizeCell(item.item))
            row.createCell(2).setCellValue(ppStr)
            row.createCell(3).setCellValue(pmStr)
            row.createCell(4).setCellValue(costoStr)
            row.createCell(5).setCellValue(gananciaStr)
            row.createCell(6).setCellValue(roiStr)
            row.createCell(7).setCellValue(sanitizeCell(item.estado.etiqueta))
            row.createCell(8).setCellValue(sanitizeCell(item.link ?: ""))
            row.createCell(9).setCellValue(sanitizeCell(item.fotos.joinToString("\n")))
            row.createCell(10).setCellValue(sanitizeCell(item.marca ?: ""))
            row.createCell(11).setCellValue(sanitizeCell(item.tipo ?: ""))
            row.createCell(12).setCellValue(sanitizeCell(item.condicion ?: ""))
            row.createCell(13).setCellValue(item.cantidad.toString())
            row.createCell(14).setCellValue(sanitizeCell(item.ubicacion ?: ""))
            row.createCell(15).setCellValue(sanitizeCell(item.descripcion ?: ""))
            row.createCell(16).setCellValue(item.fechaCompra?.toString() ?: "")
            row.createCell(17).setCellValue(item.fechaVenta?.toString() ?: "")

            if (socios.isNotEmpty()) {
                var colIdx = 18
                socios.forEach { socio ->
                    val pct = item.reparte.porcentaje(socio.id)
                    val pctStr = if (pct.signum() > 0) pct.stripTrailingZeros().toPlainString() else ""
                    row.createCell(colIdx++).setCellValue(pctStr)
                }
                val sumaStr = if (item.reparte.suma.signum() > 0) item.reparte.suma.stripTrailingZeros().toPlainString() else ""
                row.createCell(colIdx).setCellValue(sumaStr)
            }
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

    fun exportToBytes(items: List<InventarioItem>, socios: List<com.automata.commerce.model.Socio> = emptyList()): ByteArray {
        val baos = ByteArrayOutputStream()
        generateWorkbookFromItems(items, baos, socios)
        return baos.toByteArray()
    }
}

