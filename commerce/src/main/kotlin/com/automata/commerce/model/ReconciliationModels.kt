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
    val items: List<ReconciliationItem>
)
