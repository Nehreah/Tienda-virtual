package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.app.dashboard.DashboardGuard
import com.automata.app.dashboard.DealState
import com.automata.app.dashboard.requireDashboardAuth
import com.automata.app.pipeline.InboundMessagePipeline
import com.automata.commerce.service.AntiOversellSaga
import com.automata.commerce.service.ConfirmPaymentRequest
import com.automata.commerce.service.ConfirmPaymentUseCase
import com.automata.messaging.ports.ConversationStore
import com.automata.shared.Money
import com.automata.shared.ProductKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PagosRoutes")

fun Route.pagosRoutes(
    dashboardGuard: DashboardGuard,
    confirmPaymentUseCase: ConfirmPaymentUseCase,
    inboundPipeline: InboundMessagePipeline,
    antiOversellSaga: AntiOversellSaga,
    conversationStore: ConversationStore? = null
) {
    post("/api/confirmar-pago") {
        if (!call.requireDashboardAuth(dashboardGuard, mutating = true)) return@post
        val params = call.receiveParameters()
        val dealId = params["dealId"]?.trim()

        if (dealId.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "dealId requerido"))
            return@post
        }

        val deal = synchronized(AppStore.chatDeals) {
            AppStore.chatDeals.firstOrNull { it.id == dealId }
        }

        if (deal?.dealState == DealState.PENDIENTE_ENVIO) {
            call.respondRedirect("/", permanent = false)
            return@post
        }

        val foundProduct = synchronized(AppStore.products) {
            if (deal != null && deal.productKey.isNotBlank()) {
                AppStore.products[deal.productKey]
            } else if (deal != null) {
                AppStore.products.values.firstOrNull { it.itemName.equals(deal.productName, ignoreCase = true) }
            } else null
        }

        val productKey = foundProduct?.key ?: ProductKey(deal?.productKey ?: "")
        
        // Obtener el precio acordado de la conversación en BD si está disponible
        val agreedPriceCop = conversationStore?.findById(dealId)?.agreedPriceCop
        val salePrice = agreedPriceCop?.let { Money.of(it) }
            ?: deal?.offeredPrice
            ?: foundProduct?.publishedPrice
            ?: Money.of(0)

        if (salePrice.amountInCop <= 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El precio de venta debe ser mayor a 0 (A5)"))
            return@post
        }

        // 1. Ejecutar Caso de Uso de Pago (Atómico BD)
        val paymentResult = confirmPaymentUseCase.execute(
            ConfirmPaymentRequest(
                dealId = dealId,
                productKey = productKey,
                salePrice = salePrice,
                channel = deal?.channel ?: "dashboard"
            )
        )

        if (!paymentResult.success) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (paymentResult.errorMessage ?: "Fallo al procesar pago")))
            return@post
        }

        if (!paymentResult.persisted) {
            logger.warn("Pago del trato $dealId confirmado SOLO en memoria: no hay repositorio de BD (modo local).")
        }
        if (paymentResult.isIdempotentRepeat) {
            logger.info("Pago del trato $dealId ya estaba confirmado en BD: se omite descontar el stock en memoria otra vez.")
        }

        // 2. Bloquear reserva en el pipeline y actualizar memoria tras éxito en BD
        val pipelineConfirmed = inboundPipeline.confirmPayment(dealId)

        if (foundProduct != null) {
            synchronized(AppStore.inventory) {
                val currentInv = AppStore.inventory[foundProduct.key.value]
                // En un reintento la venta ya se descontó antes: recordSale no es idempotente y duplicaría el descuento.
                if (currentInv != null && !paymentResult.isIdempotentRepeat) {
                    AppStore.inventory[foundProduct.key.value] = currentInv.recordSale(1, wasReserved = true)
                }
            }

            val externalMappings = AppStore.channelMappings[foundProduct.key.value] ?: emptyMap()
            val remaining = synchronized(AppStore.inventory) {
                AppStore.inventory[foundProduct.key.value]?.stockVivo ?: 0
            }
            try {
                antiOversellSaga.executeRetractionSaga(foundProduct.key, deal?.channel ?: "dashboard", externalMappings, remaining)
            } catch (e: Exception) {
                logger.error("AntiOversellSaga error: ${e.message}", e)
            }
        }

        // 3. Actualizar estado del trato en el dashboard
        synchronized(AppStore.chatDeals) {
            val idx = AppStore.chatDeals.indexOfFirst { it.id == dealId }
            if (idx >= 0) {
                val currentDeal = AppStore.chatDeals[idx]
                val label = if (pipelineConfirmed) "Pago confirmado — pendiente envío" else "Pago confirmado"
                AppStore.chatDeals[idx] = currentDeal.copy(
                    dealState = DealState.PENDIENTE_ENVIO,
                    dealStateLabel = label,
                    offeredPrice = salePrice
                )
            }
        }

        call.respondRedirect("/", permanent = false)
    }
}
