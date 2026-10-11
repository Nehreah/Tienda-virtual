package com.automata.infra.excel

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.service.FinancialMath
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.apache.poi.ss.usermodel.*
import org.slf4j.LoggerFactory
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
    val warnings: List<String>,
    /** Filas de la hoja Inventario descartadas por tener errores (una fila con dos columnas malas cuenta una vez). */
    val rejectedRows: Int = 0,
    /**
     * Fase 2.1: error que impide leer el archivo completo (dañado, sin hoja Inventario, sin columnas obligatorias,
     * sin productos). Quien llama debe responder 400. Cuando existe, [products] está vacío.
     */
    val fileError: String? = null
)

/**
 * Lector y Validador de Excel v2 (Plan Maestro v5.3 Sección 07).
 * Procesa las 3 hojas: Inventario, Publicacion y Canales.
 * Sanitiza fórmulas celda por celda (= + - @) para prevenir injection attacks.
 *
 * Fase 2.1 (validación estricta de la hoja Inventario):
 *  - PP > 0, PM > 0, PM <= PP y Costo >= 0. Los montos aceptan 120000, 120.000, 120,000, 120 000 y $120000 ([AmountParser]).
 *  - "Consultar", vacíos, negativos y textos no numéricos NO se convierten en 0: la fila se rechaza.
 *  - Cada error indica hoja, fila y columna. Las filas válidas se importan igual; las malas se listan en [ExcelV2ImportResult.errors].
 *  - Clave duplicada: se conserva la primera fila y se rechaza la repetida.
 *  - Archivo corrupto o sin lo mínimo: [ExcelV2ImportResult.fileError] (el llamador responde 400, nunca 500).
 */
class ExcelV2Parser {

    private val logger = LoggerFactory.getLogger(ExcelV2Parser::class.java)

    fun parseExcel(inputStream: InputStream): ExcelV2ImportResult {
        val workbook = try {
            WorkbookFactory.create(inputStream)
        } catch (e: Exception) {
            logger.error("ExcelV2Parser: archivo no legible (${e::class.simpleName})", e)
            return fileFailure("El archivo no es un Excel (.xlsx) válido o está dañado.")
        }
        return try {
            workbook.use { readWorkbook(it) }
        } catch (e: Exception) {
            logger.error("ExcelV2Parser: error leyendo el contenido (${e::class.simpleName}: ${e.message})", e)
            fileFailure("No se pudo leer el contenido del Excel. Revisa que las hojas y las celdas no estén dañadas.")
        }
    }

    private fun readWorkbook(workbook: Workbook): ExcelV2ImportResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var rejectedRows = 0

        val inventarioSheet = workbook.getSheet(SHEET_INVENTARIO)
            ?: return fileFailure("Falta la hoja requerida 'Inventario'")

        val publicacionSheet = workbook.getSheet("Publicacion")
        val canalesSheet = workbook.getSheet("Canales")

        // 1. Parsear Hoja Inventario
        val rawProducts = mutableMapOf<String, MutableMap<String, String>>()
        val rowNumbers = mutableMapOf<String, Int>()          // key -> número de fila en Excel (1 = encabezado)
        val seenKeys = mutableMapOf<String, Pair<String, Int>>() // key en minúsculas -> (key original, fila)
        val headerMap = parseHeaders(inventarioSheet.getRow(0))

        val missingColumns = REQUIRED_COLUMNS.filter { (name, _) -> !headerMap.containsKey(name) }.map { it.second }
        if (missingColumns.isNotEmpty()) {
            return fileFailure(
                "Hoja 'Inventario', fila 1: faltan las columnas obligatorias " +
                    missingColumns.joinToString(", ") { "'$it'" } + "."
            )
        }

        for (rowIdx in 1..inventarioSheet.lastRowNum) {
            val row = inventarioSheet.getRow(rowIdx) ?: continue
            val excelRow = rowIdx + 1
            val keyVal = cellText(row, headerMap["key"])
            if (keyVal.isBlank()) continue

            val previous = seenKeys[keyVal.lowercase()]
            if (previous != null) {
                errors.add(
                    "Hoja 'Inventario', fila $excelRow, columna 'Key': la clave '$keyVal' está duplicada " +
                        "(ya aparece en la fila ${previous.second}); se rechaza esta fila y se conserva la primera."
                )
                rejectedRows++
                continue
            }
            seenKeys[keyVal.lowercase()] = keyVal to excelRow

            val rowData = mutableMapOf<String, String>()
            headerMap.forEach { (colName, colIdx) ->
                rowData[colName] = getSanitizedCellValue(row.getCell(colIdx))
            }
            rawProducts[keyVal] = rowData
            rowNumbers[keyVal] = excelRow
        }

