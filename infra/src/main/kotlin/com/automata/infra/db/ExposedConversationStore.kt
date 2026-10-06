package com.automata.infra.db

import com.automata.messaging.model.Conversation
import com.automata.messaging.model.ConversationState
import com.automata.messaging.ports.ConversationStore
import com.automata.shared.ProductKey
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

class ExposedConversationStore : ConversationStore {

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
        val count = ConversacionesTable.update({ ConversacionesTable.conversationId eq conversation.conversationId }) { row ->
            row[ConversacionesTable.customerId] = conversation.customerPhoneOrId
            row[ConversacionesTable.channel] = conversation.channel
            row[ConversacionesTable.state] = conversation.state.name
            row[ConversacionesTable.activeProductKey] = conversation.activeProductKey?.value
            row[ConversacionesTable.agreedPriceCop] = conversation.agreedPriceCop
            row[ConversacionesTable.counterOfferCount] = conversation.counterOfferCount
            row[ConversacionesTable.pendingCounterCop] = conversation.pendingCounterCop
            row[ConversacionesTable.pendingCounterAt] = conversation.pendingCounterAt?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }
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
                row[ConversacionesTable.pendingCounterAt] = conversation.pendingCounterAt?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }
                row[ConversacionesTable.lastUpdated] = Instant.ofEpochMilli(conversation.lastUpdated.toEpochMilliseconds())
            }
        }
    }

    override fun all(): List<Conversation> = transaction {
        ConversacionesTable.selectAll().map { it.toConversation() }
    }

    private fun ResultRow.toConversation(): Conversation {
        val stateName = this[ConversacionesTable.state]
        val convState = try {
            ConversationState.valueOf(stateName)
        } catch (e: Exception) {
            ConversationState.IDLE
        }

        val pendingAtJava = this[ConversacionesTable.pendingCounterAt]
        val lastUpdatedJava = this[ConversacionesTable.lastUpdated]

        return Conversation(
            conversationId = this[ConversacionesTable.conversationId],
            customerPhoneOrId = this[ConversacionesTable.customerId],
            channel = this[ConversacionesTable.channel],
            activeProductKey = this[ConversacionesTable.activeProductKey]?.let { ProductKey(it) },
            state = convState,
            agreedPriceCop = this[ConversacionesTable.agreedPriceCop],
            counterOfferCount = this[ConversacionesTable.counterOfferCount],
            pendingCounterCop = this[ConversacionesTable.pendingCounterCop],
            pendingCounterAt = pendingAtJava?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it.toEpochMilli()) },
            lastUpdated = lastUpdatedJava?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it.toEpochMilli()) }
                ?: kotlinx.datetime.Clock.System.now()
        )
    }
}
