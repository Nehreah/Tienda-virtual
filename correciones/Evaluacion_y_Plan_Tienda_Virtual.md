# Tienda Virtual Multicanal — Evaluación técnica y plan de implementación

**Proyecto:** `Tienda-virtual-main` (Kotlin · Ktor · PostgreSQL/Exposed · Flyway · Shopify · Meta · Gemini)
**Fecha:** 10 de octubre de 2026
**Contexto:** el proyecto no tiene datos reales, así que **no hay que migrar nada**. Se pueden editar `V001`–`V004` directamente y recrear la base.

---

## 0. Alcance y límites de esta revisión (léelo primero)

- **Todo sale de leer el código.** No pude compilar ni ejecutar las 206 pruebas: el entorno no tiene Gradle ni acceso a Maven. Cada hallazgo indica el archivo donde lo vi.
- **Dos niveles de certeza:**
  - ✅ **Verificado en el código:** lo leí y el comportamiento se deduce sin ambigüedad.
  - 🔎 **Requiere confirmar** con PostgreSQL real, un navegador o la documentación vigente de Meta/Shopify.
- **Leído en detalle:** `Application.kt`, `ChatRoutes`, `VentasRoutes`, `SociosRoutes`, `InversionRoutes`, `ArticulosRoutes` (crear/editar), `DashboardRoutes`, `DashboardGuard/Auth/OriginGuard/AuthThrottle`, `InboundMessagePipeline`, `ExposedConversationStore`, `OutboxWorker`, `InboundWebhookWorker`, `WebhookRoutes`, `ShopifyCatalogAdapter`, `MetaShopAdapter`, `SocioService`, y en SQL: V001 (socios, reparaciones), V002 (chat) y V003 (cola).
- **Revisado por búsqueda o por encabezados, no línea a línea:** `V004` (ventas), `ExcelV2Parser`, `MetricasService`, `PagosRoutes`, `GeminiLlmAdapter`, `WhatsAppMessagingAdapter`, renderers de métricas e inventario.
- Los tres planes de la carpeta `correciones/` ya cubren parte de esto. Aquí comparo **lo planeado contra lo que existe hoy en el código**.

---

## 1. Veredicto en una página

| Área | Estado | En una frase |
|---|---|---|
| Arquitectura y dominio | 🟢 Muy buena | Módulos limpios (puertos y adaptadores), dinero en `Long` COP, reglas críticas dentro de PostgreSQL con funciones atómicas. |
| Motor de negociación (chatbot) | 🟢 Bueno | L0–L4 con piso en el precio mínimo, jitter corregido (`floorMod`), escalera uniforme, escalamiento a humano. |
| Pipeline del chatbot | 🟡 Funciona, con huecos | Hay una carrera en la compuerta del bot, el primer mensaje de un cliente nuevo probablemente no se guarda y "requiere atención" nunca se enciende. |
| Seguridad de base | 🟢 Buena | Autenticación con comparación en tiempo constante, bloqueo por intentos, control de origen, HMAC en webhooks, falla cerrada. |
| Seguridad de las rutas nuevas | 🔴 Deficiente | **14 consultas SQL armadas con texto del usuario** en chat y ventas. |
| Dashboard (interfaz) | 🔴 Roto en la práctica | La política CSP bloquea htmx y, en producción, también los scripts propios. |
| Sincronización con tiendas | 🔴 No sincroniza hacia afuera | La tabla `publicaciones` nunca se llena; Meta es un esqueleto. Solo entra Shopify → local. |
| Chat en tiempo real desde el dashboard | 🔴 Backend a medias, interfaz inexistente | El SQL y las rutas existen, pero la página `/chat` es un esqueleto y el cliente SSE no recibe eventos. |
| Socios | 🟡 Modelo sólido, sin interfaz usable | La lógica es correcta; la UI está huérfana y sin estilos. |
| Reparaciones | 🔴 Módulo muerto | Hay tabla, trigger y repositorio, pero nada los usa: sin rutas, sin UI, sin Excel. |
| Pruebas / CI | 🟢 Buenas | 206 pruebas, CI con JDK 21; faltan pruebas SQL contra PostgreSQL real. |

**Conclusión:** el **cerebro** (dominio, negociación, SQL transaccional) es de buena calidad. La **cáscara** (dashboard, chat, sincronización saliente) está a medio terminar, y hay un riesgo de seguridad concreto que conviene cerrar antes de exponer nada.

---

## 2. Respuestas a tus preguntas

### 2.1 ¿Qué opino del código?

**Lo bueno ✅**
- Separación en 8 módulos con dependencias hacia adentro. Los puertos (`CatalogPublishingPort`, `MessagingPort`, repositorios) permiten cambiar adaptadores sin tocar el dominio.
- Las reglas de dinero e inventario viven en PostgreSQL: `registrar_venta_linea` (bloqueo, idempotencia, stock con reservas), `fn_repartir` (mayor residuo exacto), triggers de historial y de suma de porcentajes. Eso evita carreras entre instancias.
- El precio mínimo (PM), el costo y el margen no salen a clientes, Shopify, Meta ni a la vitrina pública, y hay pruebas de fuga (`SecurityFugaTest`).
- Webhooks con firma, idempotencia y cola durable (`eventos_entrantes`, `outbox`).
- Los planes previos son rigurosos y honestos sobre lo que no verificaron.