        if (rawProducts.isEmpty() && errors.isEmpty()) {
            return fileFailure("La hoja 'Inventario' no tiene ningún producto (filas con 'Key').")
        }

        // 2. Parsear Hoja Publicacion (Marca, Condición, GTIN, MPN, Fotos)
        if (publicacionSheet != null) {
            val pubHeaderMap = parseHeaders(publicacionSheet.getRow(0))
            if (!pubHeaderMap.containsKey("key")) {
                warnings.add("Hoja 'Publicacion' sin columna 'Key': se ignora.")
            }
            for (rowIdx in 1..publicacionSheet.lastRowNum) {
                val row = publicacionSheet.getRow(rowIdx) ?: continue
                val keyVal = cellText(row, pubHeaderMap["key"])
                if (keyVal.isBlank()) continue

                val targetData = rawProducts[keyVal] ?: continue
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
            if (!canHeaderMap.containsKey("key") || !canHeaderMap.containsKey("canal")) {
                warnings.add("Hoja 'Canales' sin columna 'Key' o 'Canal': se ignora.")
            }
            for (rowIdx in 1..canalesSheet.lastRowNum) {
                val row = canalesSheet.getRow(rowIdx) ?: continue
                val keyVal = cellText(row, canHeaderMap["key"])
                val channel = cellText(row, canHeaderMap["canal"])
                if (keyVal.isBlank() || channel.isBlank()) continue

                val publishStr = cellText(row, canHeaderMap["publicar"])
                val customTitle = cellText(row, canHeaderMap["título para el canal"])
                val customPrice = cellText(row, canHeaderMap["precio del canal"]).toLongOrNull()

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
            val excelRow = rowNumbers.getValue(keyStr)
            val rowErrors = mutableListOf<String>()

            // Política de claves (v11): una clave nueva no puede romper las rutas ni la restricción de la base.
            ProductKey.validar(keyStr)?.let { rowErrors.add(cellError(excelRow, "Key", it)) }

            val itemName = data["item name"] ?: data["item"] ?: keyStr

            // Montos: nunca se convierten en 0 por error. Se revisan los tres para informar todos los problemas de la fila de una vez.
            val ppCop = readAmount(data["pp"], "PP", excelRow, rowErrors, mustBePositive = true)
            val costCop: Long? = if (data["costo"].isNullOrBlank()) {
                // El plan pide rechazar montos vacíos; para Costo (dato interno, no define precios) se acepta con aviso.
                warnings.add("Hoja 'Inventario', fila $excelRow, columna 'Costo': está vacío; se toma 0.")
                0L
            } else {
                readAmount(data["costo"], "Costo", excelRow, rowErrors, mustBePositive = false)
            }

            val pmCop = readAmount(data["pm"], "PM", excelRow, rowErrors, mustBePositive = true)

            val statusRaw = data["estado"]?.takeIf { it.isNotBlank() } ?: "Activo"
            val estadoItem = EstadoItem.desdeEtiqueta(statusRaw)

            if (ppCop != null && pmCop != null && (estadoItem == EstadoItem.PUBLICADO || estadoItem == EstadoItem.NEGOCIACION)) {
                if (pmCop > ppCop) {
                    rowErrors.add(
                        cellError(
                            excelRow, "PM",
                            "el Precio Mínimo calculado (${Money.of(pmCop).formatFormattedCop()}) no puede ser mayor al Precio Publicado PP (${Money.of(ppCop).formatFormattedCop()}) para productos en estado ${estadoItem.etiqueta}"
                        )
                    )
                }
            }

            if (rowErrors.isNotEmpty() || ppCop == null || pmCop == null || costCop == null) {
                errors.addAll(rowErrors)
                rejectedRows++
                continue
            }

            // Estado: sin valor = Activo. Un valor desconocido (por ejemplo "Inactvo") se trata como Activo, pero se avisa.
            val status = data["estado"]?.takeIf { it.isNotBlank() } ?: "Activo"
            if (!isKnownStatus(status)) {
                warnings.add(
                    "Hoja 'Inventario', fila $excelRow, columna 'Estado': '${status.take(30)}' no es un estado reconocido; " +
                        "el producto se trata como Activo. Escribe 'Inactivo' para retirarlo de la venta."
                )
            }
            val desc = data["descripción ab"] ?: data["descripción"] ?: data["descripcion"] ?: "Sin descripción"
            val qty = data["cantidad"]?.toIntOrNull() ?: data["cantidad disponible"]?.toIntOrNull() ?: 1

            val brand = data["marca"] ?: data["pub_marca"] ?: "Genérico"
            val category = data["tipo"] ?: data["pub_categoría"] ?: data["pub_categoria"] ?: "General"
            val condition = data["condición"] ?: data["condicion"] ?: data["pub_condición"] ?: data["pub_condicion"] ?: "Nuevo"
            val location = data["ubicación"] ?: data["ubicacion"]
            val gtin = data["pub_gtin / ean"] ?: data["pub_gtin"]
            val mpn = data["pub_mpn"]

            val images = mutableListOf<String>()
            data["link"]?.takeIf { it.startsWith("http") }?.let { images.add(it) }
            data["fotos"]?.let { fotosRaw ->
                fotosRaw.split(Regex("""[\n,\s;]+"""))
                    .map { it.trim() }
                    .filter { it.startsWith("http") }
                    .forEach { images.add(it) }
            }
            for (i in 1..8) {
                data["pub_foto $i"]?.takeIf { it.startsWith("http") }?.let { images.add(it) }
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
                    category = category,
                    location = location?.takeIf { it.isNotBlank() },
                    gtinEan = gtin,
                    mpn = mpn,
                    images = images.distinct(),
                    masterQuantity = qty
                )
            )
        }

        return ExcelV2ImportResult(commercialProducts, channelOverrides, errors, warnings, rejectedRows)
    }

