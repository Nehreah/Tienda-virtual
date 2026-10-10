package com.automata.commerce.service

import com.automata.commerce.model.Cliente
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ClientePrivacyTest {

    private lateinit var service: ClienteService

    @BeforeEach
    fun setup() {
        service = ClienteService()
    }

    @Test
    fun `registrar cliente con consentimiento Habeas Data`() {
        val cliente = Cliente(
            id = "CLI-001",
            nombre = "Juan Perez",
            telefono = "3001234567",
            canalOrigen = "whatsapp",
            habeasDataAceptado = true,
            fechaConsentimiento = "2026-10-07T12:00:00Z"
        )

        val registrado = service.registrarOActualizar(cliente)
        assertEquals("CLI-001", registrado.id)
        assertEquals("Juan Perez", registrado.nombre)
        assertTrue(registrado.habeasDataAceptado)
        assertFalse(registrado.anonimizado)
    }

    @Test
    fun `anonimizar cliente de acuerdo a Ley 1581 Habeas Data`() {
        val cliente = Cliente(
            id = "CLI-002",
            nombre = "Maria Rodriguez",
            telefono = "3109876543",
            canalOrigen = "instagram",
            habeasDataAceptado = true,
            fechaConsentimiento = "2026-10-07T12:00:00Z"
        )
        service.registrarOActualizar(cliente)

        val anonimizado = service.anonimizar("CLI-002")
        assertTrue(anonimizado.anonimizado)
        assertFalse(anonimizado.habeasDataAceptado)
        assertNull(anonimizado.fechaConsentimiento)
        assertEquals("CLIENTE_ANONIMIZADO_CLI-00", anonimizado.nombre)
        assertEquals("0000000000", anonimizado.telefono)

        val recuperado = service.findById("CLI-002")
        assertNotNull(recuperado)
        assertTrue(recuperado!!.anonimizado)
    }
}
