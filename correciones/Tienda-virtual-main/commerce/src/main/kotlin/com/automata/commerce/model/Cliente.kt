package com.automata.commerce.model

import kotlinx.serialization.Serializable

@Serializable
data class Cliente(
    val id: String,
    val nombre: String,
    val telefono: String,
    val canalOrigen: String,
    val habeasDataAceptado: Boolean = false,
    val fechaConsentimiento: String? = null,
    val anonimizado: Boolean = false
)
