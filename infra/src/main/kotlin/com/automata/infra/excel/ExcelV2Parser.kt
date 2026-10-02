package com.automata.infra.excel

import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.apache.poi.ss.usermodel.*
import java.io.InputStream

data class ChannelOverride(
    val productKey: ProductKey,
    val channel: String, // "shopify", "meta_catalogo", "mercadolibre"
    val publish: Boolean,
    val customTitle: String? = null,
    val customCategory: String? = null,
    val customPriceCop: Long? = null
)

data class ExcelV2ImportResult(
    val products: List<CommercialProduct>,
    val channelOverrides: List<ChannelOverride>,
    val errors: List<String>,
    val warnings: List<String>
)

/**
 * Lector y Validador de Excel v2 (Plan Maestro v5.3 Sección 07).
 * Procesa las 3 hojas: Inventario, Publicacion y Canales.
 * Sanitiza fórmulas celda por celda (= + - @) para prevenir injection attacks.
 */
class ExcelV2Parser {

    fun parseExcel(inputStream: InputStream): ExcelV2ImportResult {
        val workbook = WorkbookFactory.create(inputStream)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val inventarioSheet = workbook.getSheet("Inventario")
            ?: return ExcelV2ImportResult(emptyList(), emptyList(), listOf("Falta la hoja requerida 'Inventario'"), emptyList())

        val publicacionSheet = workbook.getSheet("Publicacion")
        val canalesSheet = workbook.getSheet("Canales")

        // 1. Parsear Hoja Inventario
        val rawProducts = mutableMapOf<String, MutableMap<String, String>>()
        val headerMap = parseHeaders(inventarioSheet.getRow(0))

        for (rowIdx in 1..inventarioSheet.lastRowNum) {
            val row = inventarioSheet.getRow(rowIdx) ?: continue
            val keyVal = getSanitizedCellValue(row.getCell(headerMap["key"] ?: -1))
            if (keyVal.isBlank()) continue

            val rowData = mutableMapOf<String, String>()
            headerMap.forEach { (colName, colIdx) ->
                rowData[colName] = getSanitizedCellValue(row.getCell(colIdx))
            }
            rawProducts[keyVal] = rowData
        }

        // 2. Parsear Hoja Publicacion (Marca, Condición, GTIN, MPN, Fotos)
        if (publicacionSheet != null) {
            val pubHeaderMap = parseHeaders(publicacionSheet.getRow(0))
            for (rowIdx in 1..publicacionSheet.lastRowNum) {
                val row = publicacionSheet.getRow(rowIdx) ?: continue
                val keyVal = getSanitizedCellValue(row.getCell(pubHeaderMap["key"] ?: -1))
                if (keyVal.isBlank() || !rawProducts.containsKey(keyVal)) continue

                val targetData = rawProducts[keyVal]!!
                pubHeaderMap.forEach { (colName, colIdx) ->
                    val cellVal = getSanitizedCellValue(row.getCell(colIdx))
                    if (cellVal.isNotBlank()) {
                        targetData["pub_$colName"] = cellVal
                    }
                }
            }
        } else {
            warnings.add("Hoja 'Publicacion' no encontrada. Se usaran valores por defecto.")
        }

        // 3. Parsear Hoja Canales
        val channelOverrides = mutableListOf<ChannelOverride>()
        if (canalesSheet != null) {
            val canHeaderMap = parseHeaders(canalesSheet.getRow(0))
            for (rowIdx in 1..canalesSheet.lastRowNum) {
                val row = canalesSheet.getRow(rowIdx) ?: continue
                val keyVal = getSanitizedCellValue(row.getCell(canHeaderMap["key"] ?: -1))
                val channel = getSanitizedCellValue(row.getCell(canHeaderMap["canal"] ?: -1))
                if (keyVal.isBlank() || channel.isBlank()) continue

                val publishStr = getSanitizedCellValue(row.getCell(canHeaderMap["publicar"] ?: -1))
                val customTitle = getSanitizedCellValue(row.getCell(canHeaderMap["título para el canal"] ?: -1))
                val customPrice = getSanitizedCellValue(row.getCell(canHeaderMap["precio del canal"] ?: -1)).toLongOrNull()

                channelOverrides.add(
                    ChannelOverride(
                        productKey = ProductKey(keyVal),
                        channel = channel.lowercase(),
                        publish = publishStr.equals("SI", ignoreCase = true) || publishStr.equals("SÍ", ignoreCase = true) || publishStr.equals("TRUE", ignoreCase = true),
                        customTitle = customTitle.takeIf { it.isNotBlank() },
                        customPriceCop = customPrice
                    )
                )
            }
        }

        // 4. Mapear a entidades CommercialProduct con validaciones
        val commercialProducts = mutableListOf<CommercialProduct>()
        for ((keyStr, data) in rawProducts) {
            val itemName = data["item name"] ?: data["item"] ?: keyStr
            val ppCop = data["pp"]?.replace("$", "")?.replace(".", "")?.trim()?.toLongOrNull() ?: 0L
            val pmCop = data["pm"]?.replace("$", "")?.replace(".", "")?.trim()?.toLongOrNull() ?: 0L
            val costCop = data["costo"]?.replace("$", "")?.replace(".", "")?.trim()?.toLongOrNull() ?: 0L
            val status = data["estado"] ?: "Activo"
            val desc = data["descripción"] ?: data["descripcion"] ?: data["descripción ab"] ?: "Sin descripción"
            val qty = data["cantidad disponible"]?.toIntOrNull() ?: 1

            val brand = data["pub_marca"] ?: "Genérico"
            val condition = data["pub_condición"] ?: data["pub_condicion"] ?: "Nuevo"
            val gtin = data["pub_gtin / ean"] ?: data["pub_gtin"]
            val mpn = data["pub_mpn"]

            val images = mutableListOf<String>()
            data["link"]?.takeIf { it.startsWith("http") }?.let { images.add(it) }
            for (i in 1..8) {
                data["pub_foto $i"]?.takeIf { it.startsWith("http") }?.let { images.add(it) }
            }

            if (pmCop > ppCop) {
                errors.add("Error en producto $keyStr: PM ($pmCop) no puede ser mayor a PP ($ppCop)")
                continue
            }

            commercialProducts.add(
                CommercialProduct(
                    key = ProductKey(keyStr),
                    itemName = itemName,
                    publishedPrice = Money.of(ppCop),
                    minimumPrice = Money.of(pmCop),
                    cost = Money.of(costCop),
                    status = status,
                    description = desc,
                    brand = brand,
                    condition = condition,
                    gtinEan = gtin,
                    mpn = mpn,
                    images = images.distinct(),
                    masterQuantity = qty
                )
            )
        }

        workbook.close()
        return ExcelV2ImportResult(commercialProducts, channelOverrides, errors, warnings)
    }

    private fun parseHeaders(headerRow: Row?): Map<String, Int> {
        if (headerRow == null) return emptyMap()
        val map = mutableMapOf<String, Int>()
        for (colIdx in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(colIdx) ?: continue
            val headerText = getSanitizedCellValue(cell).lowercase().trim()
            if (headerText.isNotBlank()) {
                map[headerText] = colIdx
            }
        }
        return map
    }

    private fun getSanitizedCellValue(cell: Cell?): String {
        if (cell == null) return ""
        val text = when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) cell.dateCellValue.toString()
                else {
                    val d = cell.numericCellValue
                    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try { cell.stringCellValue } catch (e: Exception) { cell.numericCellValue.toString() }
            }
            else -> ""
        }.trim()

        // Prevenir ataques de inyección de fórmulas de Excel (= + - @)
        return if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@")) {
            "'$text"
        } else {
            text
        }
    }
}
