package com.automata.commerce.service

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.ports.InventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import java.time.LocalDate

data class ConfirmPaymentRequest(
    val dealId: String,
    val productKey: ProductKey,
    val salePrice: Money,
    val channel: String,
    val user: String = "dashboard"
)

data class ConfirmPaymentResult(
    val success: Boolean,
    /** true cuando el producto YA estaba vendido con este mismo pago (reintento): no se cambió nada. */
    val isIdempotentRepeat: Boolean = false,
    val item: InventarioItem? = null,
    val errorMessage: String? = null,
    /** false cuando no hay repositorio (modo solo memoria): el pago NO quedó guardado en ninguna base. */
    val persisted: Boolean = true
)

/**
 * Caso de Uso Atómico e Idempotente para Confirmación de Pagos (Fase F1).
 * Garantiza que la BD sea la fuente única de verdad antes de actualizar memoria o lanzar sagas.
 *
 * Idempotencia: confirmar dos veces el mismo pago (doble clic, reintento, dos peticiones simultáneas) devuelve
 * éxito con [ConfirmPaymentResult.isIdempotentRepeat] = true y no modifica la venta ya registrada. Quien llame
 * debe OMITIR los efectos no idempotentes (p. ej. descontar stock en memoria) cuando ese indicador sea true.
 */
class ConfirmPaymentUseCase(
    private val repo: InventarioRepository?
) {

    fun execute(request: ConfirmPaymentRequest): ConfirmPaymentResult {
        require(request.dealId.isNotBlank()) { "El dealId no puede estar vacío" }
        require(request.salePrice.amountInCop > 0) { "El precio de venta debe ser mayor a 0 (A5)" }

        if (repo == null) {
            // Modo solo memoria (APP_ENV=local sin base): no hay dónde guardar el pago. Se permite seguir para poder
            // probar el flujo, pero el resultado lo dice explícitamente (persisted = false) en vez de aparentar un
            // guardado que no ocurrió. En producción la app ni arranca sin base de datos.
            return ConfirmPaymentResult(success = true, persisted = false)
        }

        return try {
            val updatedItem = repo.marcarVendido(
                key = request.productKey,
                vendidoEn = request.salePrice,
                fecha = LocalDate.now(),
                canal = request.channel,
                usuario = request.user,
                origen = "dashboard"
            ) ?: return ConfirmPaymentResult(success = false, errorMessage = "No se encontró el producto ${request.productKey.value} en BD")

            ConfirmPaymentResult(success = true, item = updatedItem)
        } catch (e: IllegalStateException) {
            resolverReintento(request, e)
        } catch (e: Exception) {
            ConfirmPaymentResult(success = false, errorMessage = "Error en BD al confirmar pago: ${e.message}")
        }
    }

    /**
     * [InventarioRepository.marcarVendido] lanza IllegalStateException si el producto YA estaba vendido (o si no tiene
     * reparto de socios que sume 100). Se vuelve a leer el producto para distinguir los casos:
     *  - ya vendido con el mismo precio y canal de este pago -> reintento: éxito idempotente, sin tocar nada;
     *  - ya vendido con otro precio o canal -> conflicto real: se rechaza (no se pisa la venta registrada);
     *  - no está vendido -> falló otra regla: se informa el motivo original.
     */
    private fun resolverReintento(request: ConfirmPaymentRequest, causa: IllegalStateException): ConfirmPaymentResult {
        val actual = try {
            repo?.findByKey(request.productKey)
        } catch (e: Exception) {
            null
        }

        if (actual == null || !actual.estaVendido) {
            return ConfirmPaymentResult(success = false, errorMessage = "Error en BD al confirmar pago: ${causa.message}")
        }

        val canalPago = normalizarCanal(request.channel)
        val mismoPago = actual.vendidoEn == request.salePrice && actual.canalVenta == canalPago
        return if (mismoPago) {
            ConfirmPaymentResult(success = true, isIdempotentRepeat = true, item = actual)
        } else {
            ConfirmPaymentResult(
                success = false,
                item = actual,
                errorMessage = "El producto ${request.productKey.value} ya está vendido por " +
                    "${actual.vendidoEn?.formatCop() ?: "un monto no registrado"} (canal ${actual.canalVenta ?: "sin canal"}); " +
                    "este pago es por ${request.salePrice.formatCop()} (canal ${canalPago ?: "sin canal"}). " +
                    "Revísalo antes de confirmar."
            )
        }
    }

    /** Misma normalización que aplica InventarioItem.registrarVenta al guardar el canal. */
    private fun normalizarCanal(canal: String): String? = canal.trim().lowercase().takeIf { it.isNotEmpty() }
}
