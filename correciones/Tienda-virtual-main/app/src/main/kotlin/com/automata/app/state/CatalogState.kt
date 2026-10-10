package com.automata.app.state

import com.automata.app.dashboard.ApiIntegrationStatus
import com.automata.app.dashboard.ChatDealLog
import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.model.ReconciliationReport
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstracción de Estado Compartido Seguro e Hilo-Segura (Fase F2).
 * Encapsula la caché en memoria con snapshots inmutables para evitar carreras de datos
 * y ConcurrentModificationException durante lecturas del dashboard.
 */
class CatalogState {

    private val _products = ConcurrentHashMap<String, CommercialProduct>()
    private val _inventory = ConcurrentHashMap<String, OperationalInventory>()
    private val _channelMappings = ConcurrentHashMap<String, Map<String, String>>()
    private val _chatDeals = java.util.Collections.synchronizedList(mutableListOf<ChatDealLog>())
    private val _apiIntegrations = java.util.Collections.synchronizedList(mutableListOf<ApiIntegrationStatus>())

    @Volatile var databaseConnected: Boolean = false
    @Volatile var lastReconciliationReport: ReconciliationReport? = null

    // Accessors for products
    fun getProduct(key: String): CommercialProduct? = _products[key]
    fun putProduct(product: CommercialProduct) { _products[product.key.value] = product }
    fun removeProduct(key: String): CommercialProduct? = _products.remove(key)
    fun getProductsSnapshot(): List<CommercialProduct> = _products.values.toList()
    fun getProductsMap(): Map<String, CommercialProduct> = HashMap(_products)

    // Accessors for inventory
    fun getInventory(key: String): OperationalInventory? = _inventory[key]
    fun putInventory(key: String, inv: OperationalInventory) { _inventory[key] = inv }
    fun removeInventory(key: String): OperationalInventory? = _inventory.remove(key)
    fun getInventorySnapshot(): Map<String, OperationalInventory> = HashMap(_inventory)
    fun updateInventory(key: String, transform: (OperationalInventory?) -> OperationalInventory?): OperationalInventory? {
        return _inventory.compute(key) { _, current -> transform(current) }
    }

    // Accessors for channel mappings
    fun getChannelMapping(sku: String): Map<String, String> = _channelMappings[sku] ?: emptyMap()
    fun putChannelMapping(sku: String, channel: String, externalId: String) {
        _channelMappings.compute(sku) { _, current ->
            val updated = (current ?: emptyMap()).toMutableMap()
            updated[channel] = externalId
            updated
        }
    }
    fun getChannelMappingsSnapshot(): Map<String, Map<String, String>> = HashMap(_channelMappings)

    // Accessors for chat deals
    fun addOrUpdateDeal(deal: ChatDealLog) {
        synchronized(_chatDeals) {
            _chatDeals.removeAll { it.id == deal.id }
            _chatDeals.add(0, deal)
            if (_chatDeals.size > 100) {
                _chatDeals.subList(100, _chatDeals.size).clear()
            }
        }
    }
    fun getDeal(id: String): ChatDealLog? = synchronized(_chatDeals) { _chatDeals.firstOrNull { it.id == id } }
    fun getChatDealsSnapshot(): List<ChatDealLog> = synchronized(_chatDeals) { _chatDeals.toList() }

    // Direct access map delegates
    val productsMap: ConcurrentHashMap<String, CommercialProduct> get() = _products
    val inventoryMap: ConcurrentHashMap<String, OperationalInventory> get() = _inventory
    val channelMappingsMap: ConcurrentHashMap<String, Map<String, String>> get() = _channelMappings
    val apiIntegrationsList: MutableList<ApiIntegrationStatus> get() = _apiIntegrations
    val chatDealsList: MutableList<ChatDealLog> get() = _chatDeals
}
