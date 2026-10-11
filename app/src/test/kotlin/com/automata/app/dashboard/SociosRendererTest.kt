package com.automata.app.dashboard

import com.automata.commerce.model.CuentaSocio
import com.automata.commerce.model.Liquidacion
import com.automata.commerce.model.Socio
import com.automata.commerce.service.ImpactoSocio
import com.automata.shared.Money
import com.automata.shared.SignedCop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SociosRendererTest {

    private val ana = Socio(id = 1, nombre = "Ana <b>")

    private fun cuenta(saldo: Long) = CuentaSocio(
        socioId = 1, nombre = "Ana", activo = true,
        aporteEnStock = SignedCop.of(50_000), aporteVendido = SignedCop.of(70_000),
        ingresos = SignedCop.of(120_000), ganancia = SignedCop.of(50_000), ventas = 1,
        liquidado = SignedCop.of(120_000 - saldo), liquidaciones = 0, saldoPorLiquidar = SignedCop.of(saldo)
    )

    @Test
    fun `sin onclick ni Tailwind y con el nombre escapado`() {
        val html = SociosRenderer.renderVistaSocios(listOf(ana), emptyList(), cuentas = listOf(cuenta(120_000)))
        assertFalse(Regex("\\son(click|change|submit|input)=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(html.contains("class=\"hidden") || html.contains("bg-indigo"))
        assertFalse(html.contains("Ana <b>"))
        assertTrue(html.contains("Ana &lt;b&gt;"))
        assertTrue(html.contains("hx-post=\"/api/socios\""))
    }

    @Test
    fun `muestra aportes saldo y el boton Liquidar solo si hay saldo`() {
        val conSaldo = SociosRenderer.renderVistaSocios(listOf(ana), emptyList(), cuentas = listOf(cuenta(120_000)))
        assertTrue(conSaldo.contains("Saldo por liquidar"))
        assertTrue(conSaldo.contains("\$120.000"))
        assertTrue(conSaldo.contains("/api/socios/1/liquidar"))

        val sinSaldo = SociosRenderer.renderVistaSocios(listOf(ana), emptyList(), cuentas = listOf(cuenta(0)))
        assertFalse(sinSaldo.contains("/api/socios/1/liquidar"))
    }

    @Test
    fun `sin base de datos solo se muestra la lista de socios`() {
        val html = SociosRenderer.renderVistaSocios(listOf(ana), emptyList(), cuentas = null)
        assertFalse(html.contains("Saldo por liquidar"))
        assertFalse(html.contains("Liquidaciones"))
        assertTrue(html.contains("/api/socios/1/impacto"))
    }

    @Test
    fun `un saldo negativo se marca como tal`() {
        val html = SociosRenderer.renderVistaSocios(listOf(ana), emptyList(), cuentas = listOf(cuenta(-5_000)))
        assertTrue(html.contains("ui-neg"))
        assertTrue(html.contains("-\$5.000"))
    }

    @Test
    fun `las liquidaciones anuladas no ofrecen anular y muestran el motivo escapado`() {
        val vigente = Liquidacion(1, 1, "Ana", LocalDate.of(2026, 10, 1), Money.of(10_000))
        val anulada = Liquidacion(2, 1, "Ana", LocalDate.of(2026, 10, 2), Money.of(20_000), anuladaMotivo = "<x>", anuladaEn = java.time.Instant.now())
        val html = SociosRenderer.renderVistaSocios(listOf(ana), emptyList(), cuentas = listOf(cuenta(0)), liquidaciones = listOf(vigente, anulada))
        assertTrue(html.contains("/api/liquidaciones/1/anular"))
        assertFalse(html.contains("/api/liquidaciones/2/anular"))
        assertTrue(html.contains("&lt;x&gt;"))
    }

    @Test
    fun `el modal de liquidar sugiere el saldo y el de anular exige motivo`() {
        val liquidar = SociosRenderer.renderModalLiquidar(ana, cuenta(80_000), LocalDate.of(2026, 10, 10))
        assertTrue(liquidar.contains("value=\"80000\""))
        assertTrue(liquidar.contains("value=\"2026-10-10\""))
        assertTrue(liquidar.contains("hx-post=\"/api/socios/1/liquidaciones\""))
        assertTrue(liquidar.contains("Liquidar a Ana &lt;b&gt;"))

        val anular = SociosRenderer.renderModalAnularLiquidacion(7)
        assertTrue(anular.contains("name=\"motivo\"") && anular.contains("required"))
        assertTrue(anular.contains("hx-post=\"/api/liquidaciones/7/anular\""))
    }

    @Test
    fun `el modal de retiro explica si se archiva o se elimina`() {
        val archiva = SociosRenderer.renderModalImpacto(ImpactoSocio(1, 2, 3, true), "Ana")
        assertTrue(archiva.contains("Se archivará"))
        val elimina = SociosRenderer.renderModalImpacto(ImpactoSocio(1, 0, 0, false), "Ana")
        assertTrue(elimina.contains("se eliminará definitivamente"))
        assertTrue(elimina.contains("hx-delete=\"/api/socios/1\""))
    }
}
