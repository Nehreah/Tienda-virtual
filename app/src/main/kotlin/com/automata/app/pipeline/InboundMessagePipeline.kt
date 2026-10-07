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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Acceso mínimo al catálogo e inventario que necesita el pipeline (en la app lo implementa AppStore). */
interface PipelineCatalog {
    /** Claves de los productos que se pueden vender (los Inactivos no aparecen: Fase 2.2). */
    fun productKeys(): List<ProductKey>

    /**
     * Todas las claves conocidas, activas o no. Solo sirve para que el intérprete reconozca el código de un producto
     * retirado y el bot responda "no disponible" en vez de confundirlo con otro producto.
     */
    fun knownKeys(): List<ProductKey> = productKeys()

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
    // Bloqueos finos: por conversación y por SKU/producto (Fase F3 - Hallazgo A3)
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()
    private val productLocks = ConcurrentHashMap<ProductKey, Mutex>()

    private fun lockFor(conversationId: String): Mutex =
        conversationLocks.computeIfAbsent(conversationId) { Mutex() }

    private fun lockForProduct(key: ProductKey): Mutex =
        productLocks.computeIfAbsent(key) { Mutex() }

    /** Reservas activas concurrentes por id. */
    private val reservations = ConcurrentHashMap<String, Reservation>()

    suspend fun handle(
        channel: String,
        senderId: String,
        messageId: String,
        text: String,
        now: Instant = Clock.System.now()
    ): PipelineOutcome = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        // Fuera del candado: cuando el LLM sea una llamada HTTP no debe bloquear a los demás clientes.
        val intent: ParsedCustomerIntent? =
            if (trimmed.isEmpty()) null else llm.parseIntent(trimmed, catalog.knownKeys())

        val convId = "${channel.lowercase()}:$senderId"
        val outcome = lockFor(convId).withLock {
            decide(channel, senderId, messageId, trimmed, intent, now)
        }