**Lo que preocupa ❌**
- **Brecha entre lo documentado y lo implementado.** El `README` y el `MANUAL_DE_USO` describen un dashboard que sincroniza catálogos y atiende chats. El código tiene los cimientos, pero no el cableado final.
- **Dos fuentes de verdad.** `AppStore` (mapas en memoria) y PostgreSQL guardan productos e inventario. Se cargan una vez al arrancar y los webhooks actualizan ambos. Cualquier edición por otra vía puede dejarlos distintos. 🔎
- **`DashboardRenderer.kt` tiene 1 451 líneas** con HTML, CSS y JS dentro de strings de Kotlin. Y se mezclan dos sistemas de estilo: CSS propio en el dashboard y clases Tailwind en los fragmentos de socios, inventario y chat.
- **Las rutas nuevas ignoran las buenas prácticas del resto.** `ChatRoutes` y `VentasRoutes` usan `transaction { exec("...$variable...") }`, mientras que `SociosRepository` y el resto sí usan Exposed o parámetros.

### 2.2 ¿Las tiendas sincronizan?

**Respuesta corta: solo en una dirección y parcialmente.**

| Flujo | ¿Funciona? | Evidencia |
|---|---|---|
| **Shopify → sistema** (una venta descuenta stock) | ✅ Sí | `WebhookRoutes`: `orders/create` con HMAC, deduplicado por `X-Shopify-Event-Id`, llama a `venderUnidades`. Las devoluciones también. |
| **Sistema → Shopify** (cambio de stock se refleja en Shopify) | ❌ No hoy | `PublicacionesTable` solo aparece en `Tables.kt` y `OutboxWorker`; **nada la llena**. El trigger `fn_encolar_stock` hace `JOIN publicaciones`, así que nunca encola. |
| **Sistema → Meta Catalog** | ❌ No | `MetaShopAdapter`: `publishProduct`, `updateProduct`, `setStock` devuelven `NO_IMPLEMENTADO`. |
| **Entre canales** (anti-sobreventa: vendo en uno, retiro del otro) | ❌ No | `executeRetractionSaga` recibe `AppStore.channelMappings`, que **nunca se llena**. |
| **Cambios de precio / título / estado** | ❌ No | `ShopifyCatalogAdapter.updateProduct` devuelve `Ok` sin hacer nada; el worker trata cualquier tipo como `setStock`. |
| **Detección de diferencias** (reconciliación) | ❌ No | `fetchRemoteProducts()` devuelve lista vacía en ambos adaptadores. |

Otros hallazgos en la cola:
- `ArticuloLifecycleService` publica con canal `"ALL"`, pero `OutboxWorker` busca `publicaciones` por `(sku, canal='ALL')`, que no existe: esos avisos terminan siempre en `SKIPPED_NOT_PUBLISHED`. ✅
- Si el servidor se cae con una fila en `SENDING` (outbox) o `PROCESSING` (webhooks entrantes), esa fila **no se recupera** nunca. ✅
- 🔎 `ShopifyCatalogAdapter` usa `apiVersion = "2024-10"` por defecto. Confirma en la documentación de Shopify qué versiones siguen vigentes y qué exige hoy la mutación `inventorySetQuantities`.
- **Marketplace de Facebook no tiene API de escritura.** El diseño ya contempla "acción manual" (`SKIPPED_READ_ONLY`, `accion_manual_*`): es lo correcto, solo falta la interfaz.
- Bien pensado: `CHANNEL_WRITE_ENABLED=false` por defecto. Mantenlo así hasta probar con una tienda de pruebas.

### 2.3 ¿Hay buena seguridad?

**Base sólida ✅**
- `DashboardAuth`: Basic con `MessageDigest.isEqual` (tiempo constante); en producción exige usuario y clave de al menos 12 caracteres, o la app no arranca.
- `AuthThrottle`: bloqueo progresivo por IP, memoria acotada.
- `OriginGuard`: defensa CSRF por `Origin`/`Referer`, falla cerrada, probada.
- Webhooks de Meta: firma `X-Hub-Signature-256` obligatoria (503 si falta `META_APP_SECRET`). Shopify: HMAC. Ambos con tope de 1 MB.
- `fn_asegurar_rls`, anonimización de clientes y política de privacidad: buen criterio con datos personales.

**Fallos reales ❌**

