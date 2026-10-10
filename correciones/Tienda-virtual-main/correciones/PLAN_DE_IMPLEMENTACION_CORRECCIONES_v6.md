# Plan de implementación v6 — Corrección de errores de Tienda-virtual-main

Este plan cubre todos los hallazgos de la revisión del zip `Tienda-virtual-main`: despliegue, negociación, webhooks, estado en memoria, sincronización con Shopify y Meta, y socios. Cada ítem trae el problema (con su evidencia en el código), el cambio propuesto, las pruebas de comprobación y el criterio de aceptación.

> **Revisión 2 (8-oct-2026):** el ítem **F1** se rediseñó: cadena de modelos Gemini `3.5 → 3.6 → 3.7 → 3.8`, con reintentos por modelo, interruptor de circuito y respaldo final al parser local. Cambió también la decisión D5, el resumen de pruebas y la tabla de riesgos.

## 0. Alcance y advertencias

- **Todo sale de leer el código.** No compilé el proyecto ni ejecuté pruebas (el entorno de revisión no tiene red para Gradle). Los fragmentos de código son propuestas, no código verificado.
- **Los nombres de archivo y funciones** son los del zip revisado. Los números de línea son aproximados.
- **Lo que depende de APIs externas** (endpoints y campos de Meta Catalog y de la GraphQL Admin API de Shopify) debe validarse contra la documentación vigente antes de implementarlo. Esos puntos van marcados con 🔎.
- **Dos prioridades de seguridad se mantienen en todo el plan:** `CHANNEL_WRITE_ENABLED=false` sigue siendo el valor por defecto hasta que la Fase 5 pase en una tienda de pruebas, y la confirmación de pago sigue siendo humana.

### Aclaración sobre un diagnóstico anterior

Dije que nada alimentaba el outbox. Eso era incompleto. Sí existe un trigger en la base (`trg_c_inventario_stock_outbox`, en V003) que encola avisos de stock cada vez que cambia `inventario.cantidad`. Pero solo encola para productos que estén en la tabla `publicaciones`, y **nada del código llena esa tabla** salvo el propio `OutboxWorker`, que la rellena después de un "éxito" con `external_id = sku`. El resultado práctico es el mismo (no se sincroniza nada), pero la causa raíz es distinta: faltan la publicación inicial y los IDs externos. El plan de la Fase 5 parte de este diagnóstico corregido.

## 1. Resumen de hallazgos

| ID | Hallazgo | Severidad | Fase |
|---|---|---|---|
| A1 | `docker-compose.yml` no arranca (APP_ENV inválido, bind a loopback, sslmode, variables sin pasar, DB expuesta) | Alta | 1 |
| B1 | Jitter de negociación sesgado a la baja por `%` con signo negativo | Alta | 2 |
| B2 | La escalera de contraofertas permite inferir el precio mínimo | Media | 2 |
| B3 | `MAX_COUNTER_OFFERS` (4) no coincide con los pasos de la escalera (3) | Baja | 2 |
| C1 | Webhook de Meta pierde mensajes si falla el encolado | Alta | 2 |
| F1 | Gemini: un solo modelo fijo (`gemini-1.5-flash`), sin reintentos ni respaldo; la petición envía `temperature`, obsoleto en Gemini 3.x; caché sin expulsión que guarda respuestas degradadas | Media | 2 |
| S3 | Porcentaje con coma o texto inválido borra el del socio | Alta | 2 |
| S5 | Reactivar socio duplicado da error SQL crudo; repositorios en memoria inconsistentes | Baja | 2 |
| S1 | La venta con 0 socios depende del canal (bloquea en dashboard, registra sin reparto en chat/Shopify) | Alta | 3 |
| S2 | Retirar un socio con porcentaje solo en stock lo elimina y deja artículos por debajo de 100 % | Alta | 3 |
| S4 | No se puede subir un porcentaje cuando la suma ya es 100 | Media | 3 |
| D1 | `AppStore` usa mapas no concurrentes; `CatalogState` existe pero no se usa | Media | 4 |
| E1 | `publicaciones` nunca se llena; no hay publicación inicial | Alta | 5 |
| E2 | El outbox pasa el SKU como ID externo; trata `NO_IMPLEMENTADO` como éxito | Alta | 5 |
| E3 | Saga anti-sobreventa recibe mapa vacío; duplica al outbox | Alta | 5 |
| E4 | Cambios de precio, título o estado no se propagan (`updateProduct` es un stub) | Media | 5 |
| E5 | `fetchRemoteProducts` devuelve vacío: la reconciliación no detecta nada | Media | 5 |
| E6 | `inventory/update` de Shopify se descarta | Media | 5 |
| E7 | Adaptador de Meta Catalog es un esqueleto (`NO_IMPLEMENTADO`) | Alta | 5 |
| G1 | `DashboardRenderer.kt` de 71 KB; carpetas y PDFs mezclados con el código | Baja | 6 |

Esfuerzo estimado: **S** ≤ medio día, **M** 1–2 días, **L** 3–5 días.

## 2. Decisiones que necesito de ti antes de empezar

| # | Decisión | Mi recomendación |
|---|---|---|
| D1 | ¿Qué pasa al vender con 0 socios? | Dashboard exige reparto 100 %; chat y Shopify registran la venta (el pago ya ocurrió) y queda en una lista "ventas sin reparto" que se corrige después (S1). |
| D2 | ¿La escalera de contraofertas debe ser uniforme? | Sí: la respuesta depende solo del número de contraoferta, nunca de si la oferta estaba por encima o por debajo del PM (B2). |
| D3 | Al retirar un socio con porcentaje en stock | Archivarlo conservando sus porcentajes, para que los artículos sigan sumando 100 % (S2). |
| D4 | Si alguien cambia el stock en el admin de Shopify | Registrar una "deriva" para que un humano decida; no sobrescribir automáticamente (E6). |
| D5 | Modelo de Gemini | **Decidido por ti:** `gemini-3.5-flash` con reintentos; si falla, `gemini-3.6-flash`; luego `gemini-3.7-flash`; luego `gemini-3.8-flash`, cada uno con sus reintentos; al final, el parser local (F1). |
| D6 | Cómo cambiar las migraciones | Editar `V001`/`V003`/`V004` como prefieres. Riesgo: Flyway valida checksums, así que las bases ya migradas fallan hasta correr `flyway repair` (o recrearse). Ver sección 9. |

## 3. Orden de ejecución y dependencias

```
Fase 0  Base de pruebas ──┬─> Fase 1  Despliegue (A1)
                          ├─> Fase 2  Correcciones puntuales (B, C, F, S3, S5)
                          ├─> Fase 3  Socios (S1, S2, S4)          [requiere D1, D3]
                          └─> Fase 4  Estado en memoria (D1)
Fase 4 ──> Fase 5  Sincronización de canales (E1 … E7)             [requiere D4, 🔎]
Fase 6  Limpieza (al final)
```

Las fases 1 a 4 son independientes entre sí y se pueden hacer en cualquier orden después de la Fase 0. La Fase 5 va al final porque es la más grande y la que toca sistemas externos.

---

## Fase 0 — Base de pruebas (esfuerzo M)

**Objetivo:** poder escribir las pruebas de las fases siguientes y tener una línea base honesta.

### 0.1 Dependencias de prueba

Hoy el catálogo `libs.versions.toml` solo trae JUnit 5. Agregar (versiones alineadas con Ktor 2.3.8):

```toml
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-client-mock      = { module = "io.ktor:ktor-client-mock",      version.ref = "ktor" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }
```

Y en los `build.gradle.kts` de `app`, `adapters-shopify`, `adapters-meta` y `adapters-channels`: `testImplementation(...)` correspondiente.

### 0.2 CI con PostgreSQL para las pruebas SQL

H2 no ejecuta PL/pgSQL, así que los triggers y funciones (`fn_repartir`, `retirar_socio`, `trg_inversion_suma`, outbox) solo se pueden probar contra PostgreSQL real. `db/pruebas_fase2.sql` ya existe, pero su encabezado dice que **nunca se ejecutó**.

