package com.automata.commerce.ports

import com.automata.commerce.model.CuentaSocio
import com.automata.commerce.model.Liquidacion
import com.automata.shared.Money
import java.time.LocalDate

/**
 * Aportes y liquidaciones de los socios. Las reglas (monto > 0, no superar el saldo, anulación con motivo)
 * las aplica la base de datos (V005); aquí los errores llegan como IllegalArgumentException con un mensaje legible.
 */
interface LiquidacionRepository {
    /** Estado de cuenta de TODOS los socios (activos y archivados), activos primero. */
    suspend fun cuentas(): List<CuentaSocio>

    /** Liquidaciones más recientes primero; [socioId] filtra por socio. */
    suspend fun listar(socioId: Long? = null, incluirAnuladas: Boolean = true, limite: Int = 200): List<Liquidacion>

    /** Registra un pago al socio. Lanza IllegalArgumentException si el monto es inválido o supera el saldo. */
    suspend fun registrar(socioId: Long, monto: Money, fecha: LocalDate?, nota: String?, usuario: String?)

    /** Anula una liquidación vigente. El [motivo] es obligatorio. */
    suspend fun anular(id: Long, motivo: String, usuario: String?)
}
