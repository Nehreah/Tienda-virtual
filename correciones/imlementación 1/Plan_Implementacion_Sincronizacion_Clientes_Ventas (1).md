# Plan de implementación v3.1 — Base unificada, sincronización multicanal, clientes y ventas

**Proyecto:** Tienda-virtual (Kotlin · Ktor · Exposed 0.47 · Flyway · PostgreSQL/Supabase · Shopify GraphQL · Meta Graph API)
**Fecha:** 8 de octubre de 2026
**Estado:** plan basado en la lectura del código y de las migraciones del zip, más dos comprobaciones hechas en un PostgreSQL 16 local (ver sección 2.4). **Gradle, Flyway, Shopify y Meta no se ejecutaron.** El código Kotlin no se ha modificado. Cada fase se valida en tu máquina con `./gradlew test` y en staging.

**Cambios respecto a la v3:**
- **V4 dependía de `tienda.v_reparto_pendiente`, que ningún módulo creaba** (`verificar_integridad()` y el `GRANT` la usan), así que V4 habría fallado completa. Ahora la vista se crea en **V1** (artículos activos cuyo reparto de socios no suma 100 %; solo informativa).
- Se eliminó de V4 la mención a una regla de "descarte / cuenta regresiva" (`descartar_sin_reparto()`) que no existe; el único control es `config.exigir_reparto`. También se quitó el estado `Descartado`, que no está en `cat_estado`.
- El borrador de V4 ya está escrito (antes figuraba como "por escribir"); sigue sin ejecutarse en PostgreSQL.

**Cambios respecto a la v2:**
- La base se **reconstruye desde cero** en 4 módulos (V1–V4) en lugar de parchar V001–V016. Esas migraciones no pueden terminar en ninguna base (sección 2.2).
- **Socios dinámicos:** la base empieza con 0 socios. Se elimina todo rastro de Antonio y Javier.
- **Pregunta Q1 resuelta:** `costo_total` es por unidad; se agregan `costo_lote` y `cantidad_comprada`.
- **Una sola columna de stock** (`cantidad`) y **una sola cola** (`outbox`): ya no hay `outbox_canal`, `cantidad_total` ni `cantidad_reservada`.
- **Las métricas salen de la base:** `ventas` más vistas SQL; ningún cálculo depende de memoria.
- Se amplió el diagnóstico con errores que la v2 no tenía (E17–E21).

---

## 1. Decisiones confirmadas

| # | Decisión | Consecuencia en el diseño |
|---|---|---|
| 1 | Se guardan datos personales de clientes (nombre, correo, teléfono). | `cliente` guarda contacto, consentimiento y anonimización (Ley 1581 / Habeas Data). |
| 2 | **Todas** las ventas, de cualquier canal, quedan en `ventas`; de ahí salen las métricas. | Una sola función de registro; las métricas leen la base. |
| 3 | La confirmación de pago en chat es **siempre manual**. | El botón *Confirmar pago* usa el mismo registro único que Shopify. No hay interruptor automático. |
| 4 | `costo_total` es el costo **por unidad**; `costo_lote` es el costo de todo el lote. | Ganancia por venta = `precio_unitario × cantidad − costo_total × cantidad`. |
| 5 | Los socios se agregan según la necesidad; la base arranca con 0. | Sin semillas. La venta se registra aunque no haya socios (queda "sin reparto"). |
| 6 | Las migraciones y tablas se crean desde 0 según la estructura actual del proyecto. | Módulos V1–V4; la cadena V001–V016 se archiva en `db/legacy/`. |

---

## 2. Estado actual (qué encontré)

### 2.1 Cómo funciona hoy