| ID | Hallazgo | Gravedad |
|---|---|---|
| **S-1** | **Inyección SQL en 14 puntos.** `ChatRoutes.kt` (9) y `VentasRoutes.kt` (5) interpolan `convId`, `texto`, `nuevoEstado`, `nota`, `canalDetalle`, `orderId`, `key`, `canal`, `estadoLogistico`, `guia`, `transportadora`, `motivo`, `eventId` y un JSON completo dentro de `exec("SELECT ...")`. Requiere sesión autenticada, pero un operador (o un XSS futuro) puede ejecutar SQL arbitrario. **Además es un error funcional:** cualquier mensaje con un apóstrofe (`¿cuál's?`, `D'Angelo`) rompe el envío. ✅ | 🔴 Alta |
| **S-2** | **CSP inconsistente con la página.** `script-src` permite `'self'` y `cdn.tailwindcss.com`, pero el HTML carga htmx desde `unpkg.com` (no permitido) y tiene `<script>` en línea. En producción tampoco hay `'unsafe-inline'`. Resultado: htmx nunca carga y, en producción, el selector de pestañas ni arranca. ✅ (🔎 confirmar en navegador) | 🔴 Alta (funcional) |
| **S-3** | **Auditoría incompleta.** `DatabaseFactory.tx(origen, usuario)` fija `app.usuario`, pero las rutas de chat y ventas usan `transaction` crudo y pasan `"dashboard"` fijo. El historial no sabe quién hizo cada cambio. ✅ | 🟠 Media |
| **S-4** | **Token CSRF muerto.** Se inyecta en formularios y cabeceras htmx, pero ninguna ruta lo valida y `GET /` ni siquiera lo genera (`csrfToken = ""`). La defensa real es `OriginGuard`, que es suficiente; el código da una falsa impresión de doble protección. ✅ | 🟡 Baja |
| **S-5** | **Una sola credencial compartida**, sin HTTPS forzado ni HSTS, sin rotación. Aceptable en piloto; insuficiente si hay más de un operador. | 🟡 Baja |
| **S-6** | **`docker-compose.yml` sigue roto:** `APP_ENV=development` (el parser solo acepta `local` o `production`), faltan las variables de Meta, Shopify y Gemini, el puerto 5432 queda publicado con contraseña por defecto y la URL no trae `sslmode`. ✅ | 🟠 Media |

### 2.4 ¿El chatbot funciona bien?

**El núcleo, sí. El perímetro, con arreglos.**

✅ **Funciona:** detección de intención (reglas locales o Gemini con cadena de modelos y respaldo), negociación L0–L4 que nunca baja del PM, reserva con TTL de 15 min, escalamiento a humano al pedir asesor o enviar comprobante, idempotencia por mensaje, cola durable.

❌ **Problemas encontrados:**

| ID | Hallazgo | Gravedad |
|---|---|---|
| **B-1** | **La compuerta "el bot calla si el humano tiene el control" no es atómica.** `registrar_mensaje_bot` hace `SELECT atencion` y luego `INSERT`, sin bloquear la fila. Si el operador pulsa "tomar" entre ambos pasos, el bot envía igual. El plan decía "en la misma sentencia"; la implementación no lo cumple. Arreglo: `SELECT ... FOR SHARE` o `INSERT ... SELECT ... WHERE atencion='BOT'`. ✅ | 🟠 Alta |
| **B-2** | **`answer()` guarda el estado (incluida la reserva) antes de pasar por la compuerta.** Si la compuerta descarta el mensaje, el stock queda reservado y el cliente nunca recibe el aviso. ✅ | 🟠 Media |
| **B-3** | **El primer mensaje de un cliente nuevo probablemente no se guarda.** `handle()` llama `registrarMensajeCliente` antes de que exista la fila en `conversaciones`, y `conversacion_mensajes` tiene FK hacia ella; la excepción se captura y devuelve `null`. 🔎 Confirmar con PostgreSQL. | 🟠 Media |
| **B-4** | **`requiere_atencion` nunca se enciende.** No existe función SQL ni código que lo ponga en `true`; `escalate()` solo cambia el estado a `ESCALATED_HUMAN`. Los filtros "Requiere atención" del plan no pueden funcionar. ✅ | 🟠 Alta |
| **B-5** | **Nadie se entera de una escalación.** Sin SSE funcional ni notificación, un cliente que pidió asesor espera sin que suene nada. ✅ | 🟠 Alta |
| **B-6** | **Comprobantes de pago a ciegas.** Una imagen o audio llega como texto vacío; se pierde el id del medio y el operador no puede ver el comprobante antes de confirmar el pago. ✅ | 🟠 Media |
| **B-7** | `ExposedConversationStore.toConversation()` carga los **primeros** 50 mensajes (`ORDER BY creado_en ASC LIMIT 50`), no los últimos 50; además `save()` sigue reescribiendo el JSON `mensajes`, duplicando la fuente de verdad. | 🟡 Baja |
| **B-8** | Si el worker falla después de enviar la respuesta pero antes de marcar `DONE`, el reintento puede **enviar la respuesta dos veces**. | 🟡 Baja |
| **B-9** | En modo BD, `InboundWebhookWorker` no llama `recordDeal`, así que la pestaña "Mensajería" (que lee `AppStore.chatDeals` en memoria) queda vacía para canales reales. ✅ | 🟠 Media |

