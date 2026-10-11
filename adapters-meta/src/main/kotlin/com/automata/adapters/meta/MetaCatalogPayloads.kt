package com.automata.adapters.meta

import com.automata.commerce.model.PublicProductDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v14: constructor (funciones puras, sin red) de los pedidos que se envían a la Batch API del catálogo de Meta
 * (POST /{catalog_id}/items_batch). El identificador de cada ítem es la Key del producto (retailer_id).
 *
 * Seguridad: usa solo [PublicProductDto]; nunca se envían costo, precio mínimo ni margen.
 */
object MetaCatalogPayloads {

    /** Clave en [PublicProductDto.attributes] donde viaja la URL pública del producto (Meta la exige como "link"). */
    const val ATTR_LINK = "link"

    /** Meta pide "100000 COP". */
    fun formatPrice(amountInCop: Long, currency: String = "COP"): String = "$amountInCop $currency"

    /** Meta solo acepta new | refurbished | used. */
    fun mapCondition(condition: String): String = when (condition.trim().lowercase()) {
        "reacondicionado", "refurbished" -> "refurbished"
        "usado", "used" -> "used"
        else -> "new"
    }

    fun availability(quantity: Int): String = if (quantity > 0) "in stock" else "out of stock"

    /** Campos completos del ítem. Devuelve null si falta algo que Meta exige para CREAR (imagen o enlace). */
    fun missingRequiredForCreate(product: PublicProductDto): String? = when {
        product.images.firstOrNull().isNullOrBlank() -> "Meta exige al menos una imagen (image_link): agrega una foto al artículo ${product.key.value}"
        product.attributes[ATTR_LINK].isNullOrBlank() -> "Meta exige un enlace público (link) para ${product.key.value}: completa el campo Link del artículo"
        else -> null
    }

    fun itemData(product: PublicProductDto, quantity: Int, currency: String = "COP"): JsonObject = buildJsonObject {
        put("id", product.key.value)
        put("title", product.title)
        put("description", product.description.ifBlank { product.title })
        put("availability", availability(quantity))
        put("condition", mapCondition(product.condition))
        put("price", formatPrice(product.publishedPrice.amountInCop, currency))
        product.attributes[ATTR_LINK]?.takeIf { it.isNotBlank() }?.let { put("link", it) }
        product.images.firstOrNull()?.takeIf { it.isNotBlank() }?.let { put("image_link", it) }
        put("brand", product.brand.ifBlank { "Genérica" })
        put("inventory", quantity)
        product.gtinOrEan?.takeIf { it.isNotBlank() }?.let { put("gtin", it) }
        product.mpn?.takeIf { it.isNotBlank() }?.let { put("mpn", it) }
    }

    fun stockData(retailerId: String, quantity: Int): JsonObject = buildJsonObject {
        put("id", retailerId)
        put("availability", availability(quantity))
        put("inventory", quantity)
    }

    /** method = CREATE | UPDATE | DELETE */
    fun batch(method: String, data: JsonObject): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("method", method)
            put("data", data)
        })
    }
}
