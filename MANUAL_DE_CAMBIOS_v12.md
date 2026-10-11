# Manual de cambios v12 — Pago idempotente, resultado explícito sin BD y limpieza de detalles de código

**Fecha:** 11 de octubre de 2026
**Proyecto:** Tienda Virtual Multicanal (`Tienda-virtual-main`)
**Alcance pedido:** puntos 1, 2 y 5 de la revisión de código.
**Estado:** revisión estática solamente (balance de llaves/paréntesis en cada archivo y diff contra el original).
**No se compiló, no se corrieron las pruebas y el candado `FOR UPDATE` no se probó contra PostgreSQL** (en el entorno de trabajo no hay compilador de Kotlin ni red).
Antes de integrar: `./gradlew check` (compila y corre todas las pruebas).

Los archivos entregados están en la carpeta `v12/` con la **misma ruta que tienen en tu proyecto**; cada uno es el archivo completo. Reemplaza el tuyo por el entregado,
o aplica a mano los fragmentos "Antes / Después" de este manual.

## Resumen

| # | Qué | Archivos |
|---|-----|----------|
| 1 | Confirmar un pago dos veces ya no es un error; el reintento es idempotente y no descuenta stock dos veces | `ConfirmPaymentUseCase.kt`, `ExposedInventarioRepository.kt`, `PagosRoutes.kt` |
| 2 | Sin base de datos el resultado ya no aparenta un guardado que no ocurrió | `ConfirmPaymentUseCase.kt`, `PagosRoutes.kt` |
| 5 | `Money`, enum con valores obsoletos, `!!` y el nombre `formatFormattedCop` | `Money.kt`, `NegotiationEngine.kt`, `ProductKey.kt` y 7 archivos más (ver §3) |
| — | Pruebas | `ConfirmPaymentUseCaseTest.kt` (ampliado), `MoneyTest.kt` (nuevo) |

---

## 1. Pago idempotente (punto 1)

### Qué pasaba
- `ConfirmPaymentUseCase` decía ser idempotente, pero `isIdempotentRepeat` no se ponía nunca en `true`. Al confirmar dos veces,
  `registrarVenta` lanza `IllegalStateException("... ya está vendido")` y el caso de uso lo devolvía como **"Error en BD"** (la ruta respondía 500).
- `marcarVendido` leía el producto **sin bloquear la fila**. Con dos confirmaciones simultáneas, las dos pasaban el `check`; la segunda `UPDATE` esperaba a la primera
  y luego **sobrescribía** `vendido_en`, `fecha_venta` y `canal_venta` del inventario. El trigger `trg_d_inventario_vendido_manual` no se dispara en ese segundo `UPDATE`
  (el estado no cambia), así que `ventas` conservaba el primer precio y `inventario` quedaba con el segundo: datos inconsistentes.
- Aunque el reintento hubiera dado éxito, `PagosRoutes` habría llamado otra vez a `recordSale(1, ...)` y descontado el stock en memoria dos veces.

### Cambio A — `commerce/.../service/ConfirmPaymentUseCase.kt` (reescrito completo)
**Antes** (bloque con repositorio):
```kotlin
try {
    val updatedItem = repo.marcarVendido(...) ?: return ConfirmPaymentResult(success = false, errorMessage = "No se encontró ...")
    return ConfirmPaymentResult(success = true, item = updatedItem)
} catch (e: Exception) {
    return ConfirmPaymentResult(success = false, errorMessage = "Error en BD al confirmar pago: ${e.message}")
}
```
**Después:** se agrega `catch (e: IllegalStateException)` **antes** del `catch (e: Exception)` y la función privada `resolverReintento`:
```kotlin
} catch (e: IllegalStateException) {
    resolverReintento(request, e)
} catch (e: Exception) { ... igual que antes ... }
```
`resolverReintento` vuelve a leer el producto y decide:

| Situación | Resultado |
|-----------|-----------|
| Ya vendido con **mismo precio y mismo canal** que este pago | `success = true`, `isIdempotentRepeat = true`, `item = <venta existente>`; no se toca nada |
| Ya vendido con **otro precio o canal** | `success = false`, mensaje "ya está vendido por X (canal ...); este pago es por Y (canal ...). Revísalo antes de confirmar." (no se pisa la venta) |
| **No** está vendido (otra regla falló, p. ej. el reparto de socios no suma 100) | `success = false`, `"Error en BD al confirmar pago: <motivo original>"` (igual que antes) |

El canal se compara normalizado (`trim` + minúsculas), igual que lo guarda `InventarioItem.registrarVenta`.

