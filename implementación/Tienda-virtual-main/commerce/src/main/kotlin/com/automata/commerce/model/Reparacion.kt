package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey

/**
 * Reparación de un artículo (tienda.reparaciones, 1 a 1 con Inventario). Reemplaza a Revision.
 * La fila existe solo si inventario.requiere_reparacion = true; la base la crea sola (precio 0)
 * cuando marcas "sí" y la borra si vuelves a "no" con precio 0.
 *
 * "Costo total" = precio de compra + reparación. Lo calcula la base (trigger) y queda en
 * inventario.costo_total; aquí solo se ofrece la misma cuenta para pruebas y vistas previas.
 */
data class Reparacion(
    val key: ProductKey,
    val precioReparacion: Money = Money.ZERO,
    val detalles: String? = null
) {
    fun costoTotal(precioCompra: Money): Money = precioCompra + precioReparacion
}
