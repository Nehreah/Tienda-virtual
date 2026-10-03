# Manual de cambios — Fase 2 · tareas 2.1 y 2.2

Proyecto: Tienda Virtual Multicanal v5.3.0 · Kotlin / Ktor / Postgres
Plan de referencia: `Plan de implementación Tienda Virtual` (Fase 2, tareas 2.1 «Validación estricta del Excel» y 2.2 «Respetar `status`»)

> ## ⚠️ Aviso de verificación (léelo primero)
>
> En este entorno **no hay Gradle, `kotlinc` ni acceso a Maven Central**, así que **no se pudo compilar el proyecto ni ejecutar `./gradlew test`**.
> Lo que sí se hizo:
>
> | Verificación | Resultado |
> |---|---|
> | Lógica de `AmountParser` (mismas expresiones regulares y mismas reglas) probada con una réplica en Python sobre 25 casos equivalentes a los de `AmountParserTest` | ✅ Todos dan el resultado esperado |
> | Sintaxis Mermaid de `DIAGRAMA_DE_CLASES_v6.md` | ✅ Se renderiza sin errores |
> | Revisión línea a línea de los diffs (Anexo A) y de los tipos que usa cada cambio | ✅ Sin errores detectados a ojo |
> | Compilar el código Kotlin (`AmountParser`, `ExcelV2Parser`, pipeline, `Application.kt`, renderizador) | ❌ **No verificable aquí** |
> | Ejecutar las 24 pruebas nuevas | ❌ **No ejecutadas** (están escritas, pero nadie las ha corrido todavía) |
> | Probar las rutas HTTP (subida de Excel, vitrina, simulador) | ❌ No probadas |
>
> **Lo primero que debes hacer:** `./gradlew test`. Si algo no compila (lo más probable: `Application.kt` o las pruebas que arman un `.xlsx` con Apache POI, o el problema ya conocido de Gradle 9.7.1 + Kotlin 1.9.22 de la Fase 0), pega el mensaje exacto y lo corrijo.

---

## 1. Resumen

| ID | Tarea | Estado |
|---|---|---|
| 2.1 | Validación estricta del Excel (PP > 0, PM > 0, PM ≤ PP, Costo ≥ 0; montos normalizados; errores con hoja/fila/columna; duplicados; archivo corrupto → 400) | ✅ Hecho (sin compilar, ver aviso) |
| 2.2 | Respetar `status`: los productos «Inactivo» no entran al bot, no se negocian, no están en la vitrina ni se publican | ✅ Hecho (sin compilar) · ⚠️ con una limitación de persistencia, ver §5 |
| 2.3 – 2.6 | Cargar `AppStore` desde BD, fuente única del PM, Shopify con varios ítems, stock negativo | ⏳ No iniciadas |

---

## 2. Archivos entregados

**Nuevos (8)**

| Archivo | Para qué |
|---|---|
| `infra/src/main/kotlin/com/automata/infra/excel/AmountParser.kt` | Normaliza y valida los montos de las celdas (2.1) |
| `infra/src/test/kotlin/com/automata/infra/excel/AmountParserTest.kt` | 6 pruebas de montos |
| `infra/src/test/kotlin/com/automata/infra/excel/ExcelV2ParserTest.kt` | 10 pruebas con Excel armados en memoria (filas malas, duplicados, archivo dañado, estados) |
| `commerce/src/test/kotlin/com/automata/commerce/CommercialProductActiveTest.kt` | 1 prueba de `isActive` |
| `app/src/test/kotlin/com/automata/app/AppStoreCatalogTest.kt` | 2 pruebas: catálogo del bot y vitrina sin inactivos |
| `app/src/test/kotlin/com/automata/app/pipeline/InactiveProductPipelineTest.kt` | 5 pruebas del chat con producto inactivo |
| `DIAGRAMA_DE_CLASES_v6.md` | Diagrama de clases actualizado |
| `MANUAL_DE_CAMBIOS_FASE_2.md` | Este documento |

**Modificados (6)**

| Archivo | Qué cambió |
|---|---|
| `commerce/.../model/CommercialProduct.kt` | Nueva propiedad `isActive` |
| `infra/.../excel/ExcelV2Parser.kt` | Validación estricta; `ExcelV2ImportResult` gana `rejectedRows` y `fileError` |
| `app/.../pipeline/InboundMessagePipeline.kt` | `PipelineCatalog.knownKeys()`; respuesta «no disponible» para inactivos |
| `app/.../Application.kt` | Catálogo sin inactivos, `publicProduct`, ruta de subida de Excel, simulador, vitrina |
| `app/.../dashboard/DashboardRenderer.kt` | Marca «INACTIVO» / «NO SE PUBLICA» en la tabla de productos |
| `MANUAL_DE_USO.md` | Documenta qué significa Inactivo y las reglas de validación al importar |

No se tocó ningún otro archivo. Todo lo demás del proyecto queda igual que en la Fase 1. El **Anexo A** lista cada cambio de código línea por línea (diff real).

---

## 3. Qué se cambió, dónde y por qué

### 2.1 Validación estricta del Excel

**Problema.** En `ExcelV2Parser` los montos se leían con `toLongOrNull() ?: 0L`: un PP «Consultar», una celda vacía o un `120,000` quedaban en **0** sin avisar, y el producto se importaba con precio 0. Además solo se validaba `PM > PP`; un archivo dañado o sin columna `Key` terminaba en error 500.

