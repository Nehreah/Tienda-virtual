package com.automata.app.workers

import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.PublicProductDto
import com.automata.commerce.ports.CatalogPublishingPort
import com.automata.commerce.ports.ChannelListingStatus
import com.automata.commerce.ports.InMemoryInventarioRepository
import com.automata.commerce.ports.PublishedListing
import com.automata.shared.Money
import com.automata.shared.PortResult
import com.automata.shared.ProductKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ChannelPublisherTest {

    private class FakePort(override val channelName: String) : CatalogPublishingPort {
        var publishCalls = 0
        override suspend fun publishProduct(product: PublicProductDto, quantity: Int): PortResult<String> = PortResult.Ok("x")
        override suspend fun publishListing(product: PublicProductDto, quantity: Int): PortResult<PublishedListing> {
            publishCalls++
            return PortResult.Ok(PublishedListing("ext-1", "inv-1"))
        }
        override suspend fun updateProduct(product: PublicProductDto): PortResult<Unit> = PortResult.Ok(Unit)
        override suspend fun setStock(productKey: ProductKey, externalId: String, quantity: Int, expectedVersion: Long): PortResult<Unit> = PortResult.Ok(Unit)
        override suspend fun unpublishProduct(productKey: ProductKey, externalId: String): PortResult<Unit> = PortResult.Ok(Unit)
        override suspend fun getStatus(productKey: ProductKey, externalId: String?): PortResult<ChannelListingStatus> =
            PortResult.Ok(ChannelListingStatus(productKey, channelName, externalId, true, 0, "OK"))
    }

    private fun item(
        estado: EstadoItem = EstadoItem.PUBLICADO,
        precio: Long? = 120_000,
        cantidad: Int = 3,
        archivadoEn: Instant? = null,
        fotos: List<String> = emptyList(),
        link: String? = null
    ) = InventarioItem(
        key = ProductKey("PROD-001"), item = "Audífonos", estado = estado, cantidad = cantidad,
        precioCompra = Money.of(70_000), precioPublicado = precio?.let { Money.of(it) },
        costoTotal = Money.of(70_000), precioMinimo = Money.of(105_000), archivadoEn = archivadoEn,
        fotos = fotos, link = link
    )

    private fun setup(item: InventarioItem?): Triple<ChannelPublisher, FakePort, FakePort> {
        val repo = InMemoryInventarioRepository()
        item?.let { repo.store[it.key.value] = it }
        val shopify = FakePort("shopify")
        val meta = FakePort("meta_catalogo")
        return Triple(ChannelPublisher(repo, listOf(shopify, meta)), shopify, meta)
    }

    @Test
    fun `canal invalido se rechaza con 400`() = runBlocking {
        val (pub, _, _) = setup(item())
        val r = pub.publicar(ProductKey("PROD-001"), "mercadolibre", escrituraHabilitada = false)
        assertEquals(400, (r as PublicarResultado.Rechazado).status)
    }

    @Test
    fun `articulo inexistente da 404`() = runBlocking {
        val (pub, _, _) = setup(null)
        assertEquals(404, (pub.publicar(ProductKey("NO-EXISTE"), "shopify", false) as PublicarResultado.Rechazado).status)
    }

    @Test
    fun `archivado o no publicado da 409`() = runBlocking {
        val (pubA, _, _) = setup(item(estado = EstadoItem.INACTIVO, archivadoEn = Instant.now()))
        assertEquals(409, (pubA.publicar(ProductKey("PROD-001"), "shopify", false) as PublicarResultado.Rechazado).status)
        val (pubB, _, _) = setup(item(estado = EstadoItem.COMPRADO))
        assertEquals(409, (pubB.publicar(ProductKey("PROD-001"), "shopify", false) as PublicarResultado.Rechazado).status)
    }

    @Test
    fun `sin precio o sin stock no se publica`() = runBlocking {
        val (pubA, _, _) = setup(item(precio = null))
        assertEquals(400, (pubA.publicar(ProductKey("PROD-001"), "shopify", false) as PublicarResultado.Rechazado).status)
        val (pubB, _, _) = setup(item(cantidad = 0))
        assertEquals(409, (pubB.publicar(ProductKey("PROD-001"), "shopify", false) as PublicarResultado.Rechazado).status)
    }

    @Test
    fun `con escritura deshabilitada es un ensayo y no llama al canal`() = runBlocking {
        val (pub, shopify, meta) = setup(item())
        val r = pub.publicar(ProductKey("PROD-001"), "shopify", escrituraHabilitada = false)
        assertTrue(r is PublicarResultado.Ensayo)
        r as PublicarResultado.Ensayo
        assertEquals("120000", r.datos["precio"])
        assertEquals(3, r.stock)
        assertEquals(0, shopify.publishCalls)
        assertEquals(0, meta.publishCalls)
        assertFalse(r.datos.keys.any { it.contains("costo", true) || it.contains("minimo", true) })
    }

    @Test
    fun `el ensayo de Meta avisa si falta foto y enlace y acepta el alias meta`() = runBlocking {
        val (pub, _, _) = setup(item())
        val r = pub.publicar(ProductKey("PROD-001"), "meta", escrituraHabilitada = false) as PublicarResultado.Ensayo
        assertEquals("meta_catalogo", r.canal)
        assertEquals(2, r.avisos.size)

        val (pub2, _, _) = setup(item(fotos = listOf("https://x.co/a.jpg"), link = "https://x.co/p"))
        val r2 = pub2.publicar(ProductKey("PROD-001"), "meta_catalogo", escrituraHabilitada = false) as PublicarResultado.Ensayo
        assertTrue(r2.avisos.isEmpty())
    }
}