        val reply = outcome.reply ?: return@withContext outcome
        val sent = router.sendMessage(channel, senderId, reply)
        if (sent !is PortResult.Ok) {
            System.err.println("No se pudo responder en [$channel] a $senderId: $sent")
        }
        outcome.copy(sendResult = sent)
    }

    /** Libera las reservas vencidas. Se llama en cada mensaje y desde un temporizador. */
    suspend fun sweep(now: Instant = Clock.System.now()): Unit = withContext(Dispatchers.IO) {
        sweepExpired(now)
    }

    suspend fun activeReservationCount(): Int = reservations.size

    /**
     * Fase 1.5 — Hotfix del pago: el asesor confirma el pago desde el dashboard.
     * Marca la reserva asociada a [conversationId] como CONFIRMED (no vence más).
     * La conversación pasa a PAID_CONFIRMED y la reserva se extiende 365 días para que
     * el barrido de vencidas NO la libere. Devuelve true si se encontró y confirmó.
     */
    suspend fun confirmPayment(conversationId: String, now: Instant = Clock.System.now()): Boolean =
        withContext(Dispatchers.IO) {
            val conv = conversations.findById(conversationId) ?: return@withContext false
            val reservationId = conv.currentReservationId ?: return@withContext false
            val reservation = reservations[reservationId] ?: return@withContext false
            // Extender la expiración 1 año: ningún barrido la tocará.
            val permanent = reservation.copy(expiresAt = now + 8_760.hours)  // 365 * 24 h
            reservations[reservationId] = permanent
            // Actualizar estado de la conversación a PAID_CONFIRMED.
            val confirmed = conv.copy(state = ConversationState.PAID_CONFIRMED, lastUpdated = now)
            conversations.save(confirmed)
            true
        }

    // ------------------------------------------------------------------------------------------

    private suspend fun decide(
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

        // Fase 2.2: un producto Inactivo no se negocia ni se reserva. Se responde "no disponible" y se ofrecen los demás.
        if (!product.isActive) {
            val withoutInactive = if (conv.activeProductKey == product.key) conv.copy(activeProductKey = null) else conv
            return answer(withoutInactive, now, "${soldOutText(product)} ${askWhichProduct()}", product)
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
                val pending = conv.pendingCounterCop
                val counterAge = conv.pendingCounterAt?.let { now - it }
                val counterExpired = counterAge != null && counterAge > 30.minutes
                when {
                    pending == null -> answer(conv, now, infoText(product, available), product, product.publishedPrice.amountInCop)
                    counterExpired -> {
                        val fresh = conv.copy(pendingCounterCop = null, pendingCounterAt = null)
                        answer(fresh, now,
                            "Lo sentimos, esa oferta ya expiró. El precio actual de ${product.itemName} es ${product.publishedPrice.formatFormattedCop()}. " +
                            "Si quieres proponer un nuevo valor, escríbeme la cifra.",
                            product, product.publishedPrice.amountInCop)
                    }
                    else -> {
                        val agreedPrice = Money.of(pending)
                        if (!available) answer(conv, now, soldOutText(product), product)
                        else reserveAndReply(conv, product, agreedPrice, "¡Trato hecho!", now)
                    }
                }
            }
            CustomerIntentType.REQUEST_RESERVATION -> {
                if (!available) answer(conv, now, soldOutText(product), product)
                else reserveAndReply(conv, product, product.publishedPrice, null, now)
            }
            else -> answer(conv, now, infoText(product, available), product, product.publishedPrice.amountInCop)
        }
    }

    private suspend fun handleOffer(
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
        if (offered.amountInCop > product.publishedPrice.amountInCop * MAX_OFFER_FACTOR) {
            return answer(
                conv, now,
                "No estoy seguro de haber entendido la cifra. Si quieres proponer un valor para ${product.itemName}, " +
                    "escríbelo así: 110000 o 110 mil.",
                product
            )
        }
        val decision = engine.evaluateOffer(product, offered, conv.counterOfferCount)
        if (!decision.accepted || decision.isEscalatedToHuman) {
            if (decision.isEscalatedToHuman) {
                return escalate(conv, now, decision.message, holdReservation = false)
            }
            val negotiating = conv.copy(
                state = ConversationState.NEGOTIATING,
                counterOfferCount = (conv.counterOfferCount + 1).coerceAtMost(MAX_COUNTER_COUNT),
                pendingCounterCop = decision.counterOffer?.amountInCop,
                pendingCounterAt  = now
            )
            return answer(negotiating, now, decision.message, product, offered.amountInCop)
        }
        return reserveAndReply(conv, product, decision.agreedPrice ?: product.publishedPrice, decision.message, now)
    }

    private suspend fun reserveAndReply(
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

    private suspend fun tryReserve(conv: Conversation, product: CommercialProduct, now: Instant): Reservation? {
        if (!product.isActive) return null
        return lockForProduct(product.key).withLock {
            val inv = catalog.inventory(product.key) ?: return@withLock null
            if (!inv.isAvailable) return@withLock null
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
            reservation
        }
    }

    private suspend fun releaseIfOtherProduct(conv: Conversation, newKey: ProductKey): Conversation {
        val id = conv.currentReservationId ?: return conv
        val r = reservations[id] ?: return conv.copy(currentReservationId = null, agreedPriceCop = null)
        if (r.productKey == newKey) return conv
        releaseStock(r)
        reservations.remove(id)
        return conv.copy(currentReservationId = null, agreedPriceCop = null, state = ConversationState.NEGOTIATING)
    }

    private suspend fun escalate(conv: Conversation, now: Instant, reply: String, holdReservation: Boolean): PipelineOutcome {
        if (holdReservation) {
            val r = conv.currentReservationId?.let { reservations[it] }
            if (r != null && r.status == Reservation.ReservationStatus.ACTIVE) {
                reservations[r.reservationId] = r.copy(expiresAt = now + proofHold)
            }
        }
        return answer(conv.copy(state = ConversationState.ESCALATED_HUMAN), now, reply, productOf(conv))
    }

    private suspend fun sweepExpired(now: Instant) {
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
        // Limpiar candados inactivos (evita crecimiento en memoria)
        conversationLocks.entries.removeIf { !it.value.isLocked }
        productLocks.entries.removeIf { !it.value.isLocked }
    }

    private suspend fun releaseStock(r: Reservation) {
        lockForProduct(r.productKey).withLock {
            catalog.inventory(r.productKey)?.let { catalog.saveInventory(it.releaseReservation(r.quantity)) }
        }
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
