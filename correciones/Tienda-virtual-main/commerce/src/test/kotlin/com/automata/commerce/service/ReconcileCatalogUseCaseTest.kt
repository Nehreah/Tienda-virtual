package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.ReconciliationStatus
import com.automata.commerce.ports.RemoteCatalogPort
import com.automata.commerce.ports.RemoteProductItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReconcileCatalogUseCaseTest {

    private val k1 = ProductKey("PROD-MATCH")
    private val k2 = ProductKey("PROD-MISMATCH")
    private val k3 = ProductKey("PROD-LOCAL-ONLY")
    private val k4 = ProductKey("PROD-REMOTE-ONLY")

    private fun sampleProduct(key: ProductKey, price: Long = 100_000, stock: Int = 10) = CommercialProduct(
        key = key,
        itemName = "Producto ${key.value}",
        publishedPrice = Money.of(price),
        minimumPrice = Money.of(80_000),
        cost = Money.of(50_000),
        status = "Publicado",
        description = "Test",
        brand = "Marca",
        masterQuantity = stock
    )

    private class FakeRemotePort(
        override val channelName: String,
        val remoteItems: List<RemoteProductItem>
    ) : RemoteCatalogPort {
        override suspend fun fetchRemoteProducts(): List<RemoteProductItem> = remoteItems
    }

    @Test
    fun `reconciliacion clasifica correctamente MATCH_OK, MISMATCH, LOCAL_ONLY y CHANNEL_ONLY`() = runBlocking {
        val localList = listOf(
            sampleProduct(k1, price = 100_000, stock = 5),
            sampleProduct(k2, price = 100_000, stock = 5),
            sampleProduct(k3, price = 100_000, stock = 5)
        )

        val remoteList = listOf(
            RemoteProductItem(key = k1, remoteId = "rem-1", title = "Producto PROD-MATCH", price = Money.of(100_000), stockQuantity = 5, channel = "shopify"),
            RemoteProductItem(key = k2, remoteId = "rem-2", title = "Producto PROD-MISMATCH", price = Money.of(120_000), stockQuantity = 3, channel = "shopify"), // Mismatch precio y stock
            RemoteProductItem(key = k4, remoteId = "rem-4", title = "Producto Remoto Solo", price = Money.of(50_000), stockQuantity = 2, channel = "shopify")
        )

        val fakePort = FakeRemotePort("shopify", remoteList)
        val useCase = ReconcileCatalogUseCase(listOf(fakePort))

        val report = useCase.execute(localList)

        assertEquals(3, report.totalLocal)
        assertEquals(3, report.totalRemoteByChannel["shopify"])

        assertEquals(1, report.matchOkCount, "k1 debe ser MATCH_OK")
        assertEquals(1, report.mismatchCount, "k2 debe ser MISMATCH")
        assertEquals(1, report.localOnlyCount, "k3 debe ser LOCAL_ONLY")
        assertEquals(1, report.channelOnlyCount, "k4 debe ser CHANNEL_ONLY")

        val mismatchItem = report.items.first { it.key == k2.value }
        assertEquals(ReconciliationStatus.MISMATCH, mismatchItem.status)
        assertEquals(2, mismatchItem.discrepancies.size, "Debe reportar 2 discrepancias (precio y stock)")
    }
}
