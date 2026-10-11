package com.automata.app.dashboard

import com.automata.commerce.model.InventarioItem
import com.automata.commerce.model.Reparacion
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReparacionesRendererTest {

    private val key = ProductKey("PROD-001")
    private val item = InventarioItem(key = key, item = "Taladro <b>", precioCompra = Money.of(80_000), requiereReparacion = true)

    @Test
    fun `el formulario usa htmx hacia el panel, sin POST nativo ni handlers inline y con el texto escapado`() {
        val html = ReparacionesRenderer.render(
            listOf(item),
            mapOf("PROD-001" to Reparacion(key, Money.of(15_000), "Cambio <i>de</i> carbon"))
        )
        assertTrue(html.contains("hx-post=\"/api/reparaciones/PROD-001\""))
        assertTrue(html.contains("hx-target=\"#reparaciones-panel\""))
        assertFalse(html.contains("method=\"post\""))
        assertFalse(Regex("\\son(click|change|submit|input)=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(html.contains("Taladro <b>"))
        assertTrue(html.contains("Taladro &lt;b&gt;"))
        assertFalse(html.contains("<i>de</i>"))
        assertTrue(html.contains("value=\"15000\""))
    }

    @Test
    fun `muestra el mensaje de exito y el de error escapados`() {
        val html = ReparacionesRenderer.render(emptyList(), emptyMap(), error = "Fallo <script>", mensaje = "Guardado <b>")
        assertTrue(html.contains("class=\"module-error\""))
        assertTrue(html.contains("Fallo &lt;script&gt;"))
        assertTrue(html.contains("class=\"ui-ok\""))
        assertTrue(html.contains("Guardado &lt;b&gt;"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `la clave va codificada en la URL y el borrador conserva lo escrito`() {
        val raro = ProductKey("A B#1")
        val otro = InventarioItem(key = raro, item = "Lijadora", requiereReparacion = true)
        val html = ReparacionesRenderer.render(
            listOf(otro), emptyMap(),
            error = "Fallo", borrador = Reparacion(raro, Money.of(42_000), "detalle escrito")
        )
        assertTrue(html.contains("hx-post=\"/api/reparaciones/A%20B%231\""))
        assertTrue(html.contains("value=\"42000\""))
        assertTrue(html.contains("value=\"detalle escrito\""))
    }
}
