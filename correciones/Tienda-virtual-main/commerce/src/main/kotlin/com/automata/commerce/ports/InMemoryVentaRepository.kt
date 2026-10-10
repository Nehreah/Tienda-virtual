package com.automata.commerce.ports

import com.automata.commerce.model.Venta
import com.automata.commerce.model.VentaReparto
import com.automata.shared.ProductKey
import java.util.concurrent.ConcurrentHashMap

open class InMemoryVentaRepository : VentaRepository {
    val ventasStore = ConcurrentHashMap<String, Venta>()
    val repartosStore = ConcurrentHashMap<String, List<VentaReparto>>()

    override fun findByKey(key: ProductKey): Venta? = ventasStore[key.value]

    override fun findAll(): List<Venta> = ventasStore.values.toList()

    override fun repartosDe(key: ProductKey): List<VentaReparto> = repartosStore[key.value] ?: emptyList()

    override fun repartos(): List<VentaReparto> = repartosStore.values.flatten()
}
