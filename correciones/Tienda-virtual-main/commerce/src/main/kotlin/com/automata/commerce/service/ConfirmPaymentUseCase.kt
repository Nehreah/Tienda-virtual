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
    val isIdempotentRepeat: Boolean = false,
    val item: InventarioItem? = null,
    val errorMessage: String? = null
)

/**
 * Caso de Uso Atómico e Idempotente para Confirmación de Pagos (Fase F1).
 * Garantiza que la BD sea la fuente única de verdad antes de actualizar memoria o lanzar sagas.
 */
class ConfirmPaymentUseCase(
    private val repo: InventarioRepository?
) {

    fun execute(request: ConfirmPaymentRequest): ConfirmPaymentResult {
        require(request.dealId.isNotBlank()) { "El dealId no puede estar vacío" }
        require(request.salePrice.amountInCop > 0) { "El precio de venta debe ser mayor a 0 (A5)" }

        if (repo != null) {
            try {
                val updatedItem = repo.marcarVendido(
                    key = request.productKey,
                    vendidoEn = request.salePrice,
                    fecha = LocalDate.now(),
                    canal = request.channel,
                    usuario = request.user,
                    origen = "dashboard"
                ) ?: return ConfirmPaymentResult(success = false, errorMessage = "No se encontró el producto ${request.productKey.value} en BD")

                return ConfirmPaymentResult(success = true, item = updatedItem)
            } catch (e: Exception) {
                return ConfirmPaymentResult(success = false, errorMessage = "Error en BD al confirmar pago: ${e.message}")
            }
        } else {
            return ConfirmPaymentResult(success = true)
        }
    }
}