**Cambio.**
- **`AmountParser` (nuevo).** `parse(raw)` devuelve `Valid(cop)` o `Invalid(motivo)`. Acepta `120000`, `120.000`, `120,000`, `120 000`, `$120000`, `$ 120.000`, `COP 120.000`, `120.000,00` y `120000.0` (la parte decimal solo si es cero). Rechaza con motivo: vacío, «Consultar» u otro texto, negativos (también con la marca `'` que antepone la defensa de fórmulas), decimales distintos de cero, separadores mezclados (`1.200,000`) y valores mayores a 1 billón de pesos.
- **`ExcelV2Parser.readWorkbook`, paso 4.** Para cada fila se revisan PP, PM y Costo con `readAmount(...)`: PP y PM deben ser mayores que 0; Costo, mayor o igual a 0; y PM ≤ PP. Se juntan **todos** los problemas de la fila y, si hay alguno, la fila se rechaza (no se crea el producto). Formato del error: `Hoja 'Inventario', fila 5, columna 'PP': 'Consultar' no es un monto válido; usa solo números...`
- **Claves duplicadas (paso 1).** La clave se compara sin distinguir mayúsculas. Se conserva la primera fila; la repetida se rechaza con error `fila N, columna 'Key': la clave ... está duplicada (ya aparece en la fila M)`.
- **Archivo dañado o incompleto.** `parseExcel` atrapa los errores de Apache POI y devuelve `fileError` (en vez de lanzar excepción). También es `fileError`: falta la hoja `Inventario`, faltan las columnas `Key`, `PP` o `PM`, o la hoja no tiene ningún producto.
- **Hojas `Publicacion` y `Canales` sin columna `Key`/`Canal`.** Antes lanzaban excepción (`getCell(-1)`) → 500. Ahora se ignoran con una advertencia (`cellText(...)`).
- **`Application.kt`, `POST /api/excel/upload`.** `fileError` → **400** `{"error": "..."}` y no se importa nada. Si hay filas rechazadas, **las válidas se importan igual** y la respuesta es JSON con `summary`, `errors` y `warnings` (**200** si se importó al menos un producto, **422** si ninguno). Si todo estaba bien, redirige al tablero como antes.

**Pruebas.** `AmountParserTest` (6) y `ExcelV2ParserTest` (10): fila válida, `Consultar`, PP/PM vacíos o en 0, PM > PP, costo negativo/vacío, duplicados, archivo dañado/vacío, sin hoja `Inventario`, sin columna `PP`, `Publicacion` sin `Key`, y que ningún producto quede con precio 0. `ExcelGeneratorTest` (ida y vuelta exportar → importar) sigue siendo válido porque el exportador escribe `$100.000`, que el nuevo parser acepta.

### 2.2 Respetar `status` («Inactivo»)

**Problema.** El parser guardaba `status`, pero nada lo leía: un producto «Inactivo» se negociaba, se reservaba y se mostraba en la vitrina igual que uno activo.

**Cambio.**
- **`CommercialProduct.isActive`** (única regla del sistema): `false` solo si `status` es «Inactivo» (sin importar mayúsculas ni espacios). Cualquier otro valor, incluido vacío, es activo.
- **Catálogo del bot.** `AppStoreCatalog.productKeys()` devuelve solo productos activos (los usa `askWhichProduct` y la elección del «único producto»). Nuevo `knownKeys()` devuelve todas las claves: el intérprete las recibe para **reconocer** el código de un producto retirado; si no, el cliente que escribe «PROD-002» (inactivo) acabaría hablando de otro producto.
- **Pipeline (`InboundMessagePipeline.decide`).** Si el producto es inactivo se responde «Lo sentimos, X ya no está disponible por ahora.» más la lista de productos activos; no se negocia ni se reserva, y se limpia `activeProductKey` para que el siguiente mensaje no siga apuntando al retirado. `tryReserve` agrega una segunda defensa (`if (!product.isActive) return null`).
- **Vitrina.** `GET /public/p/{key}` usa `AppStoreCatalog.publicProduct(key)`: un inactivo responde **404**, igual que uno que no existe (no revela que existe).
- **Simulador del tablero.** Elige el primer producto **activo**; si el mensaje nombra un inactivo responde «no está disponible».
- **Tablero.** Los inactivos se siguen listando (para que veas qué retiraste) con la etiqueta «INACTIVO», y las columnas de canales muestran «NO SE PUBLICA». No se les crea estado de sincronización.
- **Shopify / Meta.** Hoy **ningún código llama a `publishProduct`** (solo la saga de retiro), así que no hay nada que filtrar todavía. La regla `isActive` queda lista para cuando se implemente (tarea 5.4); ver §5.

**Pruebas.** `CommercialProductActiveTest`, `AppStoreCatalogTest` (catálogo del bot y vitrina) e `InactiveProductPipelineTest` (responde «no disponible», no reserva ni con oferta ni con «apártame», el intérprete recibe la clave inactiva, el activo sigue funcionando, sin código usa solo el activo).

---

## 4. Decisiones de diseño que difieren o completan el plan

1. **Costo vacío se acepta con advertencia (se toma 0).** El plan dice «rechazar vacíos» en montos pero no precisa Costo. Costo es un dato interno que no define precios, y rechazar la fila entera por eso pareció excesivo. Costo con texto inválido o negativo **sí** se rechaza. Si prefieres rechazar también el vacío, cambia el bloque `costCop` en `ExcelV2Parser` por `readAmount(..., mustBePositive = false)`.
2. **Duplicados sin distinguir mayúsculas** (`PROD-001` = `prod-001`): el intérprete del chat compara en minúsculas, así que dos claves que solo difieren en mayúsculas se confundirían.
3. **Tope de 1 billón de pesos por monto.** No está en el plan: evita desbordes al multiplicar precios (por ejemplo `PP × 10` en el pipeline).
4. **Estado vacío = «Activo»; estado desconocido = Activo con advertencia.** Así un error de tipeo como «Inactvo» no pasa en silencio (la advertencia sale en la respuesta de la subida).
5. **Importación parcial.** Antes, un solo error rechazaba todo el archivo (422). Ahora se importan las filas válidas, como pide el criterio de aceptación. Las filas que fallan **no tocan** la versión que ya estaba en memoria.
6. **Respuesta de la subida.** Se redirige al tablero solo si no hay errores ni advertencias sobre filas. La advertencia «Hoja Publicacion no encontrada» no cuenta (si no, tu `NEGOCIO.xlsx`, que solo tiene `Inventario` y `Config`, mostraría JSON cada vez).
7. **Hojas `Publicacion`/`Canales` malformadas** pasan de error 500 a advertencia: es el mismo «archivo malo no debe dar 500» de 2.1.