### 2.5 ¿Hay chat en tiempo real desde el dashboard?

**No utilizable hoy.** Lo que existe y lo que falta:

| Pieza | Estado |
|---|---|
| Esquema SQL (`atencion`, `conversacion_mensajes`, `chat_eventos`, `tomar/devolver`, `v_bandeja`) | ✅ Implementado en V002 |
| Rutas `tomar`, `devolver`, `enviar`, `bandeja`, `mensajes`, `stream` | 🟡 Existen, pero con SQL inyectable (S-1) y sin `DatabaseFactory.tx` |
| Página `/chat` | ❌ `ChatRenderer` devuelve un fragmento sin `<head>`, sin CSS, sin htmx; las listas dicen "Cargado dinámicamente" y **nada las carga**. No aparece en la navegación. |
| Cliente SSE | ❌ Usa `source.onmessage`, pero el servidor envía eventos con nombre (`event: mensaje`). Esos eventos **no llegan** a `onmessage`; solo llegan los `ping`, que se ignoran. |
| Botones de filtro (Todas / Requiere atención / En mi control) | ❌ Decorativos, sin acción |
| Panel de conversación, caja de texto, reintentar, ventana de 24 h | ❌ No existen en la interfaz |
| Control de conexiones SSE | ❌ Bucle `while(true)` sin límite ni chequeo de cancelación |
| Contador de no leídos, sonido, título de pestaña | ❌ No existen |
| Plantillas de WhatsApp fuera de ventana de 24 h | ❌ No soportado |

### 2.6 ¿Cómo funciona la mecánica de socios?

El modelo es correcto y está bien protegido en la base:

1. **Socios dinámicos** (`tienda.socio`): empiezan en cero, máximo 12 activos, nombre único entre activos.
2. **Porcentaje de inversión por artículo** (`inventario_inversion`): cada socio tiene un `pct` por artículo. El trigger diferido `trg_inversion_suma` impide que la suma pase de 100. Un artículo vendido no admite cambios y un socio archivado no recibe porcentajes nuevos.
3. **Al vender**, `registrar_venta_linea` reparte con `fn_repartir` (método del mayor residuo: los montos siempre suman el total exacto) y **congela el costo**.
   - Si los porcentajes suman menos de 100, el reparto se **normaliza entre los socios asignados**. Ejemplo: A 30 % y B 30 % reciben 50/50, no 30/30 con 40 % perdido. Conviene que lo sepas.
   - Sin socios, la venta se registra igual y queda en `v_ventas_sin_reparto`; se corrige después con `asignar_reparto_venta`.
   - `config.exigir_reparto = 1` bloquea ventas sin reparto completo. Hoy está en 0.
4. **Retirar un socio** (`retirar_socio`): si nunca participó, se elimina; si tiene ventas o inversiones, se **archiva** y se conserva el historial. Reactivar está soportado.
5. **Métricas por socio:** `v_resumen_mensual_socio`.

**Lo que falta:**
- No hay forma de **renombrar** desde la interfaz (el servicio lo soporta, no hay ruta).
- No hay **liquidación**: el sistema calcula cuánto le corresponde a cada socio, pero no registra pagos ni saldo pendiente. 🔎 Decisión de negocio (ver §6).
- El modelo es "% por artículo", no "capital aportado en pesos". Si un socio pone dinero en distintos momentos, hoy no queda registrado.

### 2.7 ¿Debería haber algo en el dashboard para agregar socios?

**Sí, y ya existe a medias.** `SociosRenderer` tiene tabla, modal "Agregar socio" y modal de impacto al retirar. Pero:
- No está en la navegación (solo `#/inicio`, `#/apis`, `#/chats`, `#/catalogo`, `#/metricas`, `#/automatizacion`).
- Sus fragmentos usan **clases Tailwind** que la página no carga (el dashboard usa CSS propio).
- Apuntan a `#main-content`, que **no existe** en `DashboardRenderer`.
- htmx está bloqueado por la CSP (S-2).

Resultado: aunque llamaras a `/api/socios`, obtendrías HTML sin estilos que no se podría insertar en ningún sitio.

### 2.8 Pestañas de Socios, Inventario y Reparaciones, cada una editable

**Hoy no existen.**

| Pestaña | Estado actual |
|---|---|
| **Inventario** | La pestaña "Catálogo" es de **solo lectura** (10 columnas, sin botón de crear, editar ni archivar), aunque la API `/api/articulos` existe. |
| **Socios** | Sin pestaña (ver 2.7). |
| **Reparaciones** | Inexistente. Hay tabla (`reparaciones`), modelo, trigger que recalcula costo y PM, y `ExposedReparacionRepository`, pero **nadie lo instancia** en `Application.kt`, no hay rutas y el parser de Excel no lee ese dato. Consecuencia práctica: `precio_reparacion` solo se puede cargar por SQL, así que el costo total y el PM **no incluyen la reparación**. Para artículos que la necesitan, el bot podría aceptar ofertas por debajo del punto de equilibrio real. |