Agregar a `.github/workflows/ci.yml` un segundo job:

```yaml
  sql-tests:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_PASSWORD: ci, POSTGRES_DB: tiendadb }
        ports: ["5432:5432"]
        options: >-
          --health-cmd "pg_isready -U postgres" --health-interval 5s
          --health-timeout 5s --health-retries 10
    env:
      PGPASSWORD: ci
    steps:
      - uses: actions/checkout@v4
      - name: Aplicar migraciones en orden
        run: |
          for f in infra/src/main/resources/db/migrations/V00*.sql; do
            psql -h localhost -U postgres -d tiendadb -v ON_ERROR_STOP=1 -f "$f"
          done
      - name: Pruebas SQL
        run: |
          for f in db/pruebas_*.sql; do
            psql -h localhost -U postgres -d tiendadb -v ON_ERROR_STOP=1 -f "$f"
          done
```

> Verificar al implementar: si las migraciones asumen que Flyway crea el esquema `tienda`, crearlo antes con `CREATE SCHEMA IF NOT EXISTS tienda;`.

### 0.3 Línea base

1. Correr `./gradlew test` y `db/pruebas_fase2.sql` **sin cambiar nada** y anotar qué falla.
2. Corregir primero lo que ya falle antes de agregar cambios, para no mezclar causas.

**Criterio de aceptación:** `./gradlew test` y el job `sql-tests` corren en CI y quedan en verde (o con una lista conocida de fallos preexistentes documentada).

---

## Fase 1 — Despliegue (A1) · esfuerzo M

### Problema

Cuatro fallos independientes en el despliegue con Docker Compose:

1. `APP_ENV=${APP_ENV:-development}`, pero `AppEnv.parse` solo acepta `local` o `production` y lanza excepción con cualquier otro valor.
2. Aunque se corrigiera a `local`, `DashboardSecurity.plan` hace que en local el servidor escuche solo en `127.0.0.1`, y dentro de un contenedor eso lo deja inalcanzable desde el host.
3. Aunque se usara `production`, `FlywayMigrationTest` (T55) muestra que producción rechaza una URL JDBC sin `sslmode`, y la URL interna del compose (`jdbc:postgresql://db:5432/tiendadb`) no lo trae.
4. El compose no pasa `META_APP_SECRET`, `META_WEBHOOK_VERIFY_TOKEN`, `META_*`, `WHATSAPP_*`, `SHOPIFY_*`, `GEMINI_API_KEY`, `TRUST_PROXY`, `CORS_ALLOWED_HOST` ni `PAYMENT_INSTRUCTIONS`, así que dentro del contenedor el webhook de Meta respondería 503. Además publica el puerto 5432 con la contraseña por defecto `tiendapassword`.

### Cambios

**a) `AppConfig` y `DashboardSecurity`:** nueva variable `APP_BIND_HOST`, **solo respetada** si `APP_ENV=local` **y** `DASHBOARD_PASSWORD` tiene 12+ caracteres. En cualquier otro caso se ignora y se mantiene `127.0.0.1` (con aviso).

```kotlin
// DashboardSecurity.plan(env, user, password, requestedHost: String = "")
if (env == AppEnv.LOCAL) {
    val canExpose = password.length >= MIN_PASSWORD_LENGTH
    val host = if (requestedHost.isNotBlank() && canExpose) requestedHost else LOOPBACK_HOST
    if (requestedHost.isNotBlank() && !canExpose)
        warnings += "APP_BIND_HOST ignorado: requiere DASHBOARD_PASSWORD de $MIN_PASSWORD_LENGTH+ caracteres."
    return Plan(host, DashboardAuth(user, password), warnings)
}
```

**b) `docker-compose.yml`** como perfil de desarrollo:

```yaml
environment:
  - APP_ENV=${APP_ENV:-local}
  - APP_BIND_HOST=0.0.0.0              # solo surte efecto en local con clave fuerte
  - DATABASE_URL=${DATABASE_URL:-jdbc:postgresql://db:5432/tiendadb}
  - META_APP_SECRET=${META_APP_SECRET:-}
  - META_WEBHOOK_VERIFY_TOKEN=${META_WEBHOOK_VERIFY_TOKEN:-}
  - META_CATALOG_ID=${META_CATALOG_ID:-}
  - META_ACCESS_TOKEN=${META_ACCESS_TOKEN:-}
  - META_PAGE_ID=${META_PAGE_ID:-}
  - WHATSAPP_PHONE_NUMBER_ID=${WHATSAPP_PHONE_NUMBER_ID:-}
  - WHATSAPP_ACCESS_TOKEN=${WHATSAPP_ACCESS_TOKEN:-}
  - SHOPIFY_SHOP_DOMAIN=${SHOPIFY_SHOP_DOMAIN:-}
  - SHOPIFY_ACCESS_TOKEN=${SHOPIFY_ACCESS_TOKEN:-}
  - SHOPIFY_WEBHOOK_SECRET=${SHOPIFY_WEBHOOK_SECRET:-}
  - SHOPIFY_LOCATION_ID=${SHOPIFY_LOCATION_ID:-}
  - GEMINI_API_KEY=${GEMINI_API_KEY:-}
  - GEMINI_MODELS=${GEMINI_MODELS:-}        # vacío = cadena por defecto (ver F1)
  - TRUST_PROXY=${TRUST_PROXY:-false}
ports:
  - "127.0.0.1:${PORT:-8080}:8080"      # solo la propia máquina
# servicio db: quitar `ports:` (la app llega por la red interna) y
# POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?POSTGRES_PASSWORD es obligatoria}
```

Para producción real: usar la base gestionada (Supabase) con `sslmode=require` en `DATABASE_URL` y `APP_ENV=production`, y documentarlo en `README.md` y `docs/MANUAL_CLAVE_TABLERO.md`.

**c) CI:** paso `docker compose config -q` con variables ficticias, para detectar errores de sintaxis del compose.

### Pruebas de comprobación

| Prueba | Tipo | Verifica |
|---|---|---|
| `DashboardSecurityTest.local con clave fuerte y APP_BIND_HOST honra 0.0.0.0` | Unitaria (app) | host = `0.0.0.0` |
| `DashboardSecurityTest.local sin clave ignora APP_BIND_HOST y avisa` | Unitaria | host = `127.0.0.1` + advertencia |
| `DashboardSecurityTest.local con clave corta ignora APP_BIND_HOST` | Unitaria | host = `127.0.0.1` |
| `DashboardSecurityTest.production sigue escuchando en 0.0.0.0 y exige clave` | Unitaria | sin regresión |
| `ComposeConfigTest.APP_ENV por defecto es válido` | Unitaria (infra) | lee `docker-compose.yml`, extrae el valor por defecto de `APP_ENV` y lo pasa por `AppEnv.parse` sin excepción |
| `ComposeConfigTest.pasa las variables de integración` | Unitaria | el texto contiene cada variable de la lista de `AppConfig` que la app exige para webhooks |
| `ComposeConfigTest.no publica Postgres ni trae clave por defecto` | Unitaria | no aparece `5432:5432` ni `:-tiendapassword` |
| Paso CI `docker compose config -q` | CI | el archivo es válido |

> Para localizar el compose desde un test de módulo: `File(System.getProperty("user.dir")).parentFile.resolve("docker-compose.yml")`.

**Criterio de aceptación:** `docker compose up` con solo `DATABASE_PASSWORD` y `DASHBOARD_PASSWORD` (12+ caracteres) arranca, y el tablero responde en `http://127.0.0.1:8080` con Basic Auth.

---

## Fase 2 — Correcciones puntuales

### B1 — Jitter sesgado · esfuerzo S

**Problema** (`NegotiationEngine.applyJitter`): `seed % (maxJitter * 2)` devuelve valores negativos cuando `hashCode()` es negativo (cerca de la mitad de los clientes). El ajuste queda entre −3 % y −9 % en vez de ±3 %, y el `max(..., pm)` deja la contraoferta pegada al precio mínimo.

