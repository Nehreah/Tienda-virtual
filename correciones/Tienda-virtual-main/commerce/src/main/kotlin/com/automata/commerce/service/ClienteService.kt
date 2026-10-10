package com.automata.commerce.service

import com.automata.commerce.model.Cliente
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio de Gestión de Clientes y Cumplimiento de Privacidad / Habeas Data (Fase C6 / Ley 1581).
 * Soporta registro de clientes con consentimiento y ejercicio del derecho al olvido (anonimización).
 */
class ClienteService {

    private val clientesMap = ConcurrentHashMap<String, Cliente>()

    fun registrarOActualizar(cliente: Cliente): Cliente {
        val existente = clientesMap[cliente.id]
        val guardado = if (existente != null && existente.anonimizado) {
            existente
        } else {
            cliente
        }
        clientesMap[guardado.id] = guardado
        return guardado
    }

    fun findById(id: String): Cliente? = clientesMap[id]

    fun findAll(): List<Cliente> = clientesMap.values.toList()

    /**
     * Ejercicio del Derecho al Olvido (Habeas Data / Ley 1581 de Protección de Datos Personales).
     * Reemplaza nombre y teléfono por identificadores anónimos sin eliminar el registro
     * para mantener la integridad referencial de facturas/ventas pasadas.
     */
    fun anonimizar(clienteId: String): Cliente {
        val cliente = clientesMap[clienteId]
            ?: throw IllegalArgumentException("Cliente con ID $clienteId no encontrado")

        val anonimizado = cliente.copy(
            nombre = "CLIENTE_ANONIMIZADO_${cliente.id.take(6).uppercase()}",
            telefono = "0000000000",
            habeasDataAceptado = false,
            fechaConsentimiento = null,
            anonimizado = true
        )
        clientesMap[clienteId] = anonimizado
        return anonimizado
    }
}
