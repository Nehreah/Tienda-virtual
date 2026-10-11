package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.ReconciliationStatus
import com.automata.commerce.ports.RemoteCatalogPort
import com.automata.commerce.ports.RemoteProductItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Un canal caído NO es un canal vacío: no debe producir falsos "solo local". */
class ReconcileCatalogFailureTest {

    private val k = ProductKey("PROD-1")
    private val local = CommercialProduct(
        key = k, itemName = "Producto", publishedPrice = Money.of(100_000), minimumPrice = Money.of(80_000),
        cost = Money.of(50_000), status = "Activo", description = "d", brand = "m", masterQuantity = 2
    )

    private class Caido(override val channelName: String) : RemoteCatalogPort {
        override suspend fun fetchRemoteProducts(): List<RemoteProductItem> = throw IllegalStateException("$channelName caído")
    }

    private class Sano(override val channelName: String, val items: List<RemoteProductItem>) : RemoteCatalogPort {
        override suspend fun fetchRemoteProducts() = items
    }

    @Test
    fun `un canal que falla se informa y no genera falsos solo-local`() = runBlocking {
        val meta = Sano("meta", listOf(RemoteProductItem(k, "m-1", "Producto", Money.of(100_000), 2, "meta")))
        val report = ReconcileCatalogUseCase(listOf(Caido("shopify"), meta)).execute(listOf(local))

        assertEquals(setOf("shopify"), report.failedChannels.keys)
        assertTrue(report.failedChannels.getValue("shopify").contains("caído"))
        assertFalse(report.totalRemoteByChannel.containsKey("shopify"))
        assertTrue(report.items.none { it.channel == "shopify" })
        assertEquals(0, report.localOnlyCount)
        assertEquals(1, report.matchOkCount)
        assertEquals(ReconciliationStatus.MATCH_OK, report.items.single().status)
    }

    @Test
    fun `sin fallos la lista de canales fallidos esta vacia`() = runBlocking {
        val report = ReconcileCatalogUseCase(listOf(Sano("shopify", emptyList()))).execute(listOf(local))
        assertTrue(report.failedChannels.isEmpty())
        assertEquals(1, report.localOnlyCount)
    }
}
