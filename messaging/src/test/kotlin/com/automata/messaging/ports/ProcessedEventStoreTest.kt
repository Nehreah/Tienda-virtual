package com.automata.messaging.ports

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProcessedEventStoreTest {

    @Test
    fun `first delivery is new and the redelivery is ignored`() {
        val store = InMemoryProcessedEventStore()
        assertTrue(store.markIfNew("whatsapp", "wamid.1"))
        assertFalse(store.markIfNew("whatsapp", "wamid.1"))
    }

    @Test
    fun `same id in different channels is treated as different events`() {
        val store = InMemoryProcessedEventStore()
        assertTrue(store.markIfNew("whatsapp", "abc"))
        assertTrue(store.markIfNew("shopify", "abc"))
    }

    @Test
    fun `store is bounded and forgets the oldest entries`() {
        val store = InMemoryProcessedEventStore(maxEntries = 2)
        assertTrue(store.markIfNew("c", "1"))
        assertTrue(store.markIfNew("c", "2"))
        assertTrue(store.markIfNew("c", "3")) // expulsa "1"
        assertTrue(store.markIfNew("c", "1")) // ya fue olvidado
    }

    @Test
    fun `an event stuck in PROCESSING can be retaken after the stale timeout`() {
        var now = 0L
        val store = InMemoryProcessedEventStore(staleAfterMillis = 1_000L, clock = { now })
        assertTrue(store.tryStartProcessing("meta", "e1"))
        now = 500L
        assertFalse(store.tryStartProcessing("meta", "e1"))   // aun en curso
        now = 1_501L
        assertTrue(store.tryStartProcessing("meta", "e1"))    // proceso abandonado: se retoma
    }

    @Test
    fun `a failed event can be retried but a done one cannot`() {
        val store = InMemoryProcessedEventStore()
        assertTrue(store.tryStartProcessing("meta", "e2"))
        store.markFailed("meta", "e2", "boom")
        assertTrue(store.tryStartProcessing("meta", "e2"))
        store.markDone("meta", "e2")
        assertFalse(store.tryStartProcessing("meta", "e2"))
    }
}
