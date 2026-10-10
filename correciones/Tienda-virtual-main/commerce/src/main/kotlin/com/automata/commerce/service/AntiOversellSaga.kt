package com.automata.commerce.service

import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.shared.PortResult
import com.automata.shared.ProductKey

/**
 * Saga Anti-Sobreventa (Plan Maestro v5.3 Sección 06 / Figura 3).
 * Cuando se confirma una venta en cualquier canal (ej. Shopify via order webhook o WhatsApp),
 * retira el producto o ajusta a 0 el stock en los demás canales en segundos para evitar doble venta.
 */
class AntiOversellSaga(
    private val publishingPorts: List<CatalogPublishingPort>
) {
    data class SagaResult(
        val productKey: ProductKey,
        val originChannel: String,
        val updatedChannels: Map<String, Boolean>, // channelName -> success
        val doubleSaleWarning: Boolean = false
    )

    suspend fun executeRetractionSaga(
        productKey: ProductKey,
        originChannel: String,
        externalIdMap: Map<String, String>, // channelName -> externalId
        remainingStock: Int,
        expectedVersion: Long = 0L          // (H5) versión real del ítem; 0L = sin control optimista
    ): SagaResult {
        val results = mutableMapOf<String, Boolean>()

        for (port in publishingPorts) {
            val channel = port.channelName
            if (channel.equals(originChannel, ignoreCase = true)) continue

            val externalId = externalIdMap[channel]
            if (externalId != null) {
                val res = if (remainingStock <= 0) {
                    port.setStock(productKey, externalId, 0, expectedVersion)
                } else {
                    port.setStock(productKey, externalId, remainingStock, expectedVersion)
                }
                results[channel] = res is PortResult.Ok
            }
        }

        return SagaResult(
            productKey = productKey,
            originChannel = originChannel,
            updatedChannels = results
        )
    }
}
