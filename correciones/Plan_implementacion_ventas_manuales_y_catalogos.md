# Plan de implementación: ventas manuales y actualización de catálogos

Proyecto: Tienda Virtual Multicanal · Versión acotada (sin chats ni tiempo real) · 8 de octubre de 2026

## 1. Objetivo y alcance

Permitir registrar desde el dashboard ventas hechas fuera del bot (Marketplace, WhatsApp, Instagram, en persona, etc.), llevar su estado de envío por separado y lograr que el stock y los catálogos conectados se actualicen, o muestren con claridad que falta hacerlo, sin duplicar ventas ni inflar métricas.

**Principio de trabajo:** todo se resuelve modificando lo que ya existe (migraciones V001–V004, rutas, renderers y workers). No se crea ninguna migración nueva ni se duplican fuentes de datos.

**Incluye:** formulario de venta, estado logístico, vista de ventas con filtros, panel de sincronización de catálogos con reintentos y acciones manuales, y métricas por canal y logística.

**No incluye:** bandeja de chats, «tomar la palabra», SSE/WebSocket ni identidades multioperador. La sección 9 explica qué deja listo este trabajo para esa decisión futura.

**Base del plan:** lectura parcial del código. Lo que no se pudo verificar queda como tarea de la Fase 0.

## 2. Punto de partida: lo que ya existe

| Pieza | Dónde | Qué aporta |
| --- | --- | --- |
| Función única de venta | V004 `tienda.registrar_venta_linea` | Bloqueo del producto, idempotencia (deal, pedido, evento), stock con reservas, descuento parcial, estado Vendido solo al llegar a 0, reparto entre socios, costo congelado |
| Anulación | `tienda.anular_venta_linea` | Marca la venta como anulada, devuelve el stock y reabre el artículo |
| Cola de avisos | V003 `outbox` y `OutboxWorker` | Un aviso por producto, canal y versión; estados PENDING, SENT, FAILED, DEAD, SKIPPED_READ_ONLY, SKIPPED_NOT_PUBLISHED; reintentos |
| Anti-bucle | `app.canal_origen` | No avisa al canal donde ocurrió la venta |
| Publicaciones | tabla `publicaciones` | ID externo y stock publicado por canal |
| Métricas | vistas `v_ventas`, `v_resumen_canal`, función `verificar_integridad()` | Leen solo ventas vigentes |
| Reconciliación | `ReconcileCatalogUseCase`, `StockDriftDetector`, `ReconciliacionRoutes` | Detectan diferencias de stock entre lo local y los canales |
| Seguridad | `DashboardGuard`, `OriginGuard` | Autenticación y comprobación de origen en los POST |
| Usuario en auditoría | `DatabaseFactory.tx` | Fija `app.usuario` y `app.origen` en cada transacción; el historial los lee |
| Venta implícita | trigger `trg_d_inventario_vendido_manual` | Cambiar un artículo a Vendido a mano ya crea una venta |

**Lo que falta:** estado logístico, canales normalizados, token de idempotencia en ventas manuales, nota, vínculo entre una venta y su estado de sincronización por canal, acciones manuales registradas y la interfaz.

## 3. Decisiones de diseño

