package com.automata.infra.db

import com.automata.commerce.model.ReparteSocios
import com.automata.commerce.ports.InversionRepository
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import java.math.BigDecimal
import java.math.RoundingMode

class ExposedInversionRepository : InversionRepository {

    private fun buscarPorKeyInternal(key: ProductKey): ReparteSocios {
        val rows = InventarioInversionTable.select { InventarioInversionTable.key eq key.value }.toList()
        if (rows.isEmpty()) return ReparteSocios.SIN_DEFINIR
        val map = rows.associate { it[InventarioInversionTable.socioId] to it[InventarioInversionTable.pct] }
        return ReparteSocios(map)
    }

    override suspend fun buscarPorKey(key: ProductKey): ReparteSocios = DatabaseFactory.tx("buscar_inversion", "system") {
        buscarPorKeyInternal(key)
    }

    override suspend fun obtenerTodas(): Map<ProductKey, ReparteSocios> = DatabaseFactory.tx("obtener_todas_inversiones", "system") {
        val rows = InventarioInversionTable.selectAll().toList()
        val grouped = rows.groupBy { ProductKey(it[InventarioInversionTable.key]) }
        grouped.mapValues { (_, list) ->
            ReparteSocios(list.associate { it[InventarioInversionTable.socioId] to it[InventarioInversionTable.pct] })
        }
    }

    override suspend fun guardar(key: ProductKey, repartos: Map<Long, BigDecimal>): ReparteSocios = DatabaseFactory.tx("guardar_inversion", "system") {
        InventarioInversionTable.deleteWhere { InventarioInversionTable.key eq key.value }
        repartos.forEach { (socioId, pct) ->
            if (pct > BigDecimal.ZERO) {
                InventarioInversionTable.insert {
                    it[InventarioInversionTable.key] = key.value
                    it[InventarioInversionTable.socioId] = socioId
                    it[InventarioInversionTable.pct] = pct
                }
            }
        }
        buscarPorKeyInternal(key)
    }

    override suspend fun fijarPorcentaje(key: ProductKey, socioId: Long, pct: BigDecimal): ReparteSocios = DatabaseFactory.tx("fijar_porcentaje", "system") {
        val actuales = InventarioInversionTable.select { InventarioInversionTable.key eq key.value }
            .associate { it[InventarioInversionTable.socioId] to it[InventarioInversionTable.pct] }
            .toMutableMap()

        if (pct <= BigDecimal.ZERO) {
            actuales.remove(socioId)
        } else {
            actuales[socioId] = pct
        }

        InventarioInversionTable.deleteWhere { InventarioInversionTable.key eq key.value }
        actuales.forEach { (sId, p) ->
            InventarioInversionTable.insert {
                it[InventarioInversionTable.key] = key.value
                it[InventarioInversionTable.socioId] = sId
                it[InventarioInversionTable.pct] = p
            }
        }
        buscarPorKeyInternal(key)
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
            } else {
                base
            }
            map[sId] = p
        }

        return guardar(key, map)
    }

    override suspend fun limpiar(key: ProductKey): ReparteSocios = DatabaseFactory.tx("limpiar_inversion", "system") {
        InventarioInversionTable.deleteWhere { InventarioInversionTable.key eq key.value }
        ReparteSocios.SIN_DEFINIR
    }
}