### Cambio B — `infra/.../db/ExposedInventarioRepository.kt`, función `marcarVendido`
**Antes:**
```kotlin
val actual = findByKey(key) ?: return@tx null
val vendido = actual.registrarVenta(vendidoEn, fecha, canal)
```
**Después:** una línea de candado antes de leer:
```kotlin
if (InventarioTable.select { InventarioTable.key eq key.value }.forUpdate().firstOrNull() == null) return@tx null
val actual = findByKey(key) ?: return@tx null
val vendido = actual.registrarVenta(vendidoEn, fecha, canal)
```
Con esto, la segunda confirmación simultánea **espera** a que la primera haga commit, lee "Vendido", `registrarVenta` lanza `IllegalStateException` y el cambio A la resuelve como reintento.
> Si `forUpdate()` no compilara con tu versión de Exposed (0.47.0 en `libs.versions.toml`), la alternativa equivalente es
> `exec("SELECT 1 FROM tienda.inventario WHERE key = ? FOR UPDATE", listOf(TextColumnType() to key.value)) { }`.

### Cambio C — `app/.../routes/PagosRoutes.kt`
**Antes:**
```kotlin
if (currentInv != null) {
    AppStore.inventory[foundProduct.key.value] = currentInv.recordSale(1, wasReserved = true)
}
```
**Después:**
```kotlin
// En un reintento la venta ya se descontó antes: recordSale no es idempotente y duplicaría el descuento.
if (currentInv != null && !paymentResult.isIdempotentRepeat) {
    AppStore.inventory[foundProduct.key.value] = currentInv.recordSale(1, wasReserved = true)
}
```
El resto del flujo en un reintento **sí** se ejecuta a propósito: `inboundPipeline.confirmPayment` ya es idempotente (rama `CONFIRMED`), la saga anti-sobreventa solo fija el stock restante
(repetirla es inofensivo y recupera el caso "se cayó entre la BD y la saga") y actualizar el estado del trato es una asignación.

---

## 2. Éxito falso sin base de datos (punto 2)

### Qué pasaba
Con `repo == null` el caso de uso devolvía `success = true` sin haber guardado nada, sin que nadie pudiera distinguirlo de un guardado real.

### Cambio D — `ConfirmPaymentUseCase.kt` y `PagosRoutes.kt`
- `ConfirmPaymentResult` gana el campo `persisted: Boolean = true` (valor por defecto: los llamadores existentes compilan sin cambios).
- Sin repositorio: `ConfirmPaymentResult(success = true, persisted = false)`. **Sigue permitiendo el flujo en modo local** (así puedes probar sin base), pero ahora lo dice.
- `PagosRoutes.kt`: `logger.warn(...)` cuando `!paymentResult.persisted`, y `logger.info(...)` cuando es un reintento idempotente.
- En producción no cambia nada: la app ya se niega a arrancar sin `DATABASE_URL`.

---

## 3. Detalles de código (punto 5)

> **Corrección a mi revisión:** dije "unos 22 `!!`/TODO/FIXME". Al contarlos bien eran **16 `!!` reales** (el resto eran falsos positivos: la palabra `TODOS`,
> `!!window` dentro de un string de JavaScript y comentarios). **No había ningún TODO/FIXME.** Ahora quedan 0 `!!` en código de producción.

### 3.1 `shared-kernel/.../Money.kt`
- `minus`: ahora valida y lanza `IllegalArgumentException` con mensaje claro ("No se puede restar $X de $Y: Money no admite resultados negativos (usa signedDiff)").
  Antes fallaba dentro del `init` con "El monto monetario no puede ser negativo: -5", sin decir qué resta lo causó. Mismo tipo de excepción.
- **Nuevo** `signedDiff(other): SignedCop`: diferencia con signo, para ganancias/pérdidas.
- **Nuevo** `formatCop()` y `formatFormattedCop()` queda como `@Deprecated(ReplaceWith("formatCop()"))` que delega.
  **No** migré los 38 usos en 13 archivos (habría tocado `DashboardRenderer.kt` de 76 KB y otros): compilan igual y solo generan *warnings* de deprecación.
  Cuando quieras, en tu IDE: *Alt+Enter → Replace with `formatCop()`* sobre cada uso, o buscar y reemplazar `formatFormattedCop(` por `formatCop(`.

### 3.2 `commerce/.../NegotiationEngine.kt`
Se eliminaron `L3_HIGH_DISCOUNT` y `L4_FLOOR_LIMIT` (`@Deprecated`). Verifiqué que **no se usan en ningún `.kt`, `.sql`, `.js` ni `when`** del proyecto (solo aparecían en documentos históricos).
Efecto lateral a tener presente: cambia el `ordinal` de `REJECTED_BELOW_PM` y `ESCALATED_HUMAN`; el proyecto no usa `ordinal` y el nivel no se guarda por nombre en la base.

### 3.3 Reemplazo de `!!` (comportamiento idéntico)