- **Shopify → sistema:** el webhook `orders/create` llama a `vender_unidades`; `orders/cancelled` llama a `devolver_unidades`.
- **WhatsApp / Instagram / Messenger:** son chats. El bot negocia y reserva; el stock se confirma con **Confirmar pago** (`POST /api/confirmar-pago`).
- **Hacia Shopify:** `ShopifyCatalogAdapter.setStock` existe; necesita `SHOPIFY_LOCATION_ID`. **Hacia Meta:** `MetaShopAdapter` son placeholders.
- **Métricas:** `MetricasRoutes` ya lee `ventaRepo.findAll()` y `repartos()`, es decir, de la base cuando hay conexión.
- **Ventas:** hoy solo se llenan por trigger cuando un artículo pasa a `Vendido`.

### 2.2 Por qué se reconstruyen las migraciones

La cadena actual tiene tres orígenes que no encajan: `db/migracion_fase2.sql` (script manual fuera de Flyway), V001–V016 (Flyway) y lo que el código Kotlin espera en `Tables.kt`.

| ID | Problema | Cómo se comprobó |
|---|---|---|
| **E17** | **V006 tiene un error de sintaxis**: un `RAISE` con más argumentos que `%`. Falla al compilar la función en cualquier base, así que Flyway no puede completar la cadena. | Ejecutado: `too many parameters specified for RAISE` |
| **E18** | V002 choca con la tabla `ventas` que `migracion_fase2.sql` ya creó (otra forma), y V007, V008, V010 y V013 fallan en cascada después de V006. | Ejecutado sobre una base antigua sintética con `migracion_fase2.sql` aplicado |
| E4 | `outbox` definida de tres formas incompatibles (V003/V005, V010, funciones de V011/V015). | Lectura |
| E11 | `ventas` solo admite una venta por producto (`key` es clave primaria) y `MetricasService` descartaría las repetidas con `associateBy { it.key }`. | Lectura |
| E12 | `devolver_unidades` consulta `venta_items`, que ninguna migración crea. | Lectura |
| E13 | `socio` y `venta_reparto` tienen dos formas (V006/V007 contra V016/`Tables.kt`). V016 usa `IF NOT EXISTS` y se omite sobre la forma vieja. | Lectura |
| **E19** | **V008 elimina `reparaciones`** (y `metricas`), pero los triggers de `inventario` de `migracion_fase2.sql` y `ExposedReparacionRepository` siguen usándola. Cualquier escritura de inventario fallaría. | Lectura (no se llegó a ejecutar: la cadena se rompe antes) |
| **E20** | `vender_unidades` registra su idempotencia en `eventos_procesados`, la misma tabla que usa la capa de webhooks (`PROCESSING`→`DONE`). Si ambas usan el mismo ID de evento, la venta se descarta como `ALREADY_PROCESSED`. | Lectura |
| **E21** | Dos columnas de stock (`cantidad` y `cantidad_total`): el dashboard solo escribe `cantidad`, y `vender_unidades` usa `GREATEST` de ambas, lo que puede reaparecer stock vendido. `cantidad_reservada` nunca la escribe ningún código. | Lectura |

También siguen vigentes de la v2: E1 (dos caminos de sincronización: saga directa contra `OutboxWorker`), E2 (el worker pasa el SKU como `externalId`), E3 (`channelMappings` en memoria), E5/E6 (ventas de Shopify y cliente no se registran), E7/E8 (`cliente` sin poblar), E9 (el webhook ignora el resultado de `venderUnidades`), E10 (precio de Shopify truncado y sin descuentos), E14 (dos tablas de publicaciones), E15 (*Confirmar pago* solo cambia el estado), E16 (Meta sin implementar).

### 2.3 Qué se debe conservar

Las reglas de negocio de `migracion_fase2.sql` funcionan y se trasladan a V1: costo y precio mínimo (ROI 50 %), publicar solo por encima del mínimo, fechas coherentes, artículo vendido inmutable, reparación 1 a 1, historial de cambios, reparto por mayor residuo (V016).

### 2.4 Qué se verificó y qué no

