package com.automata.app.state

import com.automata.app.dashboard.ChatDealLog
import com.automata.app.dashboard.DealState
import com.automata.commerce.model.CommercialProduct
import com.automata.shared.Money
import com.automata.shared.ProductKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogStateTest {

    @Test
    fun `acceso concurrente masivo no lanza ConcurrentModificationException`() = runBlocking {
        val state = CatalogState()

        val jobs = List(50) { i ->
            launch(Dispatchers.Default) {
                val key = ProductKey("SKU-$i")
                val prod = CommercialProduct(
                    key = key,
                    itemName = "Producto $i",
                    publishedPrice = Money.of(100_000),
                    minimumPrice = Money.of(80_000),
                    cost = Money.of(50_000),
                    status = "Publicado",
                    description = "Test",
                    brand = "Marca",
                    masterQuantity = 10
                )
                state.putProduct(prod)
                state.putChannelMapping(key.value, "shopify", "ext-$i")
                state.addOrUpdateDeal(
                    ChatDealLog(
                        id = "DEAL-$i",
                        customer = "Cliente $i",
                        channel = "whatsapp",
                        channelDisplayName = "WhatsApp",
                        productName = "Producto $i",
                        offeredPrice = Money.of(100_000),
                        dealState = DealState.EN_NEGOCIACION,
                        dealStateLabel = "En negociación",
                        timestamp = "12:00"
                    )
                )

                // Lectura concurrente del snapshot
                val snapshot = state.getProductsSnapshot()
                val dealSnapshot = state.getChatDealsSnapshot()
                val mappingSnapshot = state.getChannelMappingsSnapshot()

                assertTrue(snapshot.isNotEmpty())
                assertTrue(dealSnapshot.isNotEmpty())
                assertTrue(mappingSnapshot.isNotEmpty())
            }
        }

        jobs.forEach { it.join() }
        assertEquals(50, state.getProductsSnapshot().size)
    }
}