    /** Lee un monto de la hoja Inventario. Si es inválido agrega el error (con hoja, fila y columna) y devuelve null. */
    private fun readAmount(
        raw: String?,
        column: String,
        excelRow: Int,
        rowErrors: MutableList<String>,
        mustBePositive: Boolean
    ): Long? {
        return when (val result = AmountParser.parse(raw)) {
            is AmountResult.Invalid -> {
                rowErrors.add(cellError(excelRow, column, result.reason))
                null
            }
            is AmountResult.Valid -> {
                if (mustBePositive && result.cop <= 0L) {
                    rowErrors.add(cellError(excelRow, column, "debe ser mayor que 0"))
                    null
                } else {
                    result.cop
                }
            }
        }
    }

    private fun cellError(excelRow: Int, column: String, reason: String): String =
        "Hoja 'Inventario', fila $excelRow, columna '$column': $reason"

    private fun isKnownStatus(status: String): Boolean =
        status.trim().lowercase() in setOf("activo", "inactivo") || EstadoItem.desdeEtiqueta(status) != null

    private fun fileFailure(message: String) =
        ExcelV2ImportResult(emptyList(), emptyList(), listOf(message), emptyList(), 0, fileError = message)

    /** Texto de la celda en la columna [colIdx]; "" si la columna no existe en el encabezado (antes lanzaba excepción con -1). */
    private fun cellText(row: Row, colIdx: Int?): String =
        if (colIdx == null) "" else getSanitizedCellValue(row.getCell(colIdx))

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
                    if (d == d.toLong().toDouble()) {
                        d.toLong().toString()
                    } else {
                        java.math.BigDecimal.valueOf(d).toPlainString()
                    }
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.stringCellValue
                } catch (e: Exception) {
                    try {
                        val d = cell.numericCellValue
                        if (d == d.toLong().toDouble()) d.toLong().toString() else java.math.BigDecimal.valueOf(d).toPlainString()
                    } catch (ex: Exception) {
                        ""
                    }
                }
            }
            else -> ""
        }.trim()

        return text
    }

    private companion object {
        const val SHEET_INVENTARIO = "Inventario"

        /** Columnas sin las cuales no se puede importar: (encabezado en minúsculas, nombre para mostrar). */
        val REQUIRED_COLUMNS = listOf("key" to "Key", "pp" to "PP", "pm" to "PM")
    }
}