**Verificado en PostgreSQL 16.15 local:**
- La cadena actual falla (E17, E18).
- Los borradores de V1, V2 y V3 cargan en una base vacía sin errores.
- Prueba de humo: artículo de 5 unidades con compra 10.000 y reparación 2.000 → `costo_total` 12.000, `costo_lote` 60.000, mínimo 18.000; cambiar el stock deja 1 aviso `stock_changed` hacia Shopify con el stock correcto; guardar una conversación de WhatsApp crea el cliente solo; `socio` queda en 0 filas.

**No verificado:** V4 (borrador escrito; solo se revisó por lectura que sus objetos y columnas existan en V1–V3), la vista `v_reparto_pendiente` añadida a V1 en la v3.1 (V1 debe volver a cargarse en una base vacía), ejecución con Flyway, compilación Kotlin, Shopify, Meta.

---

## 3. Arquitectura objetivo

```
Venta en cualquier canal (Shopify · WhatsApp · Instagram · Messenger)
   │   chat: tú pulsas "Confirmar pago"      Shopify: webhook orders/create
   ▼
tienda.registrar_venta_linea(...)   ← UNA sola función atómica
   ├─ idempotencia (por pedido+línea, deal o evento) y control de stock
   ├─ descuenta cantidad, consume la reserva
   ├─ inserta una fila en `ventas` (cantidad, precio y costo unitarios, canal, cliente)
   ├─ reparte ingresos, inversión y ganancia por socio (venta_reparto), si hay socios
   └─ si cantidad llega a 0 → el artículo pasa a Vendido
   │
   ▼  (trigger sobre inventario.cantidad: cualquier cambio de stock encola el aviso)
tienda.outbox ──► OutboxWorker (reintentos) ──► Shopify / Meta catálogo

Dashboard y métricas leen siempre de la base: ventas + vistas v_*
```

Principios:
1. **Una sola puerta de entrada para vender** y **una sola ruta de salida hacia los canales** (la cola).
2. **Un solo dueño de cada dato:** stock en `inventario.cantidad`; publicaciones en `publicaciones`; reparto en `inventario_inversion`/`venta_reparto`.
3. **La base calcula, el código lee:** costos, precio mínimo, ganancia y reparto los calculan funciones y triggers. Ni la app ni Excel los escriben.
4. **Las ventas se escriben solo con las funciones.** Un trigger bloquea el `INSERT/UPDATE/DELETE` directo (bandera `app.venta_interna`).
5. **Migraciones en módulos, no en parches:** cada módulo depende solo de los anteriores.

---

## 4. Modelo de datos (módulos de migración)

Las migraciones nuevas reemplazan las de `infra/src/main/resources/db/migrations/`. Los archivos antiguos pasan a `db/legacy/` (V001–V016, `migracion_fase2.sql` y sus pruebas) solo como historia. Flyway no usa `BEGIN/COMMIT` dentro de ellos.

| Módulo | Contenido | Estado |
|---|---|---|
| **V1 Núcleo** | `config`, catálogos (`cat_estado`, `cat_tipo`, `cat_condicion`, `cat_canal`), `historial`, `inventario`, `reparaciones`, `socio`, `inventario_inversion`; fórmulas (`fn_precio_minimo`, `fn_periodo`, `fn_repartir`); triggers de reglas; `fn_asegurar_rls`; vista `v_reparto_pendiente` | Borrador probado en la v3; **modificado en la v3.1 (vista nueva): repetir la carga en base vacía** |
| **V2 Clientes y reservas** | `cliente`, `conversaciones`, `reservas`; `upsert_cliente`, `anonimizar_cliente`; trigger que crea el cliente al guardar la conversación; `fn_reservado`, vista `v_stock` | Borrador cargado y probado |
| **V3 Canales y cola** | `publicaciones`, `outbox`, `eventos_procesados`, `eventos_entrantes`, `eventos_sin_resolver`, `importaciones`; `fn_encolar_stock` y su trigger | Borrador cargado y probado |
| **V4 Ventas y métricas** | `ventas` por línea, `venta_reparto`, `registrar_venta_linea`, `anular_venta_linea`, compatibilidad, vistas de métricas | Borrador escrito; **no ejecutado** (se valida en la Fase 2) |