**Cambio:**

```kotlin
val range = maxJitter * 2 + 1                        // rango inclusivo [-maxJitter, +maxJitter]
val offset = if (maxJitter > 0) Math.floorMod(seed, range) - maxJitter else 0L
```

(`Math.floorMod(Long, Long)` siempre devuelve un valor ≥ 0.)

**Pruebas** (en `NegotiationEngineTest`):

| Prueba | Verifica |
|---|---|
| `jitter es simétrico para ids con hashCode negativo` | Con PP = 1.000.000 y PM = 500.000 (sin recorte), para 5.000 `customerId` (incluidos negativos) cada contraoferta está dentro de `base ± 3 % ± 500` |
| `jitter no tiene sesgo` | El promedio de las contraofertas está a menos de 1 % de la base |
| `la contraoferta nunca baja de PM ni sube de PP` | Con márgenes finos (PM = 0,86 × PP), 5.000 ids: `pm ≤ contraoferta ≤ pp` |
| `el jitter es estable por cliente y paso` | Mismo `customerId` + paso → mismo valor; distinto paso → puede diferir |

### B2 — Escalera que revela el piso · esfuerzo M (requiere D2)

**Problema:** una oferta **por debajo del PM** recibe la contraoferta del paso (5 %, 10 %, 15 % sobre PP), pero una oferta **por encima del PM y por debajo del tramo 15 %** recibe una contraoferta calculada con otra regla (tramos L2/L3). Esa diferencia deja ver de qué lado del piso cayó la oferta, y con unas pocas sondas (o reiniciando la conversación) se puede aproximar el PM.

**Cambio:**

1. Toda oferta que **no se acepte** (por debajo del umbral de aceptación L2) recibe `counterOffer(step)`, calculada solo con el número de contraoferta, nunca con la posición de la oferta respecto al PM.
2. Los niveles `L3_HIGH_DISCOUNT` y `L4_FLOOR_LIMIT` quedan sin uso: marcarlos `@Deprecated` (no borrarlos, por compatibilidad con el dashboard) o eliminarlos junto con sus pruebas.
3. **Opcional (P2):** persistir el contador de contraofertas por `(cliente, producto)` durante 24 h, para que reiniciar la conversación no reinicie la escalera.

**Pruebas:**

| Prueba | Verifica |
|---|---|
| `ofertas bajo y sobre el PM reciben la misma respuesta` | Para el mismo `customerId` y paso, una oferta de PM − 1.000 y otra de PM + 1.000 (ambas bajo el umbral L2) producen la misma contraoferta y el mismo nivel |
| `respuesta no depende de la oferta, solo del paso` | Barrido de 200 ofertas bajo el umbral: el conjunto de contraofertas por paso tiene un único valor |
| `ofertas aceptables se siguen aceptando` | Oferta ≥ tramo L2 → `accepted = true` |
| `contraoferta nunca revela el PM` | La contraoferta de cada paso ≥ PM y el mensaje no contiene el valor del PM |
| (opcional) `contador persiste entre conversaciones` | Nueva conversación del mismo cliente continúa en el paso previo |

Actualizar las pruebas existentes de `NegotiationEngineTest` que esperen `L3`/`L4`.

### B3 — Tope de contraofertas · esfuerzo S

**Problema:** `DISCOUNTS_PP` tiene 3 pasos pero `MAX_COUNTER_OFFERS = 4`: los pasos 2 y 3 repiten el mismo "precio más bajo".

**Cambio:** `MAX_COUNTER_OFFERS = DISCOUNTS_PP.size`. Tras la contraoferta final (paso 2), la siguiente oferta escala a humano.

**Prueba:** `tras la contraoferta final, la siguiente oferta escala a humano` (`isFinalCounter` en el paso 2; `isEscalatedToHuman` con `previousCounterOffers = 3`).

### C1 — Pérdida de mensajes en `/webhooks/meta` · esfuerzo M

**Problema** (`WebhookRoutes`): se llama `tryStartProcessing` (queda en `PROCESSING`) y luego `inboundWorker.enqueue(...)` sin `try/catch`. Si el encolado lanza una excepción, la ruta responde 500, Meta reintenta, pero el evento sigue en `PROCESSING` y el reintento se ignora como duplicado: el mensaje se pierde.

**Cambio:**

1. Envolver cada evento en `try/catch`; en el `catch`, `processedEvents.markFailed(...)` (el store ya permite reprocesar eventos `FAILED`: `tryStartProcessing` los pasa a `PROCESSING` y devuelve `true`), registrar el error y marcar `failed = true`.
2. Si algún evento falló, responder **500** (para que Meta reintente); si no, 200.
3. Extraer una interfaz `InboundEnqueuer { fun enqueue(channel, senderId, messageId, text) }` implementada por `InboundWebhookWorker`, para poder simular fallos en las pruebas.
4. **Endurecimiento opcional:** tratar un `PROCESSING` con más de 10 minutos como reprocesable (por si el proceso cae a mitad), con reloj inyectable.

**Pruebas** (Ktor `testApplication`, con `ktor-server-test-host`):

| Prueba | Verifica |
|---|---|
| `si enqueue falla responde 500 y el evento queda FAILED` | Estado del store y código de respuesta |
| `el reintento tras un fallo se encola una sola vez` | 1.ª entrega falla, 2.ª entrega (misma `messageId`) encola; 3.ª entrega → ignorada |
| `un lote con un evento fallido procesa el resto` | 2 eventos, el 1.º falla: el 2.º se encola; respuesta 500 |
| `firma inválida sigue dando 401 y firma ausente de secreto 503` | Sin regresión |
| (opcional) `PROCESSING vencido se reprocesa` | Con reloj falso a +11 min |

### F1 — Gemini: cadena de modelos con reintentos y respaldo · esfuerzo M (decidido en D5)

**Problemas** (`GeminiLlmAdapter`):

1. `modelName = "gemini-1.5-flash"` está fijo en el código.
2. Un solo intento por mensaje: si Gemini falla o tarda más de 3 s, se cae directo al parser local, sin reintentos ni otro modelo.
3. La petición envía `temperature = 0.0`. La documentación de Google indica que `temperature`, `top_p` y `top_k` están **obsoletos en los modelos Gemini 3.x** y hay que quitarlos al migrar; si algún modelo los rechazara con un 400, **toda la cadena fallaría** y el bot quedaría siempre en el parser local.
4. `intentCache` deja de guardar al llegar a 500 entradas, sin expulsar las viejas, y además **guarda también los resultados del parser local**, así que una caída breve de Gemini deja respuestas degradadas en caché.

**Modelos** (IDs verificados en la documentación de Google el 8-oct-2026):

| Prioridad | ID | Nota |
|---|---|---|
| 1 | `gemini-3.5-flash` | Disponibilidad general (GA) |
| 2 | `gemini-3.6-flash` | Aparece en la página "latest model" de la Gemini API |
| 3 | `gemini-3.7-flash` | GA |
| 4 | `gemini-3.8-flash` | Documentada en la guía de desarrolladores de Google Cloud; confirmar además con una llamada real desde tu cuenta (ver prueba de contrato) |

> Interpreté "e.7" como `3.7`. Si no era eso, dímelo. Los IDs son configurables, así que el cambio de orden o de modelos no requiere tocar el código.

**Configuración nueva** (en `AppConfig`, todas con valor por defecto):