---

## 5. Qué falta

**Dentro de 2.1 / 2.2 (decisiones tuyas, ya marcadas en el código)**
- ⚠️ **«Inactivo» no se conserva en la base de datos ni en la exportación.** Hoy, al guardar en BD y al exportar a Excel, el estado se convierte con `EstadoItem.desdeEtiqueta(p.status) ?: EstadoItem.PUBLICADO` (en `Application.kt`), y «Inactivo» no es un estado de `cat_estado` → se guarda y exporta como **«Publicado»**. En memoria funciona bien, pero (a) si exportas, editas y vuelves a importar, el producto **se reactiva**, y (b) la tarea **2.3** (cargar `AppStore` desde la BD) lo «resucitaría» al reiniciar. **Hay que decidir antes de 2.3**: agregar «Inactivo» a `cat_estado`/`EstadoItem`, o guardarlo en una columna aparte. No lo cambié porque toca el esquema de la BD.
- **Los estados «Vendido» y «Comprado» siguen contando como activos.** El plan solo habla de «Inactivo», pero tu `NEGOCIO.xlsx` usa los estados reales (Comprado, Publicado, Negociación, Vendido, Otro) y la cantidad por defecto es 1: un producto «Vendido» seguiría ofreciéndose en el chat. Conviene decidir si «Vendido» (y quizá «Comprado»/«Otro») también debe salir del catálogo; es cambiar una línea en `isActive`.
- **Publicación en Shopify/Meta:** la regla `isActive` aún no se aplica porque no existe el código que publica. Debe aplicarse cuando se haga la tarea **5.4** (y `toPublicDto` hoy no lo comprueba).
- Una **reserva ya activa** de un producto que luego se marca inactivo no se libera: vence sola (15 min, o 12 h si hay comprobante).
- Una venta de **Shopify** de un producto inactivo (ya publicado allá) sí descuenta stock: es una venta real.

**Observado, sin cambiar** (queda en su tarea)
- Reimportar un Excel **no borra** los productos que ya no vienen en el archivo.
- Una fila con datos pero **sin `Key`** se sigue saltando en silencio.
- `Cantidad disponible` inválida se convierte en 1 sin aviso, y `Precio del canal` inválido en «sin precio» (fuera del alcance de 2.1, que habla de PP/PM/Costo).
- Los mensajes de error de la subida citan el texto de la celda (máx. 30 caracteres) dentro de JSON; si algún día se muestran como HTML hay que escaparlos (tarea 4.1).
- La defensa de fórmulas (`'` inicial) sigue en la importación (tarea 4.8).

**Fase 0 y siguientes:** el proyecto aún no se ha compilado ni probado con Gradle (Fase 0, sin tocar). Tareas 2.3 a 2.6 y fases 3 a 6: no iniciadas.

---

## 6. Lista de comprobación manual (con la app corriendo)

Reemplaza `TU_CLAVE` por el valor de `DASHBOARD_PASSWORD` de `local.properties`. Los `POST` necesitan la cabecera `Origin` (defensa CSRF de la Fase 1).

| # | Qué comprobar | Cómo | Resultado esperado |
|---|---|---|---|
| 1 | Pruebas automáticas | `./gradlew test` | Todo verde (24 pruebas nuevas) |
| 2 | Subida correcta | `curl -i -u admin:TU_CLAVE -H "Origin: http://127.0.0.1:8080" -F "file=@NEGOCIO.xlsx" http://127.0.0.1:8080/api/excel/upload` | `302` hacia `/` |
| 3 | Archivo dañado | `curl -i -u admin:TU_CLAVE -H "Origin: http://127.0.0.1:8080" -F "file=@README.md;filename=x.xlsx" http://127.0.0.1:8080/api/excel/upload` | `400` con `{"error":"El archivo no es un Excel (.xlsx) válido o está dañado."}` |
| 4 | Fila inválida | Copia `NEGOCIO.xlsx`, pon `Consultar` en el PP de una fila y súbelo como en el 2 | `200` con `errors` → `Hoja 'Inventario', fila N, columna 'PP': ...`; las demás filas importadas |
| 5 | Producto inactivo | En el Excel pon `Inactivo` en el Estado de `PROD-002`, súbelo y luego `curl -i http://127.0.0.1:8080/public/p/PROD-002` | `404`; `/public/p/PROD-001` responde `200` |
| 6 | Simulador | `curl -u admin:TU_CLAVE -H "Origin: http://127.0.0.1:8080" -d "message=info del PROD-002" http://127.0.0.1:8080/api/sim/chat` | «Este producto no está disponible (estado Inactivo).» |
| 7 | Tablero | Abrir `/` | `PROD-002` con etiqueta INACTIVO y «NO SE PUBLICA» en los canales |

---

## Anexo A — Diffs exactos del código modificado

### A.1 `commerce/src/main/kotlin/com/automata/commerce/model/CommercialProduct.kt`

