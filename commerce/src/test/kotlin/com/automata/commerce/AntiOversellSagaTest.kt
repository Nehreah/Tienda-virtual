package com.automata.commerce

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.service.AntiOversellSaga
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AntiOversellSagaTest {

    class MockPublishingPort(override val channelName: String) : CatalogPublishingPort {
        var lastStockSet: Int? = null
        var lastProductKeySet: ProductKey? = null

        override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> = PortResult.Ok("ext_123")
        override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> = PortResult.Ok(Unit)

        override suspend fun setStock(productKey: ProductKey, externalId: String, quantity: Int, expectedVersion: Long): PortResult<Unit> {
            lastProductKeySet = productKey
            lastStockSet = quantity
            return PortResult.Ok(Unit)
        }

        override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> = PortResult.Ok(Unit)
        override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<ChannelListingStatus> = PortResult.Ok(ChannelListingStatus(productKey, channelName, externalId, true, 0, "OK"))
    }

    @Test
    fun `order confirmed in shopify retracts stock to 0 in meta and mercadolibre`() = runBlocking {
        val metaPort = MockPublishingPort("meta_catalogo")
        val meliPort = MockPublishingPort("mercadolibre")
        val saga = AntiOversellSaga(listOf(metaPort, meliPort))

        val externalIds = mapOf("meta_catalogo" to "meta_PROD-001", "mercadolibre" to "meli_PROD-001")
        val productKey = ProductKey("PROD-001")

        val result = saga.executeRetractionSaga(
            productKey = productKey,
            originChannel = "shopify",
            externalIdMap = externalIds,
            remainingStock = 0
        )

        assertEquals("shopify", result.originChannel)
        assertTrue(result.updatedChannels["meta_catalogo"] == true)
        assertTrue(result.updatedChannels["mercadolibre"] == true)
        assertEquals(0, metaPort.lastStockSet, "El stock en Meta debe ajustarse a 0")
        assertEquals(0, meliPort.lastStockSet, "El stock en Mercado Libre debe ajustarse a 0")
    }
}
