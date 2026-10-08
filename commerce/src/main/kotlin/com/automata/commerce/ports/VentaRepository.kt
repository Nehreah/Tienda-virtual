package com.automata.commerce.ports

import com.automata.commerce.model.Venta
import com.automata.commerce.model.VentaReparto
import com.automata.shared.ProductKey

/**
 * Puerto de LECTURA de ventas y repartos por socio.
 */
interface VentaRepository {
    fun findByKey(key: ProductKey): Venta?
    fun findAllByKey(key: ProductKey): List<Venta> = findByKey(key)?.let { listOf(it) } ?: emptyList()
    fun findAll(): List<Venta>
    fun repartosDe(key: ProductKey): List<VentaReparto>
    fun repartos(): List<VentaReparto>
}
