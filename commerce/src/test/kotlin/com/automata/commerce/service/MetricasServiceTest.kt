package com.automata.commerce.service

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.OperationalInventory
import com.automata.commerce.model.Periodo
import com.automata.commerce.model.Socio
import com.automata.commerce.model.Venta
import com.automata.commerce.model.VentaReparto
import com.automata.shared.Money
import com.automata.shared.ProductKey
import com.automata.shared.SignedCop
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MetricasServiceTest {

    private val service = MetricasService()

    @Test
    fun `calcula metricas generales y por socio con cuadre exacto`() {
        val k1 = ProductKey("P-001")
        val socio1 = Socio(1L, "Antonio")
        val socio2 = Socio(2L, "Javier")

        val p1 = CommercialProduct(
            key = k1,
            itemName = "Audífonos",
            publishedPrice = Money.of(150_000),
            minimumPrice = Money.of(100_000),
            cost = Money.of(80_000),
            status = "Vendido",
            description = "Audífonos Bluetooth",
            brand = "AudioBrand"
        )
        val v1 = Venta(
            key = k1,
            fechaVenta = LocalDate.of(2026, 10, 1),
            vendidoEn = Money.of(140_000),
            canal = "whatsapp",
            ingresos = Money.of(140_000),
            costoTotal = Money.of(80_000),
            gananciaNeta = SignedCop(60_000),
            margenPct = 0.42,
            tiempoDias = 10,
            diasPublicado = 5,
            periodo = Periodo.CORTO
        )
        val r1 = VentaReparto(k1, 1L, BigDecimal("50"), inversion = 40_000, ingresos = 70_000, ganancia = 30_000)
        val r2 = VentaReparto(k1, 2L, BigDecimal("50"), inversion = 40_000, ingresos = 70_000, ganancia = 30_000)

        val datos = DatosMetricas(
            productos = listOf(p1),
            inventarios = listOf(OperationalInventory(k1, stockMaestro = 0)),
            ventas = listOf(v1),
            repartosVenta = listOf(r1, r2),
            inversionesStock = emptyMap(),
            socios = listOf(socio1, socio2)
        )

        val gen = service.calcularGenerales(datos)
        assertEquals(140_000L, gen.ingresos)
        assertEquals(60_000L, gen.gananciaNeta)
        assertEquals(1, gen.totalVendidos)

        val resSocios = service.calcularPorSocio(datos)
        assertTrue(resSocios.totalCuadra, "El total de ganancia de socios debe cuadrar con la ganancia general")
        assertEquals(2, resSocios.sociosMetrics.size)
        assertEquals(30_000L, resSocios.sociosMetrics[0].gananciaNeta)
        assertEquals(30_000L, resSocios.sociosMetrics[1].gananciaNeta)
    }
}