1. **Una sola vía de escritura.** El formulario llama a una función nueva `registrar_venta_manual`, que envuelve a `registrar_venta_linea` con `origen = manual`. El formulario nunca cambia el estado del artículo a mano, para no pasar dos veces por el trigger de venta implícita.
2. **Estado comercial y logístico separados.** El comercial es la propia venta (vigente o anulada con `anulada_en`); no se inventan estados nuevos en inventario. El logístico es una columna nueva `estado_logistico` en `ventas` con los valores SIN_ENVIO, PENDIENTE_ENVIO, ENVIADO y ENTREGADO. Cambiarlo nunca toca ingresos ni ganancia.
3. **Canales normalizados:** `shopify` (Tienda online), `facebook_marketplace`, `whatsapp`, `instagram`, `presencial` y `otro`. «Otro» guarda su texto libre en una columna nueva `canal_detalle`, no en `cat_canal`: hoy la función crea una fila en `cat_canal` con cualquier texto recibido, lo que partiría las métricas por canal.
4. **Idempotencia.** El formulario genera un UUID al abrirse y lo envía como `p_event_id`. Un doble clic o un reintento devuelve ALREADY_PROCESSED con la misma venta.
5. **Ventas de «Tienda online».** Shopify ya las registra por webhook. Para no duplicar, el formulario exige el número de pedido cuando el canal es `shopify`, de modo que la clave única (canal, pedido, línea) la deduplique contra el webhook. Sin pedido, se rechaza con un mensaje claro.
6. **La venta local nunca depende de los canales externos.** Cada venta guarda `sync_version` (versión del inventario después de vender) para unirla con las filas del outbox por producto y versión, y así mostrar su estado por canal.
7. **Acciones manuales registradas.** Columnas nuevas en `outbox` para marcar «hecho por el operador» cuando un canal no permite actualizar automáticamente (por ejemplo SKIPPED_READ_ONLY en Meta con modo CATALOG_ONLY).
8. **Sin base de datos no se vende.** Si el servidor corre en modo memoria (sin `DATABASE_URL`), el botón se deshabilita con una explicación; no se simula la venta.

## 4. Fases

### Fase 0 — Auditoría (esfuerzo: pequeño)

Verificar antes de escribir código:

- Que `ventaRepo` en `Application.kt` es la versión Exposed y no la de memoria.
- Que `inventario.version` aumenta con cada cambio de cantidad (el outbox depende de ello).
- Qué hacen `OutboxWorker` y los adaptadores cuando `stock_objetivo = 0`: ¿ocultan, marcan agotado o solo fijan 0? Y qué devuelve `CapabilityDetector` para Meta en Colombia.
- El valor de `config.exigir_reparto` y si hay socios cargados: con esa opción activa, una venta manual sin reparto completo se rechaza.
- Si `FlywayMigrationTest` ejecuta realmente el PL/pgSQL (H2 no lo soporta); si no, las pruebas de esta entrega necesitan PostgreSQL real.
- Qué pantalla del dashboard permite hoy cambiar un artículo a Vendido, para redirigirla al nuevo formulario.
- Que el esquema `tienda` de la base destino está vacío. `DatabaseFactory` usa `baselineOnMigrate(true)`: si el esquema ya tiene tablas de los scripts antiguos de la carpeta `db/`, Flyway podría omitir V001.

**Salida:** lista corta de hallazgos y ajustes a las fases siguientes.

### Fase 1 — Modificación del esquema existente (esfuerzo: medio)

Los cambios se editan directamente en los archivos de migración actuales, que aún no se han aplicado a ninguna base real. Las columnas, funciones y vistas de ventas van en V004; las columnas y funciones del outbox en V003; los canales nuevos donde ya se siembra `cat_canal`.

- **Columnas nuevas en `ventas`:** `estado_logistico` (con CHECK de valores, por defecto SIN_ENVIO), `fecha_envio`, `fecha_entrega`, `guia_envio` (opcional), `nota`, `canal_detalle` y `sync_version`.
- **Canales:** sembrar en `cat_canal` los seis canales normalizados que falten.
- **`tienda.registrar_venta_manual(...)`:** valida el canal contra la lista, exige `order_id` si es `shopify`, llama a `registrar_venta_linea` con `origen = manual` y el UUID como `event_id`, y en la misma transacción guarda logística inicial, nota, `canal_detalle` y `sync_version`. Devuelve el mismo JSON de estados que la función base.
- **`tienda.actualizar_logistica_venta(venta_id, estado, guia, usuario)`:** necesaria porque un trigger bloquea el UPDATE directo sobre `ventas`. Valida transiciones (no se pasa a ENTREGADO desde SIN_ENVIO sin pasar por envío; las ventas presenciales pueden quedar en SIN_ENVIO) y registra fechas.
- **Acciones manuales en `outbox`:** columnas `accion_manual_resuelta_en` y `accion_manual_por`, y función `tienda.resolver_accion_manual(event_id, usuario)`.
- **Reintento seguro:** función `tienda.reintentar_outbox(event_id)` que devuelve un aviso FAILED o DEAD a PENDING con intentos en 0, sin crear duplicados.
- **Vistas:** `v_venta_sync` (venta, canal, estado del aviso, stock objetivo y publicado, último error), `v_ventas_logistica` (conteos e ingresos por estado logístico y canal) y `v_acciones_manuales_pendientes`.
- **Historial:** confirmar que los cambios logísticos quedan en el historial genérico con operador, estado anterior y nuevo.
- **Datos existentes:** las ventas cargadas desde migración quedan en SIN_ENVIO por defecto (ver decisiones pendientes).

