package com.automata.commerce.model

import com.automata.shared.ProductKey
import kotlinx.serialization.Serializable

@Serializable
enum class ReconciliationStatus {
    MATCH_OK,
    MISMATCH,
    LOCAL_ONLY,
    CHANNEL_ONLY
}

@Serializable
data class DiscrepancyDetail(
    val field: String,
    val localValue: String,
    val remoteValue: String
)

@Serializable
data class ReconciliationItem(
    val key: String,
    val title: String,
    val channel: String,
    val status: ReconciliationStatus,
    val discrepancies: List<DiscrepancyDetail> = emptyList(),
    val remoteId: String? = null
)

@Serializable
data class ReconciliationReport(
    val timestamp: String,
    val totalLocal: Int,
    val totalRemoteByChannel: Map<String, Int>,
    val matchOkCount: Int,
    val mismatchCount: Int,
    val localOnlyCount: Int,
    val channelOnlyCount: Int,
    val items: List<ReconciliationItem>,
    /**
     * Canales que NO se pudieron consultar (canal -> motivo). Esos canales no aparecen en [items] ni en
     * [totalRemoteByChannel]: no se comparó nada, así que ninguna fila "solo local" viene de ellos.
     * Si esta lista no está vacía, el informe está incompleto.
     */
    val failedChannels: Map<String, String> = emptyMap()
)
