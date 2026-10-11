package com.automata.app.workers

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.PublicProductDto
import com.automata.infra.config.AppConfig
import com.automata.shared.Money

/**
 * v14: convierte una fila del inventario en el DTO PÚBLICO que se envía a Shopify y Meta.
 * El DTO no tiene costo, precio mínimo ni margen: no hay forma de que salgan por aquí.
 */

/** Un artículo solo se ofrece en los canales si está Publicado o en Negociación y no está archivado. */
internal fun InventarioItem.estaALaVenta(): Boolean =
    archivadoEn == null && (estado == EstadoItem.PUBLICADO || estado == EstadoItem.NEGOCIACION)

/**
 * Enlace público del artículo para los canales. Gana el que la persona escribió en el campo "Enlace de la publicación";
 * si está vacío y existe PUBLIC_BASE_URL, se usa la vitrina propia (/public/p/{key}).
 */
internal fun InventarioItem.enlacePublico(baseUrl: String = AppConfig.publicBaseUrl): String? =
    link?.trim()?.takeIf { it.isNotEmpty() }
        ?: baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let {
            "$it/public/p/${java.net.URLEncoder.encode(key.value, Charsets.UTF_8).replace("+", "%20")}"
        }

internal fun InventarioItem.toChannelDto(baseUrl: String = AppConfig.publicBaseUrl): PublicProductDto {
    val aLaVenta = estaALaVenta() && cantidad > 0
    val enlace = enlacePublico(baseUrl)
    return PublicProductDto(
        key = key,
        title = item,
        description = descripcion.orEmpty(),
        publishedPrice = precioPublicado ?: Money.ZERO,
        availabilityStatus = if (aLaVenta) "in stock" else "out of stock",
        brand = marca.orEmpty(),
        condition = (condicion ?: "Nuevo").lowercase(),
        gtinOrEan = null,
        mpn = null,
        images = fotos,
        attributes = if (enlace != null) mapOf("link" to enlace) else emptyMap()
    )
}
