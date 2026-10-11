package com.automata.infra.db

import com.automata.commerce.model.CuentaSocio
import com.automata.commerce.model.Liquidacion
import com.automata.commerce.ports.LiquidacionRepository
import com.automata.shared.Money
import com.automata.shared.SignedCop
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.TextColumnType
import java.time.LocalDate

/**
 * Implementación sobre PostgreSQL (V005): lee tienda.v_socio_cuenta / tienda.liquidacion y escribe SOLO mediante
 * tienda.registrar_liquidacion y tienda.anular_liquidacion, que validan monto y saldo con el socio bloqueado.
 */
class ExposedLiquidacionRepository : LiquidacionRepository {

    override suspend fun cuentas(): List<CuentaSocio> = DatabaseFactory.tx("cuentas_socios", "system") {
        val out = mutableListOf<CuentaSocio>()
        exec(
            "SELECT socio_id, nombre, activo, aporte_en_stock, aporte_vendido, ingresos, ganancia, ventas, " +
                "liquidado, liquidaciones, saldo_por_liquidar " +
                "FROM tienda.v_socio_cuenta ORDER BY activo DESC, socio_id"
        ) { rs ->
            while (rs.next()) {
                out.add(
                    CuentaSocio(
                        socioId = rs.getLong("socio_id"),
                        nombre = rs.getString("nombre"),
                        activo = rs.getBoolean("activo"),
                        aporteEnStock = SignedCop.of(rs.getLong("aporte_en_stock")),
                        aporteVendido = SignedCop.of(rs.getLong("aporte_vendido")),
                        ingresos = SignedCop.of(rs.getLong("ingresos")),
                        ganancia = SignedCop.of(rs.getLong("ganancia")),
                        ventas = rs.getInt("ventas"),
                        liquidado = SignedCop.of(rs.getLong("liquidado")),
                        liquidaciones = rs.getInt("liquidaciones"),
                        saldoPorLiquidar = SignedCop.of(rs.getLong("saldo_por_liquidar"))
                    )
                )
            }
        }
        out.toList()
    }

    override suspend fun listar(socioId: Long?, incluirAnuladas: Boolean, limite: Int): List<Liquidacion> =
        DatabaseFactory.tx("listar_liquidaciones", "system") {
            val condiciones = mutableListOf<String>()
            val args = mutableListOf<Pair<org.jetbrains.exposed.sql.IColumnType, Any?>>()
            if (socioId != null) {
                condiciones.add("l.socio_id = ?")
                args.add(LongColumnType() to socioId)
            }
            if (!incluirAnuladas) condiciones.add("l.anulada_en IS NULL")
            args.add(IntegerColumnType() to limite.coerceIn(1, 1000))

            val where = if (condiciones.isEmpty()) "" else "WHERE " + condiciones.joinToString(" AND ")
            val out = mutableListOf<Liquidacion>()
            exec(
                "SELECT l.id, l.socio_id, s.nombre, l.fecha, l.monto, l.nota, l.creado_por, l.creado_en, " +
                    "l.anulada_en, l.anulada_motivo " +
                    "FROM tienda.liquidacion l JOIN tienda.socio s ON s.id = l.socio_id " +
                    "$where ORDER BY l.fecha DESC, l.id DESC LIMIT ?",
                args
            ) { rs ->
                while (rs.next()) {
                    out.add(
                        Liquidacion(
                            id = rs.getLong("id"),
                            socioId = rs.getLong("socio_id"),
                            socioNombre = rs.getString("nombre"),
                            fecha = rs.getDate("fecha").toLocalDate(),
                            monto = Money.of(rs.getLong("monto")),
                            nota = rs.getString("nota"),
                            creadoPor = rs.getString("creado_por"),
                            creadoEn = rs.getTimestamp("creado_en")?.toInstant(),
                            anuladaEn = rs.getTimestamp("anulada_en")?.toInstant(),
                            anuladaMotivo = rs.getString("anulada_motivo")
                        )
                    )
                }
            }
            out.toList()
        }

    override suspend fun registrar(socioId: Long, monto: Money, fecha: LocalDate?, nota: String?, usuario: String?) {
        try {
            DatabaseFactory.tx("liquidacion", usuario) {
                exec(
                    "SELECT tienda.registrar_liquidacion(?::bigint, ?::bigint, ?::date, ?::text, ?::text)",
                    listOf(
                        LongColumnType() to socioId,
                        LongColumnType() to monto.amountInCop,
                        TextColumnType() to fecha?.toString(),
                        TextColumnType() to nota,
                        TextColumnType() to usuario
                    )
                ) { }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(mensajeSql(e), e)
        }
    }

    override suspend fun anular(id: Long, motivo: String, usuario: String?) {
        try {
            DatabaseFactory.tx("liquidacion", usuario) {
                exec(
                    "SELECT tienda.anular_liquidacion(?::bigint, ?::text)",
                    listOf(
                        LongColumnType() to id,
                        TextColumnType() to motivo
                    )
                ) { }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(mensajeSql(e), e)
        }
    }

    /** Mensaje de la excepción de PostgreSQL (RAISE EXCEPTION) sin el prefijo "ERROR:" ni las líneas de contexto. */
    internal fun mensajeSql(e: Throwable): String {
        var causa: Throwable = e
        while (causa.cause != null && causa.cause !== causa) causa = causa.cause!!
        val primera = (causa.message ?: e.message ?: "Error en la base de datos")
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return primera.removePrefix("ERROR:").trim().ifBlank { "Error en la base de datos" }
    }
}