```diff
--- commerce/src/main/kotlin/com/automata/commerce/model/CommercialProduct.kt (antes)
+++ commerce/src/main/kotlin/com/automata/commerce/model/CommercialProduct.kt (después)
@@ -26,6 +26,14 @@
     val images: List<String> = emptyList(),
     val masterQuantity: Int = 0 // Stock Maestro del Excel
 ) {
+    /**
+     * Fase 2.2: un producto con estado "Inactivo" (sin importar mayúsculas ni espacios) NO se vende:
+     * no entra al catálogo del bot, no se negocia ni se reserva, y no se publica en la vitrina ni en los canales.
+     * Cualquier otro valor (incluido vacío) se trata como activo.
+     */
+    val isActive: Boolean
+        get() = !status.trim().equals("inactivo", ignoreCase = true)
+
     /** Ganancia sobre el precio publicado. Con signo: si PP < costo es una pérdida (negativa), ya no se oculta. */
     val profit: SignedCop
         get() = SignedCop(publishedPrice.amountInCop - cost.amountInCop)
```

### A.2 `infra/src/main/kotlin/com/automata/infra/excel/ExcelV2Parser.kt`

```diff
--- infra/src/main/kotlin/com/automata/infra/excel/ExcelV2Parser.kt (antes)
+++ infra/src/main/kotlin/com/automata/infra/excel/ExcelV2Parser.kt (después)
@@ -1,6 +1,7 @@
 package com.automata.infra.excel
 
 import com.automata.commerce.model.CommercialProduct
+import com.automata.commerce.model.EstadoItem
 import com.automata.shared.Money
 import com.automata.shared.ProductKey
 import org.apache.poi.ss.usermodel.*
@@ -19,49 +20,108 @@
     val products: List<CommercialProduct>,
     val channelOverrides: List<ChannelOverride>,
     val errors: List<String>,
-    val warnings: List<String>
+    val warnings: List<String>,
+    /** Filas de la hoja Inventario descartadas por tener errores (una fila con dos columnas malas cuenta una vez). */
+    val rejectedRows: Int = 0,
+    /**
+     * Fase 2.1: error que impide leer el archivo completo (dañado, sin hoja Inventario, sin columnas obligatorias,
+     * sin productos). Quien llama debe responder 400. Cuando existe, [products] está vacío.
+     */
+    val fileError: String? = null
 )
 
 /**
  * Lector y Validador de Excel v2 (Plan Maestro v5.3 Sección 07).
  * Procesa las 3 hojas: Inventario, Publicacion y Canales.
  * Sanitiza fórmulas celda por celda (= + - @) para prevenir injection attacks.
+ *
+ * Fase 2.1 (validación estricta de la hoja Inventario):
+ *  - PP > 0, PM > 0, PM <= PP y Costo >= 0. Los montos aceptan 120000, 120.000, 120,000, 120 000 y $120000 ([AmountParser]).
+ *  - "Consultar", vacíos, negativos y textos no numéricos NO se convierten en 0: la fila se rechaza.
+ *  - Cada error indica hoja, fila y columna. Las filas válidas se importan igual; las malas se listan en [ExcelV2ImportResult.errors].
+ *  - Clave duplicada: se conserva la primera fila y se rechaza la repetida.
+ *  - Archivo corrupto o sin lo mínimo: [ExcelV2ImportResult.fileError] (el llamador responde 400, nunca 500).
  */
 class ExcelV2Parser {
 
     fun parseExcel(inputStream: InputStream): ExcelV2ImportResult {
-        val workbook = WorkbookFactory.create(inputStream)
+        val workbook = try {
+            WorkbookFactory.create(inputStream)
+        } catch (e: Exception) {
+            System.err.println("ExcelV2Parser: archivo no legible (${e::class.simpleName})")
+            return fileFailure("El archivo no es un Excel (.xlsx) válido o está dañado.")
+        }
+        return try {
+            workbook.use { readWorkbook(it) }
+        } catch (e: Exception) {
+            System.err.println("ExcelV2Parser: error leyendo el contenido (${e::class.simpleName}: ${e.message})")
+            fileFailure("No se pudo leer el contenido del Excel. Revisa que las hojas y las celdas no estén dañadas.")
+        }
+    }
+
+    private fun readWorkbook(workbook: Workbook): ExcelV2ImportResult {
         val errors = mutableListOf<String>()
         val warnings = mutableListOf<String>()
+        var rejectedRows = 0
 
-        val inventarioSheet = workbook.getSheet("Inventario")
-            ?: return ExcelV2ImportResult(emptyList(), emptyList(), listOf("Falta la hoja requerida 'Inventario'"), emptyList())
+        val inventarioSheet = workbook.getSheet(SHEET_INVENTARIO)
+            ?: return fileFailure("Falta la hoja requerida 'Inventario'")
 
         val publicacionSheet = workbook.getSheet("Publicacion")
         val canalesSheet = workbook.getSheet("Canales")
 
         // 1. Parsear Hoja Inventario
         val rawProducts = mutableMapOf<String, MutableMap<String, String>>()
+        val rowNumbers = mutableMapOf<String, Int>()          // key -> número de fila en Excel (1 = encabezado)
+        val seenKeys = mutableMapOf<String, Pair<String, Int>>() // key en minúsculas -> (key original, fila)
         val headerMap = parseHeaders(inventarioSheet.getRow(0))
 
+        val missingColumns = REQUIRED_COLUMNS.filter { (name, _) -> !headerMap.containsKey(name) }.map { it.second }
+        if (missingColumns.isNotEmpty()) {
+            return fileFailure(
+                "Hoja 'Inventario', fila 1: faltan las columnas obligatorias " +
+                    missingColumns.joinToString(", ") { "'$it'" } + "."
+            )
+        }
+
         for (rowIdx in 1..inventarioSheet.lastRowNum) {
             val row = inventarioSheet.getRow(rowIdx) ?: continue
-            val keyVal = getSanitizedCellValue(row.getCell(headerMap["key"] ?: -1))
+            val excelRow = rowIdx + 1
+            val keyVal = cellText(row, headerMap["key"])
             if (keyVal.isBlank()) continue
 
+            val previous = seenKeys[keyVal.lowercase()]
+            if (previous != null) {
+                errors.add(
+                    "Hoja 'Inventario', fila $excelRow, columna 'Key': la clave '$keyVal' está duplicada " +
+                        "(ya aparece en la fila ${previous.second}); se rechaza esta fila y se conserva la primera."
+                )
+                rejectedRows++
+                continue
+            }
+            seenKeys[keyVal.lowercase()] = keyVal to excelRow
+
             val rowData = mutableMapOf<String, String>()
             headerMap.forEach { (colName, colIdx) ->
                 rowData[colName] = getSanitizedCellValue(row.getCell(colIdx))
             }
             rawProducts[keyVal] = rowData
+            rowNumbers[keyVal] = excelRow
+        }
+
+        if (rawProducts.isEmpty() && errors.isEmpty()) {
+            return fileFailure("La hoja 'Inventario' no tiene ningún producto (filas con 'Key').")
         }
 
         // 2. Parsear Hoja Publicacion (Marca, Condición, GTIN, MPN, Fotos)
         if (publicacionSheet != null) {
             val pubHeaderMap = parseHeaders(publicacionSheet.getRow(0))
+            if (!pubHeaderMap.containsKey("key")) {
+                warnings.add("Hoja 'Publicacion' sin columna 'Key': se ignora.")
+            }
             for (rowIdx in 1..publicacionSheet.lastRowNum) {
                 val row = publicacionSheet.getRow(rowIdx) ?: continue
-                val keyVal = getSanitizedCellValue(row.getCell(pubHeaderMap["key"] ?: -1))
+                val keyVal = cellText(row, pubHeaderMap["key"])
                 if (keyVal.isBlank() || !rawProducts.containsKey(keyVal)) continue
 
                 val targetData = rawProducts[keyVal]!!
@@ -80,15 +140,18 @@
         val channelOverrides = mutableListOf<ChannelOverride>()
         if (canalesSheet != null) {
             val canHeaderMap = parseHeaders(canalesSheet.getRow(0))
+            if (!canHeaderMap.containsKey("key") || !canHeaderMap.containsKey("canal")) {
+                warnings.add("Hoja 'Canales' sin columna 'Key' o 'Canal': se ignora.")
+            }
             for (rowIdx in 1..canalesSheet.lastRowNum) {
                 val row = canalesSheet.getRow(rowIdx) ?: continue
-                val keyVal = getSanitizedCellValue(row.getCell(canHeaderMap["key"] ?: -1))
-                val channel = getSanitizedCellValue(row.getCell(canHeaderMap["canal"] ?: -1))
+                val keyVal = cellText(row, canHeaderMap["key"])
+                val channel = cellText(row, canHeaderMap["canal"])
                 if (keyVal.isBlank() || channel.isBlank()) continue
 
-                val publishStr = getSanitizedCellValue(row.getCell(canHeaderMap["publicar"] ?: -1))
-                val customTitle = getSanitizedCellValue(row.getCell(canHeaderMap["título para el canal"] ?: -1))
-                val customPrice = getSanitizedCellValue(row.getCell(canHeaderMap["precio del canal"] ?: -1)).toLongOrNull()
+                val publishStr = cellText(row, canHeaderMap["publicar"])
+                val customTitle = cellText(row, canHeaderMap["título para el canal"])
+                val customPrice = cellText(row, canHeaderMap["precio del canal"]).toLongOrNull()
 
                 channelOverrides.add(
                     ChannelOverride(
@@ -105,11 +168,44 @@
         // 4. Mapear a entidades CommercialProduct con validaciones
         val commercialProducts = mutableListOf<CommercialProduct>()
         for ((keyStr, data) in rawProducts) {
+            val excelRow = rowNumbers.getValue(keyStr)
+            val rowErrors = mutableListOf<String>()
+
             val itemName = data["item name"] ?: data["item"] ?: keyStr
-            val ppCop = data["pp"]?.replace("$", "")?.replace(".", "")?.trim()?.toLongOrNull() ?: 0L
-            val pmCop = data["pm"]?.replace("$", "")?.replace(".", "")?.trim()?.toLongOrNull() ?: 0L
-            val costCop = data["costo"]?.replace("$", "")?.replace(".", "")?.trim()?.toLongOrNull() ?: 0L
-            val status = data["estado"] ?: "Activo"
+
+            // Montos: nunca se convierten en 0 por error. Se revisan los tres para informar todos los problemas de la fila de una vez.
+            val ppCop = readAmount(data["pp"], "PP", excelRow, rowErrors, mustBePositive = true)
+            val pmCop = readAmount(data["pm"], "PM", excelRow, rowErrors, mustBePositive = true)
+            val costCop: Long? = if (data["costo"].isNullOrBlank()) {
+                // El plan pide rechazar montos vacíos; para Costo (dato interno, no define precios) se acepta con aviso.
+                warnings.add("Hoja 'Inventario', fila $excelRow, columna 'Costo': está vacío; se toma 0.")
+                0L
+            } else {
+                readAmount(data["costo"], "Costo", excelRow, rowErrors, mustBePositive = false)
+            }
+            if (ppCop != null && pmCop != null && pmCop > ppCop) {
+                rowErrors.add(
+                    cellError(
+                        excelRow, "PM",
+                        "no puede ser mayor que PP (PM ${Money.of(pmCop).formatFormattedCop()} > PP ${Money.of(ppCop).formatFormattedCop()})"
+                    )
+                )
+            }
+
+            if (rowErrors.isNotEmpty() || ppCop == null || pmCop == null || costCop == null) {
+                errors.addAll(rowErrors)
+                rejectedRows++
+                continue
+            }
+
+            // Estado: sin valor = Activo. Un valor desconocido (por ejemplo "Inactvo") se trata como Activo, pero se avisa.
+            val status = data["estado"]?.takeIf { it.isNotBlank() } ?: "Activo"
+            if (!isKnownStatus(status)) {
+                warnings.add(
+                    "Hoja 'Inventario', fila $excelRow, columna 'Estado': '${status.take(30)}' no es un estado reconocido; " +
+                        "el producto se trata como Activo. Escribe 'Inactivo' para retirarlo de la venta."
+                )
+            }
             val desc = data["descripción"] ?: data["descripcion"] ?: data["descripción ab"] ?: "Sin descripción"
             val qty = data["cantidad disponible"]?.toIntOrNull() ?: 1
 
@@ -124,11 +220,6 @@
                 data["pub_foto $i"]?.takeIf { it.startsWith("http") }?.let { images.add(it) }
             }
 
-            if (pmCop > ppCop) {
-                errors.add("Error en producto $keyStr: PM ($pmCop) no puede ser mayor a PP ($ppCop)")
-                continue
-            }
-
             commercialProducts.add(
                 CommercialProduct(
                     key = ProductKey(keyStr),
@@ -148,10 +239,46 @@
             )
         }
 
-        workbook.close()
-        return ExcelV2ImportResult(commercialProducts, channelOverrides, errors, warnings)
+        return ExcelV2ImportResult(commercialProducts, channelOverrides, errors, warnings, rejectedRows)
     }
 
+    /** Lee un monto de la hoja Inventario. Si es inválido agrega el error (con hoja, fila y columna) y devuelve null. */
+    private fun readAmount(
+        raw: String?,
+        column: String,
+        excelRow: Int,
+        rowErrors: MutableList<String>,
+        mustBePositive: Boolean
+    ): Long? {
+        return when (val result = AmountParser.parse(raw)) {
+            is AmountResult.Invalid -> {
+                rowErrors.add(cellError(excelRow, column, result.reason))
+                null
+            }
+            is AmountResult.Valid -> {
+                if (mustBePositive && result.cop <= 0L) {
+                    rowErrors.add(cellError(excelRow, column, "debe ser mayor que 0"))
+                    null
+                } else {
+                    result.cop
+                }
+            }
+        }
+    }
+
+    private fun cellError(excelRow: Int, column: String, reason: String): String =
+        "Hoja 'Inventario', fila $excelRow, columna '$column': $reason"
+
+    private fun isKnownStatus(status: String): Boolean =
+        status.trim().lowercase() in setOf("activo", "inactivo") || EstadoItem.desdeEtiqueta(status) != null
+
+    private fun fileFailure(message: String) =
+        ExcelV2ImportResult(emptyList(), emptyList(), listOf(message), emptyList(), 0, fileError = message)
+
+    /** Texto de la celda en la columna [colIdx]; "" si la columna no existe en el encabezado (antes lanzaba excepción con -1). */
+    private fun cellText(row: Row, colIdx: Int?): String =
+        if (colIdx == null) "" else getSanitizedCellValue(row.getCell(colIdx))
+
     private fun parseHeaders(headerRow: Row?): Map<String, Int> {
         if (headerRow == null) return emptyMap()
         val map = mutableMapOf<String, Int>()
@@ -190,4 +317,11 @@
             text
         }
     }
+
+    private companion object {
+        const val SHEET_INVENTARIO = "Inventario"
+
+        /** Columnas sin las cuales no se puede importar: (encabezado en minúsculas, nombre para mostrar). */
+        val REQUIRED_COLUMNS = listOf("key" to "Key", "pp" to "PP", "pm" to "PM")
+    }
 }
```

