package com.automata.messaging.ports

import com.automata.shared.PortResult

/**
 * Enruta el envío al adaptador del canal ("whatsapp", "instagram", "messenger").
 * La lógica comercial solo conoce el nombre del canal; no sabe nada de Meta.
 */
class ChannelRouter(adapters: List<MessagingPort>) {

    private val byChannel: Map<String, MessagingPort>

    init {
        val names = adapters.map { it.channelName.lowercase() }
        require(names.size == names.toSet().size) { "Hay dos adaptadores para el mismo canal: $names" }
        byChannel = adapters.associateBy { it.channelName.lowercase() }
    }

    fun supports(channel: String): Boolean = byChannel.containsKey(channel.lowercase())

    suspend fun sendMessage(channel: String, recipientId: String, text: String): PortResult<String> {
        val adapter = byChannel[channel.lowercase()] ?: return notConfigured(channel)
        return adapter.sendMessage(recipientId, text)
    }

    suspend fun sendProductCard(
        channel: String,
        recipientId: String,
        productTitle: String,
        priceFormatted: String,
        imageUrl: String
    ): PortResult<String> {
        val adapter = byChannel[channel.lowercase()] ?: return notConfigured(channel)
        return adapter.sendProductCard(recipientId, productTitle, priceFormatted, imageUrl)
    }

    private fun notConfigured(channel: String): PortResult<String> =
        PortResult.Permanent("CHANNEL_NOT_CONFIGURED", "No hay adaptador de mensajería para el canal '$channel'")
}
