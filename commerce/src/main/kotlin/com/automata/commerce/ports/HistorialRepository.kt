package com.automata.commerce.ports

import com.automata.commerce.model.HistorialEntry
import com.automata.shared.ProductKey

/** Puerto de LECTURA del historial de auditoría. Lo escriben los triggers de la base, no la aplicación. */
interface HistorialRepository {
    fun porKey(key: ProductKey, limit: Int = 50): List<HistorialEntry>
    fun recientes(limit: Int = 50): List<HistorialEntry>
}
