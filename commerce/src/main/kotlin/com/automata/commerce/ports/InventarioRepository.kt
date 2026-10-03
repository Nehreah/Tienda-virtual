package com.automata.commerce.ports

import com.automata.commerce.model.InventarioItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import java.time.LocalDate

/**
 * Puerto de persistencia de la tabla principal INVENTARIO.
 * [usuario] y [origen] ("dashboard" | "shopify" | "import") quedan registrados en el historial.
 */
interface InventarioRepository {
    fun findAll(): List<InventarioItem>
    fun findByKey(key: ProductKey): InventarioItem?

    /** Crea o actualiza la fila completa. Devuelve la fila tal como quedó en la base. */
    fun upsert(item: InventarioItem, usuario: String?, origen: String): InventarioItem

    /**
     * Marca el producto como Vendido (estado + fecha de venta + "Vendido en") en UNA sola transacción,
     * y opcionalmente registra el canal de venta. Devuelve null si la Key no existe.
     * Lanza IllegalStateException si el producto ya estaba vendido.
     */
    fun marcarVendido(
        key: ProductKey,
        vendidoEn: Money,
        fecha: LocalDate,
        canal: String?,
        usuario: String?,
        origen: String
    ): InventarioItem?
}