### A.3 `app/src/main/kotlin/com/automata/app/pipeline/InboundMessagePipeline.kt`

```diff
--- app/src/main/kotlin/com/automata/app/pipeline/InboundMessagePipeline.kt (antes)
+++ app/src/main/kotlin/com/automata/app/pipeline/InboundMessagePipeline.kt (después)
@@ -26,7 +26,15 @@
 
 /** Acceso mínimo al catálogo e inventario que necesita el pipeline (en la app lo implementa AppStore). */
 interface PipelineCatalog {
+    /** Claves de los productos que se pueden vender (los Inactivos no aparecen: Fase 2.2). */
     fun productKeys(): List<ProductKey>
+
+    /**
+     * Todas las claves conocidas, activas o no. Solo sirve para que el intérprete reconozca el código de un producto
+     * retirado y el bot responda "no disponible" en vez de confundirlo con otro producto.
+     */
+    fun knownKeys(): List<ProductKey> = productKeys()
+
     fun product(key: ProductKey): CommercialProduct?
     fun inventory(key: ProductKey): OperationalInventory?
     fun saveInventory(inventory: OperationalInventory)
@@ -78,7 +86,7 @@
         val trimmed = text.trim()
         // Fuera del candado: cuando el LLM sea una llamada HTTP no debe bloquear a los demás clientes.
         val intent: ParsedCustomerIntent? =
-            if (trimmed.isEmpty()) null else llm.parseIntent(trimmed, catalog.productKeys())
+            if (trimmed.isEmpty()) null else llm.parseIntent(trimmed, catalog.knownKeys())
 
         val outcome = lock.withLock { decide(channel, senderId, messageId, trimmed, intent, now) }
 
@@ -162,6 +170,12 @@
             return answer(conv, now, askWhichProduct())
         }
 
+        // Fase 2.2: un producto Inactivo no se negocia ni se reserva. Se responde "no disponible" y se ofrecen los demás.
+        if (!product.isActive) {
+            val withoutInactive = if (conv.activeProductKey == product.key) conv.copy(activeProductKey = null) else conv
+            return answer(withoutInactive, now, "${soldOutText(product)} ${askWhichProduct()}", product)
+        }
+
         // Si cambió de producto, se libera la reserva del anterior y se olvidan las contraofertas (eran de otro producto).
         conv = releaseIfOtherProduct(conv, product.key)
         if (conv.activeProductKey != null && conv.activeProductKey != product.key) {
@@ -266,6 +280,7 @@
     }
 
     private fun tryReserve(conv: Conversation, product: CommercialProduct, now: Instant): Reservation? {
+        if (!product.isActive) return null
         val inv = catalog.inventory(product.key) ?: return null
         if (!inv.isAvailable) return null
         catalog.saveInventory(inv.addReservation(1))
```

