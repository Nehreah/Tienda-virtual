package com.automata.app.routes

import com.automata.adapters.channels.MetaWebhookReceiver
import com.automata.adapters.shopify.ShopifyWebhookHandler
import com.automata.app.AppStore
import com.automata.app.dashboard.DealState
import com.automata.app.dashboard.ChatDealLog
import com.automata.app.pipeline.InboundMessagePipeline
import com.automata.app.pipeline.PipelineOutcome
import com.automata.app.workers.InboundWebhookWorker
import com.automata.commerce.ports.InventarioRepository
import com.automata.commerce.service.AntiOversellSaga
import com.automata.infra.config.AppConfig
import com.automata.messaging.model.ConversationState
import com.automata.messaging.ports.ProcessedEventStore
import com.automata.shared.Money
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("WebhookRoutes")

private fun channelDisplayName(channel: String): String = when (channel.lowercase()) {
    "whatsapp" -> "WhatsApp"
    "instagram" -> "Instagram DM"
    "messenger" -> "Messenger"
    else -> channel
}

private fun recordDeal(outcome: PipelineOutcome) {
    val conv = outcome.conversation
    val state = when (conv.state) {
        ConversationState.PAID_CONFIRMED -> DealState.PENDIENTE_ENVIO
        ConversationState.CLOSED -> DealState.CANCELADO
        else -> DealState.EN_NEGOCIACION
    }
    val deal = ChatDealLog(
        id = conv.conversationId,
        customer = conv.customerPhoneOrId,
        channel = conv.channel,
        channelDisplayName = channelDisplayName(conv.channel),
        productName = outcome.productName ?: "—",
        offeredPrice = Money.of(outcome.amountCop ?: 0L),
        dealState = state,
        dealStateLabel = outcome.dealLabel,
        timestamp = java.time.LocalTime.now().withSecond(0).withNano(0).toString(),
        productKey = conv.activeProductKey?.value ?: ""
    )
    synchronized(AppStore.chatDeals) {
        AppStore.chatDeals.removeAll { it.id == deal.id }
        AppStore.chatDeals.add(0, deal)
        if (AppStore.chatDeals.size > 100) {
            AppStore.chatDeals.subList(100, AppStore.chatDeals.size).clear()
        }
    }
}

