package com.automata.infra.db

import com.automata.messaging.model.Conversation
import com.automata.messaging.model.ConversationState
import com.automata.messaging.model.Message
import com.automata.messaging.ports.ConversationStore
import com.automata.shared.ProductKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant

class ExposedConversationStore : ConversationStore {

    private val logger = LoggerFactory.getLogger(ExposedConversationStore::class.java)

    override fun find(channel: String, customerId: String): Conversation? = transaction {
        val convId = "${channel.lowercase()}:$customerId"
        ConversacionesTable.select { ConversacionesTable.conversationId eq convId }
            .singleOrNull()
            ?.toConversation()
    }

    override fun findById(conversationId: String): Conversation? = transaction {
        ConversacionesTable.select { ConversacionesTable.conversationId eq conversationId }
            .singleOrNull()
            ?.toConversation()
    }

    override fun save(conversation: Conversation): Unit = transaction {
        val mensajesJson = try {
            Json.encodeToString(ListSerializer(Message.serializer()), conversation.messages)
        } catch (e: Exception) {
            "[]"
        }

        // IMPORTANTE: Las columnas de control humano (atencion, atendido_por, atencion_desde,
        // requiere_atencion, ultimo_entrante_en) NUNCA se actualizan desde save().
        // Solo pueden cambiar a través de las funciones SQL tomar_conversacion(),
        // devolver_conversacion() y registrar_mensaje_cliente(). Esto garantiza que una copia
        // vieja de la conversación en memoria no revierta el modo de atención humana.
        val count = ConversacionesTable.update({ ConversacionesTable.conversationId eq conversation.conversationId }) { row ->
            row[ConversacionesTable.customerId] = conversation.customerPhoneOrId
            row[ConversacionesTable.channel] = conversation.channel
            row[ConversacionesTable.state] = conversation.state.name
            row[ConversacionesTable.activeProductKey] = conversation.activeProductKey?.value
            row[ConversacionesTable.agreedPriceCop] = conversation.agreedPriceCop
            row[ConversacionesTable.counterOfferCount] = conversation.counterOfferCount
            row[ConversacionesTable.pendingCounterCop] = conversation.pendingCounterCop
            row[ConversacionesTable.pendingCounterAt] = conversation.pendingCounterAt
                ?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }
            row[ConversacionesTable.currentReservationId] = conversation.currentReservationId
            row[ConversacionesTable.mensajes] = mensajesJson
            row[ConversacionesTable.lastUpdated] = Instant.ofEpochMilli(conversation.lastUpdated.toEpochMilliseconds())
        }

