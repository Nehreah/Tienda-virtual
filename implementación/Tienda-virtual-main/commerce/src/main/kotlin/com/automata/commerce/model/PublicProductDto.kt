package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.serialization.Serializable

/**
 * DTO Público del producto para exposición en canales (Shopify, Meta, Vitrina Pública).
 * REGURIDAD CRÍTICA: Este objeto NUNCA contiene los campos internos PM (precio mínimo),
 * costo, ganancia ni porcentaje de margen.
 */
@Serializable
data class PublicProductDto(
    val key: ProductKey,
    val title: String,
    val description: String,
    val publishedPrice: Money,
    val availabilityStatus: String, // "in stock" | "out of stock"
    val brand: String,
    val condition: String,
    val gtinOrEan: String?,
    val mpn: String?,
    val images: List<String>,
    val attributes: Map<String, String> = emptyMap()
)
