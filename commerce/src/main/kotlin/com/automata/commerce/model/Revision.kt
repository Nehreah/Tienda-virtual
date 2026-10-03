package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey

/**
 * Capa REVISIÓN (tienda.revision, 1 a 1 con Inventario).
 * Datos propios que se guardan: Reparación y Detalles.
 * "Costo total" = precio de compra + reparación: se calcula, no se guarda aquí
 * (la base lo deja en inventario.costo_compra_reparacion con un trigger).
 */
data class Revision(
    val key: ProductKey,
    val reparacion: Money = Money.ZERO,
    val detalles: String? = null
) {
    fun costoTotal(precioCompra: Money): Money = precioCompra + reparacion
}