| Variable | Defecto | Significado |
|---|---|---|
| `GEMINI_MODELS` | `gemini-3.5-flash,gemini-3.6-flash,gemini-3.7-flash,gemini-3.8-flash` | Lista ordenada por prioridad. Se normaliza: recorta espacios, ignora vacíos y duplicados; si queda vacía usa el defecto |
| `GEMINI_RETRIES_PER_MODEL` | `2` | Reintentos **adicionales** por modelo (3 intentos en total por modelo) |
| `GEMINI_ATTEMPT_TIMEOUT_MS` | `3000` | Tiempo máximo de **cada intento** |
| `GEMINI_TOTAL_DEADLINE_MS` | `12000` | Techo de tiempo para **todo** el proceso (todos los modelos). Al agotarse se usa el parser local |
| `GEMINI_BACKOFF_BASE_MS` / `GEMINI_BACKOFF_CAP_MS` | `300` / `2000` | Espera entre reintentos: exponencial con *jitter* (±50 %) y tope |
| `GEMINI_BREAKER_FAILURES` / `GEMINI_BREAKER_COOLDOWN_S` | `3` / `60` | Interruptor de circuito por modelo (ver abajo) |
| `GEMINI_MAX_DAILY_CALLS` / `GEMINI_MAX_DAILY_ATTEMPTS` | `1000` / `3000` | Tope diario de llamadas lógicas (ya existe) y de intentos HTTP (nuevo, para acotar costo) |

Sin los valores por defecto, el peor caso serían 4 modelos × 3 intentos × 3 s = 36 s; por eso existe el techo total de 12 s.

**Algoritmo:**

```
parseIntent(mensaje):
  si sin clave, mensaje vacío o tope diario superado → parser local
  si está en caché (solo respuestas del LLM, con TTL) → devolverla
  dentro de withTimeoutOrNull(GEMINI_TOTAL_DEADLINE_MS):
    para cada modelo en GEMINI_MODELS (en orden):
      si el interruptor del modelo está ABIERTO → saltarlo
      repetir hasta 1 + GEMINI_RETRIES_PER_MODEL intentos:
        resultado = llamar(modelo)           // timeout por intento
        ÉXITO            → registrar éxito del modelo, cachear, devolver
        REINTENTABLE     → registrar fallo, esperar backoff, reintentar
        SALTAR_MODELO    → registrar fallo, pasar al siguiente modelo
        FATAL_CREDENCIAL → abandonar Gemini (no probar más modelos) → parser local
  agotado → parser local (sin cachear)
```

**Clasificación de errores:**

| Situación | Acción |
|---|---|
| Timeout, error de red, HTTP 408, 500, 502, 503, 504 | Reintentar el mismo modelo |
| HTTP 429 con `Retry-After` ≤ 2 s (o ausente) | Esperar y reintentar el mismo modelo |
| HTTP 429 con `Retry-After` > 2 s o mayor que el tiempo restante | Pasar al siguiente modelo (cada modelo suele tener su propia cuota) |
| HTTP 200 con JSON inválido, esquema inválido o `extractedProductKey` que no está en la lista de productos | Tratar como salida inválida: **1** reintento y luego siguiente modelo |
| HTTP 404 (modelo inexistente o retirado) | Pasar al siguiente modelo **sin** reintentar y avisar en el dashboard |
| HTTP 400 | Pasar al siguiente modelo; si lo rechazan **todos**, registrar un error visible (indica un fallo de la petición, no de capacidad) y usar el parser local |
| HTTP 401 o 403 | **No** probar otros modelos (la clave es el problema): parser local, marcar "credenciales de Gemini inválidas" en el dashboard y pausar Gemini 5 minutos |

**Interruptor de circuito por modelo:** tras `GEMINI_BREAKER_FAILURES` fallos consecutivos el modelo pasa a **ABIERTO** durante `GEMINI_BREAKER_COOLDOWN_S`; los mensajes siguientes lo saltan y no pagan sus reintentos. Cumplido el tiempo pasa a **SEMIABIERTO**: deja pasar una sola llamada de prueba; si funciona se cierra, si falla vuelve a abrirse. Así, con el modelo 1 caído, no cada mensaje espera sus tres intentos antes de llegar al modelo 2, y cuando se recupera el sistema vuelve a él solo.

**Cambios en la petición** (válidos para los cuatro modelos, 🔎 confirmar en la prueba de contrato):

- **Quitar** `temperature` (y no enviar `top_p`/`top_k`).
- Mantener `responseMimeType: application/json`. Opcional: añadir un `responseSchema` con `intentType` como enumeración, para reducir las salidas inválidas.
- Para esta tarea (extraer una intención) fijar un nivel de razonamiento bajo con `thinkingConfig.thinkingLevel = "low"`. No usar `thinking_budget` numérico (obsoleto). Si algún modelo rechaza el nivel, omitir el campo para ese modelo.
- Prompt de una sola vuelta de usuario (no hay turnos previos del modelo), así que la regla de "no terminar el historial con un turno `model`" no aplica.

**Caché:** LRU acotado (500 entradas) con TTL de 10 minutos y reloj inyectable. La clave es mensaje + productos disponibles, **independiente del modelo**. Solo se cachean respuestas del LLM; las del parser local nunca.

**Observabilidad:**

- Cada cambio de modelo, reintento y apertura del circuito se registra con SLF4J en nivel WARN (modelo, intento, causa, sin la clave).
- Contadores en memoria: respuestas por modelo, número de respaldos, número de caídas al parser local, intentos del día.
- La fila "Gemini" de `refreshIntegrationStatuses` muestra el modelo activo y, si hay respaldo en uso, "en respaldo: gemini-3.6-flash", más el estado de credenciales y de cada interruptor.

**Pruebas** (`GeminiLlmAdapterTest` y `GeminiFailoverTest`, con `ktor-client-mock` y `kotlinx-coroutines-test` en tiempo virtual; el adaptador recibe el `delay` y el reloj inyectados para no esperar de verdad):

| # | Prueba | Verifica |
|---|---|---|
| 1 | `el primer modelo responde y no se llama a los demás` | 1 petición, a `gemini-3.5-flash` |
| 2 | `500 una vez y luego 200 reintenta el mismo modelo` | 2 peticiones a 3.5, resultado correcto, 0 a 3.6 |
| 3 | `429 con Retry-After corto espera y reintenta` | El tiempo virtual avanza al menos ese valor |
| 4 | `429 con Retry-After largo pasa al siguiente modelo` | Sin reintento en 3.5; 1 llamada a 3.6 |
| 5 | `agota los reintentos en 3.5 y usa 3.6` | 3 peticiones a 3.5 y luego 3.6 |
| 6 | `orden exacto de la cadena` | Con 3.5, 3.6 y 3.7 fallando: la secuencia de URLs es 3.5×3, 3.6×3, 3.7×3, 3.8 |
| 7 | `los cuatro fallan y usa el parser local sin lanzar excepción` | Intención devuelta, **no** cacheada |
| 8 | `404 de un modelo salta al siguiente sin reintentar` | 1 petición al modelo retirado |
| 9 | `401 o 403 no prueba otros modelos` | 1 petición en total; parser local; estado "credenciales inválidas" |
| 10 | `400 en todos los modelos genera error visible y parser local` | 4 peticiones; alerta registrada |
| 11 | `JSON inválido: un reintento y luego siguiente modelo` | 2 peticiones al mismo modelo y luego el siguiente |
| 12 | `extractedProductKey inventado se descarta` | Cuenta como salida inválida y no llega al pipeline |
| 13 | `el techo total de tiempo nunca se supera` | Con todos los modelos lentos, el tiempo virtual ≤ `GEMINI_TOTAL_DEADLINE_MS` |
| 14 | `el circuito se abre tras 3 fallos y se prueba de nuevo tras el enfriamiento` | Salto de 3.5 durante 60 s; una sola llamada de prueba después |
| 15 | `la petición no incluye temperature, top_p ni top_k` | Inspección del cuerpo JSON; incluye `thinkingLevel` bajo |
| 16 | `GEMINI_MODELS se normaliza` | Espacios, duplicados y vacíos; lista vacía → defecto |
| 17 | `el backoff crece, lleva jitter y respeta el tope` | Cada espera dentro de [0,5·x, 1,5·x] y ≤ `CAP` |
| 18 | `caché: expulsa el más antiguo en la entrada 501 y respeta el TTL` | LRU y vencimiento |
| 19 | `no se cachean respuestas del parser local` | Tras un respaldo local, el mismo mensaje vuelve a intentar Gemini |
| 20 | `los topes diarios de llamadas e intentos se aplican` | Superar `GEMINI_MAX_DAILY_ATTEMPTS` → parser local directo |
| 21 | `la clave va en la cabecera y no aparece en logs` | Cabecera `x-goog-api-key`; búsqueda del valor en la salida capturada |
| 22 | `caída concurrente de 3.5` | 50 llamadas simultáneas: el circuito abre y el total de intentos a 3.5 queda acotado |
| 23 | `AppConfigTest`: variables numéricas inválidas | Valor no numérico o negativo → defecto, con aviso |
| — | **Prueba de contrato en vivo** (desactivada por defecto; se activa con `GEMINI_LIVE_TEST=1` y una clave real) | Llama una vez a cada modelo de la cadena con un mensaje de ejemplo y comprueba que la petición sin `temperature` sea aceptada y que la respuesta cumpla el esquema. Confirma que `gemini-3.8-flash` está disponible en tu cuenta. **No corre en CI** |

