package com.automata.commerce.ports

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

open class InMemoryInventarioRepository : InventarioRepository {

    val store = ConcurrentHashMap<String, InventarioItem>()

    override fun findAll(): List<InventarioItem> = store.values.toList()

    override fun findByKey(key: ProductKey): InventarioItem? = store[key.value]

    override fun upsert(item: InventarioItem, usuario: String?, origen: String): InventarioItem {
        synchronized(store) {
            val existing = store[item.key.value]
            if (existing?.estado == EstadoItem.VENDIDO && !item.estaVendido) {
                throw IllegalStateException("El producto ${item.key} ya está vendido; no se puede sobrescribir.")
            }
            store[item.key.value] = item
            return item
        }
    }

    override fun marcarVendido(
        key: ProductKey,
        vendidoEn: Money,
        fecha: LocalDate,
        canal: String?,
        usuario: String?,
        origen: String
    ): InventarioItem? {
        synchronized(store) {
            val item = store[key.value] ?: return null
            val vendido = item.registrarVenta(vendidoEn, fecha, canal)
            store[key.value] = vendido
            return vendido
        }
    }

    override fun anularVenta(key: ProductKey, usuario: String?, origen: String): InventarioItem? {
        synchronized(store) {
            val item = store[key.value] ?: return null
            val reabierto = item.anularVenta()
            store[key.value] = reabierto
            return reabierto
        }
    }

    override fun archivar(key: ProductKey, usuario: String?, motivo: String, origen: String): InventarioItem? {
        synchronized(store) {
            val item = store[key.value] ?: return null
            check(item.estado != EstadoItem.VENDIDO) { "No se puede archivar el producto $key porque está Vendido." }
            val archivado = item.copy(
                estado = EstadoItem.INACTIVO,
                archivadoEn = java.time.Instant.now(),
                archivadoPor = usuario,
                motivoArchivo = motivo
            )
            store[key.value] = archivado
            return archivado
        }
    }

    override fun restaurar(key: ProductKey, usuario: String?, origen: String): InventarioItem? {
        synchronized(store) {
            val item = store[key.value] ?: return null
            val restaurado = item.copy(
                estado = EstadoItem.PUBLICADO,
                archivadoEn = null,
                archivadoPor = null,
                motivoArchivo = null
            )
            store[key.value] = restaurado
            return restaurado
        }
    }

    override fun eliminar(key: ProductKey, usuario: String?, origen: String): Boolean {
        synchronized(store) {
            return store.remove(key.value) != null
        }
    }

    override fun hasHistoryOrReferences(key: ProductKey): Boolean {
        val item = store[key.value] ?: return false
        return item.estado == EstadoItem.VENDIDO || item.vendidoEn != null
    }
}
