# Manual de cambios v11 — htmx oficial, CRUD del catálogo, socios con aportes y liquidaciones, política de claves

**Fecha:** 10 de octubre de 2026
**Proyecto:** Tienda Virtual Multicanal (`Tienda-virtual-main`)
**Estado:** revisión estática solamente (balance de llaves/paréntesis, interpolaciones y sintaxis de `dashboard.js` con `node --check`).
**No se compiló, no se corrieron las pruebas y la migración V005 no se ejecutó contra PostgreSQL.**
**Fuera de alcance por pedido:** compilación y sincronización con tiendas (Shopify/Meta).

## 1. Qué se hizo

### 1.1 htmx oficial (reemplaza a `htmx-lite.js`)
- `DashboardRenderer.kt` carga `/static/htmx.min.js` (htmx **1.9.10**, archivo que ya estaba en `static/`) y declara
  `<meta name="htmx-config">` con `allowEval:false`, `allowScriptTags:false`, `selfRequestsOnly:true`, `historyEnabled:false`,
  `includeIndicatorStyles:false`. Compatible con la CSP `script-src 'self'` (sin `unsafe-eval`).
- `htmx-lite.js` **eliminado**.
- `dashboard.js`:
  - Los paneles cargados con `fetch()` ahora llaman a `htmx.process()`; sin eso htmx oficial no ve los `hx-*` de HTML insertado con `innerHTML`.
  - `htmx:beforeSwap` deja pasar respuestas 400/409/422 que sean `text/html` (htmx no intercambia 4xx por defecto) para que el mensaje de error se vea en el panel.
  - Avisos (toast) para 401, 403, 429, error de red y tiempo agotado.
  - Cierre de modales (`data-modal-close`, tecla Escape, clic fuera) y vaciado de ranuras (`data-clear`).
  - Carga de varios paneles por subpágina (Inventario carga el CRUD y la matriz de inversión).
- La protección CSRF del servidor no cambia (credenciales + Origin propio); el encabezado `X-CSRF-Token` se sigue enviando por `htmx:configRequest`.
- **No verificado:** la huella del archivo contra la fuente oficial (sin red). SHA-256 del archivo incluido:
  `b3bdcf5c741897a53648b1207fff0469a0d61901429ba1f6e88f98ebd84e669e`. Compárala con `htmx.org@1.9.10` → `dist/htmx.min.js`.
- `PENDIENTE_CAMBIAR_HTMX_OFICIAL.md` se reescribió como lista de pruebas en navegador.

### 1.2 CRUD visual del catálogo (subpágina Inventario)
- Nuevo panel `#catalogo-panel`: lista con filtro Activos / Archivados / Todos, alta, edición, archivado con motivo obligatorio,
  restauración y eliminación (con confirmación). Un artículo Vendido queda bloqueado.
- Archivos nuevos: `CatalogoRenderer.kt`, `CatalogoUiRoutes.kt` (rutas `/ui/catalogo...`, devuelven fragmentos HTML), `RouteSupport.kt`.
- Usa los servicios existentes (`ArticuloLifecycleService`); el costo total y el precio mínimo (costo total × 1,5) los sigue calculando PostgreSQL.
  Al editar se descarta el precio mínimo anterior para que no bloquee la validación PM ≤ PP con un valor viejo; la base recalcula.
- Estados elegibles a mano: Comprado, Publicado, Negociación, Otro (Vendido se registra desde Ventas; Inactivo con Archivar).
- Los mensajes de error de PostgreSQL (triggers) se muestran sin el prefijo `ERROR:` ni la traza.
- `ArticulosRoutes.kt` (API JSON) se refactorizó para compartir el espejo en memoria (`AppStore`) y aplica la política de claves.

### 1.3 Socios, aportes y liquidaciones
- `SociosRenderer.kt` **reescrito**: la versión anterior usaba Tailwind (no cargado) y `onclick` (bloqueado por la CSP), así que el botón
  "Agregar socio" y los modales no funcionaban. Ahora usa CSS local, `<details>` y modales htmx.