**Criterio de aceptación:** con el modelo 1 simulando fallos, el bot sigue interpretando intenciones con el modelo 2 sin superar el techo de tiempo; con los cuatro caídos responde con el parser local; al recuperarse el modelo 1 vuelve a usarse solo; y la prueba de contrato en vivo pasa para los cuatro modelos.

### S3 — Porcentaje con coma o texto inválido · esfuerzo S

**Problema** (`InversionRoutes`, `PUT /{key}/inversion/{socioId}`): `pctStr.toBigDecimalOrNull() ?: BigDecimal.ZERO`. Un valor como `33,5` (coma decimal, habitual en Colombia) o un texto cualquiera se convierte en 0, y 0 **elimina** el porcentaje del socio sin avisar.

**Cambio:** función pura `PorcentajeParser.parse(raw: String): PorcentajeResult` en `commerce`:

- Acepta `.` o `,` como separador decimal; recorta espacios; acepta un `%` final.
- Cadena **vacía** = quitar el porcentaje (acción explícita).
- Texto inválido, negativo o > 100 → error con mensaje claro (HTTP 400 con el HTML de la tabla y el aviso), **nunca** 0 por defecto.
- Más de 2 decimales → error (la columna es `numeric(5,2)`; evita redondeos silenciosos).

**Pruebas** (`PorcentajeParserTest`, tabla de casos):

| Entrada | Resultado |
|---|---|
| `"33.5"`, `"33,5"`, `" 33,5 "`, `"33,5%"` | 33.50 |
| `"0"` | 0 (válido, quita el porcentaje) |
| `""` | Quitar (explícito) |
| `"abc"`, `"33,5,2"`, `"1e2"` | Error |
| `"-1"`, `"100.01"` | Error |
| `"33.555"` | Error (más de 2 decimales) |
| `"100"` | 100 |

Más una prueba de ruta: `PUT` con `"abc"` devuelve 400 y **no** modifica el porcentaje existente.

### S5 — Detalles de socios · esfuerzo S

1. **Reactivar con nombre duplicado:** en `SocioService.reactivar`, leer el socio, comprobar que no haya otro **activo** con el mismo nombre (sin distinguir mayúsculas) y lanzar un mensaje legible. Prueba: `reactivar con nombre en uso falla con mensaje claro`.
2. **Repositorios en memoria coherentes con producción:** `InMemorySocioRepository.impacto` hoy devuelve siempre 0/0/no archivable. Inyectarle el `InversionRepository` en memoria para contar artículos con porcentaje, y que `retirar` archive en vez de eliminar cuando corresponda (misma regla que S2). Pruebas: `impacto cuenta artículos con porcentaje`, `retirar archiva si hay porcentajes`, `no quedan porcentajes huérfanos al eliminar`.
3. **Mensajes de error SQL crudos:** capturar `ExposedSQLException` en las rutas de socios y traducir los casos conocidos (índice único de nombre, trigger de suma > 100) a mensajes en español.

---

## Fase 3 — Socios (requiere D1 y D3)

### S1 — Venta con 0 socios: una sola regla · esfuerzo M

**Problema:** hoy hay dos políticas.

- **Dashboard / venta manual:** `InventarioItem.registrarVenta` exige `reparte.definida` (suma 100 % y al menos 1 socio); con 0 socios no se puede vender.
- **Chat y Shopify:** llaman directamente a `registrar_venta_linea` (SQL), que con `config.exigir_reparto = 0` registra la venta **sin filas en `venta_reparto`**.

Después, como un artículo vendido es inmutable (`trg_inversion_bloqueo`), esas ventas no se pueden repartir sin anular y rehacer; y si agregas socios después, no cuentan en las métricas por socio.

**Cambio (según D1):**

1. Mantener el bloqueo en el dashboard (acción humana, el mensaje de error ya es claro) y documentarlo.
2. Chat/Shopify siguen registrando la venta, pero quedan visibles en una lista **"Ventas sin reparto"** en el dashboard (vista nueva basada en la consulta que ya usa `verificar_integridad`: ventas vigentes sin filas en `venta_reparto`).
3. Función SQL nueva en `V004` (editando el archivo existente): `tienda.asignar_reparto_venta(p_venta_id bigint, p_porcentajes jsonb)`. Valida que la suma sea 100, que los socios existan y estén activos, y que la venta no tenga ya reparto; inserta en `venta_reparto` con el mismo método del mayor residuo. **No** toca `inventario_inversion` (el artículo vendido sigue inmutable).
4. Ruta `POST /api/ventas/{id}/reparto` y botón en la lista.
5. Alinear los textos: el comentario de `V004` y el mensaje de Kotlin deben describir la misma política.

**Pruebas:**

| Prueba | Tipo | Verifica |
|---|---|---|
| `vender desde dashboard sin socios falla con mensaje claro` | Unitaria (`InventarioItem`) | Sin regresión |
| `venta por chat sin socios se registra y aparece en "sin reparto"` | SQL | `registrar_venta_linea` con 0 socios → venta creada, 0 filas de reparto, aparece en la vista |
| `asignar_reparto_venta reparte exacto (mayor residuo)` | SQL | La suma de `ingresos` y de `inversion` cuadra con la venta; casos 3 socios con 100.000 |
| `asignar_reparto_venta rechaza suma ≠ 100` | SQL | Excepción |
| `asignar_reparto_venta rechaza socio archivado o inexistente` | SQL | Excepción |
| `asignar_reparto_venta rechaza venta que ya tiene reparto` | SQL | Excepción (idempotencia explícita) |
| `las métricas por socio incluyen la venta corregida` | Unitaria (`MetricasService`) | `totalCuadra = true` tras la corrección |

### S2 — Retirar un socio con porcentaje en stock · esfuerzo M

**Problema:** `ExposedSocioRepository.impacto` considera archivable a un socio con stock (`stockCount > 0`), pero `tienda.retirar_socio` primero **borra** sus porcentajes en artículos no vendidos y solo archiva si tiene ventas o porcentajes en artículos vendidos. Un socio con porcentaje únicamente en stock se elimina, esos artículos bajan de 100 % y el dashboard no deja venderlos hasta repartirlos de nuevo. El modal anuncia una cosa y la base hace otra.

**Cambio (según D3, recomendación: archivar conservando porcentajes):**

En `retirar_socio` (editando `V001`): eliminar el `DELETE FROM tienda.inventario_inversion ...` inicial y archivar si el socio tiene **cualquier** fila en `venta_reparto` o en `inventario_inversion`; eliminar solo si nunca participó. El `impacto` de Kotlin queda como está (ya coincide). Los artículos conservan la suma 100 %, y el socio archivado sigue recibiendo su parte al vender (el trigger de bloqueo solo impide asignarle porcentajes **nuevos**).

> Alternativa si prefieres el otro comportamiento: bloquear el retiro mientras tenga porcentajes en stock y mostrar la lista de artículos a reasignar.

