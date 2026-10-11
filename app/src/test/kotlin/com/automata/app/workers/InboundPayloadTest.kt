package com.automata.app.workers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class InboundPayloadTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `el payload es JSON valido y conserva comillas, barras y saltos de linea`() {
        val texto = "Hola \"mundo\" \\ barra\nlinea2\ttab"
        val json = parse(buildInboundPayload("5731", "wamid.1", texto))
        assertEquals("5731", json["senderId"]!!.jsonPrimitive.content)
        assertEquals("wamid.1", json["messageId"]!!.jsonPrimitive.content)
        assertEquals(texto, json["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `los caracteres de control quedan escapados y el JSON sigue siendo valido`() {
        val texto = "a\u0008b\u000Cc\u000Bd\u001Fe"
        val raw = buildInboundPayload("1", "m1", texto)
        assertFalse(raw.any { it < ' ' })
        assertEquals(texto, parse(raw)["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `el caracter NUL se descarta porque jsonb no lo admite`() {
        val raw = buildInboundPayload("1", "m1", "ho\u0000la")
        assertEquals("hola", parse(raw)["text"]!!.jsonPrimitive.content)
    }
}