fun Route.webhookRoutes(
    shopifyWebhookHandler: ShopifyWebhookHandler,
    metaWebhookReceiver: MetaWebhookReceiver,
    processedEvents: ProcessedEventStore,
    dbRepository: InventarioRepository?,
    antiOversellSaga: AntiOversellSaga,
    inboundPipeline: InboundMessagePipeline,
    pipelineScope: CoroutineScope,
    inboundWorker: InboundWebhookWorker? = null
) {
    // 5. Webhook Shopify HMAC
    post("/webhooks/shopify") {
        val topic = call.request.headers["X-Shopify-Topic"]
        val hmac = call.request.headers["X-Shopify-Hmac-SHA256"]
        
        // Fase 4.2 / Hallazgo 6: lectura segura con verificación de tamaño en bytes recibidos (anti-chunked OOM)
        val bodyBytes = call.receive<ByteArray>()
        if (bodyBytes.size > 1_048_576) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Payload demasiado grande (máx 1 MB)"))
            return@post
        }

        val result = shopifyWebhookHandler.handleWebhook(topic, hmac, bodyBytes)

        if (!result.success) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to result.message))
            return@post
        }

        val shopifyEventId = call.request.headers["X-Shopify-Event-Id"]
            ?: call.request.headers["X-Shopify-Webhook-Id"]
        if (shopifyEventId != null && !processedEvents.tryStartProcessing("shopify", shopifyEventId)) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate_ignored"))
            return@post
        }

        try {
            for (soldEvent in result.emittedEvents) {
                val keyStr = soldEvent.productKey.value
                if (soldEvent.quantity < 0) {
                    val returnQty = -soldEvent.quantity
                    if (dbRepository != null) {
                        dbRepository.devolverUnidades(soldEvent.orderId ?: "", null, returnQty)
                    }
                    synchronized(AppStore.inventory) {
                        val inv = AppStore.inventory[keyStr]
                        if (inv != null) {
                            AppStore.inventory[keyStr] = inv.copy(
                                unidadesVendidas = maxOf(0, inv.unidadesVendidas - returnQty),
                                version = inv.version + 1
                            )
                        }
                    }
                } else {
                    var ventaExitosa = true
                    if (dbRepository != null) {
                        ventaExitosa = dbRepository.venderUnidades(
                            key = soldEvent.productKey,
                            quantity = soldEvent.quantity,
                            eventId = soldEvent.eventId,
                            origen = "shopify",
                            precioUnit = soldEvent.soldPrice,
                            orderId = soldEvent.orderId
                        )
                    }
                    if (ventaExitosa) {
                        val updatedInv = synchronized(AppStore.inventory) {
                            AppStore.inventory[keyStr]?.recordSale(soldEvent.quantity)
                                ?.also { AppStore.inventory[keyStr] = it }
                        }
                        if (updatedInv != null) {
                            antiOversellSaga.executeRetractionSaga(
                                productKey     = soldEvent.productKey,
                                originChannel  = "shopify",
                                externalIdMap  = AppStore.channelMappings[keyStr] ?: emptyMap(),
                                remainingStock = updatedInv.stockVivo
                            )
                        }
                    } else {
                        logger.warn("Venta rechazada por BD (posible duplicado de orderId ${soldEvent.orderId}). No se descuenta stock en memoria.")
                    }
                }
            }
            if (shopifyEventId != null) processedEvents.markDone("shopify", shopifyEventId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (shopifyEventId != null) processedEvents.markFailed("shopify", shopifyEventId, e.message)
            logger.error("Error procesando webhook de Shopify: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error procesando orden de Shopify"))
            return@post
        }

        call.respond(HttpStatusCode.OK, mapOf("status" to "processed", "message" to result.message))
    }

    // 6. Webhook Meta
    get("/webhooks/meta") {
        val mode = call.request.queryParameters["hub.mode"]
        val token = call.request.queryParameters["hub.verify_token"]
        val challenge = call.request.queryParameters["hub.challenge"]

        val challengeResult = metaWebhookReceiver.verifyWebhookChallenge(mode, token, challenge)
        if (challengeResult != null) {
            call.respondText(challengeResult, ContentType.Text.Plain)
        } else {
            call.respond(HttpStatusCode.Forbidden, "Token de verificación de Meta inválido")
        }
    }

    post("/webhooks/meta") {
        // Fase 4.2 / Hallazgo 6: lectura segura con verificación de tamaño en bytes recibidos (anti-chunked OOM)
        val bodyBytes = call.receive<ByteArray>()
        if (bodyBytes.size > 1_048_576) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Payload demasiado grande (máx 1 MB)"))
            return@post
        }

        when (metaWebhookReceiver.authenticate(bodyBytes, call.request.headers["X-Hub-Signature-256"], AppConfig.metaAppSecret)) {
            MetaWebhookReceiver.SignatureResult.SECRET_NOT_CONFIGURED -> {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Webhook deshabilitado: falta META_APP_SECRET"))
                return@post
            }
            MetaWebhookReceiver.SignatureResult.INVALID -> {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Firma X-Hub-Signature-256 inválida"))
                return@post
            }
            MetaWebhookReceiver.SignatureResult.VALID -> Unit
        }

        val inboundEvents = metaWebhookReceiver.parseInboundPayload(bodyBytes.toString(Charsets.UTF_8))

        for (event in inboundEvents) {
            if (!processedEvents.tryStartProcessing(event.channel, event.messageId)) continue
            
            if (inboundWorker != null) {
                // Cola durable en BD: guardar primero antes de responder 200 a Meta
                inboundWorker.enqueue(event.channel, event.senderId, event.messageId, event.text)
                processedEvents.markDone(event.channel, event.messageId)
            } else {
                // Modo sin BD: procesar en segundo plano en memoria
                pipelineScope.launch {
                    try {
                        recordDeal(inboundPipeline.handle(event.channel, event.senderId, event.messageId, event.text))
                        processedEvents.markDone(event.channel, event.messageId)
                    } catch (e: CancellationException) {
                        processedEvents.markFailed(event.channel, event.messageId, "Cancelled")
                        throw e
                    } catch (e: Exception) {
                        processedEvents.markFailed(event.channel, event.messageId, e.message)
                        logger.error("Error procesando mensaje de [${event.channel}] ${event.senderId}: ${e.message}", e)
                    }
                }
            }
        }

        call.respond(HttpStatusCode.OK, mapOf("status" to "EVENT_RECEIVED"))
    }
}
