package com.automata.app.pipeline

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.model.Reservation
import com.automata.commerce.service.NegotiationEngine
import com.automata.messaging.model.Conversation
import com.automata.messaging.model.ConversationState
import com.automata.messaging.model.Message
import com.automata.messaging.ports.ChannelRouter
import com.automata.messaging.ports.ConversationStore
import com.automata.messaging.ports.CustomerIntentType
import com.automata.messaging.ports.LlmPort
import com.automata.messaging.ports.ParsedCustomerIntent
import com.automata.shared.Money
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Acceso mínimo al catálogo e inventario que necesita el pipeline (en la app lo implementa AppStore). */
interface PipelineCatalog {
    fun productKeys(): List<ProductKey>
    fun product(key: ProductKey): CommercialProduct?
    fun inventory(key: ProductKey): OperationalInventory?
    fun saveInventory(inventory: OperationalInventory)
}

/** Resultado de procesar un mensaje entrante. [reply] es null cuando el bot se queda en silencio. */
data class PipelineOutcome(
    val conversation: Conversation,
    val reply: String?,
    val productName: String?,
    val amountCop: Long?,
    val dealLabel: String,
    val sendResult: PortResult<String>? = null
)

/**
 * Pipeline de mensajes entrantes:
 * cliente -> conversación -> guardar mensaje -> intención (LLM) -> NegotiationEngine -> reserva -> respuesta (ChannelRouter).
 *
 * Reglas de seguridad:
 *  - El LLM solo interpreta el texto; los precios y el stock los decide el dominio (NegotiationEngine / OperationalInventory).
 *  - Solo se hablan al cliente datos públicos (nombre, descripción, precio publicado, precio acordado).
 *  - El bot NUNCA confirma un pago por su cuenta: un comprobante escala a un asesor humano.
 *  - Mientras la conversación está escalada, el bot calla (y se reactiva solo tras [escalationTimeout]).
 */