**Pruebas (SQL, `db/pruebas_fase3_socios.sql`):**

| Prueba | Verifica |
|---|---|
| `socio sin actividad se elimina` | Resultado `ELIMINADO`, fila inexistente |
| `socio con porcentaje solo en stock se archiva y conserva porcentajes` | Resultado `ARCHIVADO`; `sum(pct)` del artículo sigue en 100 |
| `el artículo con socio archivado se puede vender y reparte a todos` | `venta_reparto` incluye al archivado; suma cuadra |
| `socio con ventas se archiva` | Resultado `ARCHIVADO` |
| `un socio archivado no recibe porcentajes nuevos` | Excepción del trigger |
| `reactivar devuelve al socio a activo` | `activo = true`, `archivado_en` nulo |

Más una prueba unitaria de coherencia: `impacto().esArchivable` coincide con el resultado de `retirar` para los mismos escenarios (en memoria, tras S5).

### S4 — Guardar el reparto completo de una vez · esfuerzo M

**Problema:** `fijarPorcentaje` cambia un socio por vez. Con la suma ya en 100 %, subir uno falla (el trigger rechaza > 100) y hay que bajar a otro antes.

**Cambio:** ruta `PUT /api/inventario/{key}/inversion` que recibe todos los porcentajes de la fila (`pct_<socioId>`), los valida con `ReparteSocios` (suma ≤ 100) y llama a `InversionRepository.guardar` (ya existe, atómico: borra e inserta en una transacción con el trigger diferido). En la tabla del dashboard, un botón "Guardar fila". Se conserva el endpoint por celda y "repartir en partes iguales".

**Pruebas:**

| Prueba | Tipo | Verifica |
|---|---|---|
| `guardar fila con suma 100 reemplaza todos los porcentajes` | Ruta (`testApplication`) | Estado final igual al enviado |
| `guardar fila con suma > 100 devuelve error y no cambia nada` | Ruta | Estado previo intacto |
| `guardar fila usa el parser tolerante a coma` | Ruta | `33,34` + `33,33` + `33,33` → ok |
| `guardar fila con un artículo vendido falla` | SQL | Trigger de bloqueo |
| `repartir en partes iguales suma exactamente 100 para 1…12 socios` | Unitaria (`InMemoryInversionRepository`) | Para n = 1…12 la suma es 100,00 y el sobrante va al socio de menor id |

---

## Fase 4 — Estado en memoria (D1) · esfuerzo M

**Problema:** `AppStore` (en `Application.kt`) usa `mutableMapOf`/`mutableListOf` sin sincronización uniforme: la revisión encontró unas 69 referencias a `AppStore.products/inventory/chatDeals` frente a unos 37 bloques `synchronized`. Existe `CatalogState` (con `ConcurrentHashMap` y snapshots), pero ningún código de `main` lo usa. Además `WebhookRoutes` hace lectura-modificación-escritura sobre `AppStore.inventory` (no atómica entre hilos).

**Cambio:**

1. Convertir `AppStore` en una fachada sobre una única instancia de `CatalogState` (`AppStore.state`), manteniendo temporalmente las propiedades antiguas como delegados para migrar por etapas.
2. Agregar a `CatalogState` operaciones atómicas: `updateInventory(key) { inv -> ... }` basada en `ConcurrentHashMap.compute`, y `addOrUpdateDeal` con tope de 100 elementos (hoy `recordDeal` lo hace a mano con `synchronized`).
3. Migrar los accesos: `WebhookRoutes` (`recordSale`, devoluciones), `PagosRoutes`, `DashboardRoutes`, `ArticulosRoutes`, `ExcelRoutes`. Los renderers deben recibir snapshots, no mapas vivos.
4. Eliminar `mutableMapOf`/`mutableListOf` de `AppStore` al terminar.

**Pruebas:**

| Prueba | Verifica |
|---|---|
| `CatalogStateConcurrencyTest.lecturas de snapshot no fallan bajo escritura concurrente` | 8 hilos escriben/borran 20.000 operaciones mientras 4 hilos iteran snapshots: sin `ConcurrentModificationException` |
| `CatalogStateConcurrencyTest.updateInventory es atómico` | 1.000 `recordSale(1)` concurrentes sobre stock 1.000 → `unidadesVendidas = 1000`, sin ventas perdidas |
| `CatalogStateTest.chatDeals respeta el tope de 100` | Tras 250 inserciones quedan 100, los más recientes |
| `Guardia de arquitectura en CI` | `grep` que falla si `AppStore` vuelve a declarar `mutableMapOf`/`mutableListOf` |
| Pruebas existentes (`AppStoreCatalogTest`, `CatalogStateTest`) | Siguen en verde |

---

## Fase 5 — Sincronización de canales (requiere D4 y 🔎)

### Principio de diseño

Tu base es el **centro** (hub): Shopify y Meta se conectan a ella, no entre sí. El trigger de `V003` ya implementa la idea correcta (un solo punto que encola avisos y evita devolverlos al canal de origen). Por eso este plan **usa el outbox como único camino de sincronización de salida** y retira la saga anti-sobreventa en memoria, que hoy duplica el mecanismo y recibe un mapa vacío.

Se avanza por etapas, siempre en modo solo lectura hasta la 5c:

```
5a  Lectura y reconciliación          (sin escribir en canales)
5b  Publicaciones y outbox correctos  (CHANNEL_WRITE_ENABLED=false → se ve qué se enviaría)
5c  Publicar y sincronizar Shopify    (tienda de desarrollo)
5d  Meta Catalog                      (catálogo de pruebas)
5e  Entrada desde Shopify: deriva
```

### E5 — Lectura de catálogos remotos y reconciliación (5a) · esfuerzo M

**Problema:** `fetchRemoteProducts` devuelve `emptyList()` en `ShopifyCatalogAdapter` y en `MetaShopAdapter`, así que `ReconcileCatalogUseCase` marca todo como `LOCAL_ONLY` y no detecta diferencias reales.

**Cambio:**

- **Shopify:** consulta GraphQL paginada de productos y variantes (SKU, precio, `inventoryQuantity`, estado, IDs). Respetar el costo de la consulta y `THROTTLED` (ya mapeado a `RateLimited`). 🔎 validar campos según `SHOPIFY_API_VERSION`.
- **Meta:** lectura paginada de los productos del catálogo (`retailer_id`, nombre, precio, disponibilidad). 🔎 validar endpoint, campos y versión de Graph API (`META_GRAPH_API_VERSION`).
- Agregar a la reconciliación los estados `REMOTE_ONLY`, `PRICE_MISMATCH` y `STOCK_MISMATCH` si no existen.

**Pruebas** (`ktor-client-mock` con respuestas JSON de ejemplo en `src/test/resources`):

| Prueba | Verifica |
|---|---|
| `Shopify fetchRemoteProducts pagina y mapea SKU, precio y stock` | 2 páginas → lista completa, `key = SKU` |
| `Shopify fetchRemoteProducts maneja THROTTLED y 401` | `RateLimited` / `AuthExpired`, sin excepción |
| `Meta fetchRemoteProducts pagina y mapea retailer_id` | Idem |
| `ReconcileCatalogUseCase detecta LOCAL_ONLY, REMOTE_ONLY, PRICE_MISMATCH, STOCK_MISMATCH` | Con puertos falsos |
| `ReconcileCatalogUseCase tolera que un canal falle` | El otro canal sigue reportando |

### E1 — Publicación inicial y `publicaciones` (5b) · esfuerzo L

**Problema:** nada crea filas en `publicaciones` (salvo el `OutboxWorker`, con `external_id = sku`), por lo que el trigger de stock no tiene a quién encolar. Además, `publishProduct` de Shopify devuelve el ID del **producto**, pero `setStock` necesita el `inventoryItemId`; la tabla ya tiene la columna `external_inventory_id` para eso y nadie la usa.

**Cambio:**

