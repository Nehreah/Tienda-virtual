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
}