### 4.1 Reglas de modelo clave

- **Stock:** `inventario.cantidad` es la única columna de stock. Lo ofrecible es `cantidad − reservas vigentes` (`v_stock.disponible`).
- **Costo (Q1):** `costo_total` = `precio_compra + reparación`, ambos **por unidad**. `costo_lote = costo_total × cantidad_comprada`. `cantidad_comprada` sube al reponer a mano y no baja al vender.
- **Precio mínimo:** `costo_total × (1 + ROI)`, redondeado hacia arriba. Se calcula sobre el costo unitario.
- **Socios:** sin semillas. `config.exigir_reparto = 0` por defecto: se vende aunque no haya socios o no sumen 100. Con `1`, vender a mano o por chat exige 100 %. Las ventas de Shopify **nunca** se bloquean por esto, porque la venta ya ocurrió.
- **Cliente:** `cliente_id = canal:idDelCanal`. El trigger de `conversaciones` lo crea y refresca; para WhatsApp guarda el teléfono.
- **Cola:** un solo aviso por `(sku, canal, versión)`. Stock publicado = `cantidad − reservas vigentes`. No se avisa al canal donde ocurrió la venta.

### 4.2 `ventas` (módulo V4)

Una fila por **línea vendida**.

| Columna | Detalle |
|---|---|
| `venta_id` | Clave primaria (`BIGINT` identidad). `key` apunta a `inventario(key)` con índice, ya no es única. |
| `cantidad`, `precio_unitario` | Unidades vendidas y precio neto por unidad. `vendido_en` = precio unitario. `ingresos` = `cantidad × precio_unitario`. |
| `costo_unitario`, `costo_total` | Congelados al vender. `costo_total` es el costo **de la línea** (`costo_unitario × cantidad`). |
| `ganancia_neta`, `margen_pct`, `tiempo_dias`, `dias_publicado`, `periodo` | Calculados al vender. |
| `canal`, `fecha_venta`, `origen` | `origen`: `chat`, `shopify`, `manual`. |
| `deal_id`, `order_id`, `line_id`, `event_id` | Llaves de idempotencia, únicas entre ventas vigentes. **No** se usa `eventos_procesados` (E20). |
| `cliente_id` | Del parámetro o, si hay reserva, de su conversación. |
| `anulada_en`, `anulada_motivo` | Anulación suave. Las métricas leen `v_ventas` (`WHERE anulada_en IS NULL`). |

`venta_reparto(venta_id, key, socio_id, pct, inversion, ingresos, ganancia)`: `inversion` es el costo de la línea repartido por socio. Si no hay socios, la venta no tiene filas aquí.

### 4.3 Funciones de V4

- `registrar_venta_linea(p_key, p_cantidad, p_precio_unit, p_canal, p_fecha, p_origen, p_event_id, p_deal_id, p_order_id, p_line_id, p_reserva_id, p_cliente_id, p_usuario)` devuelve JSONB con `status` (`SUCCESS`, `ALREADY_PROCESSED`, `SIN_STOCK`, `NO_ENCONTRADO`) y `venta_id`. Bloquea la fila de inventario, comprueba idempotencia y stock, inserta la venta y el reparto, descuenta, consume la reserva y, si `cantidad` llega a 0, marca `Vendido`.
  Para `origen = 'shopify'` solo se comprueba el stock físico (la venta ya ocurrió); para chat y manual también se respetan las reservas ajenas.
- `anular_venta_linea(...)`: por `venta_id`, por `(canal, order_id, line_id)`, por pedido completo o por `key`. Marca la anulación, borra su reparto, devuelve el stock y reabre el artículo. Reemplaza a `devolver_unidades` (E12).
- **Compatibilidad:** `vender_unidades` (misma firma de hoy), `devolver_unidades`, `registrar_venta` y `anular_venta` pasan a ser envoltorios. El Kotlin actual sigue funcionando mientras se migra.
- **Vía manual:** un trigger sobre `inventario.estado`. Si alguien marca `Vendido` a mano, registra una línea con las unidades que quedaban; si lo saca de `Vendido`, anula las ventas y repone el stock.
- **Vistas de métricas:** `v_ventas`, `v_ventas_detalle`, `v_resumen_mensual_socio` (dinámica, une `venta_reparto` con `socio`), `v_rendimiento_tipo`, `v_periodo_tipo`, `v_resumen_canal`, `v_cliente_resumen`. Además `verificar_integridad()` (incluye "ventas sin reparto" y "stock negativo").

