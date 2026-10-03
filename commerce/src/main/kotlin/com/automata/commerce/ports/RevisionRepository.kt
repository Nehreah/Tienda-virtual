package com.automata.commerce.ports

import com.automata.commerce.model.Revision
import com.automata.shared.ProductKey

/** Puerto de la capa REVISIÓN. Al guardar, la base recalcula el Costo: Compra + Reparación del Inventario. */
interface RevisionRepository {
    fun find(key: ProductKey): Revision?

    /** Crea o actualiza. Lanza IllegalArgumentException si la Key no existe en Inventario. */
    fun guardar(rev: Revision, usuario: String?, origen: String): Revision
}
