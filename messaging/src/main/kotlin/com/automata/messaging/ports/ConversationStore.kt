package com.automata.messaging.ports

import com.automata.messaging.model.Conversation
import com.automata.messaging.model.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * Almacén de conversaciones. Hay UNA conversación por (canal, id del cliente en ese canal);
 * su id es siempre "<canal>:<idCliente>", así que buscarla nunca crea duplicados.
 */
interface ConversationStore {
    fun find(channel: String, customerId: String): Conversation?
    fun findById(conversationId: String): Conversation?
    fun save(conversation: Conversation)
    fun all(): List<Conversation>

    // Métodos para el nuevo modelo de persistencia de mensajes (Fase 2)
    fun registrarMensajeCliente(convId: String, canalMsgId: String?, contenido: String): Long? = null
    fun registrarMensajeBot(convId: String, contenido: String): Long? = null
    fun marcarEnvio(msgId: Long, estado: String, error: String? = null, canalMsgId: String? = null) {}
    /** B-4: Activa la bandera "requiere atención" en la BD y encola un evento SSE de tipo "atencion". */
    fun marcarRequiereAtencion(convId: String, motivo: String = "escalacion") {}
}

/**
 * Implementación en memoria (segura entre hilos). Se pierde al reiniciar:
 * la versión persistente en PostgreSQL queda pendiente (ver manual).
 */
class InMemoryConversationStore : ConversationStore {

    private val byId = ConcurrentHashMap<String, Conversation>()

    override fun find(channel: String, customerId: String): Conversation? =
        byId["${channel.lowercase()}:$customerId"]

    override fun findById(conversationId: String): Conversation? = byId[conversationId]

    override fun save(conversation: Conversation) {
        byId[conversation.conversationId] = conversation
    }

    override fun all(): List<Conversation> = byId.values.toList()

    override fun registrarMensajeCliente(convId: String, canalMsgId: String?, contenido: String): Long? {
        val conv = byId[convId]
        if (conv != null) {
            byId[convId] = conv.copy(messages = conv.messages + Message(canalMsgId ?: "mock_id", "CLIENTE", contenido))
        }
        return 1L
    }

    override fun registrarMensajeBot(convId: String, contenido: String): Long? {
        val conv = byId[convId]
        if (conv != null && conv.atencion == "HUMANO") return null
        
        if (conv != null) {
            byId[convId] = conv.copy(messages = conv.messages + Message("mock_bot", "BOT", contenido))
        }
        return 2L
    }

    override fun marcarEnvio(msgId: Long, estado: String, error: String?, canalMsgId: String?) {
        // No-op for tests
    }
}