| Archivo | Antes | Después | Por qué es equivalente |
|---------|-------|---------|------------------------|
| `ProductKey.kt` (`parse`, `parseOrNull`) | `ProductKey(raw!!.trim())` | `ProductKey(raw?.trim().orEmpty())` | Solo se llega ahí si `validar(raw)` ya garantizó texto no vacío |
| `ExposedInventarioRepository.kt` (`upsert`) | `findByKey(item.key)!!` | `findByKey(item.key) ?: error("No se pudo releer el artículo ... después de guardarlo.")` | Mismo fallo imposible, pero con mensaje en vez de `NullPointerException` |
| `ExposedLiquidacionRepository.kt` (`mensajeSql`) | `while (causa.cause != null && causa.cause !== causa) causa = causa.cause!!` | `while (true) { val siguiente = causa.cause ?: break; if (siguiente === causa) break; causa = siguiente }` | Mismas condiciones de parada |
| `ExcelV2Parser.kt` | `if (keyVal.isBlank() \|\| !rawProducts.containsKey(keyVal)) continue` + `rawProducts[keyVal]!!` | `if (keyVal.isBlank()) continue` + `rawProducts[keyVal] ?: continue` | Un solo acceso al mapa en vez de dos |
| `ExcelGenerator.kt` | `item.vendidoEn!!`, `item.precioPublicado!!` | `val vendidoEn = item.vendidoEn` / `val precioPublicado = item.precioPublicado` y se usan las copias locales | Kotlin no hace *smart cast* de propiedades de otro módulo; la copia local sí |
| `CatalogoRenderer.kt` (3 usos) | `if (edita) ... item!!.key.value` | `if (item != null) ... item.key.value` | `edita` era exactamente `item != null`; `item` es parámetro y sí permite *smart cast* |
| `ExcelRoutes.kt` (4 usos, 3 rutas) | `if (fileBytes == null) { responder 400; return@post }` + `fileBytes!!` | `val xlsxBytes = fileBytes ?: run { responder 400; return@post }` y se usa `xlsxBytes` | Misma respuesta 400; `fileBytes` es `var` modificada en una lambda, por eso no había *smart cast* |
| `ArticulosRoutes.kt` (2 usos) | `params["precioCompra"]!!.trim()...` y `params[name]!!.trim()` | `precioCompraRaw.trim()...` (ya existía como `val` local) y `params[name]?.trim()` | La condición ya garantizaba que no era nulo |
| `CatalogoUiRoutes.kt` | `ProductKey(params["key"]!!.trim())` | `ProductKey(params["key"].orEmpty().trim())` | Va justo después de `ProductKey.validar(...)` |

---

## 4. Pruebas

- `ConfirmPaymentUseCaseTest.kt` (las 2 pruebas originales se conservan; se agregan 8):
  reintento idempotente; reintento con el canal escrito distinto (`"  WhatsApp "`); mismo producto con otro precio → rechazo y la venta no se pisa; otro canal → rechazo;
  producto inexistente; falla por reparto de socios no se confunde con reintento; sin repositorio → `persisted = false`; **dos hilos simultáneos → una venta + un reintento**.
- `MoneyTest.kt` (nuevo): `minus` correcto y con error claro, `signedDiff`, `formatCop` y el alias antiguo.
- **Limitación:** la prueba concurrente usa el repositorio en memoria (que sincroniza con `synchronized`). El candado `FOR UPDATE` real solo se comprueba con PostgreSQL;
  una prueba de integración con Testcontainers sería el siguiente paso.

---

## 5. Lo que NO se hizo y límites conocidos

1. **Mover los `InMemory*` fuera del paquete `ports`:** no lo apliqué. Toca ~20 archivos (incluidos casi todos los tests y `Application.kt`) y, sin poder compilar, el riesgo de un
   import roto supera el beneficio, que es solo de orden. Si lo quieres, lo hago como entrega aparte.
2. **El criterio de "mismo pago" es precio + canal.** El camino `marcarVendido` no guarda el `dealId`. Dos tratos distintos del **mismo producto, mismo precio y mismo canal**
   se verían como reintento. La base ya tiene idempotencia por `deal_id` en `tienda.vender_unidades`; el siguiente paso sería que el pago use esa función pasando el `dealId`.
3. **Un conflicto (otro precio/canal) sigue respondiendo HTTP 500** desde `PagosRoutes` (así responden todos los fallos hoy). Lo correcto sería 409; no lo cambié para no salir del alcance.
4. `anularVenta`, `archivar` y `restaurar` siguen leyendo sin candado; no estaban en el pedido.
5. Los otros puntos de la revisión (2 fuentes de verdad `AppStore`/BD, partir `DashboardRenderer.kt`, orden del repositorio) no se tocaron.