**Además, un riesgo al editar (A-1):** `PUT /api/articulos/{key}` es un **reemplazo completo con valores por defecto**: si envías solo `item`, el servidor pone `precioCompra = 0`, `cantidad = 1` y `estado = Publicado`. Editar un campo puede corromper los demás. Hay que convertirlo en actualización parcial (PATCH) antes de enlazarlo a una tabla editable. ✅

---

## 3. Hallazgos adicionales relevantes

| ID | Hallazgo | Gravedad |
|---|---|---|
| X-1 | La pestaña "Mensajería" del dashboard muestra un **registro en memoria** (`AppStore.chatDeals`, máx. 100), que se pierde al reiniciar y no es la bandeja real. | 🟠 |
| X-2 | `GET /chat` y `GET /ventas` existen pero no están enlazadas y devuelven esqueletos ("Cargado dinámicamente"). `/ventas` tiene el mismo problema que `/chat`. | 🟠 |
| X-3 | Las rutas de ventas y chat devuelven JSON crudo (`"raw": "{...}"`) y deciden éxito con `contains("SUCCESS")` sobre texto. Frágil. | 🟡 |
| X-4 | Reserva de stock duplicada: el pipeline suma reservas en `AppStore.inventory` (memoria) **y** en la tabla `reservas`. Debe haber una sola fuente. 🔎 | 🟡 |
| X-5 | El pipeline llama a Gemini fuera del candado de conversación (ya evitado cuando el chat está en modo humano). Sin impacto grave. | ⚪ |

---

## 4. Plan de implementación

**Esfuerzo:** **S** ≤ medio día · **M** 1–2 días · **L** 3–5 días.
**Regla de trabajo (la tuya):** en cada fase se entregan **solo los archivos modificados completos**, un **manual de cambios** (qué parte exacta cambió y por qué) y el **diagrama de clases actualizado en archivo aparte**.
**Base de datos:** como no hay datos reales, se editan `V001`–`V004` y se recrea el esquema `tienda` vacío (Flyway valida checksums; `baselineOnMigrate(true)` omitiría V001 si el esquema ya trae tablas viejas).

### Orden recomendado

```
F0 Línea base ─> F1 Cerrar riesgos ─> F2 Base del dashboard ─> F3 Pestañas (Socios/Inventario/Reparaciones)
                                                                 │
                                                  ┌──────────────┴──────────────┐
                                             F4 Chat en tiempo real      F5 Sincronización de tiendas
                                                  └──────────────┬──────────────┘
                                                            F6 Calidad y operación
```

F4 y F5 son independientes entre sí. Recomiendo F4 primero (es lo que más se nota en la operación diaria); F5 requiere credenciales y una tienda de pruebas.

---

### Fase 0 — Línea base (S–M)

**Objetivo:** saber qué está verde y qué no antes de cambiar nada.

- Compilar y correr `./gradlew check test` sin modificaciones; anotar fallos preexistentes.
- Agregar al CI un job con **PostgreSQL 16 real** que aplique `V001`–`V004` y ejecute `db/pruebas_fase2.sql` (su encabezado dice que nunca se ejecutó). H2 no soporta PL/pgSQL.
- Crear un esquema `tienda` vacío para desarrollo.
- Confirmar las dudas 🔎 de este informe: B-3 (primer mensaje), S-2 (CSP en navegador), X-4 (doble reserva).

**Aceptación:** pruebas Kotlin y SQL corriendo en CI, con lista conocida de fallos.

---

### Fase 1 — Cerrar riesgos (M)

**Objetivo:** que lo ya construido sea seguro y no corrompa datos.

| Tarea | Archivos | Detalle |
|---|---|---|
| **1.1** Eliminar toda la interpolación SQL | `ChatRoutes.kt`, `VentasRoutes.kt` | Usar `exec(sql, args = listOf(TextColumnType() to ...))` como ya hace `ExposedConversationStore`, o mover a repositorios. Validar listas cerradas: `canal`, `estadoLogistico`, `nuevoEstado` (solo estados de `ConversationState`). El JSON de reparto se recibe tipado, no como texto SQL. |
| **1.2** Auditoría real | rutas de chat y ventas | Reemplazar `transaction` por `DatabaseFactory.tx(origen = "dashboard", usuario = <usuario autenticado>)`. Exponer el usuario desde `DashboardAuth` tras validar. |
| **1.3** Respuestas tipadas | rutas de ventas | Parsear el JSON de la función (`kotlinx.serialization`) en vez de `contains("SUCCESS")`; mapear cada estado a un mensaje claro (SIN_STOCK, FECHA_INVALIDA, etc.) como ya define el plan de ventas. |
| **1.4** Editar sin corromper | `ArticulosRoutes.kt`, `ArticuloLifecycleService` | `PUT` → `PATCH` parcial: solo se actualizan los campos presentes. Cambio de stock por endpoint dedicado con motivo, no por edición libre. |
| **1.5** Compuerta del bot atómica | `V002` (`registrar_mensaje_bot`) | `SELECT atencion ... FOR SHARE` (o `INSERT ... SELECT ... WHERE atencion='BOT'`). Prueba de carrera real en PostgreSQL. |
| **1.6** Primer mensaje | `V002` (`registrar_mensaje_cliente`) | Crear la conversación si no existe (upsert) dentro de la función, antes del `INSERT`. |
| **1.7** `docker-compose.yml` | `docker-compose.yml`, `Dockerfile` | `APP_ENV=production`, `sslmode` en la URL, pasar todas las variables (Meta, Shopify, WhatsApp, Gemini, `TRUST_PROXY`, `CORS_ALLOWED_HOST`, `PAYMENT_INSTRUCTIONS`), no publicar 5432. |
| **1.8** CSRF honesto | `DashboardRenderer`, `DashboardRoutes` | O se valida el token de verdad, o se elimina el código muerto y se documenta que la defensa es `OriginGuard`. Recomiendo lo segundo. |

