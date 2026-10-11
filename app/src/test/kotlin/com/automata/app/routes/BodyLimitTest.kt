package com.automata.app.routes

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BodyLimitTest {

    @Test
    fun `reads content within the limit`() {
        val data = ByteArray(20_000) { (it % 251).toByte() }
        assertArrayEquals(data, data.inputStream().readBytesLimited(20_000))
    }

    @Test
    fun `returns null as soon as the limit is exceeded`() {
        val data = ByteArray(20_001)
        assertNull(data.inputStream().readBytesLimited(20_000))
    }

    @Test
    fun `empty stream gives empty array`() {
        assertArrayEquals(ByteArray(0), ByteArray(0).inputStream().readBytesLimited(10))
    }
}
