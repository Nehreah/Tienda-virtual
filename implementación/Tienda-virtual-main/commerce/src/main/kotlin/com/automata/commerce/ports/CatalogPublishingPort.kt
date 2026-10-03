package com.automata.commerce.ports

import com.automata.commerce.model.PublicProductDto
import com.automata.shared.PortResult
import com.automata.shared.ProductKey

data class ChannelListingStatus(
    val productKey: ProductKey,
    val channel: String,
    val externalId: String?,
    val isPublished: Boolean,
    val stockSynced: Int,
    val lastSyncMessage: String
)

/**
 * Puerto Hexagonal para adaptadores de publicación de catálogo (Shopify GraphQL, Meta Batch API, Mercado Libre).
 */
interface CatalogPublishingPort {
    val channelName: String

    suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> // Retorna External ID
    suspend fun updateProduct(product: PublicProductDto): PortResult<Unit>
    suspend fun setStock(productKey: ProductKey, externalId: String, quantity: Int, expectedVersion: Long): PortResult<Unit>
    suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit>
    suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<ChannelListingStatus>
}