### Fase 2 — Backend (esfuerzo: medio)

Nuevo archivo `VentasRoutes.kt` siguiendo el patrón de `ArticulosRoutes.kt`. Todas las rutas que modifican datos pasan por `DashboardGuard` con `mutating = true`. El usuario autenticado llega a la base mediante el `app.usuario` que ya fija `DatabaseFactory.tx`.

| Ruta | Acción |
| --- | --- |
| `POST /api/ventas` | Registrar venta (producto, cantidad, precio final, fecha, canal, detalle, envío, nota, token) |
| `GET /api/ventas` | Listado con filtros por estado logístico, canal, fecha y anuladas |
| `POST /api/ventas/{id}/logistica` | Cambiar estado de envío sin tocar la venta |
| `POST /api/ventas/{id}/anular` | Anular con motivo obligatorio |
| `GET /api/sync` | Estado de sincronización por producto y canal |
| `POST /api/sync/{eventId}/reintentar` | Reintentar un aviso FAILED o DEAD |
| `POST /api/sync/{eventId}/manual-hecho` | Marcar acción manual como realizada |

**Validaciones previas** (la base sigue siendo la autoridad): cantidad entre 1 y el disponible (`v_stock`), precio entero en COP (reusar `AmountParser`), fecha no futura, canal en la lista, `canal_detalle` obligatorio si es `otro`.

**Mensajes por estado de la función:**

| Estado | Mensaje al operador |
| --- | --- |
| SUCCESS | Venta registrada; muestra stock restante y estado de sincronización |
| ALREADY_PROCESSED | Esta venta ya estaba registrada (sin duplicar), con enlace a ella |
| SIN_STOCK | Indica unidades solicitadas y disponibles (descontando reservas ajenas) |
| NO_DISPONIBLE | El artículo está inactivo o archivado |
| FECHA_INVALIDA | La fecha es anterior a la compra o publicación; muestra esas fechas |
| NO_ENCONTRADO / DATOS_INVALIDOS | Error genérico sin detalles internos |

Si el precio da pérdida (ganancia negativa), el sistema lo permite pero la interfaz pide confirmación explícita antes de enviar.

### Fase 3 — Interfaz (esfuerzo: medio a grande)

Nuevo `VentasRenderer.kt`; no se agrega más código al `DashboardRenderer.kt`, que ya es muy grande. Todo texto de usuario (nota, detalle de canal, nombres) se escapa al generar HTML.

- **Botón «Registrar venta»** en cada producto del inventario, con ventana de formulario (HTMX). Campos: producto (precargado), cantidad (tope = disponible), precio final, fecha (hoy en hora de Colombia), canal, detalle si es «Otro», ¿requiere envío? (desmarcado por defecto en `presencial`) y nota. El botón se deshabilita al enviar.
- **Vista de ventas:** tabla con filtros y acciones (cambiar estado de envío, anular). Las ventas anuladas se muestran diferenciadas.
- **Indicador de sincronización por venta:** una etiqueta por canal conectado (actualizado, pendiente, error, acción manual requerida). Si algo falta, el mensaje debe decir literalmente que la venta quedó registrada localmente y la sincronización sigue pendiente.
- **Redirección del flujo antiguo:** el cambio de estado a Vendido desde inventario abre el nuevo formulario, para que ninguna venta quede sin precio, canal ni estado de envío.

