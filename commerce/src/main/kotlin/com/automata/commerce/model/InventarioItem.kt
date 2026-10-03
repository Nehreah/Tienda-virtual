package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey
import java.time.LocalDate

/**
 * Fila de la tabla principal INVENTARIO (tienda.inventario). Una fila por Key.
 *
 * Las reglas de aquí son las mismas que las restricciones CHECK de schema.sql:
 *  - PM <= PP
 *  - fecha de publicación >= fecha de compra
 *  - estado Vendido  <=>  tiene fecha de venta Y "Vendido en"
 *
 * costoCompraReparacion NO lo calcula la aplicación: lo mantiene un trigger de la base
 * (precio de compra + reparación de Revisión). Aquí solo se lee.
 */
data class InventarioItem(
    val key: ProductKey,
    val item: String,
    val link: String? = null,
    val fotos: List<String> = emptyList(),
    val estado: EstadoItem = EstadoItem.COMPRADO,
    val fechaCompra: LocalDate? = null,
    val fechaPublicacion: LocalDate? = null,
    val fechaVenta: LocalDate? = null,
    val precioPublicado: Money? = null,   // PP ("Precio de venta" de la matriz)
    val precioMinimo: Money? = null,      // PM (interno, nunca sale a canales públicos)
    val precioCompra: Money = Money.ZERO,
    val vendidoEn: Money? = null,         // precio final en que quedó el negocio
    val costoCompraReparacion: Money = Money.ZERO,
    val descripcion: String? = null
) {
    init {
        require(item.isNotBlank()) { "El nombre del ítem no puede estar vacío (key $key)" }
        if (precioMinimo != null && precioPublicado != null) {
            require(precioMinimo <= precioPublicado) {
                "PM (${precioMinimo.amountInCop}) no puede ser mayor a PP (${precioPublicado.amountInCop}) en $key"
            }
        }
        if (fechaCompra != null && fechaPublicacion != null) {
            require(!fechaPublicacion.isBefore(fechaCompra)) {
                "La fecha de publicación no puede ser anterior a la de compra en $key"
            }
        }
        require((estado == EstadoItem.VENDIDO) == (fechaVenta != null && vendidoEn != null)) {
            "Un producto Vendido exige fecha de venta y 'Vendido en' (y al revés) en $key"
        }
    }

    val estaVendido: Boolean get() = estado == EstadoItem.VENDIDO

    /** Devuelve una copia en estado Vendido. Falla si ya estaba vendido (evita doble venta). */
    fun registrarVenta(vendidoEn: Money, fecha: LocalDate): InventarioItem {
        check(!estaVendido) { "El producto $key ya está vendido" }
        return copy(estado = EstadoItem.VENDIDO, vendidoEn = vendidoEn, fechaVenta = fecha)
    }
}
