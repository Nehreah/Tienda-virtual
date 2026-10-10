package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import kotlinx.serialization.Serializable

@Serializable
data class StockDriftAlert(
    val sku: String,
    val title: String,
    val channel: String,
    val localStock: Int,
    val publishedStock: Int,
    val driftDelta: Int,
    val alertMessage: String
)

@Serializable
data class StockDriftReport(
    val timestamp: String,
    val totalAlerts: Int,
    val alerts: List<StockDriftAlert>
)

class StockDriftDetector {

    fun detectDrift(
        localProducts: List<CommercialProduct>,
        publishedStockByChannel: Map<String, Map<String, Int>> // sku -> mapOf(channel -> stock)
    ): StockDriftReport {
        val now = java.time.Instant.now().toString()
        val alerts = mutableListOf<StockDriftAlert>()

        for (product in localProducts) {
            val sku = product.key.value
            val localStock = product.masterQuantity
            val channelStocks = publishedStockByChannel[sku] ?: emptyMap()

            for ((channel, publishedStock) in channelStocks) {
                if (publishedStock != localStock) {
                    val delta = publishedStock - localStock
                    alerts.add(
                        StockDriftAlert(
                            sku = sku,
                            title = product.itemName,
                            channel = channel,
                            localStock = localStock,
                            publishedStock = publishedStock,
                            driftDelta = delta,
                            alertMessage = "Deriva detectada en $channel: stock publicado ($publishedStock) difiere del stock local ($localStock)"
                        )
                    )
                }
            }
        }

        return StockDriftReport(
            timestamp = now,
            totalAlerts = alerts.size,
            alerts = alerts
        )
    }
}