---

## 5. Fases

### Fase 0 — Decisión de base y respaldo (1 día)

- [ ] Rama nueva en Git (`feature/base-unificada`).
- [ ] **Decidir el destino de los datos actuales.** Las migraciones nuevas parten de una base **vacía**. Si Supabase tiene inventario o ventas reales, hay que exportarlos y cargarlos después (Fase 1).
  Si la base es de pruebas: crear un proyecto/esquema `tienda` nuevo.
- [ ] Mantener `CHANNEL_WRITE_ENABLED=false` hasta la Fase 6.
- [ ] Confirmar en Shopify: moneda COP, `SHOPIFY_LOCATION_ID` y webhooks `orders/create` y `orders/cancelled` suscritos.
- [ ] **Si ya hay datos reales**, ejecutar y guardar:

```sql
SELECT table_name, column_name, data_type FROM information_schema.columns
WHERE table_schema = 'tienda' ORDER BY table_name, ordinal_position;
SELECT * FROM tienda.flyway_schema_history ORDER BY installed_rank;  -- ¿qué migraciones se aplicaron?
SELECT count(*) FILTER (WHERE estado = 'Vendido') AS vendidos, count(*) AS total FROM tienda.inventario;
```

**Aceptación:** respaldo hecho y decisión tomada sobre los datos existentes.

---

### Fase 1 — Base nueva: módulos V1, V2 y V3 (2-3 días)

**Objetivo:** reemplazar la cadena de migraciones por el esquema unificado y que el código existente arranque contra él.

- [ ] Mover V001–V016 y los scripts de `db/` a `db/legacy/`; colocar V1, V2 y V3 en `infra/src/main/resources/db/migrations/`.
- [ ] `DatabaseFactory`: pasar `baselineOnMigrate(true)` a `false`. Con `true`, sobre una base antigua Flyway se "baselinearía" en la versión 1 y correría V2+ sobre el esquema viejo. Con `false` falla con un error claro.
- [ ] Una base que ya tenga historial Flyway con V001–V016 rechazará el arranque (checksum y migraciones no encontradas). Es lo esperado: se usa una base nueva.
- [ ] Ajustes de `Tables.kt`: `PublicacionesTable` gana `external_inventory_id`; el resto de tablas del núcleo ya coincide con el código. `OutboxTable` apunta a `tienda.outbox` (sin cambios).
- [ ] `OutboxWorker`: usar `external_inventory_id`/`external_id` de `publicaciones` en `setStock`, no el SKU (E2). Sin fila de publicación → `SKIPPED_NOT_PUBLISHED`.
- [ ] Cargar `channelMappings` desde `publicaciones` al arrancar (E3).
- [ ] Si hay datos que conservar: script de carga único desde la exportación (inventario, reparaciones, ventas históricas a `origen = 'migracion'`). Los socios se recrean desde el dashboard.
- [ ] Pruebas SQL de módulo (nuevas, estilo `pruebas_fase2.sql`): costo y lote, publicar bajo el mínimo, reparación, socios en 0, suma de reparto ≤ 100, `v_reparto_pendiente` (con 0 socios lista los artículos activos; con reparto en 100 % no lo lista), cliente automático, aviso de stock único por versión.

**Aceptación:**
- Flyway aplica V1–V3 en una base vacía y la app arranca.
- Crear un socio desde el dashboard funciona; la base empieza con 0 socios.
- Cambiar el stock de un producto publicado deja un aviso `PENDING` por cada canal publicado.
- Reiniciar el servidor no pierde ningún mapeo de canal.