1. `ShopifyGraphQLPayloads.buildProductCreatePayload`: pedir también `variants { id inventoryItem { id } }` y devolver un `PublishResult(externalId, externalInventoryId)`. Cambiar la firma del puerto: `publishProduct(...): PortResult<PublishResult>` (Meta usará `externalInventoryId = null`).
2. Caso de uso `PublishProductUseCase(repoPublicaciones, adapters)`: construye el `PublicProductDto` desde la base, llama al adaptador y **solo si hay éxito** hace `upsert` en `publicaciones` (`external_id`, `external_inventory_id`, `stock_publicado`, `publicado = true`).
3. Ruta `POST /api/articulos/{key}/publicar?canal=shopify|meta_catalogo` y botón en el dashboard. Con `CHANNEL_WRITE_ENABLED=false` hace un **ensayo**: muestra el payload que enviaría y no escribe nada.
4. Quitar del `OutboxWorker` el `insert` de `publicaciones` con `externalId = sku`.
5. Reemplazar `AppStore.channelMappings` por una lectura de `publicaciones` (el dashboard y la saga dejan de depender del mapa en memoria).

**Pruebas:**

| Prueba | Tipo | Verifica |
|---|---|---|
| `publishProduct devuelve ID de producto e inventoryItemId` | Adaptador (mock) | Ambos IDs parseados del JSON de ejemplo |
| `PublishProductUseCase guarda publicaciones solo si el canal confirma` | Unitaria con fakes | Error del canal → sin fila |
| `PublishProductUseCase en modo lectura no llama al adaptador` | Unitaria | Se devuelve el ensayo, 0 llamadas |
| `ruta publicar rechaza artículo inactivo o sin precio publicado` | Ruta | 400 con mensaje |
| `el trigger encola stock para un producto publicado` | SQL | Tras insertar en `publicaciones` y cambiar `cantidad`, hay fila en `outbox` |

### E2 — Outbox correcto (5b) · esfuerzo M

**Problema** (`OutboxWorker.processBatch`):

- Llama `adapter.setStock(productKey, entry.sku, ...)`: pasa el **SKU** como ID externo, pero Shopify espera el `inventoryItemId`.
- Ignora los errores `NO_IMPLEMENTADO`, deja `allSuccess = true`, marca el evento `SENT` y actualiza `stock_publicado` aunque nada se haya enviado (la base afirmaría que Meta tiene un stock que nunca recibió).
- No filtra por `tipo`, aunque la tabla admite otros tipos (`recipient_id` para reintentos de mensajes).

**Cambio:**

1. Para cada entrada, leer `external_inventory_id` (o `external_id` según el canal) de `publicaciones` y pasarlo a `setStock`.
2. Resultado `NO_IMPLEMENTADO` → estado nuevo `SKIPPED_NOT_IMPLEMENTED` (agregarlo a `ck_outbox_estado` en `V003`), **sin** tocar `stock_publicado`.
3. `Transient` y `RateLimited` → `FAILED` con `proxima_ejecucion` creciente (backoff exponencial, respetando `retryAfterSeconds`); `Permanent` o superar `maxRetries` → `DEAD`; `AuthExpired` → pausar el canal y avisar en el dashboard.
4. Filtrar `tipo IN ('stock_changed', 'product_updated')`.
5. Separar la lógica en una clase pura (`OutboxProcessor`) que reciba interfaces (`OutboxStore`, `PublicacionesLookup`, adaptadores) para poder probarla con fakes sin base de datos.
6. Mostrar en el dashboard el conteo por estado del outbox (pendientes, fallidos, muertos, omitidos).

**Pruebas** (`OutboxProcessorTest`, con fakes):

| Prueba | Verifica |
|---|---|
| `usa external_inventory_id y no el SKU` | El adaptador falso recibe el ID de la publicación |
| `NO_IMPLEMENTADO queda SKIPPED_NOT_IMPLEMENTED y no actualiza stock_publicado` | Estado y tabla de publicaciones intactos |
| `modo solo lectura marca SKIPPED_READ_ONLY` | 0 llamadas al adaptador |
| `error transitorio reprograma con backoff creciente` | `proxima_ejecucion` aumenta en cada intento |
| `RateLimited respeta retryAfterSeconds` | Reprogramación ≥ ese tiempo |
| `Permanent o agotar reintentos pasa a DEAD` | Estado y `ultimo_error` |
| `no procesa tipos distintos de stock/producto` | Una fila con `recipient_id` queda intacta |
| `dos workers no toman la misma entrada` | El `UPDATE ... WHERE estado IN (...)` devuelve 0 filas al segundo |
| `SQL: el trigger no avisa al canal de origen` | Venta por Shopify → solo se encola Meta |
| `SQL: índice único evita duplicados por (sku, canal, versión)` | Segundo encolado ignorado |

### E3 — Retirar la saga en memoria (5b) · esfuerzo S

**Problema:** `AntiOversellSaga` se invoca tras cada venta (`WebhookRoutes`, `PagosRoutes`) pero recibe `AppStore.channelMappings`, que nunca se llena, así que no envía nada. El trigger + outbox ya cubren el mismo caso (y descuentan las reservas vigentes).

**Cambio:** eliminar las dos llamadas a `executeRetractionSaga`, marcar la clase `@Deprecated` (o borrarla con su prueba tras confirmar que nada más la usa) y documentar que el camino es trigger → outbox → adaptador. La latencia queda acotada por el intervalo del worker (hoy 5 s); si el plan original pedía "en segundos", se puede bajar a 2 s.

**Pruebas:**

| Prueba | Verifica |
|---|---|
| `SQL: venta en WhatsApp encola stock hacia Shopify y Meta` | Dos filas en `outbox` con `stock_objetivo = cantidad − reservas` |
| `SQL: stock agotado encola 0` | `stock_objetivo = 0` |
| `Integración con fakes: venta → worker → setStock(0) en el otro canal` | Un único `setStock` por canal, sin duplicados |
| Búsqueda en CI | Ningún `main` referencia `executeRetractionSaga` |

### E4 — Cambios de precio, título y estado (5c) · esfuerzo L

**Problema:** `ShopifyCatalogAdapter.updateProduct` devuelve `Ok` sin hacer nada (engañoso), y nada propaga cambios de precio, título, descripción, archivado o restaurado.

**Cambio:**

1. En `V003`: segunda función/trigger `fn_encolar_producto` sobre `inventario` cuando cambian `precio_publicado`, `item`, `descripcion`, `estado` o `archivado_en`, que encola `tipo = 'product_updated'` por canal publicado (índice único análogo al de stock).
2. `ShopifyCatalogAdapter.updateProduct` real (mutación de actualización de producto/variante). 🔎
3. Mientras un adaptador no implemente `updateProduct`, debe devolver `Permanent("NO_IMPLEMENTADO", ...)` en vez de `Ok`.
4. Archivar o marcar inactivo un artículo debe poner stock 0 (o `unpublish`) en los canales.
5. `PublicProductDto` nunca incluye costo ni precio mínimo (ya existe `SecurityFugaTest`; extenderla).

**Pruebas:**

| Prueba | Verifica |
|---|---|
| `SQL: cambiar precio_publicado encola product_updated por canal publicado` | Filas en `outbox` |
| `SQL: cambiar solo el costo no encola nada` | 0 filas |
| `Shopify updateProduct envía la mutación esperada` | Cuerpo de la petición (mock) |
| `adaptador sin implementar no devuelve Ok` | `Permanent("NO_IMPLEMENTADO")` |
| `archivar artículo deja stock 0 en canales` | Cola o llamada `unpublish` |
| `SecurityFugaTest: el payload a canales no contiene costo ni PM` | Búsqueda en el JSON enviado |

### E7 — Adaptador de Meta Catalog (5d) · esfuerzo L 🔎

**Problema:** todos los métodos de `MetaShopAdapter` devuelven `NO_IMPLEMENTADO`.

