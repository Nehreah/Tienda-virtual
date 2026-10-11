package com.automata.commerce.ports

import com.automata.commerce.model.Reparacion
import com.automata.shared.ProductKey

/**
 * Puerto de la tabla REPARACIONES. Al guardar, la base recalcula costo_total y precio_minimo del Inventario.
 * Reemplaza a RevisionRepository.
 */
interface ReparacionRepository {
    fun find(key: ProductKey): Reparacion?

    /** Todas las filas de reparaciones en una sola consulta (evita una consulta por artículo en el panel). */
    fun findAll(): List<Reparacion>

    /**
     * Crea o actualiza. Si el artículo aún no tenía requiere_reparacion = true, lo marca (la base crea la fila).
     * Lanza IllegalArgumentException si la Key no existe en Inventario.
     * La base rechaza el cambio si el artículo está Vendido.
     */
    fun guardar(rep: Reparacion, usuario: String?, origen: String): Reparacion
}