- Tabla de socios con aporte en stock, aporte vendido, ganancia, liquidado y saldo por liquidar, más una tabla de liquidaciones.
- **Migración nueva `V005__liquidaciones_y_formato_de_claves.sql`:** tabla `tienda.liquidacion` (inmutable: no se borra ni se edita, solo se anula con motivo),
  vista `tienda.v_socio_cuenta`, funciones `registrar_liquidacion` (valida monto > 0, no pasarse del saldo, bloquea al socio para pagos simultáneos)
  y `anular_liquidacion`, y `retirar_socio` ahora también archiva si hay liquidaciones.
- Nuevos: `Liquidacion.kt` (modelo), `LiquidacionRepository.kt` (puerto), `ExposedLiquidacionRepository.kt`. Rutas nuevas en `SociosRoutes.kt`:
  `GET /api/socios/{id}/liquidar`, `POST /api/socios/{id}/liquidaciones`, `GET|POST /api/liquidaciones/{id}/anular`.
- Sin base de datos solo se muestra la lista de socios y las rutas de liquidación responden 503.

> **SUPUESTO DE NEGOCIO — confírmalo.** Aporte = parte del costo según el % de inversión del socio en cada artículo. Lo vendido
> (capital recuperado + ganancia, `venta_reparto.ingresos`) pertenece al socio; **saldo por liquidar = ingresos − liquidaciones vigentes**.
> Si se anula una venta ya liquidada, el saldo queda negativo. Si prefieres liquidar solo la ganancia, o llevar aportes en efectivo
> como movimientos propios, hay que cambiar la vista `v_socio_cuenta` y los textos; el resto de la estructura sirve igual.

### 1.4 Política de claves (`ProductKey`)
- `ProductKey.validar/parse/parseOrNull`: 1–64 caracteres, letras/números/`.`/`_`/`-`, sin espacios, empieza con letra o número
  (así nunca contiene `/`, `?`, `#`, `%`, comillas ni caracteres de control).
- Se aplica **al crear**: formulario del catálogo, `POST /api/articulos` (ahora 400 en vez de error 500 si la clave es inválida) e importación de Excel
  (la fila se rechaza con error en la columna `Key`).
- El constructor `ProductKey(...)` sigue siendo permisivo a propósito: se usa para **leer** claves ya guardadas y SKU externos (Shopify, reservas),
  y no debe romper esas lecturas.
- La base refuerza la regla con `ck_inventario_key_formato` (`NOT VALID`: aplica a filas nuevas o modificadas, no revisa las existentes).
  Depende de la configuración regional de PostgreSQL para letras acentuadas (`[[:alnum:]]`).
- `InversionRoutes.kt`: `repartir-igual` con clave vacía devuelve 400 en vez de lanzar.
- `InventarioRenderer.kt`: se añadió el `import java.math.BigDecimal` que faltaba (habría fallado al compilar).

## 2. Pruebas añadidas (sin ejecutar)
`ProductKeyTest`, `CatalogoRendererTest`, `SociosRendererTest`, `CatalogoFormularioTest` (lectura/validación del formulario, `parseCop`, `mensajeLegible`),
un caso nuevo en `ExcelV2ParserTest`, y `DashboardRendererTest` actualizado a `htmx.min.js`.
No hay pruebas contra PostgreSQL para V005 (vista y funciones); es lo primero que conviene probar al tener una base.

## 3. Qué sigue pendiente
- **Compilar** (`./gradlew check test`) y corregir lo que aparezca; probar V005 en PostgreSQL.
- Probar todo en navegador con `APP_ENV=production` (ver `PENDIENTE_CAMBIAR_HTMX_OFICIAL.md`).
- **Sincronización con tiendas** (sin tocar, por pedido): nada inserta en `publicaciones`, nadie llama a `publishProduct`, `fetchRemoteProducts()` devuelve
  lista vacía y Meta es un esqueleto.
- Handlers en línea que la CSP bloquea: `VentasRenderer.kt` (4 `onclick`/similares) y el `<script>` de `ChatRenderer.kt`.
- `DIAGRAMA_DE_CLASES_v7.md` aún menciona `htmx-lite.js`.
- Búsqueda por texto en el catálogo, importación/exportación de reparaciones y `DashboardRenderer.kt` (1 450 líneas) sin dividir.

## 4. Archivos de esta entrega
Se entregan solo los archivos nuevos o modificados, con su ruta dentro del repositorio; cópialos encima del proyecto.
**Eliminar:** `app/src/main/resources/static/htmx-lite.js`.
