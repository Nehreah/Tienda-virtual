package com.automata.commerce.ports

import com.automata.commerce.model.Metrica
import com.automata.commerce.model.Venta
import com.automata.shared.ProductKey

/**
 * Puerto de LECTURA de ventas y métricas por socio. Las escribe la base con triggers
 * (se registra una venta con InventarioRepository.marcarVendido o cambiando el estado a mano).
 */
interface VentaRepository {
    fun findByKey(key: ProductKey): Venta?
    fun findAll(): List<Venta>
    fun metricaDe(key: ProductKey): Metrica?
}