**Pruebas:** un mensaje con `'` y con `'); DROP TABLE ...` se guarda como texto; dos hilos (tomar vs. respuesta del bot) nunca producen un envío posterior al `tomar`; editar solo `ubicacion` no cambia precio ni stock.
**Aceptación:** `grep 'exec("' ... | grep '\$'` devuelve solo constantes numéricas seguras; el historial muestra el operador real.

---

### Fase 2 — Base del dashboard (M)

**Objetivo:** una interfaz que cargue y funcione, sobre la cual montar las pestañas.

- **Recursos propios:** servir htmx y el JS del dashboard desde `/static/` (ruta nueva) con `Content-Security-Policy: script-src 'self'` **sin** `'unsafe-inline'`. Mueve los `<script>` en línea a archivos estáticos.
- **Un solo sistema de estilos:** adoptar el CSS propio del dashboard (ya soporta modo claro/oscuro) y reescribir los fragmentos de Tailwind. Eliminar `cdn.tailwindcss.com` de la CSP.
- **Carcasa con pestañas cargadas bajo demanda:** cada pestaña pide su fragmento (`hx-get="/ui/socios"`, etc.) al activarse. Navegación nueva: Inicio · Mensajería · Inventario · Socios · Reparaciones · Ventas · Sincronización · Métricas · APIs · Automatización.
- **Componentes reutilizables** (en archivos `dashboard/components/`): tabla con edición en línea (fila → formulario → `PUT/PATCH` → fila), modal de confirmación, avisos (toast), estado vacío, indicador de carga.
- **Partir `DashboardRenderer.kt`** por pestañas (ya hay renderers para ventas, métricas, inventario y socios; falta moverlos a la carcasa).
- Respuestas de error de htmx visibles (`htmx:responseError` → aviso al usuario).

**Aceptación:** con `APP_ENV=production`, todas las pestañas navegan y los botones htmx responden; sin errores de CSP en la consola del navegador.

---

### Fase 3 — Pestañas Socios, Inventario y Reparaciones (L)

**Objetivo:** gestionar el negocio desde el dashboard, con edición real.

#### 3.A Pestaña **Socios**

- **Tabla:** nombre, estado, artículos con participación, inversión estimada, ventas, ganancia acumulada (de `v_resumen_mensual_socio`).
- **Acciones:** **Agregar** (modal), **Renombrar** (edición en línea; nueva ruta `PUT /api/socios/{id}` usando `SocioService.renombrar`), **Retirar** (modal de impacto ya existente), **Reactivar**, ver archivados.
- **Avisos:** cuántos artículos activos no tienen reparto completo (`v_reparto_pendiente`) y enlace directo a corregirlos.
- **Duplicado y límite de 12** con mensaje claro (el servicio ya valida).

#### 3.B Pestaña **Inventario**

- **Tabla** con búsqueda, filtros (estado, tipo, marca, "sin reparto completo", "requiere reparación") y paginación en servidor.
- **Columnas:** key, artículo, estado, cantidad, costo total, PP, PM, ganancia/ROI, ubicación, canales publicados.
- **Edición en línea** (PATCH parcial de la Fase 1.4): nombre, descripción, marca, tipo, condición, ubicación, precio de compra, precio publicado, estado.
- **Alta de artículo** (modal) y **archivar / restaurar**.
- **Ajuste de stock** como acción separada con motivo (queda en historial).
- **Matriz de socios por artículo:** campos de % por socio, botón "repartir en partes iguales" (ya existe en `InversionRoutes`), suma visible con aviso si ≠ 100.
- **Acción "Registrar venta"** por fila (enlaza con `registrar_venta_manual`).
- **Importar/Exportar Excel** (ya existe `ExcelRoutes`).

#### 3.C Pestaña **Reparaciones**

- **Cablear** `ExposedReparacionRepository` en `Application.kt` y crear `ReparacionesRoutes.kt`:
  `GET /ui/reparaciones`, `PUT /api/reparaciones/{key}` (precio y detalles), con alta al marcar `requiere_reparacion`.