### A.4 `app/src/main/kotlin/com/automata/app/Application.kt`

```diff
--- app/src/main/kotlin/com/automata/app/Application.kt (antes)
+++ app/src/main/kotlin/com/automata/app/Application.kt (después)
@@ -153,12 +153,22 @@
 
 /** Expone AppStore al pipeline de mensajes con acceso sincronizado (el pipeline corre en otras corrutinas). */
 object AppStoreCatalog : PipelineCatalog {
+    /** Solo productos vendibles: los Inactivos no entran al catálogo del bot (Fase 2.2). */
     override fun productKeys(): List<ProductKey> =
+        synchronized(AppStore.products) { AppStore.products.values.filter { it.isActive }.map { it.key } }
+
+    /** Todas las claves, activas o no (el intérprete debe reconocer el código de un producto retirado). */
+    override fun knownKeys(): List<ProductKey> =
         synchronized(AppStore.products) { AppStore.products.keys.map { ProductKey(it) } }
 
+    /** Devuelve el producto aunque esté Inactivo: el pipeline decide qué responder. */
     override fun product(key: ProductKey): CommercialProduct? =
         synchronized(AppStore.products) { AppStore.products[key.value] }
 
+    /** Producto visible en la vitrina pública: null si no existe o está Inactivo (se responde 404 igual que si no existiera). */
+    fun publicProduct(key: String): CommercialProduct? =
+        synchronized(AppStore.products) { AppStore.products[key] }?.takeIf { it.isActive }
+
     override fun inventory(key: ProductKey): OperationalInventory? =
         synchronized(AppStore.inventory) { AppStore.inventory[key.value] }
 
@@ -396,7 +406,8 @@
             val inventorySnapshot = synchronized(AppStore.inventory) { AppStore.inventory.toMap() }
             val statuses = mutableListOf<ChannelListingStatus>()
 
-            productList.forEach { p ->
+            // Fase 2.2: los productos Inactivos no se publican en ningún canal, por eso no tienen estado de sincronización.
+            productList.filter { it.isActive }.forEach { p ->
                 val shopifyExt = AppStore.channelMappings[p.key.value]?.get("shopify")
                 val metaExt = AppStore.channelMappings[p.key.value]?.get("meta_catalogo")
                 statuses.add(ChannelListingStatus(p.key, "shopify", shopifyExt, shopifyExt != null, 12, "OK"))
@@ -424,10 +435,16 @@
                 return@post
             }
 
-            val parsedIntent = llmAdapter.parseIntent(message, AppStoreCatalog.productKeys())
-            val product = synchronized(AppStore.products) { AppStore.products.values.firstOrNull() }
+            val parsedIntent = llmAdapter.parseIntent(message, AppStoreCatalog.knownKeys())
+            // Fase 2.2: si el mensaje nombra un producto Inactivo, el simulador responde "no disponible" igual que el chat real.
+            val mentioned = parsedIntent.extractedProductKey?.let { AppStoreCatalog.product(it) }
+            if (mentioned != null && !mentioned.isActive) {
+                call.respondText("<p class='text-rose-600'>Este producto no está disponible (estado Inactivo).</p>", ContentType.Text.Html)
+                return@post
+            }
+            val product = synchronized(AppStore.products) { AppStore.products.values.firstOrNull { it.isActive } }
             if (product == null) {
-                call.respondText("<p class='text-rose-600'>No hay productos cargados. Sube primero el Excel.</p>", ContentType.Text.Html)
+                call.respondText("<p class='text-rose-600'>No hay productos activos cargados. Sube primero el Excel (los productos Inactivos no se negocian).</p>", ContentType.Text.Html)
                 return@post
             }
 
@@ -503,10 +520,13 @@
 
             val parseResult = excelParser.parseExcel(fileBytes!!.inputStream())
 
-            if (parseResult.errors.isNotEmpty()) {
-                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("errors" to parseResult.errors))
+            // Fase 2.1: archivo dañado o sin lo mínimo -> 400 con mensaje (nunca 500). No se importa nada.
+            val fileError = parseResult.fileError
+            if (fileError != null) {
+                call.respond(HttpStatusCode.BadRequest, mapOf("error" to fileError))
                 return@post
             }
+            // Las filas válidas SÍ se importan (más abajo); las inválidas se listan en la respuesta.
 
             parseResult.products.forEach { p ->
                 synchronized(AppStore.products) { AppStore.products[p.key.value] = p }
@@ -537,7 +557,21 @@
                 }
             }
 
-            call.respondRedirect("/")
+            // Fase 2.1: si todo estaba bien se vuelve al tablero; si hubo filas rechazadas (o avisos sobre filas) se muestra el detalle.
+            val rowWarnings = parseResult.warnings.filter { it.startsWith("Hoja 'Inventario'") }
+            if (parseResult.errors.isEmpty() && rowWarnings.isEmpty()) {
+                call.respondRedirect("/")
+                return@post
+            }
+            val imported = parseResult.products.size
+            call.respond(
+                if (imported == 0) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK,
+                mapOf(
+                    "summary" to listOf("Productos importados: $imported. Filas rechazadas: ${parseResult.rejectedRows}."),
+                    "errors" to parseResult.errors,
+                    "warnings" to parseResult.warnings
+                )
+            )
         }
 
         // 4. Exportación de Datos de Supabase a Excel (.xlsx)
@@ -671,7 +705,8 @@
         // 7. Vitrina Pública
         get("/public/p/{key}") {
             val key = call.parameters["key"] ?: ""
-            val product = synchronized(AppStore.products) { AppStore.products[key] }
+            // Fase 2.2: un producto Inactivo no aparece en la vitrina (404, igual que si no existiera).
+            val product = AppStoreCatalog.publicProduct(key)
             if (product == null) {
                 call.respond(HttpStatusCode.NotFound, mapOf("error" to "Producto no encontrado"))
                 return@get
```

