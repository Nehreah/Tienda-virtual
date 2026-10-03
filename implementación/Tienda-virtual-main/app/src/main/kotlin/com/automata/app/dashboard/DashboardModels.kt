package com.automata.app.dashboard

enum class ApiStatus {
    FUNCIONAL, DEGRADADO, DESHABILITADO
}

data class ApiIntegrationStatus(
    val name: String,
    val category: String,
    val status: ApiStatus,
    val detail: String,
    val lastCheck: String
)

enum class DealState {
    TRATO_CERRADO,        // Trato Realizado / Aceptado
    PENDIENTE_ENVIO,      // Pendiente de Envío
    EN_NEGOCIACION,       // En Negociación
    CANCELADO             // Cancelado / Rechazado
}

data class ChatDealLog(
    val id: String,
    val customer: String,
    val channel: String,            // whatsapp, instagram, messenger, shopify_webhooks, simulator
    val channelDisplayName: String,
    val productName: String,
    val offeredPrice: String,
    val dealState: DealState,
    val dealStateLabel: String,
    val timestamp: String
)
