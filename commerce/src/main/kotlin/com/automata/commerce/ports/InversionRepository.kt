package com.automata.commerce.ports

import com.automata.commerce.model.ReparteSocios
import com.automata.shared.ProductKey
import java.math.BigDecimal

interface InversionRepository {
    suspend fun buscarPorKey(key: ProductKey): ReparteSocios
    suspend fun obtenerTodas(): Map<ProductKey, ReparteSocios>
    suspend fun guardar(key: ProductKey, repartos: Map<Long, BigDecimal>): ReparteSocios
    suspend fun fijarPorcentaje(key: ProductKey, socioId: Long, pct: BigDecimal): ReparteSocios
    suspend fun repartirPartesIguales(key: ProductKey, sociosIds: List<Long>): ReparteSocios
    suspend fun limpiar(key: ProductKey): ReparteSocios
}