        if (count == 0) {
            ConversacionesTable.insert { row ->
                row[ConversacionesTable.conversationId] = conversation.conversationId
                row[ConversacionesTable.customerId] = conversation.customerPhoneOrId
                row[ConversacionesTable.channel] = conversation.channel
                row[ConversacionesTable.state] = conversation.state.name
                row[ConversacionesTable.activeProductKey] = conversation.activeProductKey?.value
                row[ConversacionesTable.agreedPriceCop] = conversation.agreedPriceCop
                row[ConversacionesTable.counterOfferCount] = conversation.counterOfferCount
                row[ConversacionesTable.pendingCounterCop] = conversation.pendingCounterCop
                row[ConversacionesTable.pendingCounterAt] = conversation.pendingCounterAt
                    ?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }
                row[ConversacionesTable.currentReservationId] = conversation.currentReservationId
                row[ConversacionesTable.mensajes] = mensajesJson
                row[ConversacionesTable.lastUpdated] = Instant.ofEpochMilli(conversation.lastUpdated.toEpochMilliseconds())
                // atencion = 'BOT' por defecto en el esquema; nunca se escribe aquí
            }
        }
    }

    override fun all(): List<Conversation> = transaction {
        ConversacionesTable.selectAll().map { it.toConversation() }
    }

    // -------------------------------------------------------------------------------------------
    // Persistencia de mensajes (fuente de verdad: conversacion_mensajes)
    // -------------------------------------------------------------------------------------------

    /**
     * Registra un mensaje del cliente. Idempotente: si [canalMsgId] ya existe no inserta nada.
     * También actualiza `ultimo_entrante_en` en la conversación.
     * @return id de la fila en conversacion_mensajes, o null si la conversación no existe.
     */
    override fun registrarMensajeCliente(convId: String, canalMsgId: String?, contenido: String): Long? = transaction {
        try {
            val jsonRes = exec(
                "SELECT tienda.registrar_mensaje_cliente(?, ?, ?) AS id",
                args = listOf(
                    org.jetbrains.exposed.sql.TextColumnType() to convId,
                    org.jetbrains.exposed.sql.TextColumnType() to canalMsgId,
                    org.jetbrains.exposed.sql.TextColumnType() to contenido
                )
            ) { rs -> if (rs.next()) rs.getLong("id").takeIf { !rs.wasNull() } else null }
            jsonRes
        } catch (e: Exception) {
            logger.warn("registrar_mensaje_cliente falló para $convId: ${e.message}")
            null
        }
    }

    override fun registrarMensajeBot(convId: String, contenido: String): Long? = transaction {
        try {
            val jsonRes = exec(
                "SELECT tienda.registrar_mensaje_bot(?, ?) AS id",
                args = listOf(
                    org.jetbrains.exposed.sql.TextColumnType() to convId,
                    org.jetbrains.exposed.sql.TextColumnType() to contenido
                )
            ) { rs -> if (rs.next()) rs.getLong("id").takeIf { !rs.wasNull() } else null }
            jsonRes
        } catch (e: Exception) {
            logger.warn("registrar_mensaje_bot falló para $convId: ${e.message}")
            null
        }
    }

    override fun marcarEnvio(msgId: Long, estado: String, error: String?, canalMsgId: String?) {
        transaction {
            try {
                exec(
                    "SELECT tienda.marcar_envio(?, ?, ?, ?)",
                    args = listOf(
                        org.jetbrains.exposed.sql.LongColumnType() to msgId,
                        org.jetbrains.exposed.sql.TextColumnType() to estado,
                        org.jetbrains.exposed.sql.TextColumnType() to error,
                        org.jetbrains.exposed.sql.TextColumnType() to canalMsgId
                    )
                ) { rs -> }
            } catch (e: Exception) {
                logger.warn("marcar_envio falló para msg $msgId: ${e.message}")
            }
        }
    }

    /** B-4: Enciende requiere_atencion y encola evento SSE de tipo "atencion". */
    override fun marcarRequiereAtencion(convId: String, motivo: String) {
        transaction {
            try {
                exec(
                    "SELECT tienda.marcar_requiere_atencion(?, ?)",
                    args = listOf(
                        org.jetbrains.exposed.sql.TextColumnType() to convId,
                        org.jetbrains.exposed.sql.TextColumnType() to motivo
                    )
                ) { rs -> }
            } catch (e: Exception) {
                logger.warn("marcar_requiere_atencion falló para $convId: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------------------------

    private fun ResultRow.toConversation(): Conversation {
        val stateName = this[ConversacionesTable.state]
        val convState = try {
            ConversationState.valueOf(stateName)
        } catch (e: Exception) {
            ConversationState.IDLE
        }

        val pendingAtJava = this[ConversacionesTable.pendingCounterAt]
        val lastUpdatedJava = this[ConversacionesTable.lastUpdated]

        // B-7: Cargamos los ÚLTIMOS 50 mensajes (DESC) y luego los revertimos para
        // mantener el orden cronológico en el contexto del LLM.
        val convId = this[ConversacionesTable.conversationId]
        val messagesList = ConversacionMensajesTable.select { ConversacionMensajesTable.conversationId eq convId }
            .orderBy(ConversacionMensajesTable.creadoEn to org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(50)
            .map { row ->
                Message(
                    messageId = row[ConversacionMensajesTable.canalMsgId] ?: row[ConversacionMensajesTable.id].toString(),
                    sender = row[ConversacionMensajesTable.emisor],
                    content = row[ConversacionMensajesTable.contenido],
                    timestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(row[ConversacionMensajesTable.creadoEn].toEpochMilli())
                )
            }
            .reversed() // Revertir para que el orden sea cronológico (más antiguo → más reciente)

        return Conversation(
            conversationId = this[ConversacionesTable.conversationId],
            customerPhoneOrId = this[ConversacionesTable.customerId],
            channel = this[ConversacionesTable.channel],
            activeProductKey = this[ConversacionesTable.activeProductKey]?.let { ProductKey(it) },
            state = convState,
            messages = messagesList,
            currentReservationId = this[ConversacionesTable.currentReservationId],
            agreedPriceCop = this[ConversacionesTable.agreedPriceCop],
            counterOfferCount = this[ConversacionesTable.counterOfferCount],
            pendingCounterCop = this[ConversacionesTable.pendingCounterCop],
            pendingCounterAt = pendingAtJava?.let {
                kotlinx.datetime.Instant.fromEpochMilliseconds(it.toEpochMilli())
            },
            lastUpdated = kotlinx.datetime.Instant.fromEpochMilliseconds(lastUpdatedJava.toEpochMilli()),
            atencion = this[ConversacionesTable.atencion]
        )
    }
}