**Cambio:** implementar sobre el `HttpClient` de Ktor (como Shopify), leyendo `META_CATALOG_ID`, `META_ACCESS_TOKEN` y `META_GRAPH_API_VERSION` de la configuración (la clave la pone el dueño en su entorno; el código no la contiene).

- `publishProduct`, `updateProduct`, `setStock` y `unpublishProduct` mediante la **API por lotes del catálogo** (creación/actualización/borrado de ítems identificados por `retailer_id = SKU`, con precio en COP, disponibilidad y cantidad). 🔎 validar el endpoint, los campos obligatorios y el formato de precio.
- `fetchRemoteProducts` según E5.
- Mapeo de errores: límite de uso → `RateLimited`; token vencido o inválido → `AuthExpired`; producto rechazado por Meta → `Permanent` con el motivo; 5xx y red → `Transient`.
- En modo `CATALOG_ONLY` (Colombia) no intentar operaciones de checkout.
- Para Meta, `externalInventoryId = null` y `external_id = retailer_id`.

**Pruebas** (`MetaShopAdapterTest`, `ktor-client-mock`):

| Prueba | Verifica |
|---|---|
| `publishProduct arma el lote con retailer_id, precio COP y disponibilidad` | Cuerpo y URL de la petición |
| `setStock con 0 marca el ítem sin disponibilidad` | Cuerpo |
| `429 / código de límite → RateLimited` | Mapeo |
| `token inválido → AuthExpired` | Mapeo |
| `ítem rechazado → Permanent con motivo` | Mapeo |
| `5xx → Transient` | Mapeo |
| `sin credenciales → CONFIG_ERROR y 0 llamadas HTTP` | Sin regresión |
| `la clave no aparece en logs ni en el cuerpo` | Búsqueda del valor en la salida capturada |
| **Prueba manual (staging)** | Con un catálogo de pruebas: publicar 1 producto, cambiar precio, poner stock 0, comprobar en Commerce Manager |

### E6 — Entrada desde Shopify: deriva de inventario (5e) · esfuerzo M (según D4)

**Problema:** el webhook `inventory/update` se interpreta pero no hace nada; los cambios hechos en el admin de Shopify no llegan a tu sistema.

**Cambio:**

1. `processInventoryUpdated` emite un evento de deriva con `inventory_item_id` y `available`.
2. En la ruta: buscar la publicación por `external_inventory_id`; si `available == stock_publicado` es el **eco** de nuestra propia escritura → ignorar. Si difiere, insertar en `eventos_sin_resolver` (tipo `DERIVA_STOCK`, con SKU, valor local y valor remoto) para que un humano lo resuelva desde la pantalla que ya existe (`/api/eventos-sin-resolver`). Usar `StockDriftDetector` si encaja.
3. Idempotencia con `X-Shopify-Event-Id`, como en los pedidos.
4. Pedidos y cancelaciones se mantienen como están (siguen actualizando stock local).

**Pruebas:**

| Prueba | Verifica |
|---|---|
| `ShopifyWebhookHandler: inventory/update emite evento con item y cantidad` | Parseo |
| `eco de nuestra escritura se ignora` | `available == stock_publicado` → 0 filas nuevas |
| `diferencia genera DERIVA_STOCK una sola vez` | Un evento repetido (mismo `X-Shopify-Event-Id`) no duplica |
| `firma HMAC inválida sigue dando 401` | Sin regresión (`ShopifyHmacTest`) |
| `producto no publicado se ignora con log` | Sin fila ni error |

**Criterio de aceptación de la Fase 5 (en tienda de pruebas):** (1) publicar un producto desde el dashboard lo crea en Shopify y guarda ambos IDs; (2) una venta por WhatsApp deja el stock en 0 en Shopify y Meta en menos de 2 ciclos del worker; (3) un cambio de precio en el dashboard llega a ambos canales; (4) un cambio de stock manual en Shopify aparece como deriva; (5) con `CHANNEL_WRITE_ENABLED=false` no sale ninguna escritura; (6) la reconciliación muestra 0 diferencias tras sincronizar.

---

## Fase 6 — Limpieza (G1) · esfuerzo M

- Dividir `DashboardRenderer.kt` (71 KB) por vistas (`Inventario`, `Chat`, `Integraciones`, `Config`, `Layout`) sin cambiar el HTML generado. **Prueba de seguridad del refactor:** `DashboardRendererTest` + una prueba "golden" que compare el HTML antes y después para un conjunto fijo de datos.
- Mover `correciones/` (nombre con errata, y con carpetas de nombre mal codificado) y el PDF del Plan Maestro a `docs/` (o fuera del repo de código), y confirmar si `db/migracion_fase2.sql` está superado por las migraciones `V00x` para no mantener dos fuentes.
- Actualizar `README.md`, `MANUAL_DE_USO.md` y crear `MANUAL_DE_CAMBIOS_v6.md` (qué cambió y qué falta, como en las rondas anteriores).

---

## 9. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| **Editar migraciones existentes** (`V001`, `V003`, `V004`) rompe la validación de checksums de Flyway en bases ya migradas | En desarrollo, recrear la base. En bases con datos, ejecutar una vez `flyway repair` (o alinear checksums) tras revisar el diff, y hacer copia de seguridad antes. Si prefieres evitarlo, la alternativa son migraciones nuevas (`V005`, `V006`), a costa de sumar archivos. |
| Las funciones SQL nuevas (`asignar_reparto_venta`, `fn_encolar_producto`) quedan sin probar | Se cubren con `db/pruebas_fase3_*.sql` en el job `sql-tests` de CI |
| Endpoints de Meta y Shopify cambian entre versiones | Los puntos 🔎 se validan contra la documentación antes de implementar; las versiones quedan configurables |
| Los modelos Gemini 3.x cambian parámetros o se retiran | La cadena es configurable (`GEMINI_MODELS`); un 404 salta al siguiente modelo y avisa; la prueba de contrato en vivo detecta cambios antes de desplegar |
| Enviar datos erróneos a canales reales | `CHANNEL_WRITE_ENABLED=false` por defecto, ensayo (dry-run) en la publicación, y pruebas solo contra tienda y catálogo de desarrollo |
| Cambiar la escalera de negociación altera la conversión de ventas | Es una decisión de negocio (D2); se puede probar primero con `/api/sim/chat` |

## 10. Definición de terminado (global)

1. `./gradlew test` en verde y el job `sql-tests` en verde en CI.
2. `docker compose up` arranca con la configuración mínima documentada.
3. Cada hallazgo de la tabla de la sección 1 tiene al menos una prueba que **falla antes** del cambio y **pasa después**.
4. `MANUAL_DE_CAMBIOS_v6.md` lista cada ID resuelto, los archivos tocados y lo que queda pendiente.
5. Nada de lo implementado escribe en Shopify o Meta con `CHANNEL_WRITE_ENABLED=false`.

## 11. Resumen de pruebas nuevas por área

| Área | Pruebas nuevas (aprox.) | Dónde |
|---|---|---|
| Despliegue (A1) | 8 | `app/.../DashboardSecurityTest`, `infra/.../ComposeConfigTest`, CI |
| Negociación (B1–B3) | 12 | `commerce/.../NegotiationEngineTest` |
| Webhook Meta (C1) | 5 | `app/.../WebhookMetaRouteTest` (`testApplication`) |
| Gemini (F1) | 23 + 1 de contrato en vivo | `app/.../GeminiLlmAdapterTest`, `GeminiFailoverTest`, `AppConfigTest` |
| Porcentajes y socios (S3, S5, S4) | ~20 | `commerce/.../PorcentajeParserTest`, `SocioServiceTest`, rutas |
| Socios en SQL (S1, S2) | ~14 | `db/pruebas_fase3_socios.sql` |
| Estado en memoria (D1) | 4 | `app/.../CatalogStateConcurrencyTest` |
| Sincronización (E1–E7) | ~45 | adaptadores (mock HTTP), `OutboxProcessorTest`, `db/pruebas_fase3_canales.sql` |
