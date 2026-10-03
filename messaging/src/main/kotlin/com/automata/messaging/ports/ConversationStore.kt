package com.automata.messaging.ports

import com.automata.messaging.model.Conversation
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
}
