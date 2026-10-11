package com.automata.app.dashboard

import com.automata.app.dashboard.CatalogoRenderer.Filtro
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CatalogoRendererTest {

    private fun item(
        key: String = "PROD-001",
        nombre: String = "Audífonos",
        estado: EstadoItem = EstadoItem.PUBLICADO,
        archivadoEn: Instant? = null
    ) = InventarioItem(
        key = ProductKey(key), item = nombre, estado = estado,
        precioCompra = Money.of(70_000), precioPublicado = Money.of(120_000),
        costoTotal = Money.of(70_000), precioMinimo = Money.of(105_000), archivadoEn = archivadoEn
    )

    @Test
    fun `el panel escapa texto variable y no usa JavaScript en linea ni Tailwind`() {
        val html = CatalogoRenderer.renderPanel(
            items = listOf(item(nombre = "<img src=x onerror=alert(1)>")),
            filtro = Filtro.ACTIVOS,
            mensaje = "<b>listo</b>",
            error = "<script>x</script>"
        )
        assertFalse(html.contains("<img src=x"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertFalse(Regex("\\son(click|change|submit|input)=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(html.contains("class=\"bg-") || html.contains("class=\"text-"))
    }

    @Test
    fun `el filtro separa activos de archivados`() {
        val activo = item(key = "A-1")
        val archivado = item(key = "A-2", estado = EstadoItem.INACTIVO, archivadoEn = Instant.now())
        val items = listOf(activo, archivado)

        val soloActivos = CatalogoRenderer.renderPanel(items, Filtro.ACTIVOS)
        assertTrue(soloActivos.contains("A-1"))
        assertFalse(soloActivos.contains("id=\"art-A-2\""))

        val soloArchivados = CatalogoRenderer.renderPanel(items, Filtro.ARCHIVADOS)
        assertTrue(soloArchivados.contains("id=\"art-A-2\""))
        assertTrue(soloArchivados.contains("Restaurar"))
        assertFalse(soloArchivados.contains("id=\"art-A-1\""))

        val todos = CatalogoRenderer.renderPanel(items, Filtro.TODOS)
        assertTrue(todos.contains("id=\"art-A-1\"") && todos.contains("id=\"art-A-2\""))
    }

    @Test
    fun `un articulo vendido queda bloqueado sin acciones de edicion`() {
        val vendido = InventarioItem(
            key = ProductKey("V-1"), item = "Vendido", estado = EstadoItem.VENDIDO,
            fechaVenta = java.time.LocalDate.now(), vendidoEn = Money.of(100_000)
        )
        val html = CatalogoRenderer.renderPanel(listOf(vendido), Filtro.ACTIVOS)
        assertTrue(html.contains("Vendido: bloqueado"))
        assertFalse(html.contains("/editar"))
        assertFalse(html.contains("hx-delete"))
    }

    @Test
    fun `la key se codifica en las URL de las acciones`() {
        val html = CatalogoRenderer.renderPanel(listOf(item(key = "A B")), Filtro.ACTIVOS)
        assertTrue(html.contains("/ui/catalogo/A%20B/editar?filtro=activos"))
    }

    @Test
    fun `el formulario de alta pide la key y el de edicion la deja de solo lectura`() {
        val alta = CatalogoRenderer.renderFormulario(null, Filtro.ACTIVOS)
        assertTrue(alta.contains("hx-post=\"/ui/catalogo?filtro=activos\""))
        assertTrue(alta.contains("name=\"key\"") && alta.contains("required"))

        val edicion = CatalogoRenderer.renderFormulario(item(), Filtro.TODOS)
        assertTrue(edicion.contains("hx-put=\"/ui/catalogo/PROD-001?filtro=todos\""))
        assertTrue(edicion.contains("readonly"))
        assertEquals(1, Regex("name=\"estado\"").findAll(edicion).count())
        assertFalse(edicion.contains("value=\"Vendido\"") || edicion.contains("value=\"Inactivo\""))
    }

    @Test
    fun `el formulario conserva lo escrito y muestra el error`() {
        val html = CatalogoRenderer.renderFormulario(
            item = null, filtro = Filtro.ACTIVOS,
            valores = mapOf("key" to "PROD-9", "item" to "\"><x>"),
            error = "PM no puede ser mayor a PP"
        )
        assertTrue(html.contains("value=\"PROD-9\""))
        assertTrue(html.contains("&quot;&gt;&lt;x&gt;"))
        assertTrue(html.contains("PM no puede ser mayor a PP"))
    }

    @Test
    fun `el modal de archivar exige motivo y se cierra con data-modal-close`() {
        val html = CatalogoRenderer.renderModalArchivar(item(), Filtro.ACTIVOS)
        assertTrue(html.contains("name=\"motivo\"") && html.contains("required"))
        assertTrue(html.contains("data-modal-close"))
        assertTrue(html.contains("hx-post=\"/ui/catalogo/PROD-001/archivar?filtro=activos\""))
    }
}
