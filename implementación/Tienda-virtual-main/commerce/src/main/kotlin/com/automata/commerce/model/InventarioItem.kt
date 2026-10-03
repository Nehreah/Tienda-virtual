package com.automata.commerce.model

import com.automata.shared.Money
import com.automata.shared.ProductKey
import java.time.LocalDate

/**
 * Fila de la tabla principal INVENTARIO (tienda.inventario). Una fila por Key.
 *
 * Las reglas de aquí son las mismas que aplican los triggers de la base (db/migracion_fase2.sql):
 *  - PM <= PP, pero SOLO cuando el estado es Publicado o Negociación (D3)
 *  - fecha de publicación >= fecha de compra
 *  - estado Vendido  =>  tiene fecha de venta Y "Vendido en"
 *    (lo contrario NO se exige: puedes dejar fecha_venta / vendido_en llenos antes de cambiar el
 *     estado a Vendido; así se puede hacer a mano desde el editor de tablas)
 *
 * NO los calcula la aplicación, los calcula un trigger de la base:
 *  - costoTotal   = precio de compra + reparación
 *  - precioMinimo = costoTotal × 1,5 redondeado hacia arriba al peso (ROI mínimo 50 % sobre el COSTO TOTAL)
 * Aquí solo se leen; repositorios y Excel no los escriben.
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
    val precioMinimo: Money? = null,      // PM (interno, calculado por la base, nunca sale a canales públicos)
    val precioCompra: Money = Money.ZERO,
    val vendidoEn: Money? = null,         // precio final en que quedó el negocio
    val costoTotal: Money = Money.ZERO,   // antes costoCompraReparacion
    val descripcion: String? = null,
    val tipo: String? = null,             // reemplaza a tienda.clasificacion
    val requiereReparacion: Boolean = false,
    val reparte: ReparteSocios = ReparteSocios.SIN_DEFINIR,   // reemplaza a tienda.inversion
    val canalVenta: String? = null        // reemplaza a tienda.canal_venta
) {
    init {
        require(item.isNotBlank()) { "El nombre del ítem no puede estar vacío (key $key)" }
        if (estado == EstadoItem.PUBLICADO || estado == EstadoItem.NEGOCIACION) {
            if (precioMinimo != null && precioPublicado != null) {
                require(precioMinimo <= precioPublicado) {
                    "PM (${precioMinimo.amountInCop}) no puede ser mayor a PP (${precioPublicado.amountInCop}) en $key"
                }
            }
        }
        if (fechaCompra != null && fechaPublicacion != null) {
            require(!fechaPublicacion.isBefore(fechaCompra)) {
                "La fecha de publicación no puede ser anterior a la de compra en $key"
            }
        }
        if (estado == EstadoItem.VENDIDO) {
            require(fechaVenta != null && vendidoEn != null) {
                "Un producto Vendido exige fecha de venta y 'Vendido en' en $key"
            }
        }
    }

    val estaVendido: Boolean get() = estado == EstadoItem.VENDIDO

    /**
     * Devuelve una copia en estado Vendido. Falla si ya estaba vendido (evita doble venta),
     * si no hay reparto de socios definido (D7) o si la fecha de venta es anterior a la compra o
     * a la publicación. La base repite estas validaciones.
     */
    fun registrarVenta(vendidoEn: Money, fecha: LocalDate, canal: String? = null): InventarioItem {
        check(!estaVendido) { "El producto $key ya está vendido" }
        check(reparte.definida) { "Define el % de Antonio y Javier (suman 100) antes de vender $key" }
        require(fechaCompra == null || !fecha.isBefore(fechaCompra)) {
            "La fecha de venta no puede ser anterior a la de compra en $key"
        }
        require(fechaPublicacion == null || !fecha.isBefore(fechaPublicacion)) {
            "La fecha de venta no puede ser anterior a la de publicación en $key"
        }
        return copy(
            estado = EstadoItem.VENDIDO,
            vendidoEn = vendidoEn,
            fechaVenta = fecha,
            canalVenta = canal?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Deshace una venta: el artículo vuelve a [nuevoEstado] y se borran los datos de venta.
     * La base hace lo mismo con un trigger (y deja todo en el historial).
     */
    fun anularVenta(nuevoEstado: EstadoItem = EstadoItem.PUBLICADO): InventarioItem {
        check(estaVendido) { "El producto $key no está vendido" }
        require(nuevoEstado != EstadoItem.VENDIDO) { "El nuevo estado no puede ser Vendido" }
        return copy(estado = nuevoEstado, fechaVenta = null, vendidoEn = null, canalVenta = null)
    }
}
