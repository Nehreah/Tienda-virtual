package com.automata.adapters.meta

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.shared.PortResult
import com.automata.shared.ProductKey

/**
 * Adaptador de Catálogo para Meta (Facebook/Instagram/WhatsApp Commerce Manager).
 * Operará en modo CATALOG_ONLY cuando el checkout nativo no esté disponible en el país.
 */
import com.automata.commerce.ports.RemoteCatalogPort
import com.automata.commerce.ports.RemoteProductItem

class MetaShopAdapter(
    private val catalogId: String,
    private val accessToken: String,
    private val countryCode: String = "CO"
) : CatalogPublishingPort, RemoteCatalogPort {

    override val channelName: String = "meta_catalogo"

    override suspend fun fetchRemoteProducts(): List<RemoteProductItem> {
        if (catalogId.isBlank() || accessToken.isBlank()) return emptyList()
        return emptyList()
    }

    val currentMode: ShopMode
        get() = CapabilityDetector.detectMode(countryCode, accessToken.isNotBlank())

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> {
        return PortResult.Permanent("NO_IMPLEMENTADO", "Adaptador de Meta Catalog pendiente de integración completa (M12)")
    }

    override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> {
        return PortResult.Permanent("NO_IMPLEMENTADO", "Adaptador de Meta Catalog pendiente de integración completa (M12)")
    }

    override suspend fun setStock(
        productKey: ProductKey,
        externalId: String,
        quantity: Int,
        expectedVersion: Long
    ): PortResult<Unit> {
        return PortResult.Permanent("NO_IMPLEMENTADO", "Adaptador de Meta Catalog pendiente de integración completa (M12)")
    }

    override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> {
        return setStock(productKey, externalId, 0, 0L)
    }

    override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<ChannelListingStatus> {
        return PortResult.Ok(
            ChannelListingStatus(
                productKey = productKey,
                channel = channelName,
                externalId = externalId,
                isPublished = externalId != null,
                stockSynced = 0,
                lastSyncMessage = "Modo: ${currentMode.name} - Meta Commerce Manager"
            )
        )
    }
}