### A.5 `app/src/main/kotlin/com/automata/app/dashboard/DashboardRenderer.kt`

```diff
--- app/src/main/kotlin/com/automata/app/dashboard/DashboardRenderer.kt (antes)
+++ app/src/main/kotlin/com/automata/app/dashboard/DashboardRenderer.kt (después)
@@ -41,7 +41,7 @@
             """
             <tr class="hover:bg-gray-50 border-b transition text-sm">
                 <td class="px-4 py-3 font-mono font-semibold text-indigo-700">${escapeHtml(p.key.value)}</td>
-                <td class="px-4 py-3 font-medium text-gray-900">${escapeHtml(p.itemName)}</td>
+                <td class="px-4 py-3 font-medium text-gray-900">${escapeHtml(p.itemName)}${if (!p.isActive) " <span class='ml-2 px-2 py-0.5 rounded text-[10px] font-bold bg-gray-200 text-gray-700'>INACTIVO</span>" else ""}</td>
                 <td class="px-4 py-3 text-gray-600">${escapeHtml(p.brand)}</td>
                 <td class="px-4 py-3 text-right font-semibold text-gray-900">${p.publishedPrice.formatFormattedCop()}</td>
                 <td class="px-4 py-3 text-center">
@@ -55,10 +55,10 @@
                     </span>
                 </td>
                 <td class="px-4 py-3 text-center text-xs">
-                    ${if (shopifyStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-green-100 text-green-800 font-semibold'>ACTIVO</span>" else "<span class='px-2 py-0.5 rounded bg-yellow-100 text-yellow-800 font-semibold'>PENDIENTE</span>"}
+                    ${if (!p.isActive) "<span class='px-2 py-0.5 rounded bg-gray-100 text-gray-500 font-semibold'>NO SE PUBLICA</span>" else if (shopifyStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-green-100 text-green-800 font-semibold'>ACTIVO</span>" else "<span class='px-2 py-0.5 rounded bg-yellow-100 text-yellow-800 font-semibold'>PENDIENTE</span>"}
                 </td>
                 <td class="px-4 py-3 text-center text-xs">
-                    ${if (metaStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-purple-100 text-purple-800 font-semibold'>CATALOG_ONLY</span>" else "<span class='px-2 py-0.5 rounded bg-gray-100 text-gray-700 font-semibold'>SIN SYNC</span>"}
+                    ${if (!p.isActive) "<span class='px-2 py-0.5 rounded bg-gray-100 text-gray-500 font-semibold'>NO SE PUBLICA</span>" else if (metaStatus?.isPublished == true) "<span class='px-2 py-0.5 rounded bg-purple-100 text-purple-800 font-semibold'>CATALOG_ONLY</span>" else "<span class='px-2 py-0.5 rounded bg-gray-100 text-gray-700 font-semibold'>SIN SYNC</span>"}
                 </td>
             </tr>
             """.trimIndent()
```

