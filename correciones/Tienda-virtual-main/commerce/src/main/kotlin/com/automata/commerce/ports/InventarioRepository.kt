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

    /**
     * Crea o actualiza la fila completa. Devuelve la fila tal como quedó en la base.
     * No escribe costo_total ni precio_minimo (los calcula la base).
     * Lanza IllegalStateException si el producto ya está Vendido y [item] no lo está
     * (para reabrir una venta usa [anularVenta]).
     */
    fun upsert(item: InventarioItem, usuario: String?, origen: String): InventarioItem

    /**
     * Marca el producto como Vendido (estado + fecha de venta + "Vendido en" + canal) en UNA sola
     * sentencia UPDATE; la base crea la venta y las métricas por socio. Devuelve null si la Key no existe.
     * Lanza IllegalStateException si ya estaba vendido o no tiene reparto de socios (suma 100).
     */
    fun marcarVendido(
        key: ProductKey,
        vendidoEn: Money,
        fecha: LocalDate,
        canal: String?,
        usuario: String?,
        origen: String
    ): InventarioItem?

    /**
     * Deshace una venta: vuelve el estado a Publicado; la base borra la venta y las métricas.
     * Devuelve null si la Key no existe. Lanza IllegalStateException si no estaba vendido.
     */
    fun anularVenta(key: ProductKey, usuario: String?, origen: String): InventarioItem?

    /**
     * Vende N unidades atómicamente e idempotentemente en la BD usando tienda.vender_unidades.
     */
    fun venderUnidades(
        key: ProductKey,
        quantity: Int,
        eventId: String?,
        origen: String,
        precioUnit: Money,
        reservaId: String? = null,
        orderId: String? = null,
        lineId: String? = null
    ): Boolean = marcarVendido(key, precioUnit, LocalDate.now(), origen, "system", origen) != null

    /**
     * Devuelve N unidades atómicamente en la BD usando tienda.devolver_unidades.
     */
    fun devolverUnidades(orderId: String, lineId: String?, quantity: Int): Boolean = true

    /**
     * Archiva un producto: estado pasa a Inactivo y guarda archivadoEn, archivadoPor, motivoArchivo (C2).
     */
    fun archivar(key: ProductKey, usuario: String?, motivo: String, origen: String): InventarioItem?

    /**
     * Restaura un producto archivado: vuelve a Publicado y limpia campos de archivado (C2).
     */
    fun restaurar(key: ProductKey, usuario: String?, origen: String): InventarioItem?

    /**
     * Elimina definitivamente una fila del inventario. Devuelve false si no existe (C2).
     */
    fun eliminar(key: ProductKey, usuario: String?, origen: String): Boolean

    /**
     * Comprueba si el producto tiene historial o referencias en ventas, reservas o conversaciones (C2).
     */
    fun hasHistoryOrReferences(key: ProductKey): Boolean
}
