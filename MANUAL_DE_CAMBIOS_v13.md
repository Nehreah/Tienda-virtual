# Manual de cambios v13 — Tabla de reparaciones del dashboard (completo)

**Fecha:** 11 de octubre de 2026
**Proyecto:** Tienda Virtual Multicanal (`Tienda-virtual-main`)
**Alcance pedido:** "en el dashboard no sirve la tabla de reparaciones".
**Estado:** revisión estática solamente (balance de llaves/paréntesis, `node --check` sobre el JS y diff contra el original).
**No se compiló, no se corrieron las pruebas y no se probó contra PostgreSQL ni en el navegador** (en el entorno de trabajo no hay compilador de Kotlin, ni red, ni base de datos).
Antes de integrar: `./gradlew check`.

Los archivos entregados están en la carpeta `v13/` con la **misma ruta que tienen en tu proyecto**; cada uno es el archivo completo.

## Resumen

| # | Qué | Archivo |
|---|-----|---------|
| 1 | Si el panel responde un error en HTML (503 sin BD, 500 con el motivo), ahora se **ve el mensaje** en vez de "No se pudo cargar el módulo (503)" | `app/src/main/resources/static/dashboard.js` |
| 2 | Guardar una reparación ya no te saca a Inicio ni muestra JSON crudo: el panel se actualiza en el sitio con el mensaje de éxito o de error | `ReparacionesRoutes.kt`, `ReparacionesRenderer.kt` |
| 3 | `dbRepository` se crea según `databaseConnected` (causa probable de la tabla que no cargaba) y ya no traga el error en silencio | `Application.kt` |
| 4 | Una sola consulta para todas las reparaciones (antes una por artículo) | `ReparacionRepository.kt`, `ExposedReparacionRepository.kt`, `ReparacionesRoutes.kt` |
| 5 | La clave va codificada en la URL del formulario | `ReparacionesRenderer.kt` |
| 6 | Si guardar falla, la fila conserva lo que escribiste | `ReparacionesRenderer.kt`, `ReparacionesRoutes.kt` |
| — | Pruebas del renderer (3) | `ReparacionesRendererTest.kt` (nuevo) |

## Qué encontré (y qué no pude confirmar)

No pude reproducir tu fallo exacto (no hay base ni navegador aquí). Lo que sí encontré leyendo el flujo completo (`dashboard.js` → `GET /ui/reparaciones` → renderer → `POST /api/reparaciones/{key}`):

1. **Los errores del panel nunca se veían.** `dashboard.js` descartaba cualquier respuesta no-200 y mostraba solo "No se pudo cargar el módulo (503)". La ruta de reparaciones responde **503** cuando falta alguno de sus dos repositorios y **500** si la lectura falla; en ambos casos construía un HTML con el motivo… que el navegador tiraba. Resultado: la tabla "no sirve" y no dice por qué. (Catálogo tiene el mismo patrón 503; también se beneficia.)
2. **Guardar te sacaba de la pantalla.** El formulario hacía un POST nativo y la ruta respondía `redirect("/")`: volvías a **Inicio** (se perdía `#/reparaciones`), parecía que no había pasado nada. Si algo fallaba (artículo vendido, validación), el navegador mostraba una página con JSON crudo.
3. **Causa probable de que la tabla no cargue en local (NO modificada, ver §4):** en `Application.kt`, `dbRepository` se crea con `AppConfig.databaseConfigured`, pero `reparacionRepo` con `AppStore.databaseConnected`. Si hay `DATABASE_URL` pero la conexión o las migraciones fallaron (en modo local la app arranca igual, "solo memoria"), `dbRepository` existe y `reparacionRepo` es `null` → la ruta responde 503. Con el cambio 1 ahora verás el aviso "requiere conexión a PostgreSQL… revisa DATABASE_URL y los logs de arranque".

Si tu base **sí** está conectada y la tabla igual falla, con el cambio 1 el mensaje real aparecerá en el panel y los logs; mándamelo y lo corrijo.

---

## 1. `app/src/main/resources/static/dashboard.js` — función `loadPanel`

**Antes:**
```js
.then(function (response) {
    if (!response.ok) { throw new Error('No se pudo cargar el módulo (' + response.status + ').'); }
    return response.text();
})
.then(function (html) {
    target.innerHTML = html;
    target.setAttribute('data-loaded', 'true');
    ...
```
**Después:**
```js
.then(function (response) {
    var esHtml = (response.headers.get('Content-Type') || '').toLowerCase().indexOf('text/html') === 0;
    if (!response.ok && !esHtml) { throw new Error('No se pudo cargar el módulo (' + response.status + ').'); }
    return response.text().then(function (html) { return { html: html, ok: response.ok }; });
})
.then(function (res) {
    target.innerHTML = res.html;
    if (res.ok) { target.setAttribute('data-loaded', 'true'); }
    ...
```
**Por qué:** una respuesta de error **en HTML** trae el mensaje pensado para la persona; se muestra. Los errores en texto plano (401, 403, 429) y los de red siguen con el aviso genérico. Si hubo error no se marca `data-loaded`, así que al volver a entrar a la subpágina se reintenta (antes, un panel ya cargado nunca se refrescaba hasta recargar la página; eso no cambia para los que cargaron bien).

## 2. `ReparacionesRenderer.kt`

