package com.automata.commerce

import com.automata.commerce.model.Periodo
import com.automata.commerce.model.ReparteSocios
import com.automata.commerce.service.FinancialMath
import com.automata.shared.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FinancialMathTest {

    @Test
    fun `ejemplo del manual seccion 6`() {
        val costo = Money.of(800_000) + Money.of(100_000)          // 900.000
        val vendido = Money.of(1_200_000)
        val ganancia = FinancialMath.gananciaNeta(vendido, costo)
        assertEquals(300_000L, ganancia.amount)
        assertEquals(1.0 / 3.0, FinancialMath.roi(ganancia, costo)!!, 1e-9)       // 33,3 %
        assertEquals(0.25, FinancialMath.margen(ganancia, vendido)!!, 1e-9)       // 25 %

        val dias = FinancialMath.dias(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 10, 2))
        assertEquals(20, dias)
        assertEquals(15_000L, FinancialMath.gananciaPorDia(ganancia, dias!!).amount)
    }

    @Test
    fun `ejemplo del usuario - vendido 300, costo 220, Antonio 50 por ciento`() {
        val ganancia = FinancialMath.gananciaNeta(Money.of(300), Money.of(220))
        assertEquals(80L, ganancia.amount)

        val retorno = FinancialMath.repartir(300, BigDecimal("50"), BigDecimal("50"))!!
        assertEquals(150L, retorno.first)
        assertEquals(150L, retorno.second)

        val gan = FinancialMath.repartir(ganancia.amount, BigDecimal("50"), BigDecimal("50"))!!
        assertEquals(40L, gan.first)
        assertEquals(40L, gan.second)
    }

    @Test
    fun `una perdida da ganancia negativa`() {
        val g = FinancialMath.gananciaNeta(Money.of(500), Money.of(700))
        assertEquals(-200L, g.amount)
        assertTrue(g.isNegative)
        assertEquals(-200.0 / 700.0, FinancialMath.roi(g, Money.of(700))!!, 1e-9)
        assertEquals(-0.4, FinancialMath.margen(g, Money.of(500))!!, 1e-9)
    }

    @Test
    fun `costo cero y vendido cero dan vacio`() {
        val g = FinancialMath.gananciaNeta(Money.of(100), Money.ZERO)
        assertNull(FinancialMath.roi(g, Money.ZERO))
        assertNull(FinancialMath.margen(g, Money.ZERO))
    }

    @Test
    fun `dias - sin fecha de compra es vacio, sin venta usa hoy`() {
        val hoy = LocalDate.of(2026, 10, 2)
        assertNull(FinancialMath.dias(null, null, hoy))
        assertEquals(31, FinancialMath.dias(LocalDate.of(2026, 9, 1), null, hoy))
    }

    @Test
    fun `ganancia por dia usa minimo 1 dia`() {
        val g = FinancialMath.gananciaNeta(Money.of(1_000), Money.of(400))
        assertEquals(600L, FinancialMath.gananciaPorDia(g, 0).amount)
    }

    @Test
    fun `tiempo legible`() {
        assertEquals("0 días", FinancialMath.tiempoLegible(0))
        assertEquals("1 día", FinancialMath.tiempoLegible(1))
        assertEquals("20 días", FinancialMath.tiempoLegible(20))
        assertEquals("1 mes", FinancialMath.tiempoLegible(30))
        assertEquals("2 meses 3 días", FinancialMath.tiempoLegible(63))
    }

    @Test
    fun `reparto con redondeo siempre suma exacto`() {
        val r = FinancialMath.repartir(100, BigDecimal("33.33"), BigDecimal("66.67"))!!
        assertEquals(33L, r.first)
        assertEquals(67L, r.second)
        assertEquals(100L, r.first + r.second)
    }

    @Test
    fun `reparto de una ganancia negativa tambien cuadra`() {
        val r = FinancialMath.repartir(-201, BigDecimal("50"), BigDecimal("50"))!!
        assertEquals(-201L, r.first + r.second)
    }

    @Test
    fun `sin reparto definido devuelve vacio`() {
        assertNull(FinancialMath.repartir(300, BigDecimal.ZERO, BigDecimal.ZERO))
        assertNull(FinancialMath.repartir(300, ReparteSocios.SIN_DEFINIR))
    }

    // ---------- Decisiones D1, D4 y D5 ----------

    @Test
    fun `D1 - precio minimo es costo total por 1,5 redondeado hacia arriba`() {
        assertEquals(30_000L, FinancialMath.precioMinimo(Money.of(20_000)).amountInCop)   // sin reparación
        assertEquals(37_500L, FinancialMath.precioMinimo(Money.of(25_000)).amountInCop)   // compra 20.000 + reparación 5.000
        assertEquals(500L, FinancialMath.precioMinimo(Money.of(333)).amountInCop)         // 499,5 -> 500
        assertEquals(0L, FinancialMath.precioMinimo(Money.ZERO).amountInCop)
    }

    @Test
    fun `D1 - sobre el costo total el ROI real con reparacion nunca baja de 50 por ciento`() {
        val costoTotal = Money.of(20_000) + Money.of(5_000)
        val pm = FinancialMath.precioMinimo(costoTotal)
        val roi = FinancialMath.roi(FinancialMath.gananciaNeta(pm, costoTotal), costoTotal)!!
        assertTrue(roi >= 0.5)
        // Si se calculara solo sobre la compra (20.000 × 1,5 = 30.000) el ROI real sería de solo 20 %:
        val roiSoloCompra = FinancialMath.roi(FinancialMath.gananciaNeta(Money.of(30_000), costoTotal), costoTotal)!!
        assertEquals(0.2, roiSoloCompra, 1e-9)
    }

    @Test
    fun `D1 - el ROI minimo es configurable`() {
        assertEquals(24_000L, FinancialMath.precioMinimo(Money.of(20_000), 20).amountInCop)
        assertThrows(IllegalArgumentException::class.java) { FinancialMath.precioMinimo(Money.of(1), -1) }
    }

    @Test
    fun `D5 - limites del periodo - 14 corto, 15 medio, 29 medio, 30 largo`() {
        assertEquals(Periodo.CORTO, FinancialMath.periodo(0))
        assertEquals(Periodo.CORTO, FinancialMath.periodo(14))
        assertEquals(Periodo.MEDIO, FinancialMath.periodo(15))
        assertEquals(Periodo.MEDIO, FinancialMath.periodo(29))
        assertEquals(Periodo.LARGO, FinancialMath.periodo(30))
        assertEquals(Periodo.LARGO, FinancialMath.periodo(400))
        assertEquals(Periodo.CORTO, FinancialMath.periodo(-3))   // días negativos cuentan como 0
    }

    @Test
    fun `D5 - etiquetas de periodo`() {
        assertEquals(listOf("corto", "medio", "largo"), Periodo.entries.map { it.etiqueta })
        assertEquals(Periodo.MEDIO, Periodo.desdeEtiqueta(" Medio "))
        assertNull(Periodo.desdeEtiqueta("eterno"))
    }

    @Test
    fun `D4 - dias desde la compra y dias desde la publicacion`() {
        val compra = LocalDate.of(2026, 9, 1)
        val publicacion = LocalDate.of(2026, 9, 6)
        val venta = LocalDate.of(2026, 9, 21)
        val hoy = LocalDate.of(2026, 10, 2)
        assertEquals(20, FinancialMath.dias(compra, venta, hoy))                  // desde la compra
        assertEquals(15, FinancialMath.diasPublicado(publicacion, venta, hoy))    // desde la publicación
        assertNull(FinancialMath.diasPublicado(null, venta, hoy))                 // sin publicación: vacío
        assertEquals(26, FinancialMath.diasPublicado(publicacion, null, hoy))     // sigue en stock: usa hoy
    }
}
