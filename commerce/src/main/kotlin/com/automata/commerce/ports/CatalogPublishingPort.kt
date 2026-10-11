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
 * Resultado de crear un producto en un canal (v14).
 * [externalId] = ID del producto en el canal; [externalInventoryId] = ID que pide setStock
 * (Shopify: inventory_item_id; Meta: null, usa el mismo retailer_id).
 */
data class PublishedListing(
    val externalId: String,
    val externalInventoryId: String? = null
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

    /**
     * v14: igual que [publishProduct] pero devuelve también el ID de inventario del canal.
     * Por defecto delega en [publishProduct] (los adaptadores antiguos y los falsos de las pruebas siguen funcionando).
     */
    suspend fun publishListing(product: PublicProductDto, quantity: Int): PortResult<PublishedListing> =
        when (val r = publishProduct(product, quantity)) {
            is PortResult.Ok -> PortResult.Ok(PublishedListing(r.value))
            is PortResult.Failure -> r
        }

    /**
     * v14: actualiza título, descripción, precio y demás datos de un producto YA publicado.
     * [updateProduct] no recibe el ID externo, por eso existe este método. Por defecto no está implementado,
     * para que ningún canal parezca "sincronizado" sin estarlo.
     */
    suspend fun updateListing(externalId: String, product: PublicProductDto): PortResult<Unit> =
        PortResult.Permanent("NO_IMPLEMENTADO", "El canal $channelName no implementa updateListing")
}
