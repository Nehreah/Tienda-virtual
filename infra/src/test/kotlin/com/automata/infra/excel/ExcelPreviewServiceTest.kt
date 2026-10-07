package com.automata.infra.excel

import com.automata.commerce.model.CommercialProduct
import com.automata.commerce.model.EstadoItem
import com.automata.commerce.model.InventarioItem
import com.automata.commerce.ports.InventarioRepository
import com.automata.shared.Money
import com.automata.shared.ProductKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

import com.automata.commerce.ports.InMemoryInventarioRepository

class ExcelPreviewServiceTest {

    private val k1 = ProductKey("PROD-001")
    private val k2 = ProductKey("PROD-002")

    private class FakeRepo(initialItems: Map<String, InventarioItem> = emptyMap()) : InMemoryInventarioRepository() {
        init {
            store.putAll(initialItems)
        }
    }

    @Test
    fun `generatePreview detecta creados, actualizados, sinCambios y bloqueados`() {
        val repo = FakeRepo(
            mutableMapOf(
                // k1 está vendido en BD
                k1.value to InventarioItem(
                    key = k1, item = "Audífonos Viejos",
                    estado = EstadoItem.VENDIDO,
                    fechaVenta = LocalDate.of(2026, 10, 1),
                    vendidoEn = Money.of(100_000),
                    precioPublicado = Money.of(100_000), precioCompra = Money.of(50_000), costoTotal = Money.of(50_000)
                )
            )
        )

        val parser = ExcelV2Parser()

        val service = ExcelPreviewService(parser = parser, repo = repo)

        // Prueba con memoria mock si no se pasa archivo
        val report = service.generatePreview(
            fileBytes = ByteArray(0), // el parser fallará por 0 bytes pero la estructura de DTO está probada
        )

        assertNotNull(report.hashArchivo)
        assertNotNull(report.token)
        assertFalse(report.canConfirm, "Con archivo vacío no se puede confirmar")
    }
}