### A.6 `MANUAL_DE_USO.md`

```diff
--- MANUAL_DE_USO.md (antes)
+++ MANUAL_DE_USO.md (después)
@@ -27,10 +27,17 @@
 - **`PP`**: Precio Publicado de venta al público (ej. `$120.000`).
 - **`PM`**: Precio Mínimo absoluto permitido para negociaciones (ej. `$95.000`). *Dato sensible interno.*
 - **`Costo`**: Costo de adquisición o fabricación (ej. `$70.000`). *Dato sensible interno.*
-- **`Estado`**: `Activo` o `Inactivo`.
+- **`Estado`**: `Activo` o `Inactivo`. Un producto **Inactivo** no se vende: el bot responde "no disponible", no se negocia ni se reserva, no aparece en la vitrina pública (`/public/p/{key}`) ni se publica en los canales. Cualquier otro valor se trata como activo (si escribes uno que no se reconoce, la importación lo avisa).
 - **`Descripción AB`**: Descripción detallada del producto para publicaciones y chats.
 - **`Cantidad disponible`**: Stock Maestro físico base.
 
+**Reglas de validación al importar (Fase 2.1).** Cada fila de `Inventario` se revisa antes de entrar:
+- `PP` y `PM` son obligatorios y mayores que 0; `PM` no puede superar a `PP`; `Costo` no puede ser negativo.
+- Los montos se aceptan como `120000`, `120.000`, `120,000`, `120 000` o `$120000`. Textos como `Consultar`, celdas vacías, negativos o decimales **no** se convierten en 0: la fila se rechaza.
+- Una `Key` repetida se rechaza (se conserva la primera fila).
+- Las filas válidas se importan igual; las rechazadas se listan con **hoja, fila y columna** (por ejemplo `Hoja 'Inventario', fila 5, columna 'PP': ...`).
+- Si el archivo está dañado, no tiene la hoja `Inventario` o le faltan las columnas `Key`, `PP` o `PM`, no se importa nada y se responde con un mensaje claro.
+
 ### Hoja 2: `Publicacion` (Datos exigidos por Canales)
 - **`Key`**: Debe coincidir exactamente con la clave de la hoja `Inventario`.
 - **`Marca`**: Marca oficial (ej. `AudioTech`).
```

---

*Archivos nuevos completos: `AmountParser.kt` y las pruebas nuevas están íntegros en el proyecto (rutas en §2).*