---

### Fase 2 — Ventas por unidad y registro único: módulo V4 (5-6 días)

**Objetivo:** que todas las ventas entren por una función y las métricas salgan de la base. Corrige E5, E9, E10, E11, E12, E15, E20.

**Migración `V4__ventas_y_metricas.sql`** (borrador en `borradores_migraciones_V1_V4/`; cargarla y probarla en una base vacía es lo primero de esta fase): `ventas`, `venta_reparto`, funciones y vistas descritas en la sección 4.

**Cambios en Kotlin:**

| Archivo | Cambio |
|---|---|
| `Tables.kt` (`VentasTable`, `VentaRepartoTable`) | Clave primaria `venta_id`; columnas nuevas (`cantidad`, `precio_unitario`, `costo_unitario`, `origen`, `order_id`, `line_id`, `cliente_id`, `anulada_en`). |
| `Venta.kt`, `VentaRepository` | `findByKey` devuelve `List<Venta>`; lecturas contra `tienda.v_ventas`. |
| `MetricasService` | Agrupar por `ventaId`, ponderar por `cantidad`, sumar por línea. Leer vistas y reparto desde la base. Revisar `MetricasRenderer` y `MetricasRoutes`. |
| `InventarioRepository` | `registrarVentaLinea(...)` y `anularVentaLinea(...)` devuelven un estado (`SUCCESS`, `ALREADY_PROCESSED`, `SIN_STOCK`, `NO_ENCONTRADO`) en vez de `Boolean` (E9). |
| `ConfirmPaymentUseCase` | Llama `registrarVentaLinea` con `deal_id` y reserva. Sigue siendo el botón manual (E15). |
| `ShopifyWebhookHandler` | `id` real de la línea (no el índice); precio neto por unidad con `BigDecimal` restando `discount_allocations`; alertar si la moneda ≠ COP (E10). |
| `WebhookRoutes` | `registrarVentaLinea` por línea; en `SIN_STOCK` guardar en `eventos_sin_resolver` y alertar en el dashboard; en cancelación, `anularVentaLinea`. |
| `Application.kt` | Sin conexión de repositorios nuevos en esta fase. |

**Aceptación:**
- Vender dos veces el mismo producto de varias unidades genera dos filas en `ventas` y las métricas suman ambas.
- Una orden de Shopify genera fila con fecha, precio neto, canal `shopify` y reparto (si hay socios).
- Repetir el mismo webhook no duplica la venta.
- Una cancelación devuelve el stock y deja la venta anulada.
- Vender con 0 socios registra la venta sin reparto; con socios, el reparto suma exacto.
- *Confirmar pago* descuenta stock, consume la reserva y registra la venta.
- `costo_lote` y `ganancia` de un producto de varias unidades coinciden con el cálculo manual.

---

### Fase 3 — Clientes y datos personales (2 días)

**Objetivo:** cada venta ligada a un cliente. El cliente de chat ya se crea solo desde V2.

**Cambios en Kotlin:**

| Archivo | Cambio |
|---|---|
| `commerce/ports/ClienteRepository.kt` (nuevo) | Puerto: `findAll` (con resumen), `anonimizar`, `registrarContacto`. |
| `infra/db/ExposedClienteRepository.kt` (nuevo) | Lee `tienda.v_cliente_resumen`; llama `upsert_cliente` y `anonimizar_cliente` con `exec`. |
| `ShopifyWebhookHandler` / `WebhookRoutes` | Leer `customer` y `shipping_address.phone`; llamar `upsert_cliente('shopify:<id>', …)` antes de registrar la venta. Un fallo de cliente nunca devuelve 500 a Shopify. |
| `ClientesRoutes`, `Application.kt` | Leer de `ClienteRepository` cuando hay base; cablear. |

