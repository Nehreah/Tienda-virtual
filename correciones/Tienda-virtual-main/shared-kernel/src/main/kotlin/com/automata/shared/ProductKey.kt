package com.automata.shared

import kotlinx.serialization.Serializable

/**
 * Identificador único e inmutable del producto en la Matriz Comercial (Excel).
 * Ejemplo: "PROD-001" o "A-0412".
 */
@Serializable
data class ProductKey(val value: String) {
    init {
        require(value.isNotBlank()) { "ProductKey no puede estar vacío" }
    }

    override fun toString(): String = value
}