class InboundMessagePipeline(
    private val llm: LlmPort,
    private val engine: NegotiationEngine,
    private val catalog: PipelineCatalog,
    private val conversations: ConversationStore,
    private val router: ChannelRouter,
    private val paymentInstructions: String = "Un asesor te enviará los datos de pago en breve.",
    private val reservationTtl: Duration = 15.minutes,
    private val proofHold: Duration = 12.hours,
    private val escalationTimeout: Duration = 24.hours
) {
    private val lock = Mutex()

    /** Reservas activas por id. Solo se toca dentro de [lock]. */
    private val reservations = mutableMapOf<String, Reservation>()

    suspend fun handle(
        channel: String,
        senderId: String,
        messageId: String,
        text: String,
        now: Instant = Clock.System.now()
    ): PipelineOutcome {
        val trimmed = text.trim()
        // Fuera del candado: cuando el LLM sea una llamada HTTP no debe bloquear a los demás clientes.
        val intent: ParsedCustomerIntent? =
            if (trimmed.isEmpty()) null else llm.parseIntent(trimmed, catalog.productKeys())

        val outcome = lock.withLock { decide(channel, senderId, messageId, trimmed, intent, now) }

        val reply = outcome.reply ?: return outcome
        val sent = router.sendMessage(channel, senderId, reply)
        if (sent !is PortResult.Ok) {
            System.err.println("No se pudo responder en [$channel] a $senderId: $sent")
        }
        return outcome.copy(sendResult = sent)
    }

    /** Libera las reservas vencidas. Se llama en cada mensaje y desde un temporizador. */
    suspend fun sweep(now: Instant = Clock.System.now()) {
        lock.withLock { sweepExpired(now) }
    }

    suspend fun activeReservationCount(): Int = lock.withLock { reservations.size }

    // ------------------------------------------------------------------------------------------

    private fun decide(
        channel: String,
        senderId: String,
        messageId: String,
        text: String,
        intent: ParsedCustomerIntent?,
        now: Instant
    ): PipelineOutcome {
        sweepExpired(now)

        val ch = channel.lowercase()
        var conv = conversations.find(ch, senderId) ?: Conversation(
            conversationId = "$ch:$senderId",
            customerPhoneOrId = senderId,
            channel = ch,
            lastUpdated = now
        )
        if (conv.state == ConversationState.CLOSED) {
            conv = conv.copy(state = ConversationState.IDLE)
        }
        if (conv.state == ConversationState.ESCALATED_HUMAN && now - conv.lastUpdated > escalationTimeout) {
            conv = conv.copy(state = ConversationState.IDLE)
        }
        conv = conv.append(Message(messageId, "CUSTOMER", text, now), now)

        // Con un asesor atendiendo, el bot no interviene.
        if (conv.state == ConversationState.ESCALATED_HUMAN) {
            conversations.save(conv)
            return PipelineOutcome(conv, null, productNameOf(conv), conv.agreedPriceCop, label(conv))
        }

        // Mensaje sin texto (foto, audio, sticker...).
        if (intent == null) {
            return if (conv.state == ConversationState.PAYMENT_PENDING) {
                escalate(conv, now, "Recibimos tu archivo. Un asesor verificará tu pago y te confirmará por este chat.", holdReservation = true)
            } else {
                answer(conv, now, "Por ahora solo puedo leer mensajes de texto. Cuéntame qué producto te interesa.")
            }
        }

        when (intent.intentType) {
            CustomerIntentType.REQUEST_HUMAN_AGENT ->
                return escalate(conv, now, "Claro, te comunico con un asesor. Te responderá por este mismo chat.", holdReservation = false)
            CustomerIntentType.SEND_PAYMENT_PROOF -> {
                // Siempre escala (hay dinero de por medio, incluso si la reserva ya venció y el cliente pagó tarde),
                // pero solo dice "recibimos tu comprobante" si realmente había un pago pendiente.
                val awaitingPayment = conv.state == ConversationState.PAYMENT_PENDING || conv.state == ConversationState.RESERVED
                val proofReply = if (awaitingPayment) {
                    "¡Gracias! Recibimos tu comprobante. Un asesor verificará el pago y te confirmará por este chat."
                } else {
                    "Entendido. Te comunico con un asesor para ayudarte con el pago; te responderá por este mismo chat."
                }
                return escalate(conv, now, proofReply, holdReservation = awaitingPayment)
            }
            else -> Unit
        }

        val key = intent.extractedProductKey ?: conv.activeProductKey ?: catalog.productKeys().singleOrNull()
        val product = key?.let { catalog.product(it) }
        if (product == null) {
            return answer(conv, now, askWhichProduct())
        }

        // Si cambió de producto, se libera la reserva del anterior y se olvidan las contraofertas (eran de otro producto).
        conv = releaseIfOtherProduct(conv, product.key)
        if (conv.activeProductKey != null && conv.activeProductKey != product.key) {
            conv = conv.copy(counterOfferCount = 0, pendingCounterCop = null)
        }

        val available = catalog.inventory(product.key)?.isAvailable ?: false
        conv = conv.copy(
            activeProductKey = product.key,
            state = if (conv.state == ConversationState.IDLE) ConversationState.NEGOTIATING else conv.state
        )

        // Ya tiene una reserva viva de este producto: se le recuerda en vez de volver a negociar.
        val active = conv.currentReservationId?.let { reservations[it] }
        if (active != null && active.status == Reservation.ReservationStatus.ACTIVE) {
            val mins = (active.expiresAt - now).inWholeMinutes.coerceAtLeast(0)
            val price = conv.agreedPriceCop?.let { Money.of(it).formatFormattedCop() } ?: product.publishedPrice.formatFormattedCop()
            return answer(
                conv, now,
                "Tu reserva de ${product.itemName} por $price sigue activa ($mins min restantes). $paymentInstructions",
                product
            )
        }

        return when (intent.intentType) {
            CustomerIntentType.MAKE_OFFER -> handleOffer(conv, product, intent.offeredPrice, available, now)
            CustomerIntentType.ACCEPT_COUNTER_OFFER -> {
                // Solo cuenta si realmente hay una contraoferta pendiente; si no, "ok" es una consulta cualquiera.
                // El precio sale de la conversación (lo fijó el servidor), NUNCA del texto del cliente.
                val pending = conv.pendingCounterCop
                if (pending == null) answer(conv, now, infoText(product, available), product, product.publishedPrice.amountInCop)
                else handleOffer(conv, product, Money.of(pending), available, now)
            }
            CustomerIntentType.REQUEST_RESERVATION -> {
                if (!available) answer(conv, now, soldOutText(product), product)
                else reserveAndReply(conv, product, product.publishedPrice, null, now)
            }
            else -> answer(conv, now, infoText(product, available), product, product.publishedPrice.amountInCop)
        }
    }

    private fun handleOffer(
        conv: Conversation,
        product: CommercialProduct,
        offered: Money?,
        available: Boolean,
        now: Instant
    ): PipelineOutcome {
        if (!available) return answer(conv, now, soldOutText(product), product)
        if (offered == null) {
            return answer(
                conv, now,
                "El precio publicado de ${product.itemName} es ${product.publishedPrice.formatFormattedCop()}. " +
                    "Si quieres proponer un valor, escríbeme la cifra.",
                product
            )
        }
        // Una "cifra" enorme casi seguro NO es una oferta (teléfono, cédula, número de guía...): no se reserva por error.
        if (offered.amountInCop > product.publishedPrice.amountInCop * MAX_OFFER_FACTOR) {
            return answer(
                conv, now,
                "No estoy seguro de haber entendido la cifra. Si quieres proponer un valor para ${product.itemName}, " +
                    "escríbelo así: 110000 o 110 mil.",
                product
            )
        }
        // El motor decide (revalida contra el PM vigente). Cada rechazo por debajo del piso baja un escalón la contraoferta.
        val decision = engine.evaluateOffer(product, offered, conv.counterOfferCount)
        if (!decision.accepted) {
            val negotiating = conv.copy(
                state = ConversationState.NEGOTIATING,
                counterOfferCount = (conv.counterOfferCount + 1).coerceAtMost(MAX_COUNTER_COUNT),
                pendingCounterCop = decision.counterOffer?.amountInCop
            )
            return answer(negotiating, now, decision.message, product, offered.amountInCop)
        }
        return reserveAndReply(conv, product, decision.agreedPrice ?: product.publishedPrice, decision.message, now)
    }

    private fun reserveAndReply(
        conv: Conversation,
        product: CommercialProduct,
        agreed: Money,
        prefix: String?,
        now: Instant
    ): PipelineOutcome {
        val reservation = tryReserve(conv, product, now) ?: return answer(conv, now, soldOutText(product), product)
        val updated = conv.copy(
            state = ConversationState.PAYMENT_PENDING,
            currentReservationId = reservation.reservationId,
            agreedPriceCop = agreed.amountInCop,
            counterOfferCount = 0,
            pendingCounterCop = null
        )
        val text = buildString {
            if (!prefix.isNullOrBlank()) append(prefix).append(' ')
            append("Te reservamos ${product.itemName} por ${reservationTtl.inWholeMinutes} minutos a ${agreed.formatFormattedCop()}. ")
            append(paymentInstructions)
            append(" Cuando pagues, envíanos el comprobante por este chat.")
        }
        return answer(updated, now, text, product, agreed.amountInCop)
    }

    private fun tryReserve(conv: Conversation, product: CommercialProduct, now: Instant): Reservation? {
        val inv = catalog.inventory(product.key) ?: return null
        if (!inv.isAvailable) return null
        catalog.saveInventory(inv.addReservation(1))
        val reservation = Reservation(
            reservationId = "RES-${UUID.randomUUID()}",
            conversationId = conv.conversationId,
            productKey = product.key,
            quantity = 1,
            createdAt = now,
            expiresAt = now + reservationTtl
        )
        reservations[reservation.reservationId] = reservation
        return reservation
    }

    private fun releaseIfOtherProduct(conv: Conversation, newKey: ProductKey): Conversation {
        val id = conv.currentReservationId ?: return conv
        val r = reservations[id] ?: return conv.copy(currentReservationId = null, agreedPriceCop = null)
        if (r.productKey == newKey) return conv
        releaseStock(r)
        reservations.remove(id)
        return conv.copy(currentReservationId = null, agreedPriceCop = null, state = ConversationState.NEGOTIATING)
    }

    private fun escalate(conv: Conversation, now: Instant, reply: String, holdReservation: Boolean): PipelineOutcome {
        if (holdReservation) {
            // Un pago en verificación no puede perder su reserva a los 15 minutos.
            val r = conv.currentReservationId?.let { reservations[it] }
            if (r != null && r.status == Reservation.ReservationStatus.ACTIVE) {
                reservations[r.reservationId] = r.copy(expiresAt = now + proofHold)
            }
        }
        return answer(conv.copy(state = ConversationState.ESCALATED_HUMAN), now, reply, productOf(conv))
    }

    private fun sweepExpired(now: Instant) {
        val expired = reservations.values.filter { it.status == Reservation.ReservationStatus.ACTIVE && now >= it.expiresAt }
        for (r in expired) {
            releaseStock(r)
            reservations.remove(r.reservationId)
            val c = conversations.findById(r.conversationId) ?: continue
            if (c.currentReservationId == r.reservationId) {
                val newState = when (c.state) {
                    ConversationState.RESERVED, ConversationState.PAYMENT_PENDING -> ConversationState.NEGOTIATING
                    else -> c.state
                }
                conversations.save(c.copy(state = newState, currentReservationId = null, agreedPriceCop = null, lastUpdated = now))
            }
        }
    }

    private fun releaseStock(r: Reservation) {
        catalog.inventory(r.productKey)?.let { catalog.saveInventory(it.releaseReservation(r.quantity)) }
    }

    // ---- respuestas ----------------------------------------------------------------------------

    private fun answer(
        conv: Conversation,
        now: Instant,
        reply: String,
        product: CommercialProduct? = null,
        amountCop: Long? = null
    ): PipelineOutcome {
        val updated = conv.append(Message("bot-${UUID.randomUUID()}", "BOT", reply, now), now)
        conversations.save(updated)
        val shownProduct = product ?: productOf(updated)
        return PipelineOutcome(
            conversation = updated,
            reply = reply,
            productName = shownProduct?.itemName,
            amountCop = amountCop ?: updated.agreedPriceCop ?: shownProduct?.publishedPrice?.amountInCop,
            dealLabel = label(updated)
        )
    }

    private fun infoText(product: CommercialProduct, available: Boolean): String {
        val desc = product.description.trim().take(200)
        return listOfNotNull(
            "${product.itemName}: ${product.publishedPrice.formatFormattedCop()}.",
            desc.takeIf { it.isNotEmpty() },
            if (available) "Tenemos disponibilidad." else "Por ahora está agotado.",
            if (available) "Si quieres proponer un precio, escríbeme la cifra." else null
        ).joinToString(" ")
    }

    private fun soldOutText(product: CommercialProduct): String =
        "Lo sentimos, ${product.itemName} ya no está disponible por ahora."

    private fun askWhichProduct(): String {
        val lines = catalog.productKeys().take(5).mapNotNull { catalog.product(it) }
            .joinToString("\n") { "• ${it.key} – ${it.itemName} (${it.publishedPrice.formatFormattedCop()})" }
        return if (lines.isEmpty()) "Por ahora no tenemos productos disponibles."
        else "¿Cuál producto te interesa? Escríbeme el código:\n$lines"
    }

    private fun productOf(conv: Conversation): CommercialProduct? = conv.activeProductKey?.let { catalog.product(it) }

    private fun productNameOf(conv: Conversation): String? = productOf(conv)?.itemName

    private fun label(conv: Conversation): String = when (conv.state) {
        ConversationState.IDLE, ConversationState.NEGOTIATING -> "En consulta / negociación"
        ConversationState.RESERVED -> "Reservado"
        ConversationState.PAYMENT_PENDING -> "Reservado, esperando pago"
        ConversationState.PAID_CONFIRMED -> "Pagado"
        ConversationState.ESCALATED_HUMAN -> "Escalado a asesor humano"
        ConversationState.CLOSED -> "Cerrado"
    }

    private fun Conversation.append(message: Message, now: Instant): Conversation =
        copy(messages = (messages + message).takeLast(MAX_MESSAGES), lastUpdated = now)

    private companion object {
        const val MAX_MESSAGES = 50

        /** Tope del contador de contraofertas (evita crecer sin límite si el cliente insiste). */
        const val MAX_COUNTER_COUNT = 10

        /** Una oferta mayor a este múltiplo del precio publicado se considera un error de lectura, no una oferta. */
        const val MAX_OFFER_FACTOR = 10L
    }
}
