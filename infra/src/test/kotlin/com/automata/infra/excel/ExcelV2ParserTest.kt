package com.automata.infra.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class ExcelV2ParserTest {

    private val parser = ExcelV2Parser()
    private val header = listOf("Key", "Item name", "PP", "PM", "Costo", "Estado")

    /** Arma un .xlsx en memoria. Cada celda puede ser String, número o null (celda vacía). */
    private fun xlsx(
        rows: List<List<Any?>>,
        headers: List<String> = header,
        extra: (XSSFWorkbook) -> Unit = {}
    ): ByteArray {
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet("Inventario")
        val h = sheet.createRow(0)
        headers.forEachIndexed { i, name -> h.createCell(i).setCellValue(name) }
        rows.forEachIndexed { r, values ->
            val row = sheet.createRow(r + 1)
            values.forEachIndexed { c, v ->
                when (v) {
                    null -> Unit
                    is Number -> row.createCell(c).setCellValue(v.toDouble())
                    else -> row.createCell(c).setCellValue(v.toString())
                }
            }
        }
        extra(wb)
        val out = ByteArrayOutputStream()
        wb.write(out)
        wb.close()
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray) = parser.parseExcel(bytes.inputStream())

    @Test
    fun `fila valida con montos en texto y numericos se importa`() {
        val r = parse(xlsx(listOf(
            listOf("PROD-001", "Audífonos", "\$120.000", "\$95.000", "\$70.000", "Publicado"),
            listOf("PROD-002", "Reloj", 250000, 210000, 150000, "Activo")
        )))
        assertNull(r.fileError)
        assertTrue(r.errors.isEmpty(), "errores: ${r.errors}")
        assertEquals(2, r.products.size)
        assertEquals(120_000L, r.products[0].publishedPrice.amountInCop)
        assertEquals(95_000L, r.products[0].minimumPrice.amountInCop)
        assertEquals(250_000L, r.products[1].publishedPrice.amountInCop)
    }

    @Test
    fun `una fila con Consultar se rechaza con hoja fila y columna y las demas se importan`() {
        val r = parse(xlsx(listOf(
            listOf("PROD-001", "Audífonos", "\$120.000", "\$95.000", "\$70.000", "Publicado"),
            listOf("PROD-002", "Reloj", "Consultar", "\$210.000", "\$150.000", "Publicado"),
            listOf("PROD-003", "Cámara", "300000", "250000", "200000", "Publicado")
        )))
        assertNull(r.fileError)
        assertEquals(listOf("PROD-001", "PROD-003"), r.products.map { it.key.value })
        assertEquals(1, r.rejectedRows)
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].startsWith("Hoja 'Inventario', fila 3, columna 'PP':"), r.errors[0])
        assertTrue(r.products.none { it.publishedPrice.amountInCop == 0L || it.minimumPrice.amountInCop == 0L })
    }

    @Test
    fun `PP y PM vacios o en cero se rechazan`() {
        val r = parse(xlsx(listOf(
            listOf("A-1", "Uno", null, "50000", "10000", "Publicado"),
            listOf("A-2", "Dos", "100000", "", "10000", "Publicado"),
            listOf("A-3", "Tres", 0, 0, 0, "Publicado")
        )))
        assertTrue(r.products.isEmpty())
        assertEquals(3, r.rejectedRows)
        assertTrue(r.errors.any { it.startsWith("Hoja 'Inventario', fila 2, columna 'PP':") && it.contains("vacío") })
        assertTrue(r.errors.any { it.startsWith("Hoja 'Inventario', fila 3, columna 'PM':") && it.contains("vacío") })
        assertTrue(r.errors.any { it.startsWith("Hoja 'Inventario', fila 4, columna 'PP':") && it.contains("mayor que 0") })
        assertTrue(r.errors.any { it.startsWith("Hoja 'Inventario', fila 4, columna 'PM':") && it.contains("mayor que 0") })
    }

    @Test
    fun `PM mayor que PP se rechaza`() {
        val r = parse(xlsx(listOf(listOf("A-1", "Uno", "100000", "120000", "50000", "Publicado"))))
        assertTrue(r.products.isEmpty())
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].startsWith("Hoja 'Inventario', fila 2, columna 'PM':"), r.errors[0])
    }

    @Test
    fun `costo negativo se rechaza y costo vacio se acepta con aviso`() {
        val r = parse(xlsx(listOf(
            listOf("A-1", "Uno", "100000", "80000", -5000, "Publicado"),
            listOf("A-2", "Dos", "100000", "80000", null, "Publicado")
        )))
        assertEquals(listOf("A-2"), r.products.map { it.key.value })
        assertEquals(0L, r.products[0].cost.amountInCop)
        assertTrue(r.errors.single().startsWith("Hoja 'Inventario', fila 2, columna 'Costo':"))
        assertTrue(r.errors.single().contains("negativo"))
        assertTrue(r.warnings.any { it.startsWith("Hoja 'Inventario', fila 3, columna 'Costo':") })
    }

    @Test
    fun `clave duplicada conserva la primera fila y rechaza la repetida`() {
        val r = parse(xlsx(listOf(
            listOf("PROD-001", "Primero", "100000", "80000", "50000", "Publicado"),
            listOf("prod-001", "Repetido", "999999", "888888", "1000", "Publicado")
        )))
        assertEquals(1, r.products.size)
        assertEquals("Primero", r.products[0].itemName)
        assertEquals(100_000L, r.products[0].publishedPrice.amountInCop)
        assertEquals(1, r.rejectedRows)
        assertTrue(r.errors.single().startsWith("Hoja 'Inventario', fila 3, columna 'Key':"))
        assertTrue(r.errors.single().contains("duplicada"))
    }

    @Test
    fun `archivo danado devuelve fileError y no lanza excepcion`() {
        val corrupto = parse("esto no es un excel".toByteArray())
        assertNotNull(corrupto.fileError)
        assertTrue(corrupto.products.isEmpty())

        val vacio = parse(ByteArray(0))
        assertNotNull(vacio.fileError)
    }

    @Test
    fun `sin hoja Inventario o sin columnas obligatorias devuelve fileError`() {
        val sinHoja = XSSFWorkbook().also { it.createSheet("Otra") }.let { wb ->
            val out = ByteArrayOutputStream(); wb.write(out); wb.close(); out.toByteArray()
        }
        assertTrue(parse(sinHoja).fileError!!.contains("Inventario"))

        val sinPP = parse(xlsx(listOf(listOf("A-1", "Uno", "80000")), headers = listOf("Key", "Item name", "PM")))
        assertNotNull(sinPP.fileError)
        assertTrue(sinPP.fileError!!.contains("'PP'"))

        val sinProductos = parse(xlsx(emptyList()))
        assertNotNull(sinProductos.fileError)
    }

    @Test
    fun `hoja Publicacion sin columna Key no rompe la importacion`() {
        val bytes = xlsx(listOf(listOf("A-1", "Uno", "100000", "80000", "50000", "Publicado"))) { wb ->
            val pub = wb.createSheet("Publicacion")
            pub.createRow(0).createCell(0).setCellValue("Marca")
            pub.createRow(1).createCell(0).setCellValue("ACME")
        }
        val r = parse(bytes)
        assertNull(r.fileError)
        assertEquals(1, r.products.size)
        assertTrue(r.warnings.any { it.contains("Publicacion") })
    }

    @Test
    fun `estado Inactivo se conserva y un estado desconocido avisa`() {
        val r = parse(xlsx(listOf(
            listOf("A-1", "Uno", "100000", "80000", "50000", "Inactivo"),
            listOf("A-2", "Dos", "100000", "80000", "50000", "Inactvo"),
            listOf("A-3", "Tres", "100000", "80000", "50000", ""),
            listOf("A-4", "Cuatro", "100000", "80000", "50000", "Publicado")
        )))
        assertEquals(4, r.products.size)
        assertFalse(r.products[0].isActive)
        assertTrue(r.products[1].isActive)       // typo: se trata como activo...
        assertTrue(r.products[2].isActive)       // vacío = Activo
        assertTrue(r.products[3].isActive)
        assertEquals("Activo", r.products[2].status)
        // ...pero se avisa, solo para la fila del typo
        val avisos = r.warnings.filter { it.contains("columna 'Estado'") }
        assertEquals(1, avisos.size)
        assertTrue(avisos[0].contains("fila 3"))
    }

    @Test
    fun `parsea correctamente las columnas extendidas de NEGOCIO xlsx`() {
        val headers = listOf(
            "Key", "Item name", "PP", "PM", "Costo", "Ganancia", "ROI/Margen",
            "Estado", "Link", "Fotos", "Marca", "Tipo", "Condición", "cantidad", "ubicación",
            "Descripción AB", "Fecha Compra", "Fecha Venta"
        )
        val data = listOf(
            listOf(
                "PROD-100", "Smartphone Pro", "1500000", "1200000", "900000", "600000", "40%",
                "Publicado", "https://ejemplo.com", "https://foto1.jpg", "TechBrand", "Celulares",
                "Reacondicionado", 5, "Estante B-12", "Excelente estado", "2026-01-01", null
            )
        )
        val r = parse(xlsx(data, headers = headers))
        assertNull(r.fileError)
        assertEquals(1, r.products.size)
        val p = r.products[0]
        assertEquals("PROD-100", p.key.value)
        assertEquals("Smartphone Pro", p.itemName)
        assertEquals("TechBrand", p.brand)
        assertEquals("Celulares", p.category)
        assertEquals("Reacondicionado", p.condition)
        assertEquals(5, p.masterQuantity)
        assertEquals("Estante B-12", p.location)
        assertEquals("Excelente estado", p.description)
    }

    @Test
    fun `A6 grandes numeros en formulas y celdas numericas no usan notacion cientifica`() {
        val r = parse(xlsx(listOf(
            listOf("PROD-LARGE", "Televisor OLED 75", "12345678", "10000000", "8000000", "Publicado")
        )))
        assertNull(r.fileError)
        assertEquals(1, r.products.size)
        assertEquals(12_345_678L, r.products[0].publishedPrice.amountInCop)
        assertEquals(10_000_000L, r.products[0].minimumPrice.amountInCop)
    }
}

