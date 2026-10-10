package com.automata.commerce.ports

import com.automata.commerce.model.ReparteSocios
import com.automata.shared.ProductKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

open class InMemoryInversionRepository : InversionRepository {
    private val store = ConcurrentHashMap<String, Map<Long, BigDecimal>>()

    override suspend fun buscarPorKey(key: ProductKey): ReparteSocios {
        val map = store[key.value] ?: emptyMap()
        return ReparteSocios(map)
    }

    override suspend fun obtenerTodas(): Map<ProductKey, ReparteSocios> {
        return store.mapKeys { ProductKey(it.key) }.mapValues { ReparteSocios(it.value) }
    }

    override suspend fun guardar(key: ProductKey, repartos: Map<Long, BigDecimal>): ReparteSocios {
        val filtered = repartos.filterValues { it > BigDecimal.ZERO }
        val r = ReparteSocios(filtered) // valida suma <= 100
        store[key.value] = filtered
        return r
    }

    override suspend fun fijarPorcentaje(key: ProductKey, socioId: Long, pct: BigDecimal): ReparteSocios {
        val actuales = (store[key.value] ?: emptyMap()).toMutableMap()
        if (pct <= BigDecimal.ZERO) actuales.remove(socioId) else actuales[socioId] = pct
        return guardar(key, actuales)
    }

    override suspend fun repartirPartesIguales(key: ProductKey, sociosIds: List<Long>): ReparteSocios {
        if (sociosIds.isEmpty()) return limpiar(key)
        val n = sociosIds.size
        val cien = BigDecimal(100)
        val base = cien.divide(BigDecimal(n), 2, RoundingMode.DOWN)
        val sobrante = cien - base.multiply(BigDecimal(n))

        val map = HashMap<Long, BigDecimal>()
        var extra = sobrante
        sociosIds.sorted().forEach { sId ->
            val p = if (extra > BigDecimal.ZERO) {
                val valExtra = base + extra
                extra = BigDecimal.ZERO
                valExtra
            } else base
            map[sId] = p
        }
        return guardar(key, map)
    }

    override suspend fun limpiar(key: ProductKey): ReparteSocios {
        store.remove(key.value)
        return ReparteSocios.SIN_DEFINIR
    }
}