### Fase 4 — Actualización de catálogos (esfuerzo: medio)

La cola y el trigger ya existen; el trabajo es hacerlos visibles y operables.

- **Panel «Sincronización de catálogos»:** por producto y canal, mostrar stock objetivo contra stock publicado, estado, intentos, último error y fecha de la última sincronización.
- **Reintentar:** botón para avisos FAILED o DEAD, usando `reintentar_outbox`. No crea avisos nuevos, así que no hay duplicados.
- **Acciones manuales:** los avisos SKIPPED_READ_ONLY aparecen como tarea pendiente («actualiza este catálogo a mano») con botón «Hecho», que registra operador y hora.
- **Stock en cero:** según lo que revele la Fase 0, definir qué ocurre al agotarse un producto (marcar sin stock en el canal, ocultar, o tarea manual). No se asume que todos los canales ofrecen lo mismo.
- **Reconciliación:** botón «Comprobar diferencias» que ejecuta el detector de deriva existente y lista productos cuyo stock local y publicado no coinciden tras un fallo.
- **Anulaciones:** al anular una venta el stock se repone y el trigger encola de nuevo los avisos; la interfaz debe reflejarlo igual que una venta.
- **Alerta:** contador visible de avisos DEAD y acciones manuales pendientes.

### Fase 5 — Métricas (esfuerzo: pequeño)

- Las cifras salen solo de `ventas` vigentes. Cambiar el estado logístico no puede alterar ingresos, porque vive en otra columna.
- Añadir filtros por canal, fecha y estado logístico en `MetricasRoutes`, apoyados en `v_ventas_logistica`.
- Definiciones explícitas:

| Métrica | Definición |
| --- | --- |
| Ventas registradas | Líneas vigentes (no anuladas) |
| Unidades vendidas | Suma de cantidad de líneas vigentes |
| Ingresos | Suma de `ingresos` de líneas vigentes, contados una sola vez |
| Ventas por canal | `v_resumen_canal` |
| Pendientes de envío | Vigentes con estado PENDIENTE_ENVIO |
| Entregadas | Vigentes con estado ENTREGADO |
| Anuladas | Líneas con `anulada_en`; no suman ingresos |
| Sincronizaciones pendientes | Avisos PENDING, FAILED o DEAD más acciones manuales sin resolver |

### Fase 6 — Pruebas (esfuerzo: medio)

Como la lógica está en PL/pgSQL, estas pruebas deben correr contra PostgreSQL real (por ejemplo con Testcontainers o el `docker-compose` del proyecto), además de las pruebas actuales.

- Mismo token enviado dos veces: una sola venta y respuesta ALREADY_PROCESSED.
- Dos ventas simultáneas de la última unidad: una SUCCESS y una SIN_STOCK.
- Vender 2 de 5 unidades: queda saldo 3 y el artículo no pasa a Vendido; vender las últimas lo pasa a Vendido.
- Venta presencial: queda en SIN_ENVIO y no pide datos de envío.
- Cambiar PENDIENTE_ENVIO a ENVIADO a ENTREGADO: ingresos y métricas no cambian.
- Canal `shopify` sin pedido: rechazado; con pedido repetido: no duplica.
- Canal `otro`: no crea fila en `cat_canal`.
- Fecha anterior a la compra: FECHA_INVALIDA con mensaje legible.
- Venta con un canal externo caído: la venta queda registrada y el aviso queda PENDING o FAILED, nunca se pierde.
- Reintento de un aviso DEAD: se envía una sola vez.
- Anulación: devuelve el stock y reencola la sincronización.
- Venta marcada con el flujo antiguo (cambio de estado) y con el nuevo formulario: nunca genera dos ventas.
- Nota con HTML o script: se muestra escapada.
- Intento de POST desde otro origen o sin autenticación: rechazado.

