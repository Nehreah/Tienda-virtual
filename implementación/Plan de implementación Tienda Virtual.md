Contenido

- [Resumen](#resumen)
- [Fase 0 · Compilar](#f0)
- [Fase 1 · Seguridad](#f1)
- [Manual de la clave](#clave)
- [Fase 2 · Datos y ventas](#f2)
- [Fase 3 · Negociación](#f3)
- [Fase 4 · Robustez](#f4)
- [Fase 5 · Fuente única](#f5)
- [Fase 6 · Después](#f6)
- [Backlog menor](#backlog)
- [Riesgos y orden](#riesgos)

Tienda Virtual Multicanal v5.3.0 · Kotlin / Ktor / Postgres

# Plan de implementación

Ordena en seis fases las correcciones de la revisión de seguridad y de bugs. Las fases 0 a 2 deben estar terminadas antes de conectar clientes reales por WhatsApp. Las estimaciones suponen una persona a tiempo completo.

**Fases 0–2 · 8 a 11 días**Compila, cierra el acceso, carga datos bien. Bloquea el lanzamiento.

**Fases 3–4 · 6 a 8 días**Negociación que no filtra el PM y endurecimiento general.

**Fases 5–6 · 3 a 5 semanas**Base de datos como fuente única, pago, Gemini real, CI y Meta Shop.

### Cómo está organizado el código

Módulos Gradle: `shared-kernel`, `commerce` (negociación, saga, finanzas), `messaging`, `infra` (config, Postgres, Excel, Gemini), `adapters-channels` (Meta/WhatsApp), `adapters-shopify`, `adapters-meta` y `app` (Ktor, tablero, pipeline). Casi todas las rutas y el estado en memoria (`AppStore`) viven en `app/.../Application.kt` (612 líneas), por eso la mayoría de cambios de las fases 1 y 2 tocan ese archivo.

Fase 0 · 0,5 a 1 día

## Que el proyecto compile y las pruebas corran

Requisito de todo lo demás

No se puede verificar ninguna corrección sin una línea base verde. El wrapper usa Gradle 9.7.1 y el plugin de Kotlin es 1.9.22, combinación que probablemente no compila.

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 0.1 | Ejecutar `./gradlew build` y anotar el error exacto. Si es la incompatibilidad Kotlin/Gradle, fijar el wrapper en una versión 8.x que funcione con Kotlin 1.9.22 (`gradle/wrapper/gradle-wrapper.properties`). | `./gradlew build` termina sin errores. |
| 0.2 | Ejecutar `./gradlew test` y registrar qué pruebas fallan antes de tocar nada (hay pruebas en commerce, infra, app, adapters y shared-kernel). | Lista de pruebas rojas guardada. Cada una se corrige o se justifica. |
| 0.3 | Quitar la ruta fija `/home/nehreah/...` de `start.sh`. Usar solo `./gradlew` y avisar si falta Java. | `start.sh` corre en otra máquina sin editar. |
| 0.4 | Crear una rama de trabajo y confirmar la línea base en git. Cada tarea siguiente va en su propio commit. | Historial limpio, un commit por ID de tarea. |

Fase 1 · 3 a 4 días

## Cerrar el acceso: fallar cerrado por defecto

CríticoArchivos: `AppConfig.kt`, `DashboardAuth.kt`, `Application.kt`, `MetaWebhookReceiver.kt`, `start.sh`

Regla de diseño: si falta un secreto, la app no se abre al público. Se agrega una variable `APP_ENV` con valores `local` (por defecto) y `production`.

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 1.1 | **Clave del tablero obligatoria.**    En `production`, si `DASHBOARD_PASSWORD` está vacía o tiene menos de 12 caracteres, la app se niega a arrancar con un mensaje claro.   En `local` sin clave, escuchar solo en `127.0.0.1` y registrar un aviso. Nunca en `0.0.0.0`.   Proteger igual `/api/excel/export`, `/api/excel/upload` y `/api/sim/chat`.   Escribir el manual de la clave (sección siguiente) y enlazarlo desde el README. | Prueba en `DashboardAuthTest`: sin clave en producción la configuración lanza error. Con la app local sin clave, un `curl` desde otra IP no conecta. |
| 1.2 | **Firma de Meta siempre obligatoria.** Eliminar `REQUIRE_META_SIGNATURE` como opción. Si falta `META_APP_SECRET`, `POST /webhooks/meta` responde 503. Rechazar con 401 toda petición sin `X-Hub-Signature-256` válida. En `verifyWebhookChallenge` rechazar token vacío en ambos lados. | Pruebas en `MetaWebhookReceiverTest`: sin firma, firma mala, secreto vacío y token vacío fallan; firma buena pasa. |
| 1.3 | **CORS cerrado.** Quitar `anyHost()`. Sin `CORS_ALLOWED_HOST` no se instala CORS (solo mismo origen). El tablero no necesita CORS. | Una petición con `Origin: https://otro.com` no recibe cabeceras `Access-Control-Allow-*`. |
| 1.4 | **Defensa contra CSRF.** Opción mínima: en todo POST del tablero (`/api/excel/upload`, `/api/sim/chat`) exigir que `Origin` o `Referer` coincida con el host propio. Opción completa: reemplazar Basic por cookie de sesión `SameSite=Strict; HttpOnly; Secure` con token CSRF en el formulario. Empezar por la mínima y dejar la completa para la fase 4. | POST con `Origin` ajeno responde 403 aunque lleve credenciales válidas. |
| 1.5 | **Secretos aleatorios al crear la configuración.** `start.sh` deja de copiar el `.example` tal cual: genera con `openssl rand` el verify token y la clave del tablero y los escribe en `local.properties`. `AppConfig` rechaza valores placeholder (`verify_token_secret_123`, `shpat_xxx`, `change_me`) y no marca esas integraciones como configuradas. | Tras la primera ejecución `local.properties` no contiene ningún valor del `.example`. El tablero muestra la integración como "sin configurar" mientras haya placeholders. |
| 1.6 | **Fuerza bruta.** Limitar intentos fallidos de autenticación por IP (por ejemplo 5 en 5 minutos, luego 429 con espera creciente). | El sexto intento fallido seguido recibe 429 aunque la clave sea correcta hasta que pasa la espera. |

### Cierre de fase

- Con la app desplegada y sin ninguna variable, no responde a internet.
- Un navegador abierto en otra pestaña no puede leer el tablero ni subir un Excel.
- Un POST falso al webhook de Meta no crea reservas ni envía mensajes.

Entregable de 1.1 · archivo `docs/MANUAL_CLAVE_TABLERO.md`

## Manual: cómo crear, cambiar y proteger la clave

Este texto va al repositorio tal cual. La clave protege el tablero, la exportación con costos y PM, y la subida de Excel.

### 1. Crear la clave la primera vez

Genera una clave larga y aleatoria. No uses nombres, fechas ni palabras del negocio.

```
openssl rand -base64 24
# ejemplo de salida: 3q9fX1mB7sV0uK2eYtR8nLhPzWc4JdAo
```

Guárdala en un gestor de contraseñas. Luego pónla en `local.properties` (en el servidor, nunca en git):

```
DASHBOARD_USER=admin
DASHBOARD_PASSWORD=3q9fX1mB7sV0uK2eYtR8nLhPzWc4JdAo
APP_ENV=production
```

También puedes usar variables de entorno con los mismos nombres. Si existen ambas, gana la variable de entorno.

### 2. Entrar al tablero

El navegador pide usuario y clave. Entra con `admin` (o el valor de `DASHBOARD_USER`) y tu clave. Usa siempre HTTPS: con HTTP la clave viaja legible.

### 3. Cambiar la clave

1. Genera otra con `openssl rand -base64 24`.
2. Reemplaza el valor de `DASHBOARD_PASSWORD`.
3. Reinicia la app. La clave se lee al arrancar, así que el cambio no aplica sin reinicio.
4. Cierra sesión en los navegadores y entra con la nueva.

Cambia la clave cada 90 días y de inmediato si alguien que la conocía deja el equipo o si sospechas que se filtró.

### 4. Si olvidaste la clave

No hay recuperación por correo. Entra al servidor, genera una clave nueva, edítala en `local.properties` y reinicia.

### 5. Qué hace la app para protegerte

- En producción no arranca si la clave falta o tiene menos de 12 caracteres.
- Bloquea temporalmente la IP tras 5 intentos fallidos.
- Compara la clave en tiempo constante y nunca la escribe en los registros.

Mejora opcional para la fase 4: guardar `DASHBOARD_PASSWORD_HASH` (bcrypt o argon2) en lugar de la clave en texto plano.

Fase 2 · 4 a 6 días

## Datos correctos y ventas que no se pierden

AltoArchivos: `ExcelV2Parser.kt`, `Application.kt`, `ShopifyWebhookHandler.kt`, `ExposedInventarioRepository.kt`

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 2.1 | **Validación estricta del Excel** (`ExcelV2Parser` líneas \~109-111).    Exigir PP > 0, PM > 0 y PM ≤ PP. Costo no puede ser negativo.   Normalizar montos: aceptar `120.000`, `120,000`, `120 000` y `$120000`. Rechazar "Consultar" y vacíos.   Rechazar la fila con un error que indique hoja, número de fila y columna. No convertir a `0L`.   Avisar de claves duplicadas y rechazar el duplicado.   Si el archivo está corrupto, responder 400 con mensaje, no 500. | Pruebas nuevas con filas malas. Un Excel con una fila inválida importa las filas válidas y devuelve la lista de errores por fila. Ningún producto queda con precio 0. |
| 2.2 | **Respetar `status`.** Los productos "Inactivo" no entran al catálogo del bot, no se negocian, no se publican en la vitrina ni en Shopify. | Prueba: un producto inactivo responde "no disponible" en el chat y no aparece en `/public/p/{key}`. |
| 2.3 | **Cargar `AppStore` desde la BD al arrancar.** Leer inventario, reservas, ventas e historial con los repositorios Exposed. Si la BD está configurada y la carga falla, no arrancar. | Reiniciar la app no cambia el catálogo ni "resucita" unidades vendidas. |
| 2.4 | **Una sola fuente del PM.** Decidir si el PM lo calcula el trigger de la BD o el Excel. Recomendado: el valor del Excel es el dato y el trigger solo valida. Quitar la duplicación y registrar los errores de `upsert` como error visible en el tablero, no solo en consola. | Un fallo de escritura en BD detiene la operación y se muestra en el tablero. |
| 2.5 | **Shopify con varios ítems** (`ShopifyWebhookHandler` línea \~56). Recorrer todos los `line_items`, descontar cada SKU, y manejar `orders/cancelled` y reembolsos devolviendo el stock. Mover la marca de idempotencia para después de procesar con éxito, de modo que un fallo permita el reintento. | Pruebas con pedido de 3 ítems, cancelación y fallo a mitad de la saga seguido de reintento exitoso. |
| 2.6 | **Nunca vender más de lo que hay.** Quitar el `coerceAtLeast(0)` de `stockVivo` o dejarlo con una alerta cuando oculte un valor negativo. | Un stock negativo genera una alerta visible en el tablero. |

### Cierre de fase

- Subir `NEGOCIO.xlsx` importa sin errores, y un archivo dañado a propósito se rechaza con mensaje claro.
- Reiniciar y volver a abrir muestra el mismo inventario.
- Un pedido de Shopify con 3 productos descuenta los 3.

Fase 3 · 2 a 3 días

## Negociación que no deja deducir el PM

AltoArchivos: `NegotiationEngine.kt`, `InboundMessagePipeline.kt`

Con pasos fijos de 25 %, 50 % y 75 % del tramo PP→PM, tres contraofertas permiten calcular PM ≈ PP − 2·(c1 − c3). Con PP 120.000 y PM 95.000 salen 114k, 108k y 102k, y de ahí 96k. Además, cualquier oferta mayor o igual al PM se acepta al valor exacto, lo que permite tantear el umbral.

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 3.1 | Agregar variación aleatoria a los pasos de la escalera (por ejemplo ±5 % del paso, redondeado a miles), con semilla inyectable para las pruebas. | La fórmula PM ≈ PP − 2·(c1 − c3) falla por más de un 5 % del tramo en 1.000 simulaciones. |
| 3.2 | Limitar contraofertas por cliente y producto. Tras N intentos (propuesto 4), pasar a un asesor humano y dejar de ofrecer descuentos. | El quinto mensaje de regateo no recibe una nueva cifra. |
| 3.3 | En la franja baja (niveles L3 y L4) no aceptar al valor exacto ofrecido. Responder con una contraoferta, aunque la oferta supere el PM. | Una oferta de PM + 1.000 recibe contraoferta por encima de lo ofrecido, no aceptación. |
| 3.4 | `pendingCounterCop` con vencimiento (propuesto 30 minutos). Aceptar solo respuestas explícitas ("sí", "acepto", "dale", "de acuerdo"). Quitar "una", "va" y "bien" de las palabras de aceptación. | Un "ok" a las 3 horas no acepta una contraoferta vieja. |
| 3.5 | Intérprete de productos con regex y límites de palabra, priorizando la clave más larga. No recortar cifras al quitar la clave del texto. | `PROD-1` no se confunde con `PROD-10`; una oferta "10000" no pierde dígitos. |

Fase 4 · 4 a 5 días

## Endurecer entradas, integraciones y base de datos

Medio

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 4.1 | **Frontend.** Construir el HTML con `ktor-server-html-builder` (ya está como dependencia) para que escape por defecto, empezando por el simulador (`itemName`). Añadir cabeceras `Content-Security-Policy`, `X-Frame-Options: DENY` y `X-Content-Type-Options`. Descargar Tailwind y htmx a archivos propios, o dejar CDN con SRI y versión fija. | Un nombre de producto con `<script>` se muestra como texto. Las cabeceras aparecen en las respuestas. |
| 4.2 | **Límites de tamaño.** Tope en `receive<ByteArray>()` de webhooks (por ejemplo 1 MB) y en la subida de Excel (por ejemplo 5 MB, máximo de filas y de hojas). Revisar la razón de compresión del zip antes de abrir con POI. Rate limit general por IP. | Un cuerpo de 50 MB responde 413 sin consumir memoria. |
| 4.3 | **Memoria acotada.** Poner tope y expiración a `chatDeals` y a las conversaciones (por ejemplo 24 h de inactividad, máximo N conversaciones). | La memoria se mantiene estable con 10.000 conversaciones simuladas. |
| 4.4 | **Shopify.** Pasar el GraphQL a variables (no interpolar `sku` ni `externalId`). Subir `apiVersion` desde 2024-01 a una versión vigente. Leer `locationId` de configuración o consultarlo, en vez del placeholder `gid://shopify/Location/12345678`. | Un SKU con comillas no rompe la consulta. Las llamadas funcionan contra una tienda de pruebas. |
| 4.5 | **SQL.** Activar RLS en `inventario`, `historial` y `cat_estado`. Añadir `ALTER DEFAULT PRIVILEGES` para tablas futuras. Usar `sslmode=verify-full` en `DATABASE_URL`. Nuevo script `db/migracion_fase4_rls.sql` con su prueba. | Un rol sin permisos no lee ni escribe las tablas. Conexión sin TLS válido falla. |
| 4.6 | **Concurrencia.** Reemplazar `runBlocking` en handlers por funciones `suspend`. JDBC en `Dispatchers.IO`. Copiar `apiIntegrations` antes de pasarlo al renderizador y proteger `channelMappings` (mapa concurrente o mutex). | Sin `runBlocking` en rutas. Prueba de carga con 50 mensajes simultáneos sin errores. |
| 4.7 | **Sesión con cookie y token CSRF** (versión completa de 1.4) y, opcional, clave guardada como hash. | El tablero funciona con cookie `SameSite=Strict` y los POST exigen token. |
| 4.8 | **Excel y exportación.** Mover la defensa contra inyección de fórmulas (`'` inicial) de la importación a la exportación, de modo que textos como "-20%" o "+57…" no se corrompan al importar. | Ida y vuelta de un texto "+57 300…" lo deja intacto. Una celda exportada que empieza con `=` no ejecuta fórmula. |

Fase 5 · 2 a 3 semanas

## Base de datos como única fuente de verdad

Siguiente rondaArchivos: `AntiOversellSaga.kt`, `MetaShopAdapter.kt`, `ExposedVentaRepository.kt`, `Reservation.kt`

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 5.1 | **Reserva atómica.** `UPDATE inventario SET stock_vivo = stock_vivo - 1 WHERE key = ? AND stock_vivo > 0` y comprobar filas afectadas. `AppStore` pasa a ser caché de lectura. | 100 reservas concurrentes de la última unidad producen exactamente 1 éxito. |
| 5.2 | **Persistir conversaciones, reservas y ventas** con tablas y migración nuevas. | Reiniciar a mitad de una negociación conserva el estado. |
| 5.3 | **Pago real.** Botón "Confirmar pago" en el tablero que asigna `PAID_CONFIRMED`, registra la venta y dispara la saga. Las reservas con comprobante pendiente no se liberan a las 12 h sin revisión humana (alerta en el tablero). | Un cliente que ya pagó no ve su producto volver a venderse. |
| 5.4 | **Saga que sí actúa.** Escribir `channelMappings`, usar `expectedVersion` real, enviar las ventas del chat a Shopify, y que el tablero muestre estados reales en vez de "12" y "OK" fijos. | Una venta por chat reduce el inventario en Shopify y se refleja en el tablero. |
| 5.5 | **Outbox con reintentos** para los envíos de mensajes y de catálogo. | Un fallo de red al enviar se reintenta con espera creciente y queda registrado. |
| 5.6 | **Cola por conversación** para procesar en orden los mensajes del mismo cliente. | Dos mensajes seguidos se procesan en el orden recibido. |

Fase 6 · continuo

## Después

Mejora continua

| ID | Tarea | Criterio de aceptación |
| --- | --- | --- |
| 6.1 | Gemini real con salida JSON validada contra un esquema y las reglas locales como respaldo (`GeminiLlmAdapter.kt`). | Una respuesta mal formada del modelo cae a las reglas locales sin error al cliente. |
| 6.2 | CI con build, tests y escaneo de dependencias. Actualizar Ktor 2.3.8, Exposed 0.47, logback 1.4.14 y POI 5.2.5. Quitar H2 y instalar `status-pages`. | Pipeline verde en cada pull request. Sin vulnerabilidades altas abiertas. |
| 6.3 | Dockerfile, endpoint `/healthz` y pruebas de integración con Testcontainers. | `docker run` levanta la app y `/healthz` responde 200. |
| 6.4 | Plantillas de WhatsApp para escribir fuera de la ventana de 24 h. Meta Shop real en lugar del stub. | Mensaje de seguimiento enviado con plantilla aprobada. |
| 6.5 | **Privacidad (Ley 1581 de 2012).** Política de tratamiento de datos, autorización del titular, plazo de retención y borrado de teléfonos y chats. Revisar con un abogado antes de operar. | Política publicada, aviso en el primer mensaje del bot y proceso de borrado documentado. |

Sin fase fija

## Backlog menor

- Actualizar `MANUAL_DE_CAMBIOS_v4.md` y crear la v5: el motor ya hace contraofertas y el manual dice que el PM se filtra. Revisar también `DIAGRAMA_DE_CLASES_v4.md` y el README (menciona H2).
- Instalar el plugin `StatusPages` con respuestas de error uniformes que no revelen trazas.
- Reemplazar los `println` de errores por un logger con niveles, sin datos personales ni secretos en los registros.

Planificación

## Orden, dependencias y riesgos

F0 Compilar

*0,5–1 d*

F1 Seguridad

*3–4 d*

F2 Datos

*4–6 d*

F3 Negociación

*2–3 d*

F4 Robustez

*4–5 d*

F5 Fuente única

*2–3 sem*

F6 Después

Escala aproximada, unos 2 meses en total. Rojo: bloquea el lanzamiento. Ámbar: antes de escalar. Azul: crecimiento.

| Riesgo | Qué hacer |
| --- | --- |
| La compilación (0.1) revela más fallos de los previstos y desplaza el calendario. | Hacerla primero, hoy. Es la tarea con mayor incertidumbre. |
| Cargar desde BD (2.3) antes de decidir la fuente del PM (2.4) puede fijar un valor equivocado. | Decidir 2.4 antes de cerrar 2.3. |
| La reserva atómica (5.1) cambia el modelo de datos y rompe pruebas existentes. | Hacerla tras la fase 2 y con migración reversible. |
| Los cambios de negociación (3.x) alteran la experiencia del cliente. | Probarlos en el simulador del tablero antes de activarlos en WhatsApp. |
| Datos de clientes sin política legal. | Iniciar 6.5 en paralelo desde la fase 1; no depende del código. |

### Regla de verificación en todas las fases

Cada tarea termina con una prueba automática que falle antes del cambio y pase después, y con `./gradlew test` en verde. No se pasa a la fase siguiente con pruebas rojas.