- **Tabla:** artículo, precio de compra, **precio de reparación (editable)**, costo total, **PM resultante**, detalles (editable), estado.
- **Efecto visible:** al guardar, la fila muestra el costo total y el PM recalculados por el trigger (V001) y se refleja en el motor de negociación.
- **Excel:** agregar la columna/hoja de reparación al parser y al exportador para que no se pierda en una reimportación.
- Aviso cuando un artículo con `requiere_reparacion` tiene precio de reparación 0.

**Pruebas:** edición concurrente de dos filas; porcentajes que suman >100 rechazados con mensaje legible; archivar un socio con ventas conserva el historial; cambiar el precio de reparación cambia el PM y la respuesta del bot; todo escapado contra HTML.
**Aceptación:** las tres pestañas listan, crean, editan y (donde aplica) archivan sin recargar la página, con el operador registrado en el historial.

---

### Fase 4 — Chat en tiempo real y control humano (L)

**Objetivo:** ver conversaciones en vivo, tomar el control, escribir y devolver al bot.

**SQL (editando V002):**
- `tienda.marcar_requiere_atencion(conv_id, motivo)`; `escalate()` del pipeline la invoca (B-4). Se apaga al tomar o responder.
- `v_bandeja`: agregar mensajes sin responder y contador de no leídos; última actividad.
- `tomar_conversacion`: validar que exista (hoy devuelve `HUMANO` aunque no haya fila).
- `devolver_conversacion`: validar `p_nuevo_estado` contra los estados permitidos.
- Mensaje del cliente en `answer()` vs. compuerta: guardar el estado de venta **después** de confirmar el envío, o liberar la reserva si se descarta (B-2).

**Backend:**
- **SSE correcto:** enviar siempre `event: message` con JSON `{convId, tipo, id}` (o registrar un `addEventListener` por cada tipo en el cliente). Bucle `while (isActive)`, latido cada 15 s, `Last-Event-ID`, evento `reset` si el cursor es demasiado viejo, **límite de conexiones por sesión**, cierre ordenado.
- `GET /ui/chat/bandeja` y `GET /ui/chat/{id}` devuelven fragmentos HTML (los datos del cliente se escapan; el JS usa `textContent`).
- Escalaciones del pipeline encolan un evento `atencion`.
- **Ventana de 24 h:** cálculo exacto en minutos, bloqueo con aviso (hoy `null` permite enviar). Soporte de plantillas aprobadas de WhatsApp si la cuenta las tiene 🔎.
- Reintento de mensajes en `FALLO` reutilizando la misma fila.

**Interfaz (pestaña "Mensajería"):**
- Bandeja con canal, cliente, último mensaje, hora, no leídos y etiqueta (Bot atendiendo / Atención humana / **Requiere atención**). Filtros funcionales.
- Panel de conversación con historial, caja de texto (habilitada solo en modo humano), **Tomar la palabra** / **Devolver al bot**, estados de envío (enviando / enviado / fallo + reintentar).
- Panel lateral: producto activo, precio acordado, reserva, estado de negociación, botones **Confirmar pago** y **Registrar venta**.
- **Alertas:** contador en el título de la pestaña, sonido opcional, aviso de "reconectando…".
- La pestaña actual basada en `AppStore.chatDeals` se reemplaza por la bandeja real.

**Segunda etapa:** ver comprobantes de pago (descargar el medio por la API de Meta, B-6), estados "entregado/leído", varios operadores.

**Pruebas (PostgreSQL real):** mensaje nuevo aparece sin recargar y sobrevive a una reconexión; tras "tomar" ninguna respuesta del bot sale; carrera tomar-vs-bot; `save()` con copia vieja no revierte `atencion`; devolver con estado `ESCALATED_HUMAN` no deja al bot mudo; webhook repetido no duplica; HTML en un mensaje se ve como texto; 6.º stream simultáneo rechazado.
**Aceptación:** un mensaje de WhatsApp de prueba aparece en el dashboard en menos de ~3 s, puedes tomar el chat, responder, y devolverlo al bot con contexto.

---

### Fase 5 — Sincronización real de tiendas (L)

**Objetivo:** que el stock y las ventas se reflejen entre el sistema y los canales, o que lo pendiente quede visible.