**Privacidad (obligatorio antes de producción):**
- Registrar `consentimiento_en` cuando el cliente acepta la política (`docs/whatsapp-templates/bienvenida_privacidad.md`). Para Shopify, el consentimiento viene del checkout.
- **Limitación a decidir:** `anonimizar_cliente` borra contacto y mensajes, pero el teléfono sigue dentro de `conversation_id` y `customer_id`. Definir una política de retención o un borrado de conversaciones.

**Aceptación:** una venta de WhatsApp y una de Shopify traen `cliente_id`; un cliente que escribe aparece en `cliente`; `/api/clientes` muestra compras, unidades y total gastado.

---

### Fase 4 — Un solo camino de sincronización (2 días)

**Objetivo:** corregir E1. El trigger de la cola ya existe desde V3.

1. `AntiOversellSaga` deja de llamar a los adaptadores: la cola es el único camino (el trigger ya encola).
2. `OutboxWorker` es el único que escribe en los canales y el único que respeta `CHANNEL_WRITE_ENABLED`.
3. Reintentos: mantener backoff y estado `DEAD` tras 3 intentos; alerta en el dashboard.
4. Documentar que el stock publicado es `cantidad − reservas vigentes`.

**Aceptación:** con `CHANNEL_WRITE_ENABLED=true` en staging, una venta por WhatsApp deja stock 0 en Shopify en menos de un minuto; con `false`, nada se escribe en los canales; ninguna ruta llama a `setStock` fuera del worker.

---

### Fase 5 — Adaptador de Meta catálogo (3-5 días)

Igual que la v2: implementar `publishProduct`, `updateProduct`, `setStock`, `unpublishProduct`, `fetchRemoteProducts`; mapear errores a `PortResult`; revisar antes en la documentación de Meta (`META_GRAPH_API_VERSION`) qué endpoint corresponde en modo `CATALOG_ONLY` (Colombia); pruebas con respuestas simuladas; mostrar en el dashboard que Meta **no sincroniza** hasta terminar.

---

### Fase 6 — Conciliación, activación y monitoreo (2 días)

- Mientras `CHANNEL_WRITE_ENABLED=false` los avisos quedan en `SKIPPED_READ_ONLY` y **no se reenvían**. Antes de activar, conciliar: publicar el stock actual de todos los productos publicados.
- Programar `ReconcileCatalogUseCase` y `StockDriftDetector` cada hora.
- Dashboard: eventos `DEAD`, `SIN_STOCK` sin resolver, diferencias de stock y ventas sin reparto (`verificar_integridad()`).
- Activación escalonada: Shopify primero, vender un producto de prueba por canal, luego Meta.
- Reversión: `CHANNEL_WRITE_ENABLED=false`.

---

### Fase 7 — Pruebas y despliegue (3 días)

| Prueba | Qué valida |
|---|---|
| `db/pruebas_nucleo.sql` (V1) | Costos, lote, precio mínimo, reparación, socios en 0, reparto ≤ 100. |
| `db/pruebas_ventas.sql` (V4) | Dos ventas del mismo producto, idempotencia, `SIN_STOCK`, anulación, reparto exacto, vía manual. |
| `db/pruebas_cola.sql` (V3) | Un aviso por canal publicado; sin avisar al canal de origen; estados válidos. |
| `ShopifyCustomerParsingTest`, `ShopifyNetPriceTest` | Cliente, teléfono alterno, descuentos, `id` de línea, moneda. |
| `ConfirmPaymentVentaTest` | Confirmar pago descuenta stock, consume reserva, liga cliente y deal. |
| `OutboxExternalIdTest` | El worker usa el ID externo, no el SKU. |
| `MetricasMultiUnidadTest`, `VentaUnificadaTest` | Ventas repetidas y de varios canales suman en métricas y por socio. |

**Despliegue:** CI en verde; migraciones aplicadas primero en staging (Flyway las corre al arrancar); **migración antes que código**; respaldo previo; imagen Docker y revisión del dashboard.

---

## 6. Cronograma estimado

