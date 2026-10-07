package com.automata.infra.excel

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.ExcelPreviewReport
import com.automata.commerce.model.FieldChange
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.PreviewItem
import com.automata.commerce.ports.InventarioRepository
import com.automata.infra.db.ExposedImportacionesRepository
import java.security.MessageDigest

class ExcelPreviewService(
    private val parser: ExcelV2Parser = ExcelV2Parser(),
    private val repo: InventarioRepository? = null,
    private val importacionesRepo: ExposedImportacionesRepository? = null
) {

    fun generatePreview(
        fileBytes: ByteArray,
        existingMemoryProducts: Map<String, CommercialProduct> = emptyMap()
    ): ExcelPreviewReport {
        val hash = calculateSha256(fileBytes)
        val token = "PREVIEW-$hash"

        val parseResult = parser.parseExcel(fileBytes.inputStream())
        if (parseResult.fileError != null) {
            return ExcelPreviewReport(
                token = token,
                hashArchivo = hash,
                creados = emptyList(),
                actualizados = emptyList(),
                sinCambios = emptyList(),
                noTocados = emptyList(),
                bloqueados = emptyList(),
                fileError = parseResult.fileError,
                formatErrors = parseResult.errors,
                warnings = parseResult.warnings,
                canConfirm = false
            )
        }

        val alreadyImported = importacionesRepo?.isHashImported(hash) ?: false

        // Leer productos actuales (de BD si existe, o de memoria)
        val currentDbItems: Map<String, InventarioItem> = try {
            repo?.findAll()?.associateBy { it.key.value } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val creados = mutableListOf<PreviewItem>()
        val actualizados = mutableListOf<PreviewItem>()
        val sinCambios = mutableListOf<PreviewItem>()
        val bloqueados = mutableListOf<PreviewItem>()

        val excelKeys = mutableSetOf<String>()

        for (product in parseResult.products) {
            val keyStr = product.key.value
            excelKeys.add(keyStr)

            val existingDb = currentDbItems[keyStr]
            val existingMem = existingMemoryProducts[keyStr]

            if (existingDb == null && existingMem == null) {
                // Producto Nuevo
                creados.add(
                    PreviewItem(
                        key = keyStr,
                        item = product.itemName,
                        status = product.status,
                        category = product.category
                    )
                )
            } else {
                // Producto existente
                val dbState = existingDb?.estado ?: EstadoItem.desdeEtiqueta(existingMem?.status ?: "") ?: EstadoItem.PUBLICADO
                val excelState = EstadoItem.desdeEtiqueta(product.status) ?: EstadoItem.PUBLICADO

                // Hallazgo N2: Vendido en BD pero diferente estado en Excel -> Bloqueado
                if (dbState == EstadoItem.VENDIDO && excelState != EstadoItem.VENDIDO) {
                    bloqueados.add(
                        PreviewItem(
                            key = keyStr,
                            item = product.itemName,
                            status = product.status,
                            category = product.category,
                            reason = "El producto $keyStr está marcado como Vendido en la base de datos y no se puede modificar su estado mediante importación Excel. Usa anularVenta() si requieres reabrirlo."
                        )
                    )
                    continue
                }

                // Comparar campos
                val changes = compareFields(product, existingDb, existingMem)
                if (changes.isNotEmpty()) {
                    actualizados.add(
                        PreviewItem(
                            key = keyStr,
                            item = product.itemName,
                            status = product.status,
                            category = product.category,
                            changes = changes
                        )
                    )
                } else {
                    sinCambios.add(
                        PreviewItem(
                            key = keyStr,
                            item = product.itemName,
                            status = product.status,
                            category = product.category
                        )
                    )
                }
            }
        }

        // Identificar no tocados
        val allKnownKeys = currentDbItems.keys + existingMemoryProducts.keys
        val noTocadosKeys = allKnownKeys - excelKeys
        val noTocados = noTocadosKeys.mapNotNull { key ->
            val db = currentDbItems[key]
            val mem = existingMemoryProducts[key]
            if (db != null) {
                PreviewItem(key = key, item = db.item, status = db.estado.etiqueta, category = db.tipo ?: "General")
            } else if (mem != null) {
                PreviewItem(key = key, item = mem.itemName, status = mem.status, category = mem.category)
            } else null
        }

        val formatErrors = parseResult.errors
        val canConfirm = formatErrors.isEmpty() && bloqueados.isEmpty() && !alreadyImported

        return ExcelPreviewReport(
            token = token,
            hashArchivo = hash,
            creados = creados,
            actualizados = actualizados,
            sinCambios = sinCambios,
            noTocados = noTocados,
            bloqueados = bloqueados,
            fileError = parseResult.fileError,
            formatErrors = formatErrors,
            warnings = parseResult.warnings,
            canConfirm = canConfirm,
            alreadyImported = alreadyImported
        )
    }

    private fun compareFields(
        newProd: CommercialProduct,
        dbItem: InventarioItem?,
        memProd: CommercialProduct?
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()

        fun check(fieldName: String, oldVal: String?, newVal: String?) {
            val oldNorm = oldVal?.trim() ?: ""
            val newNorm = newVal?.trim() ?: ""
            if (oldNorm != newNorm) {
                changes.add(FieldChange(fieldName, oldNorm.ifBlank { "—" }, newNorm.ifBlank { "—" }))
            }
        }

        val oldName = dbItem?.item ?: memProd?.itemName
        check("Nombre Item", oldName, newProd.itemName)

        val oldPubPrice = dbItem?.precioPublicado?.amountInCop ?: memProd?.publishedPrice?.amountInCop
        if (oldPubPrice != newProd.publishedPrice.amountInCop) {
            changes.add(FieldChange("Precio Publicado", oldPubPrice?.toString() ?: "0", newProd.publishedPrice.amountInCop.toString()))
        }

        val oldMinPrice = dbItem?.precioMinimo?.amountInCop ?: memProd?.minimumPrice?.amountInCop
        if (oldMinPrice != newProd.minimumPrice.amountInCop) {
            changes.add(FieldChange("Precio Mínimo", oldMinPrice?.toString() ?: "0", newProd.minimumPrice.amountInCop.toString()))
        }

        val oldCost = dbItem?.precioCompra?.amountInCop ?: memProd?.cost?.amountInCop
        if (oldCost != newProd.cost.amountInCop) {
            changes.add(FieldChange("Costo/Precio Compra", oldCost?.toString() ?: "0", newProd.cost.amountInCop.toString()))
        }

        val oldMasterQty = dbItem?.cantidad ?: memProd?.masterQuantity
        if (oldMasterQty != newProd.masterQuantity) {
            changes.add(FieldChange("Cantidad Maestro", oldMasterQty?.toString() ?: "0", newProd.masterQuantity.toString()))
        }

        val oldStatus = dbItem?.estado?.etiqueta ?: memProd?.status
        check("Estado", oldStatus, newProd.status)

        val oldCategory = dbItem?.tipo ?: memProd?.category
        check("Categoría", oldCategory, newProd.category)

        return changes
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