**Antes:**
```kotlin
fun render(items: List<InventarioItem>, reparaciones: Map<String, Reparacion>, error: String? = null): String = buildString {
...
<form class="repair-form" method="post" action="/api/reparaciones/${...key...}">
```
**Después:**
```kotlin
fun render(items: List<InventarioItem>, reparaciones: Map<String, Reparacion>, error: String? = null, mensaje: String? = null): String = buildString {
...
if (!mensaje.isNullOrBlank()) append("<p class=\"ui-ok\" role=\"status\">${DashboardRenderer.escapeHtml(mensaje)}</p>")
...
<form class="repair-form" hx-post="/api/reparaciones/${...key...}" hx-target="#reparaciones-panel" autocomplete="off">
```
**Por qué:** mismo patrón que el módulo de Socios (`hx-post` + `hx-target`): el servidor devuelve el panel completo y htmx lo reemplaza. `mensaje` es un parámetro nuevo con valor por defecto, así que los llamadores existentes compilan igual. `ui-ok` es una clase que ya existe en el CSS.

## 3. `ReparacionesRoutes.kt` (archivo completo)

- Nueva función local `panel(...)` (igual que en `SociosRoutes`): lee inventario y reparaciones, pinta el panel y responde con el estado HTTP indicado. Si la lectura falla, registra el error en el log y devuelve el panel con el motivo (500).
- `GET /ui/reparaciones` usa `panel(...)`. Sin BD sigue respondiendo 503, pero con `respondFragmento` y un texto más útil.
- `POST /api/reparaciones/{key}`:

| Situación | Antes | Después |
|-----------|-------|---------|
| Sin BD | 503 + JSON | 503 + fragmento HTML |
| Precio/clave inválidos | 400 + JSON | 400 + panel con el error |
| Artículo inexistente | 404 + JSON | 404 + panel con el error |
| Detalles > 1000 caracteres | 400 + JSON | 400 + panel con el error |
| Falla al guardar (p. ej. el trigger "ya está vendido") | 400/409/500 + JSON genérico ("No se pudo guardar la reparación") | 409 + panel con el motivo real (`mensajeLegible()`, la primera línea del `RAISE EXCEPTION`), y `logger.warn` |
| Éxito | `redirect("/")` | 200 + panel actualizado con "Reparación de X guardada…" |

Los 4xx en HTML sí los intercambia `dashboard.js` (`htmx:beforeSwap`), por eso los errores de validación/regla se ven dentro del panel.
Se unificaron los tres `catch` (`IllegalArgumentException`, `IllegalStateException`, `Exception`) en uno: las reglas de la base llegan como excepción SQL, no como esas dos, y caían todas en el mensaje genérico.

## 4. Pruebas

`ReparacionesRendererTest.kt` (nuevo, 2 pruebas): el formulario usa `hx-post`/`hx-target`, no hay `method="post"` ni handlers inline, y nombre/detalles/mensajes salen escapados; el mensaje de éxito y el de error se pintan con su clase y escapados.

## 5. Cambios 3 a 6 (los pendientes de la primera entrega)

**3. `Application.kt` (~línea 327).** Antes: `if (AppConfig.databaseConfigured) { try { ExposedInventarioRepository() } catch (e: Exception) { null } }`.
Después: `if (AppStore.databaseConnected)` y el `catch` registra `logger.error(...)` antes de devolver `null`.
Por qué: ahora todos los repositorios dependen de lo mismo (`databaseConnected`). Antes, con `DATABASE_URL` pero conexión fallida, `dbRepository` existía y `reparacionRepo` era `null` (503).
Efecto a tener presente: en ese caso (BD configurada pero caída, solo en local) los módulos que usan `dbRepository` pasan a "sin BD" de forma coherente en vez de fallar con errores SQL.

**4. N+1.** `ReparacionRepository` gana `findAll(): List<Reparacion>` (única implementación: `ExposedReparacionRepository`, con `selectAll()`); `ReparacionesRoutes.panel` hace `reparaciones.findAll().associateBy { it.key.value }` en vez de un `find` por artículo. Mismo resultado: la fila solo existe si el artículo requiere reparación.

**5. Clave en la URL.** `ReparacionesRenderer`: `val urlKey = URLEncoder.encode(key, UTF_8).replace("+", "%20")` y el `hx-post` usa `urlKey`. Ktor decodifica el parámetro de ruta, así que el servidor recibe la clave original. Para claves normales (`PROD-001`) no cambia nada.

**6. Borrador al fallar.** `render(...)` gana `borrador: Reparacion? = null` (valor por defecto, compatible); la fila de esa clave muestra lo que escribiste. `panel(...)` lo recibe y la ruta lo pasa cuando `guardar` falla.

## 6. Pruebas
`ReparacionesRendererTest.kt` (nuevo, 3 pruebas): formulario htmx y texto escapado; mensajes escapados; clave `A B#1` codificada como `A%20B%231` y borrador conservado.

## 7. Límites conocidos
1. No compilado ni probado (ver arriba). Si `./gradlew check` marca algo, mándame el error.
2. Con el borrador, un error de validación previo al guardado (precio inválido, detalles > 1000) sigue repintando desde la base.
3. El diagrama anterior era v9; v13 cubre solo las clases tocadas.
