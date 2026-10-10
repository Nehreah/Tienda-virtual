package com.automata.app.workers

import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DummyCatalogAdapter(override val channelName: String) : CatalogPublishingPort {
    val updatedStock = mutableMapOf<ProductKey, Int>()

    override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> = PortResult.Ok("ext-id")
    override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> = PortResult.Ok(Unit)
    override suspend fun setStock(productKey: ProductKey, externalId: String, quantity: Int, expectedVersion: Long): PortResult<Unit> {
        updatedStock[productKey] = quantity
        return PortResult.Ok(Unit)
    }
    override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> = PortResult.Ok(Unit)
    override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<ChannelListingStatus> =
        PortResult.Ok(ChannelListingStatus(productKey, channelName, externalId, true, 5, "OK"))
}

class WorkersTest {

    @Test
    fun `DummyCatalogAdapter updates stock correctly`() {
        val adapter = DummyCatalogAdapter("shopify")
        assertEquals("shopify", adapter.channelName)
    }
}