1. **Poblar `publicaciones`.** Decisión previa (ver §6): *vincular productos que ya existen en Shopify por SKU* (recomendado primero) o *publicarlos desde el sistema* (`publishProduct`, hoy sin usar). Guardar `external_id` y `external_inventory_id` (lo exige `setStock`).
2. **Corregir la cola:** el canal `"ALL"` debe expandirse a un aviso por canal publicado; recuperación de filas `SENDING`/`PROCESSING` atascadas (marca de tiempo + reintento tras N minutos); parar de tratar `product_updated` como `setStock`.
3. **Shopify:** implementar `updateProduct` y `fetchRemoteProducts`; manejar `inventory_levels/update` registrando **deriva** para decisión humana (no sobrescribir). 🔎 Validar `apiVersion` y los requisitos actuales de `inventorySetQuantities`.
4. **Meta (Colombia, `CATALOG_ONLY`) y Marketplace:** sin API de escritura, convertir cada aviso en **tarea manual** visible ("actualiza este catálogo a mano") con botón **Hecho** que registra operador y hora (las columnas y la función `resolver_accion_manual` ya existen).
5. **Anti-sobreventa:** alimentar `channelMappings` desde `publicaciones` (o eliminar el mapa duplicado y usar la tabla).
6. **Panel "Sincronización"** (usa `v_venta_sync` y `v_acciones_manuales_pendientes`): stock objetivo vs. publicado por canal, estado, intentos, último error, **Reintentar** (`reintentar_outbox`), **Comprobar diferencias** (reconciliación), contador de avisos `DEAD` y acciones pendientes.
7. **Despliegue prudente:** `CHANNEL_WRITE_ENABLED=false` hasta que pase en una tienda de pruebas; primero un producto, luego el catálogo.

**Pruebas:** venta local → aviso a Shopify una sola vez; Shopify caído → aviso `FAILED` y luego reintento sin duplicar; caída del servidor con fila `SENDING` → se recupera; venta en Shopify → no se re-avisa a Shopify (anti-bucle por `app.canal_origen`).
**Aceptación:** cada producto muestra, por canal, "actualizado", "pendiente", "error" o "acción manual"; ninguna venta local depende de que el canal externo responda.

---

### Fase 6 — Calidad y operación (M)

- **Una sola fuente de verdad:** retirar `AppStore.products/inventory` como caché de escritura (leer de PostgreSQL con caché de lectura corta) o centralizar todo en `CatalogState`. Eliminar la reserva duplicada en memoria (X-4).
- **Métricas:** filtros por canal, fecha y estado logístico (ya hay vistas), definiciones explícitas en el manual.
- **Operación:** `/health` y `/ready`, logs sin datos personales, copias de seguridad de PostgreSQL, retención de mensajes (borrado programado según la política de privacidad), HTTPS/HSTS en el proxy, rotación de `DASHBOARD_PASSWORD`.
- **Multioperador (opcional):** usuarios individuales con rol, para saber quién tomó cada chat.
- **Documentación:** actualizar `README` y `MANUAL_DE_USO` para que digan exactamente lo que el sistema hace.

---

## 5. Riesgos del plan

| Riesgo | Medida |
|---|---|
| Rehacer el dashboard rompe lo que sí funciona | Fase 0 con línea base; migrar pestaña por pestaña, sin tocar el motor de negociación ni las funciones de venta. |
| Tests SQL no corren en H2 | Job de CI con PostgreSQL real desde la Fase 0. |
| Edición en línea corrompe datos | PATCH parcial (1.4) **antes** de la Fase 3; validación en servidor y en la base. |
| APIs de Meta/Shopify cambian | Marcadas 🔎; validar con la documentación vigente antes de la Fase 5. |
| Bot y operador responden a la vez | Compuerta atómica (1.5) + prueba de carrera. |
| SSE bloqueado por proxy | `X-Accel-Buffering: no`, latidos y plan B de sondeo corto del mismo endpoint. |
| Un operador olvida un chat en modo humano | Aviso por inactividad; sin devolución silenciosa. |

---

## 6. Decisiones que necesito de ti

1. **Socios:** ¿basta el modelo "% por artículo", o necesitas **registrar aportes de capital en pesos y liquidar** a cada socio (saldo, pagos)? Cambia el esquema.
2. **Operadores:** ¿una sola persona, o varias con usuario propio (afecta auditoría y asignación de chats)?
3. **Shopify:** ¿ya tienes tienda con productos? ¿Vinculamos por SKU lo existente, o prefieres publicar desde el sistema?
4. **Meta y Marketplace:** ¿aceptas que sean siempre **acción manual** con recordatorio en el dashboard?
5. **Comprobantes de pago:** ¿quieres verlos dentro del chat (segunda etapa de la Fase 4)?
6. **Retención de mensajes:** ¿cuánto tiempo guardarlos antes de borrarlos?
7. **Reparto incompleto:** ¿activar `exigir_reparto = 1` (no se vende sin socios al 100 %) o dejarlo flexible?
8. **Interfaz:** ¿mantenemos HTML en servidor + htmx (recomendado: menos piezas, ya está en el proyecto) o prefieres una SPA?

---

## 7. Resumen de prioridades

| Prioridad | Qué | Fase |
|---|---|---|
| 🔴 Ya | Inyección SQL, edición que corrompe, compose roto | F1 |
| 🔴 Ya | Dashboard que no carga (CSP) | F2 |
| 🟠 Siguiente | Pestañas Socios / Inventario / Reparaciones editables | F3 |
| 🟠 Siguiente | Chat en vivo + "requiere atención" + notificaciones | F4 |
| 🟡 Después | Sincronización saliente a Shopify/Meta y panel de sync | F5 |
| ⚪ Continuo | Una fuente de verdad, operación y documentación | F6 |