| Fase | Días | Depende de |
|---|---|---|
| 0 Decisión de base | 1 | — |
| 1 Base nueva V1–V3 | 2-3 | 0 |
| 2 Ventas y V4 | 5-6 | 1 |
| 3 Clientes | 2 | 2 |
| 4 Un solo camino | 2 | 1 |
| 5 Meta | 3-5 | 4 |
| 6 Conciliación | 2 | 4 (y 5) |
| 7 Pruebas y despliegue | 3 | todas |

Las fases 4 y 5 pueden avanzar en paralelo con la 2 y la 3. Total aproximado: **4 semanas**.

---

## 7. Variables de configuración

| Variable | Valor por defecto | Para qué |
|---|---|---|
| `CHANNEL_WRITE_ENABLED` | `false` | Permite que el worker escriba stock en Shopify/Meta. |
| `SHOPIFY_LOCATION_ID` | — | Necesaria para `inventorySetQuantities`. |
| `SHOPIFY_WEBHOOK_SECRET` | — | Verificación HMAC de los webhooks. |
| `META_CATALOG_ID`, `META_ACCESS_TOKEN`, `META_GRAPH_API_VERSION` | — | Adaptador de Meta. |

Configuración dentro de la base (`tienda.config`): `roi_minimo_pct` (50), `periodo_corto_dias` (15), `periodo_medio_dias` (30), `exigir_reparto` (0).

---

## 8. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Perder datos existentes al pasar a la base nueva. | Fase 0: respaldo y decisión explícita; script de carga único; `baselineOnMigrate=false` impide que Flyway corra sobre la base vieja sin avisar. |
| El esquema real de Supabase difiere de lo que se asumió. | Las consultas de la Fase 0 solo se necesitan si hay datos que conservar. |
| `vender_unidades` de hoy se llama con la firma antigua durante la transición. | Se conserva como envoltorio de `registrar_venta_linea`. |
| `findByKey` de ventas devuelve varias filas y rompe `singleOrNull`. | Se cambia a lista en la Fase 2; hasta entonces no hay ventas de varias unidades. |
| Vender sin socios deja métricas por socio vacías. | `verificar_integridad()` y el dashboard muestran "ventas sin reparto"; `exigir_reparto=1` lo impide en chat y manual. |
| Avisos perdidos mientras la escritura estaba apagada. | Conciliación inicial antes de activar. |
| Meta sin integrar da falsa sensación de sincronía. | Indicador visible hasta terminar la Fase 5. |
| Guardar datos personales sin base legal. | Consentimiento registrado, anonimización y política de retención. |
| Desplegar código antes que la migración. | Orden fijo: migración → código. |

---

## 9. Checklist final de aceptación

- [ ] Flyway aplica V1–V4 en una base vacía (sin errores por objetos inexistentes) y la app arranca; la base empieza con 0 socios.
- [ ] Una venta por WhatsApp, Instagram, Messenger y Shopify queda en `ventas` con fecha, cantidad, precio neto, canal y cliente.
- [ ] Un producto de varias unidades puede venderse varias veces; `costo_total` es por unidad y `costo_lote` es el del lote.
- [ ] Repetir un webhook no duplica la venta; una cancelación devuelve el stock y anula la venta.
- [ ] El reparto por socio se calcula en cada venta y suma exacto; sin socios la venta se registra sin reparto.
- [ ] Las métricas salen de `ventas` y las vistas `v_*` y suman todas las ventas, sin importar el canal.
- [ ] `cliente` se llena sola y `/api/clientes` muestra compras y total gastado.
- [ ] Cualquier cambio de stock deja un aviso en `outbox` para cada canal publicado distinto del de origen.
- [ ] Con `CHANNEL_WRITE_ENABLED=false` no se escribe nada en los canales.
- [ ] La confirmación de pago en chat sigue siendo manual y descuenta stock y registra la venta.
- [ ] Los eventos `SIN_STOCK` y `DEAD` se ven en el dashboard.
- [ ] Anonimizar un cliente funciona sobre la base y la política de retención está definida.
- [ ] CI en verde y migraciones aplicadas en staging y producción.
