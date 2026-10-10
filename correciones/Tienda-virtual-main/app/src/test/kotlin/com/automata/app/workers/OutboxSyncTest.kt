package com.automata.app.workers

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.infra.config.AppConfig
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OutboxSyncTest {

    private class FakePublishingPort(override val channelName: String) : CatalogPublishingPort {
        var publishCalls = 0
        var setStockCalls = 0

        override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> {
            publishCalls++
            return PortResult.Ok("ext-id-123")
        }

        override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> {
            return PortResult.Ok(Unit)
        }

        override suspend fun setStock(productKey: ProductKey, sku: String, quantity: Int, expectedVersion: Long): PortResult<Unit> {
            setStockCalls++
            return PortResult.Ok(Unit)
        }

        override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> {
            return PortResult.Ok(Unit)
        }

        override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<com.automata.commerce.ports.ChannelListingStatus> {
            return PortResult.Ok(
                com.automata.commerce.ports.ChannelListingStatus(
                    productKey = productKey,
                    channel = channelName,
                    externalId = externalId,
                    isPublished = true,
                    stockSynced = 10,
                    lastSyncMessage = "OK"
                )
            )
        }
    }

    @Test
    fun `channelWriteEnabled es false por defecto`() {
        assertFalse(AppConfig.channelWriteEnabled, "CHANNEL_WRITE_ENABLED debe ser false por defecto en modo solo lectura")
    }

    @Test
    fun `OutboxWorker respeta el adaptador fake`() {
        val port = FakePublishingPort("shopify")
        val worker = OutboxWorker(listOf(port))
        assertNotNull(worker)
        assertEquals(0, port.setStockCalls)
    }
}
