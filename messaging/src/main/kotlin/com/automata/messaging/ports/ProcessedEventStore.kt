package com.automata.messaging.ports

/**
 * Idempotencia de webhooks: Meta y Shopify pueden reenviar el mismo evento.
 * [markIfNew] registra (canal, id externo del evento) y devuelve true solo la PRIMERA vez;
 * si devuelve false, el evento ya fue procesado y debe ignorarse.
 */
interface ProcessedEventStore {
    fun markIfNew(channel: String, externalEventId: String): Boolean
}

/**
 * Implementación en memoria (acotada). Protege contra reenvíos mientras el proceso está vivo;
 * se pierde al reiniciar. La versión persistente en PostgreSQL queda pendiente (ver manual).
 */
class InMemoryProcessedEventStore(private val maxEntries: Int = 10_000) : ProcessedEventStore {

    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > maxEntries
    }

    override fun markIfNew(channel: String, externalEventId: String): Boolean = synchronized(seen) {
        val key = "$channel|$externalEventId"
        if (seen.containsKey(key)) {
            false
        } else {
            seen[key] = true
            true
        }
    }
}
