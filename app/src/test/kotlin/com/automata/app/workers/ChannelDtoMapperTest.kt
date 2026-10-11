package com.automata.app.workers

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ChannelDtoMapperTest {

    private fun item(estado: EstadoItem = EstadoItem.PUBLICADO, archivadoEn: Instant? = null, link: String? = null, cantidad: Int = 2) =
        InventarioItem(
            key = ProductKey("PROD 001"), item = "Audífonos", estado = estado, cantidad = cantidad, link = link,
            precioCompra = Money.of(70_000), precioPublicado = Money.of(120_000),
            costoTotal = Money.of(70_000), precioMinimo = Money.of(105_000), archivadoEn = archivadoEn,
            fotos = listOf("https://x.co/a.jpg")
        )

    @Test
    fun `solo Publicado y Negociacion sin archivar estan a la venta`() {
        assertTrue(item().estaALaVenta())
        assertTrue(item(EstadoItem.NEGOCIACION).estaALaVenta())
        assertFalse(item(EstadoItem.COMPRADO).estaALaVenta())
        assertFalse(item(EstadoItem.INACTIVO, archivadoEn = Instant.now()).estaALaVenta())
        assertFalse(item(archivadoEn = Instant.now()).estaALaVenta())
    }

    @Test
    fun `el enlace escrito por la persona gana y si no se usa la vitrina propia`() {
        assertEquals("https://otra.co/x", item(link = " https://otra.co/x ").enlacePublico("https://mi.co"))
        assertEquals("https://mi.co/public/p/PROD%20001", item().enlacePublico("https://mi.co/"))
        assertNull(item().enlacePublico(""))
    }

    @Test
    fun `el DTO de canal trae fotos y enlace y marca agotado lo que no esta a la venta`() {
        val dto = item().toChannelDto("https://mi.co")
        assertEquals("in stock", dto.availabilityStatus)
        assertEquals(listOf("https://x.co/a.jpg"), dto.images)
        assertEquals("https://mi.co/public/p/PROD%20001", dto.attributes["link"])
        assertEquals(setOf("link"), dto.attributes.keys)
        assertEquals("out of stock", item(archivadoEn = Instant.now()).toChannelDto("").availabilityStatus)
        assertEquals("out of stock", item(cantidad = 0).toChannelDto("").availabilityStatus)
    }
}
