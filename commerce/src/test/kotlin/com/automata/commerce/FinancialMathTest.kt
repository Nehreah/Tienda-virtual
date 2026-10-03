package com.automata.commerce

import com.automata.commerce.model.Inversion
import com.automata.commerce.service.FinancialMath
import com.automata.shared.Money
import com.automata.shared.ProductKey
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
        assertNull(FinancialMath.repartir(300, Inversion(ProductKey("A-1"))))
    }
}