### Fase 7 — Despliegue (esfuerzo: pequeño)

1. Probar el esquema completo sobre una base vacía. Si ya corriste las migraciones en alguna base de desarrollo, recrearla: Flyway rechaza migraciones modificadas después de aplicadas.
2. Dejar que la aplicación aplique las migraciones al arrancar sobre un esquema `tienda` vacío.
3. Desplegar la aplicación y probar con un producto de prueba en cada canal conectado.
4. Primera semana: vigilar avisos DEAD, `verificar_integridad()` y diferencias de reconciliación.
5. Reversión: mientras la base sea nueva, se recrea desde las migraciones.

## 5. Criterios de terminado

- Se puede registrar una venta de cualquier canal normalizado desde el producto, con precio, fecha, cantidad, nota y envío.
- Un doble clic o reintento no duplica la venta ni el ingreso.
- El stock local baja correctamente (parcial o total) y el artículo pasa a Vendido solo al llegar a cero.
- La venta muestra su estado de envío, editable sin volver a registrarla, y una presencial no exige envío.
- Cada catálogo conectado aparece actualizado, o con estado pendiente, error o acción manual, y se puede reintentar o marcar como hecho.
- Las métricas por canal y logística coinciden con las ventas persistidas, sin doble conteo.
- Todo es autenticado, queda en el historial con operador y las pruebas existentes siguen pasando.

## 6. Riesgos y medidas

| Riesgo | Medida |
| --- | --- |
| Venta duplicada | Función única, token de idempotencia, claves únicas existentes |
| Venta de Shopify registrada dos veces (webhook y manual) | Pedido obligatorio en canal `shopify` |
| Canales partidos en las métricas | Lista cerrada de canales y `canal_detalle` para «Otro» |
| Stock distinto entre canales | Outbox con estado por canal, reintentos y reconciliación |
| Canal sin API de escritura | Acción manual registrada y visible |
| Métricas infladas | Logística en columna separada; métricas solo desde `ventas` vigentes |
| Venta con pérdida por error de digitación | Confirmación explícita antes de enviar |
| Venta imposible por reparto de socios incompleto | Mensaje claro y revisión de `exigir_reparto` en la Fase 0 |
| Flyway omite V001 por esquema con tablas antiguas | Aplicar sobre un esquema `tienda` vacío |

## 7. Decisiones pendientes

1. **Ventas históricas:** ¿quedan en SIN_ENVIO, o prefieres marcarlas ENTREGADO? Las que vinieron de Shopify probablemente tuvieron envío.
2. **«Tienda online» manual:** ¿te sirve exigir el número de pedido, o prefieres bloquear ese canal en el formulario porque Shopify ya entra solo?
3. **Meta en Colombia:** si el catálogo es solo de lectura para la API, ¿aceptas que sea siempre una acción manual?
4. **Pérdidas:** ¿permitir con confirmación (propuesto) o bloquear por debajo del costo?
5. **Cancelaciones de envío o devoluciones parciales:** hoy la anulación es de la línea completa; ¿es suficiente?

## 8. Orden sugerido

Fase 0, luego Fase 1 y 2 juntas, Fase 3, Fase 4 y por último 5, 6 y 7. Las fases 4 y 5 pueden ir en paralelo con la 3 una vez estén las vistas en V004.

## 9. Qué deja listo para los chats

- La identidad del operador por transacción (`app.usuario`) y el historial con operador, que luego servirán para saber quién tomó una conversación.
- El patrón de rutas protegidas con comprobación de origen y el renderer separado, reutilizables para la bandeja.
- La venta desde una conversación ya queda prevista: bastará pasar `reserva_id` y `cliente_id` a la misma función.
- No resuelve lo más costoso de los chats: la tabla de mensajes, el modo de atención, el tiempo real y la ventana de 24 horas. Eso sería otro proyecto, con su propio plan.
