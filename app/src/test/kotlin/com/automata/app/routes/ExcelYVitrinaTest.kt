package com.automata.app.routes

import com.automata.app.AppStore
import com.automata.commerce.model.CommercialProduct
import com.automata.infra.excel.ExcelPreviewService
import com.automata.infra.excel.ExcelV2Parser
import com.automata.shared.Money
import com.automata.shared.ProductKey
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Excel (exporta costos y precio mínimo) y vitrina pública (nunca debe mostrar costos). */
class ExcelYVitrinaTest {

    private val keysCreadas = mutableListOf<String>()

    @AfterEach
    fun limpiar() {
        synchronized(AppStore.products) { keysCreadas.forEach { AppStore.products.remove(it) } }
        keysCreadas.clear()
    }

    private fun producto(key: String, estado: String) = CommercialProduct(
        key = ProductKey(key), itemName = "Audífonos $key",
        publishedPrice = Money.of(120_000), minimumPrice = Money.of(95_000), cost = Money.of(70_000),
        status = estado, description = "Descripción pública", brand = "Marca", masterQuantity = 3
    ).also {
        keysCreadas += key
        synchronized(AppStore.products) { AppStore.products[key] = it }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.montarExcel() {
        val guard = guardiaDePrueba()
        val parser = ExcelV2Parser()
        rutas { excelRoutes(guard, null, null, ExcelPreviewService(parser = parser), parser) }
    }

    // ---------------- Excel ----------------

    @Test
    fun `exportar el Excel sin credenciales es 401`() = testApplication {
        montarExcel()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/excel/export").status)
    }

    @Test
    fun `el usuario de lectura puede exportar y el archivo es un xlsx`() = testApplication {
        montarExcel()
        val r = client.get("/api/excel/export") { comoViewer() }
        assertEquals(HttpStatusCode.OK, r.status)
        val bytes = r.bodyAsBytes()
        // Un .xlsx es un ZIP: empieza por "PK".
        assertTrue(bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
    }

    @Test
    fun `subir un Excel sin credenciales es 401`() = testApplication {
        montarExcel()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/excel/preview").status)
    }

    @Test
    fun `el usuario de lectura no puede subir un Excel`() = testApplication {
        montarExcel()
        val r = client.post("/api/excel/preview") { comoViewer(); desdeElSitio() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `subir un Excel sin Origin propio es 403`() = testApplication {
        montarExcel()
        val r = client.post("/api/excel/preview") { comoAdmin(); desde() }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    // ---------------- vitrina pública ----------------

    @Test
    fun `la vitrina de un producto que no existe es 404`() = testApplication {
        rutas { publicRoutes() }
        assertEquals(HttpStatusCode.NotFound, client.get("/public/p/NO-EXISTE-XYZ").status)
    }

    @Test
    fun `la vitrina muestra el precio publicado y nunca el costo ni el minimo`() = testApplication {
        producto("VITRINA-ACTIVO", "Activo")
        rutas { publicRoutes() }
        val r = client.get("/public/p/VITRINA-ACTIVO")
        assertEquals(HttpStatusCode.OK, r.status)
        val cuerpo = r.bodyAsText()
        assertTrue(cuerpo.contains("120000"), cuerpo)
        assertFalse(cuerpo.contains("95000"), "no debe exponer el precio mínimo: $cuerpo")
        assertFalse(cuerpo.contains("70000"), "no debe exponer el costo: $cuerpo")
        assertFalse(cuerpo.contains("minimumPrice") || cuerpo.contains("cost"), cuerpo)
    }

    @Test
    fun `un producto inactivo no aparece en la vitrina`() = testApplication {
        producto("VITRINA-INACTIVO", "Inactivo")
        rutas { publicRoutes() }
        assertEquals(HttpStatusCode.NotFound, client.get("/public/p/VITRINA-INACTIVO").status)
    }
}
