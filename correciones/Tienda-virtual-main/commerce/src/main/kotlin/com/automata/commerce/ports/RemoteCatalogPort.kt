package com.automata.commerce.ports

import com.automata.shared.Money
import com.automata.shared.ProductKey

data class RemoteProductItem(
    val key: ProductKey,
    val remoteId: String,
    val title: String,
    val price: Money?,
    val stockQuantity: Int,
    val channel: String,
    val rawStatus: String = "ACTIVE"
)

/**
 * Puerto Hexagonal para consultar de solo lectura el catálogo en canales remotos (Shopify, Meta).
 */
interface RemoteCatalogPort {
    val channelName: String
    suspend fun fetchRemoteProducts(): List<RemoteProductItem>
}
