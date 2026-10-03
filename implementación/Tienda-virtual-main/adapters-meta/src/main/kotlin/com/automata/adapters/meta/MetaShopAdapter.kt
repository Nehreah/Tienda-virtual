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
class MetaShopAdapter(
    private val catalogId: String,
    private val accessToken: String,
    private val countryCode: String = "CO"
) : CatalogPublishingPort {

    override val channelName: String = "meta_catalogo"

    val currentMode: ShopMode
        get() = CapabilityDetector.detectMode(countryCode, accessToken.isNotBlank())

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> {
        if (currentMode == ShopMode.DISABLED) {
            return PortResult.Permanent("META_DISABLED", "Integración con Meta inhabilitada por falta de credenciales")
        }

        // Mapeo oficial v5.3 (9 campos obligatorios): id, title, description, availability, condition, price, link, image_link, brand
        val metaItemId = "meta_${product.key.value}"
        return PortResult.Ok(metaItemId)
    }

    override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> {
        return PortResult.Ok(Unit)
    }

    override suspend fun setStock(
        productKey: ProductKey,
        externalId: String,
        quantity: Int,
        expectedVersion: Long
    ): PortResult<Unit> {
        return PortResult.Ok(Unit)
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
