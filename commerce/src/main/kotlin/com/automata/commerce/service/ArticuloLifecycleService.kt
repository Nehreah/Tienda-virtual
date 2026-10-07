package com.automata.commerce.service

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Reservation
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.ports.ReservationRepository
import com.automata.shared.ProductKey

/**
 * Caso de uso / Servicio del Ciclo de Vida del Artículo (Fase C2).
 * Maneja la creación, edición, archivado con motivo, restauración y eliminación definitiva.
 */
class ArticuloLifecycleService(
    private val repo: InventarioRepository,
    private val onOutboxEvent: ((tipo: String, sku: String, stock: Int) -> Unit)? = null
) {

    fun crear(item: InventarioItem, usuario: String?, origen: String = "dashboard"): InventarioItem {
        require(item.key.value.isNotBlank()) { "La Key del producto no puede estar vacía" }
        require(item.item.isNotBlank()) { "El nombre del producto no puede estar vacío" }
        val existing = repo.findByKey(item.key)
        require(existing == null) { "Ya existe un producto registrado con la Key ${item.key.value}" }

        val created = repo.upsert(item, usuario = usuario, origen = origen)
        onOutboxEvent?.invoke("CATALOG_PUBLISH", created.key.value, created.cantidad)
        return created
    }

    fun editar(item: InventarioItem, usuario: String?, origen: String = "dashboard"): InventarioItem {
        val existing = repo.findByKey(item.key)
            ?: throw IllegalArgumentException("No existe el producto ${item.key.value} para editar")

        val updated = repo.upsert(item, usuario = usuario, origen = origen)
        onOutboxEvent?.invoke("CATALOG_UPDATE", updated.key.value, updated.cantidad)
        return updated
    }

    suspend fun archivar(
        key: ProductKey,
        motivo: String,
        usuario: String?,
        origen: String = "dashboard",
        reservationRepo: ReservationRepository? = null
    ): InventarioItem {
        require(motivo.isNotBlank()) { "Debes proporcionar un motivo para archivar el artículo" }
        val existing = repo.findByKey(key)
            ?: throw IllegalArgumentException("No existe el producto ${key.value}")

        check(existing.estado != EstadoItem.VENDIDO) {
            "No se puede archivar el producto ${key.value} porque está Vendido"
        }

        if (reservationRepo != null) {
            val activeRes = reservationRepo.findActiveByConversation(key.value)
            if (activeRes != null) {
                if (activeRes.status == Reservation.ReservationStatus.CONFIRMED) {
                    throw IllegalStateException("No se puede archivar el producto ${key.value} porque tiene un pago verificado pendiente de entrega")
                } else if (activeRes.status == Reservation.ReservationStatus.ACTIVE) {
                    reservationRepo.release(activeRes.reservationId)
                }
            }
        }

        val archived = repo.archivar(key, usuario = usuario, motivo = motivo, origen = origen)
            ?: throw IllegalStateException("No se pudo archivar el producto ${key.value}")
        onOutboxEvent?.invoke("CATALOG_UNPUBLISH", key.value, 0)
        return archived
    }

    fun restaurar(key: ProductKey, usuario: String?, origen: String = "dashboard"): InventarioItem {
        val existing = repo.findByKey(key)
            ?: throw IllegalArgumentException("No existe el producto ${key.value}")

        val restored = repo.restaurar(key, usuario = usuario, origen = origen)
            ?: throw IllegalStateException("No se pudo restaurar el producto ${key.value}")
        onOutboxEvent?.invoke("CATALOG_PUBLISH", key.value, restored.cantidad)
        return restored
    }

    fun eliminar(key: ProductKey, usuario: String?, origen: String = "dashboard"): Boolean {
        val existing = repo.findByKey(key)
            ?: throw IllegalArgumentException("No existe el producto ${key.value}")

        val hasHistory = repo.hasHistoryOrReferences(key)
        check(!hasHistory) {
            "No se puede eliminar el producto ${key.value} porque tiene historial en ventas, reservas o conversaciones. Debes archivarlo."
        }

        return repo.eliminar(key, usuario = usuario, origen = origen)
    }
}
