package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey

/**
 * Entidad de Dominio Comercial (Matriz Comercial Excel).
 * Contiene tanto los datos comerciales públicos como los financieros internos.
 */
data class CommercialProduct(
    val key: ProductKey,
    val itemName: String,
    val publishedPrice: Money, // PP
    val minimumPrice: Money,   // PM (INFORMACIÓN INTERNA PROTEGIDA)
    val cost: Money,           // Costo interno (INFORMACIÓN INTERNA PROTEGIDA)
    val status: String,        // "Activo" | "Inactivo"
    val description: String,
    val brand: String,
    val condition: String = "Nuevo", // "Nuevo" | "Reacondicionado" | "Usado"
    val skuInternal: String? = null,
    val gtinEan: String? = null,
    val mpn: String? = null,
    val category: String = "General",
    val attributes: Map<String, String> = emptyMap(),
    val images: List<String> = emptyList(),
    val masterQuantity: Int = 0 // Stock Maestro del Excel
) {
    val profit: Money
        get() = if (publishedPrice >= cost) publishedPrice - cost else Money.ZERO

    val marginPercentage: Double
        get() = if (publishedPrice.amountInCop > 0) (profit.amountInCop.toDouble() / publishedPrice.amountInCop.toDouble()) * 100.0 else 0.0

    /**
     * Convierte la entidad interna en un DTO público seguro sin campos sensibles.
     */
    fun toPublicDto(titleOverride: String? = null, priceOverride: Money? = null, isAvailable: Boolean = true): PublicProductDto {
        return PublicProductDto(
            key = key,
            title = titleOverride?.takeIf { it.isNotBlank() } ?: itemName,
            description = description,
            publishedPrice = priceOverride ?: publishedPrice,
            availabilityStatus = if (isAvailable) "in stock" else "out of stock",
            brand = brand,
            condition = condition.lowercase(),
            gtinOrEan = gtinEan,
            mpn = mpn,
            images = images,
            attributes = attributes
        )
    }
}
