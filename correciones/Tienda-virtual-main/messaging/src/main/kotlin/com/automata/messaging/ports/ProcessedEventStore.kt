package com.automata.messaging.ports

/**
 * Idempotencia de webhooks: Meta y Shopify pueden reenviar el mismo evento.
 * [markIfNew] registra (canal, id externo del evento) y devuelve true solo la PRIMERA vez;
 * si devuelve false, el evento ya fue procesado y debe ignorarse.
 */
enum class EventProcessingState {
    PROCESSING,
    DONE,
    FAILED
}

interface ProcessedEventStore {
    fun markIfNew(channel: String, externalEventId: String): Boolean = tryStartProcessing(channel, externalEventId).also {
        if (it) markDone(channel, externalEventId)
    }

    fun tryStartProcessing(channel: String, externalEventId: String): Boolean
    fun markDone(channel: String, externalEventId: String)
    fun markFailed(channel: String, externalEventId: String, errorMessage: String? = null)
}

class InMemoryProcessedEventStore(private val maxEntries: Int = 10_000) : ProcessedEventStore {

    private val states = object : LinkedHashMap<String, EventProcessingState>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EventProcessingState>): Boolean =
            size > maxEntries
    }

    override fun tryStartProcessing(channel: String, externalEventId: String): Boolean = synchronized(states) {
        val key = "$channel|$externalEventId"
        val current = states[key]
        if (current == EventProcessingState.DONE || current == EventProcessingState.PROCESSING) {
            false
        } else {
            states[key] = EventProcessingState.PROCESSING
            true
        }
    }

    override fun markDone(channel: String, externalEventId: String): Unit = synchronized(states) {
        val key = "$channel|$externalEventId"
        states[key] = EventProcessingState.DONE
    }

    override fun markFailed(channel: String, externalEventId: String, errorMessage: String?): Unit = synchronized(states) {
        val key = "$channel|$externalEventId"
        states[key] = EventProcessingState.FAILED
    }

    override fun markIfNew(channel: String, externalEventId: String): Boolean = synchronized(states) {
        val key = "$channel|$externalEventId"
        if (states.containsKey(key) && states[key] == EventProcessingState.DONE) {
            false
        } else {
            states[key] = EventProcessingState.DONE
            true
        }
    }
}
