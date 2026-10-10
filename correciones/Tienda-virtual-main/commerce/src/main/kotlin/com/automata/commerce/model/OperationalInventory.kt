package com.automata.commerce.model

import com.automata.shared.ProductKey

/**
 * Entidad que rastrea el Estado Operacional del Inventario en PostgreSQL.
 * Implementa las reglas del Punto 4:
 * stockVivo = stockMaestro - reservasActivas - unidadesVendidas.
 */
data class OperationalInventory(
    val productKey: ProductKey,
    val stockMaestro: Int,
    val reservasActivas: Int = 0,
    val unidadesVendidas: Int = 0,
    val version: Long = 1L
) {
    /** Stock disponible real. Puede ser negativo si se vendió más de lo que había (alerta en tablero). */
    val stockVivo: Int
        get() = stockMaestro - reservasActivas - unidadesVendidas

    val isAvailable: Boolean
        get() = stockVivo > 0

    /** Fase 2.6: stock negativo significa venta/reserva que excedió el maestro; el tablero debe alertarlo. */
    val hasNegativeStock: Boolean
        get() = stockVivo < 0

    fun withUpdatedMaster(newMasterStock: Int): OperationalInventory {
        return copy(
            stockMaestro = newMasterStock,
            version = version + 1
        )
    }

    fun addReservation(qty: Int): OperationalInventory {
        require(stockVivo >= qty) { "Stock vivo insuficiente ($stockVivo) para reservar $qty unidades de $productKey" }
        return copy(
            reservasActivas = reservasActivas + qty,
            version = version + 1
        )
    }

    fun releaseReservation(qty: Int): OperationalInventory {
        return copy(
            reservasActivas = (reservasActivas - qty).coerceAtLeast(0),
            version = version + 1
        )
    }

    fun recordSale(qty: Int, wasReserved: Boolean = false): OperationalInventory {
        val newReservas = if (wasReserved) (reservasActivas - qty).coerceAtLeast(0) else reservasActivas
        return copy(
            reservasActivas = newReservas,
            unidadesVendidas = unidadesVendidas + qty,
            version = version + 1
        )
    }
}
