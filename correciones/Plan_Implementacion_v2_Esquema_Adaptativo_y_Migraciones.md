# Plan de Implementación v2 — Esquema adaptativo y migraciones (Supabase)

**Proyecto:** Tienda Virtual Multicanal (Kotlin / Ktor / Exposed / Flyway / PostgreSQL en Supabase)
**Versión:** 2 — reemplaza a `Plan_Implementacion_Esquema_y_Migraciones.md`

> **Alcance de la revisión.** Estático: leí `Tables.kt`, los repositorios Exposed, `DatabaseFactory.kt`, `Application.kt` (arranque e importación de Excel), `ExcelPreviewService.kt`, `ExcelV2Parser.kt`, `FinancialMath.kt`, las migraciones `V001`–`V013`, los scripts de `db/` y tu `NEGOCIO.xlsx`. **No ejecuté nada contra PostgreSQL** (el entorno no tiene base). Todo SQL de este plan debe probarse (F4 y F5) antes de usarse en Supabase.

---

## 1. Objetivo

Reemplazar las 13 migraciones actuales (más los scripts manuales de `db/`) por un esquema base coherente que funcione en **cualquier estado de la base**, sin pasos manuales en el SQL Editor:

1. **Supabase vacío** (tu caso hoy: aún no has creado la cuenta): al arrancar la app se crean el esquema, las tablas, las restricciones, los triggers, las funciones y las vistas.
2. **Supabase con datos:** la app **adopta** lo que existe, completa lo que falte sin borrar nada y trabaja con esos datos.
3. **Excel:** importar y exportar sigue funcionando con **el mismo formato de archivo** (`NEGOCIO.xlsx` es solo la guía de ese formato, no datos a cargar).
4. **Reglas de negocio** actuales conservadas (costo total, precio mínimo, validaciones de venta, ventas y métricas calculadas, historial).
5. **Exactamente lo que el código Kotlin usa hoy**, sin cambiar su lógica de negocio.
6. Migraciones divididas por dominio (pocos archivos), no 13 pasos ni uno gigante.

---

## 2. Hallazgo clave (heredado de la v1)

El código Kotlin sigue usando el modelo "fase 2": `inventario` con `pct_antonio`/`pct_javier` y las tablas `reparaciones`, `ventas`, `metricas`, `historial`. Ninguna clase usa las tablas normalizadas de `V006`–`V008` (`socio`, `inventario_reparto`, `reparacion`, `venta_reparto`, `cliente`, `cat_canal`, `cat_condicion`, `producto_foto`, `publicacion_canal`), y `V008` borra tablas y columnas que el código necesita.

**Decisión:** el esquema base implementa el modelo que el código usa hoy. La normalización por socios es una fase posterior y opcional (F8).

---

## 3. Problemas detectados y cómo se resuelven

Los marcados **(verificado)** los confirmé leyendo el código en esta revisión.

| # | Problema | Evidencia | Solución |
|---|---|---|---|
| P1 | Nadie crea `inventario`, `historial` ni `venta_items` **(verificado)** | Ningún `CREATE TABLE` para esas tablas en `db/` ni en las migraciones | `V001` y `V002` las crean |
| P2 | `V008` borra tablas y columnas que el código usa | `ReparacionesTable`, `MetricasTable`, `pctAntonio` | No se replica ese borrado |
| P3 | Dos definiciones incompatibles de `ventas` | `V002` (por conversación) vs `Tables.kt` (una fila por producto) | Se usa la de `Tables.kt` |
| P4 | `outbox` con tres formas distintas | `V003`, `V010`, `OutboxTable` | Se usa la de `OutboxTable` (PK `event_id`) |
| P5 | `vender_unidades`/`devolver_unidades` usan columnas inexistentes | `cantidad_total`, `cantidad_reservada`, `version` vs `cantidad` | Se conserva `cantidad` y se agregan `cantidad_reservada` y `version` |
| P6 | `venta_items` nunca se escribe | `vender_unidades` no guarda `order_id`/`line_id` | `vender_unidades` inserta en `venta_items` |
| P7 | Kotlin manda `line_id = ''` y la función compara `IS NULL` | `ExposedInventarioRepository.devolverUnidades` | `NULLIF(p_line_id, '')` en la función |
| P8 | Las reglas R1–R11 viven en un script manual que nunca se ejecutó | Cabecera de `migracion_fase2.sql` | Pasan a la migración repetible `R__` |
| P9 | `V001`–`V003` usan el rol `app_user`, que no existe en Supabase | `CREATE POLICY ... TO app_user` | El rol se crea si no existe |
| P10 | `baselineOnMigrate(true)` salta `V001` en una base con tablas **(verificado)** | `DatabaseFactory.kt` usa el baseline por defecto (versión 1) | `baselineVersion = 0` (ver §5) |
| P11 | Si Flyway falla, la app arranca igual sin base **(verificado)** | `Application.kt`: el `catch` solo registra el error | En producción, abortar el arranque |
| **P12** | **Importar Excel: la base sobrescribe el PM del archivo** **(verificado)** | El importador manda `precioMinimo = p.minimumPrice` y `costoTotal = p.cost`; el trigger recalcula `precio_minimo = costo_total × 1,5` | Decisión D10 (la fórmula manda) y F3 |
| **P13** | **La vista previa de Excel mostraría un "cambio de PM" falso en cada importación** | `ExcelPreviewService.compareFields` compara el PM del Excel contra el PM de la base, que ya es el calculado | La vista previa compara contra el PM esperado (F3) |
| **P14** | **Un artículo `Publicado` con PP menor al mínimo calculado cancelaría toda la importación** | R5 lanza excepción dentro de la transacción única de importación | La vista previa lo detecta fila por fila y bloquea la confirmación (F3) |
| **P15** | **Una fila `Vendido` en Excel sería rechazada por la base** | R6 exige `vendido_en` y reparto 100 %; la plantilla de Excel no trae ninguno | La vista previa la marca como bloqueada con instrucciones (F3) |
| **P16** | **Tras importar, la memoria queda con el PM del Excel y la base con el calculado** | Después del commit, `Application.kt` guarda en `AppStore` los valores del archivo, no los de la base | Recargar desde la base tras el commit (F3) |
| P17 | No existe ninguna prueba de esquema **(verificado)** | `FlywayMigrationTest` solo prueba el rechazo sin SSL | Pruebas de F4 |

---

## 4. Decisiones de diseño (a confirmar antes de escribir SQL)

| ID | Decisión | Recomendación | Motivo |
|---|---|---|---|
| D1 | Qué modelo implementa el esquema base | Modelo actual del código (`pct_*`, `reparaciones`, `ventas`, `metricas`) | Es lo que Kotlin lee y escribe |
| D2 | Escenario de la base | **Arranque adaptativo:** vacía → crear; con datos compatibles → adoptar y completar; incompatible → abortar con informe | Cubre tu requisito sin pasos manuales |
| D3 | Stock: `cantidad` vs `cantidad_total` | Conservar `cantidad`; agregar `cantidad_reservada` y `version` | No toca el código Kotlin |
| D4 | `ventas` | Forma de `Tables.kt` (una fila por producto, calculada por trigger) | El código y las reglas la usan |
| D5 | `outbox` | Forma de `OutboxTable` (PK `event_id`) | El código la usa |
| D6 | `publicaciones` vs `publicacion_canal` | `publicaciones`; `publicacion_canal` va a F8 | El código la usa |
| D7 | FK `reservas.product_key → inventario(key)` | Sí, con `ON UPDATE CASCADE` | Integridad del stock |
| D8 | Rol de la app | Crear `app_user` (NOLOGIN) y mantener RLS; la app se conecta como `postgres`, que ignora RLS | Cierra la API REST de Supabase sin cambiar la conexión |
| D9 | Tablas de clientes | Fuera (el código no las usa); se reevalúa en F8 | Evita tablas muertas |
| **D10** | **Precio mínimo al importar Excel** | **La fórmula de la base manda** (costo total × 1,5, redondeado hacia arriba). El PM del Excel se ignora y la vista previa lo avisa | Es la razón de ser de la regla R1; la alternativa (respetar el PM del Excel) rompería esa regla |
| **D11** | **Filas `Vendido` en Excel** | Se bloquean en la vista previa con el mensaje "registra la venta desde el dashboard" | La base exige fecha, precio de venta y reparto entre socios, datos que el Excel no trae |
| **D12** | **Formato del Excel** | **Sin cambios** (columnas, hojas y orden iguales a `NEGOCIO.xlsx`). La columna PM sigue siendo obligatoria en el archivo, aunque la base la recalcule | Compatibilidad total con tu guía actual |
| **D13** | **Restricciones sobre datos existentes** | Se agregan como `NOT VALID` y se reportan las filas que no las cumplen, en vez de fallar | Permite adoptar datos "sucios" sin perderlos |
| **D14** | **Recalcular datos viejos** | Nunca automático. `R__` no reescribe datos; recalcular costo y PM de todo el inventario es un script explícito y opcional | Evita cambiar precios sin que lo decidas |

---

## 5. Arranque adaptativo (nuevo)

### 5.1 Diagnóstico previo (solo lectura)

Una clase Kotlin nueva, `SchemaPreflight`, consulta `information_schema` y `pg_constraint` **antes** de ejecutar Flyway. No modifica nada. Clasifica la base en un estado y lo escribe en el log:

| Estado | Cómo se detecta | Qué hace la app |
|---|---|---|
| **VACÍA** | No existe el esquema `tienda`, o existe sin objetos | Flyway crea todo desde `V001` |
| **ADOPTABLE** | Existen tablas del modelo, sin historial de Flyway, y todas las columnas existentes tienen tipo compatible con `Tables.kt` | Flyway adopta con `baselineVersion = 0`, aplica `V001`–`V004` en modo aditivo y completa lo que falte |
| **GESTIONADA** | Existe `flyway_schema_history` coherente con nuestras versiones | Flyway aplica solo las migraciones pendientes |
| **INCOMPATIBLE** | Una columna con tipo distinto al esperado, o un `flyway_schema_history` con versiones que no coinciden con nuestros archivos | **Aborta** con un informe de diferencias. No altera nada |

Además informa, solo informa, cuántas filas de los datos existentes violarían las restricciones nuevas (ver D13).

### 5.2 Migraciones aditivas

Todo el SQL de `V001`–`V004` es idempotente y no destructivo:
- Tablas: `CREATE TABLE IF NOT EXISTS`.
- Columnas faltantes: `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (con valor por defecto cuando es `NOT NULL`).
- Restricciones: `ADD CONSTRAINT ... NOT VALID`, solo si no existen (D13). Una restricción `NOT VALID` no revisa filas viejas, pero sí revisa toda fila nueva o modificada.
- Datos semilla (`config`, `cat_estado`): `INSERT ... ON CONFLICT DO NOTHING`.
- **Nunca** `DROP`, `TRUNCATE` ni `DELETE` sobre datos.

### 5.3 Configuración de Flyway

| Opción | Valor | Motivo |
|---|---|---|
| `baselineOnMigrate` | `true` | Permite adoptar una base con tablas y sin historial de Flyway |
| `baselineVersion` | **`0`** | Con el valor por defecto (1) Flyway se salta `V001`; con 0 aplica `V001`–`V004` aunque ya existan tablas (P10) |
| `validateOnMigrate` | `true` | Si alguien edita una migración ya aplicada, Flyway se niega a arrancar |
| `outOfOrder` | `false` | Evita aplicar migraciones fuera de secuencia |
| `cleanDisabled` | `true` | Nunca se usa `flyway clean` sobre Supabase |

### 5.4 Comportamiento ante fallos

- `APP_ENV=production`: si el diagnóstico da INCOMPATIBLE o Flyway falla, la app **no arranca** (P11).
- `APP_ENV=local`: sigue arrancando en modo memoria, con un aviso visible en el log y en el tablero.

> **Qué no puede hacer este mecanismo:** corregir automáticamente una tabla existente con columnas de tipo distinto (por ejemplo, `precio_compra` como `TEXT`). Eso se detecta y se informa, pero cambiar tipos sobre datos reales requiere decisión humana.

---

## 6. Esquema objetivo (lo que debe crear el SQL)

Todo en el esquema `tienda`. Tipos tomados de `Tables.kt`.

### 6.1 `V001__base_inventario.sql`

| Elemento | Detalle |
|---|---|
| Esquema y rol | `CREATE SCHEMA IF NOT EXISTS tienda`; `app_user` (NOLOGIN), creado solo si no existe |
| `config` | `clave TEXT PK`, `valor NUMERIC NOT NULL`. Semillas: `roi_minimo_pct=50`, `periodo_corto_dias=15`, `periodo_medio_dias=30` |
| `cat_tipo` | `tipo TEXT PK` (se autoregistra por trigger) |
| `cat_estado` | `estado TEXT PK`. Semillas: Comprado, Publicado, Negociación, Vendido, Inactivo, Otro |
| `historial` | `id BIGINT` identidad PK, `tabla`, `key`, `operacion`, `cambios JSONB`, `origen`, `usuario NULL`, `creado_en TIMESTAMPTZ DEFAULT now()` |
| `inventario` | Ver columnas abajo |
| `reparaciones` | `key TEXT PK → inventario(key) ON DELETE CASCADE`, `precio_reparacion BIGINT NOT NULL DEFAULT 0 CHECK (>= 0)`, `detalles TEXT`, `created_at`, `updated_at` |

**Columnas de `inventario`:**

| Columna | Tipo | Notas |
|---|---|---|
| `key` | TEXT PK | Índice único adicional sobre `UPPER(key)` |
| `item` | TEXT NOT NULL | |
| `tipo` | TEXT | FK a `cat_tipo` |
| `link` | TEXT | |
| `fotos` | TEXT NOT NULL DEFAULT '' | URLs separadas por salto de línea |
| `estado` | TEXT NOT NULL | FK a `cat_estado` |
| `fecha_compra`, `fecha_publicacion`, `fecha_venta` | DATE | |
| `precio_publicado` | BIGINT | |
| `precio_minimo` | BIGINT | **Lo calcula el trigger** |
| `precio_compra` | BIGINT NOT NULL | CHECK >= 0 |
| `vendido_en` | BIGINT | |
| `canal_venta` | TEXT | Se normaliza a minúscula |
| `requiere_reparacion` | BOOLEAN NOT NULL DEFAULT false | |
| `pct_antonio`, `pct_javier` | NUMERIC(5,2) NOT NULL DEFAULT 0 | CHECK entre 0 y 100; la suma es 0 o 100 |
| `costo_total` | BIGINT NOT NULL DEFAULT 0 | **Lo calcula el trigger** |
| `descripcion`, `marca`, `condicion`, `ubicacion` | TEXT | |
| `cantidad` | INTEGER NOT NULL DEFAULT 1 | CHECK >= 0 (D3) |
| `cantidad_reservada` | INTEGER NOT NULL DEFAULT 0 | CHECK >= 0 y <= `cantidad` (D3) |
| `version` | BIGINT NOT NULL DEFAULT 1 | Sincronización con canales (D3) |
| `archivado_en`, `archivado_por`, `motivo_archivo` | TIMESTAMPTZ / TEXT / TEXT | |
| `created_at`, `updated_at` | TIMESTAMPTZ DEFAULT now() | |

Restricciones de `inventario`: `ck_pct_rango`, `ck_pct_suma`, `ck_inventario_montos` (montos >= 0), índices por `estado` y `tipo`. Todas con `NOT VALID` si la tabla ya existe (D13).

### 6.2 `V002__ventas_y_metricas.sql`

| Tabla | Columnas y reglas |
|---|---|
| `ventas` | `key TEXT PK → inventario(key)`, `fecha_venta DATE NOT NULL`, `vendido_en BIGINT NOT NULL CHECK (>= 0)`, `canal`, `ingresos`, `costo_total`, `ganancia_neta` (BIGINT, puede ser negativa), `margen_pct NUMERIC(7,2)`, `tiempo_dias`, `dias_publicado` (INTEGER >= 0), `periodo` (`corto`/`medio`/`largo`), `created_at`, `updated_at`. Índice por `fecha_venta` |
| `metricas` | `key TEXT PK → ventas(key) ON DELETE CASCADE`, `ingresos_antonio`, `ganancias_antonio`, `ingresos_javier`, `ganancias_javier` (BIGINT NOT NULL) |
| `venta_items` | `item_id` PK, `order_id`, `line_id`, `product_key → inventario(key)`, `quantity`, `unit_price_cop`, `canal`, `created_at`. Índice por `(order_id, line_id)` |

### 6.3 `V003__mensajeria_y_reservas.sql`

| Tabla | Columnas y reglas |
|---|---|
| `conversaciones` | `conversation_id` PK, `customer_id`, `channel`, `state DEFAULT 'IDLE'`, `active_product_key`, `agreed_price_cop`, `counter_offer_count DEFAULT 0`, `pending_counter_cop`, `pending_counter_at`, `current_reservation_id`, `mensajes JSONB`, `last_updated`, `created_at`. Índices por `(channel, customer_id)` y `last_updated` |
| `reservas` | `reservation_id` PK, `conversation_id`, `product_key` (FK, D7), `quantity > 0`, `status DEFAULT 'ACTIVE'` con CHECK de 5 estados (`ACTIVE`, `CONFIRMED`, `CANCELLED`, `EXPIRED`, `CONSUMED`), `expires_at`, `order_ref`, `created_at`, `updated_at`. Índices por conversación, producto y `(status, expires_at)` |
| `outbox` | PK `event_id`; `tipo DEFAULT 'stock_changed'`, `sku`, `canal`, `recipient_id NULL`, `version DEFAULT 1`, `stock_objetivo DEFAULT 0`, `payload_json NULL`, `estado DEFAULT 'PENDING'` (CHECK `PENDING`/`SENDING`/`SENT`/`FAILED`/`DEAD`), `intentos >= 0`, `proxima_ejecucion`, `creado_en`, `actualizado_en NULL`, `ultimo_error`. Índices por `(estado, proxima_ejecucion)` y `(sku, canal, version)` |
| `eventos_entrantes` | `id` PK, `canal`, `evento_externo_id`, `payload JSONB`, `estado DEFAULT 'RECIBIDO'`, `intentos`, `proxima_ejecucion`, `error_msg`, `creado_en`, `procesado_en`; UNIQUE `(canal, evento_externo_id)` |
| `eventos_procesados` | PK `(canal, evento_externo_id)`, `estado DEFAULT 'DONE'`, `procesado_en`, `actualizado_en` |
| `eventos_sin_resolver` | `id BIGSERIAL` PK, `canal`, `tipo`, `referencia`, `sku`, `motivo`, `payload JSONB`, `estado` (CHECK `PENDIENTE`/`RESUELTO`/`DESCARTADO`), `creado_en`, `resuelto_en` |

### 6.4 `V004__canales_e_importaciones.sql`

| Tabla | Columnas y reglas |
|---|---|
| `publicaciones` | PK `(sku, canal)`, `external_id NOT NULL`, `publicado DEFAULT true`, `stock_publicado DEFAULT 0`, `ultima_sincronizacion`. Índice por `(canal, external_id)` |
| `importaciones` | `id` PK, `usuario`, `fecha`, `hash_archivo`, `estado DEFAULT 'PENDING'` (CHECK `PENDING`/`SUCCESS`/`FAILED`), `conteos JSONB`, `errores JSONB` |

> **A verificar en F5:** `Application.kt` guarda las importaciones con `estado = "COMPLETED"`, pero el CHECK propuesto solo admite `PENDING`/`SUCCESS`/`FAILED`. Hay que alinear uno de los dos (lo más simple: agregar `COMPLETED` al CHECK, sin tocar Kotlin).

### 6.5 `R__funciones_triggers_y_vistas.sql` (migración repetible)

Se vuelve a ejecutar sola cuando cambia el archivo. Usa `CREATE OR REPLACE` y `DROP TRIGGER IF EXISTS` antes de cada `CREATE TRIGGER`. **No modifica datos existentes** (D14).

| Grupo | Contenido |
|---|---|
| Fórmulas | `fn_config`, `fn_precio_minimo`, `fn_periodo` |
| Historial | `trg_historial_generico` y un trigger `trg_z_historial` en `inventario`, `reparaciones`, `ventas` y `metricas` |
| Inventario | `trg_inventario_antes` (normalización, validaciones, costo y precio mínimo) y `trg_inventario_despues` (reparación y venta) |
| Ventas | `sync_venta` (calcula `ventas` y `metricas`), `trg_bloquear_escritura_directa` |
| Reparaciones | `trg_reparaciones_antes` y `trg_reparaciones_despues` |
| Comodidad | `registrar_venta`, `anular_venta` |
| Stock atómico | `vender_unidades` y `devolver_unidades`, ajustadas a D3, D5, P6 y P7 |
| Vistas | `v_stock`, `v_ventas_detalle`, `v_resumen_mensual_socio`, `v_rendimiento_tipo`, `v_periodo_tipo` |
| Seguridad | RLS activado, `REVOKE` a `anon` y `authenticated` si existen, permisos a `app_user` |

---

## 7. Reglas de negocio que el esquema debe conservar

Tomadas de `db/migracion_fase2.sql`. Son los criterios de prueba de F4.

| Regla | Comportamiento |
|---|---|
| Costo total (R1) | `costo_total = precio_compra + precio de la reparación` (solo si `requiere_reparacion`) |
| Precio mínimo (R1/D1) | `precio_minimo = costo_total × (100 + roi_minimo_pct) / 100`, redondeado hacia arriba al peso (`roi_minimo_pct` = 50 en `config`). Kotlin tiene el espejo en `FinancialMath.precioMinimo` |
| Recalcular al cambiar la reparación (R2) | Un cambio de precio de reparación vuelve a calcular costo y mínimo del artículo |
| Crear fila de reparación (R3) | Al marcar `requiere_reparacion`, se crea la fila de `reparaciones` con precio 0 |
| No quitar reparación con precio (R4) | No se puede desmarcar `requiere_reparacion` si la reparación tiene precio > 0; si está en 0, la fila se borra |
| Publicar exige precio válido (R5) | `Publicado`/`Negociación` exigen `precio_publicado` y que no sea menor al mínimo. Solo se valida al crear o cuando cambian estado, precio o mínimo |
| Vender exige datos (R6) | `Vendido` exige `fecha_venta`, `vendido_en`, `pct_antonio + pct_javier = 100` y fechas coherentes |
| Ventas calculadas (R7/R8) | `ganancia_neta = vendido_en − costo_total`; `margen_pct`; `tiempo_dias` desde la compra; `dias_publicado` desde la publicación; `periodo` corto (< 15 días), medio (15 a 29), largo (30 o más) |
| Reparto entre socios (R7/R8) | Antonio redondeado al peso, Javier el resto, para que la suma cuadre exacto. Sin reparto no hay métricas |
| Vendido es inmutable (R9) | Un artículo vendido no admite cambios de compra, fecha de compra, reparación ni porcentajes. Para corregir: estado `Publicado` (anula la venta), editar y volver a vender |
| Anulación | Al dejar de estar `Vendido`, se borran `fecha_venta`, `vendido_en` y `canal_venta`, y se elimina la venta |
| Escritura directa bloqueada | `ventas` y `metricas` solo las modifican los triggers de `inventario` |
| Fechas | `fecha_publicacion` no puede ser anterior a `fecha_compra` |
| Normalización | `tipo` sin espacios sobrantes y se autoregistra en `cat_tipo`; `canal_venta` en minúscula |
| Historial (R11) | Cada cambio en `inventario`, `reparaciones`, `ventas` y `metricas` queda en `historial` con `origen` y `usuario` (`app.origen`/`app.usuario`, que ya fija `DatabaseFactory.tx`) |

> No encontré en el repositorio una regla identificada como "R10". Conviene confirmar contra tu plan cuál es.

---

## 8. Excel: importar y exportar (nuevo)

### 8.1 Lo que no cambia
- El formato del archivo: hojas `Inventario` y `Config`, y las columnas de `NEGOCIO.xlsx` (D12).
- La exportación (`ExcelGenerator`): solo lee `inventario`, no se ve afectada.
- El parser (`ExcelV2Parser`): la columna PM sigue siendo obligatoria y se sigue validando `PM > 0` y `PM <= PP`.
- La importación en dos pasos (vista previa y confirmación) y la protección por hash SHA-256 contra reimportar el mismo archivo.

### 8.2 Lo que cambia (la fórmula manda, D10)

| Qué | Cambio |
|---|---|
| Vista previa: comparación de PM (P13) | `ExcelPreviewService.compareFields` compara contra el PM **esperado** (`FinancialMath.precioMinimo` del costo) y no contra el PM del archivo |
| Vista previa: aviso (P12) | Si el PM del Excel difiere del esperado, agrega un `warning`: "PM del Excel ($95.000) será reemplazado por el calculado ($105.000)" |
| Vista previa: bloqueo por precio (P14) | Si el estado es `Publicado`/`Negociación` y PP < PM esperado, la fila va a `bloqueados` con su motivo y `canConfirm = false` |
| Vista previa: bloqueo por venta (P15, D11) | Una fila `Vendido` va a `bloqueados` con el motivo "registra la venta desde el dashboard" |
| Memoria tras importar (P16) | Después del commit, `AppStore` se recarga desde la base (con el mismo mapeo que usa el arranque) y no con los valores del archivo. Aplica a `/api/excel/confirmar` y `/api/excel/upload` |

El repositorio (`ExposedInventarioRepository.upsert`) y las rutas siguen igual. La importación sigue siendo una sola transacción: todo o nada.

> **Ejemplo con tu archivo:** `PROD-001` trae PM `$95.000` y costo `$70.000`. La base guardará `$105.000`. La vista previa avisará del reemplazo, y como PP `$120.000` ≥ `$105.000`, la fila se importa. Si PP fuera `$100.000`, la fila quedaría bloqueada antes de confirmar.

---

## 9. Fases de implementación

Cada fase = una rama = un PR, con el build en verde antes y después.

### F0 — Diagnóstico y decisiones (S)

1. Confirmar las decisiones D1–D14 de la §4.
2. **Crear tu proyecto en Supabase** (aún no existe): cuenta en supabase.com, nuevo proyecto, guardar la contraseña de la base de datos.
3. Tu escenario es **VACÍA**: no hay nada que adoptar y el arranque crea todo. Si algún día ya hubiera datos, el arranque adaptativo (§5) los trata solo.

### F1 — Escribir los archivos SQL (M)

1. Crear `V001`, `V002`, `V003`, `V004` y `R__` en `infra/src/main/resources/db/migrations/`, siguiendo la §6 y el modo aditivo de §5.2.
2. Mover las `V001`–`V013` actuales y los scripts de `db/` a `docs/legacy-sql/` (fuera del classpath), para conservar el historial sin que Flyway los ejecute.
3. Ajustar `vender_unidades` y `devolver_unidades` (P4–P7): `cantidad` en lugar de `cantidad_total`, `event_id` en el insert del outbox, insertar en `venta_items`, y `NULLIF(p_line_id, '')`.
4. Convertir `00_diagnostico_previo.sql` en una consulta de referencia en `docs/legacy-sql/` (el diagnóstico real pasa a ser `SchemaPreflight`).

**Entregable:** los 5 archivos SQL completos.

### F2 — Arranque adaptativo en Kotlin (M)

1. **Nuevo:** `SchemaPreflight.kt` en `infra/.../db/` (diagnóstico de solo lectura, §5.1).
2. `DatabaseFactory.kt`: ejecutar el diagnóstico antes de Flyway y configurar Flyway según §5.3.
3. `Application.kt` (solo el arranque): con `APP_ENV=production`, si el diagnóstico da INCOMPATIBLE o `connect()` falla, abortar con `exitProcess(1)` (P11).
4. `Tables.kt`: actualizar los comentarios que apuntan a `db/migracion_fase2.sql`.

**Entregable:** archivos Kotlin modificados completos.

### F3 — Compatibilidad con Excel (S)

1. `ExcelPreviewService.kt`: los cambios de la §8.2 (comparación, avisos, bloqueos).
2. `Application.kt`: recarga de `AppStore` desde la base tras el commit, en `/api/excel/confirmar` y `/api/excel/upload` (P16).
3. Sin cambios en `ExcelV2Parser`, `ExcelGenerator`, el repositorio ni `NEGOCIO.xlsx`.

**Entregable:** archivos Kotlin modificados completos.

### F4 — Pruebas contra PostgreSQL real (M)

1. Añadir Testcontainers (`postgres:16`) al catálogo de versiones y a `infra/build.gradle.kts` (solo `testImplementation`).
2. `SchemaMigrationTest`:
   - **Base vacía:** aplica las migraciones y termina sin errores.
   - **Idempotencia:** vuelve a ejecutarlas y no cambia nada.
   - **Base con datos (nuevo):** crea un esquema "antiguo" con filas, arranca y comprueba que los datos siguen intactos y que se completaron las columnas faltantes.
   - **Base incompatible (nuevo):** una columna con tipo distinto debe producir el estado INCOMPATIBLE y no alterar nada.
   - Compara las columnas de cada `Table` de Exposed con `information_schema.columns`; falla si falta alguna.
3. `ReglasNegocioTest`: portar los 11 casos de `db/pruebas_fase2.sql` (R1–R9, R11) como pruebas JUnit.
4. `StockAtomicoTest`: `vender_unidades` (éxito, `SIN_STOCK`, evento repetido, consumo de reserva) y `devolver_unidades` (con y sin `line_id`).
5. `ExcelPreviewServiceTest` (existente): ampliar con PM reemplazado, PP menor al mínimo, fila `Vendido` bloqueada y comparación sin falsos cambios.
6. `ExcelImportacionDbTest` (nuevo): importar el formato de `NEGOCIO.xlsx` contra la base real y exportar de vuelta.

> Los scripts `migracion_fase2.sql` y `pruebas_fase2.sql` declaran que **nunca se ejecutaron**. Estas pruebas serán su primera verificación real.

### F5 — Ejecutar y corregir hasta que todo pase (M)

1. `./gradlew :infra:test` con Docker disponible.
2. Corregir el SQL de F1 según los fallos. Mientras no se haya aplicado en Supabase, los archivos `V00x` se pueden reescribir libremente.

### F6 — Aplicar en Supabase (S)

1. En `local.properties`, usar el **Session pooler** (puerto 5432), tal como indica `local.properties.example`: `DATABASE_URL=jdbc:postgresql://aws-0-REGION.pooler.supabase.com:5432/postgres?sslmode=require`, `DATABASE_USER=postgres.ID_DEL_PROYECTO` y `DATABASE_PASSWORD`. El modo transacción (6543) puede romper Flyway.
2. Arrancar la app y revisar el log: debe aparecer el estado del diagnóstico (**VACÍA**), **"Flyway: 5 migraciones aplicadas"** y **"Conexión a Supabase establecida correctamente"**.
3. En el Table Editor de Supabase, elegir el esquema `tienda` y comprobar las tablas de la §6.
4. Humo manual: insertar un artículo de prueba, marcar `requiere_reparacion`, publicar con precio menor al mínimo (debe fallar), vender y revisar `ventas`, `metricas` e `historial`. Importar un Excel con el formato de `NEGOCIO.xlsx` y exportarlo de vuelta. Borrar los datos de prueba después.

### F7 — Retiro de lo viejo y documentación (S)

1. Actualizar `README.md`, `MANUAL_DE_USO.md` y el diagrama de clases (`DIAGRAMA_DE_CLASES_v4.md`) al nuevo esquema.
2. Dejar una nota en `docs/legacy-sql/` explicando que son referencia histórica.
3. **Regla desde aquí:** una vez aplicado en Supabase, `V001`–`V004` no se editan más. Los cambios de tablas van en `V005`, `V006`…, y los de funciones o triggers en `R__`.

### F8 — Normalización por socios (posterior y opcional) (L)

Solo si más adelante quieres más de dos socios o reparto flexible: `socio`, `inventario_reparto`, `reparacion`, `venta_reparto`, `producto_foto`, `publicacion_canal` y `cliente`, junto con el refactor de los repositorios Kotlin y una migración de datos real (ver §10).

---

## 10. Estrategia de evolución con datos

El proyecto debe seguir funcionando cuando la base ya tenga datos reales.

**Cuándo se congela el esquema.** Mientras Flyway no haya aplicado `V001`–`V004` en ningún Supabase, puedes reescribirlos. En cuanto se apliquen (incluso con datos de prueba), quedan **congelados**: Flyway guarda el checksum de cada archivo y se niega a arrancar si cambias uno ya aplicado.

**Reglas de aquí en adelante:**

1. Las migraciones versionadas (`V00x`) son **inmutables**. Todo cambio de tablas va en un archivo nuevo: `V005`, `V006`…
2. **Cambios en tres tiempos (expandir, migrar, contraer)**, nunca "borrar y recrear". Ejemplo con F8: `V005` crea `socio` e `inventario_reparto`; `V006` copia los datos desde `pct_antonio`/`pct_javier`; se despliega el Kotlin que lee las tablas nuevas; `V007` elimina lo viejo solo cuando todo esté verificado.
3. Toda migración que toque datos debe ser idempotente (`IF NOT EXISTS`, `ON CONFLICT`), ir en una transacción y verificar conteos antes y después.
4. Los triggers y funciones (`R__`) sí se pueden editar, pero un cambio de regla **no reescribe los datos viejos**. Si cambias, por ejemplo, el ROI mínimo, hace falta una migración `V` que recalcule los artículos existentes (D14).
5. **Antes de aplicar en producción:** respaldo con `pg_dump` y prueba sobre una copia (un segundo proyecto de Supabase o un Postgres local con Docker). Revisa qué respaldos automáticos incluye tu plan de Supabase; no dependas solo de eso.
6. Prueba automática de migración con datos: crear la base, aplicar hasta la versión N, insertar datos sintéticos, aplicar la N+1 y comprobar que todo sigue bien.
7. Nunca se usa `flyway clean` sobre Supabase (`cleanDisabled`).
8. Orden de despliegue: primero la migración compatible hacia atrás, luego el código nuevo.

**Mover la base a otro proyecto o servidor.** Flyway no copia datos. Lo más simple es restaurar un `pg_dump` completo (esquema `tienda`, datos y `flyway_schema_history`) sobre una base **vacía**. La app arranca después y solo aplica las migraciones que falten. No arranques la app antes de restaurar.

---

## 11. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Las reglas nunca se ejecutaron contra PostgreSQL | F4 y F5 las prueban antes de tocar Supabase |
| Diferencias entre `Tables.kt` y el SQL | La prueba que compara columnas con `information_schema` |
| `baselineVersion = 0` no se comporta como se espera en Supabase | Prueba específica "base con datos" en F4 y verificación en una copia antes de usarlo con datos reales |
| Datos existentes que violan las restricciones nuevas | Restricciones `NOT VALID` más reporte del diagnóstico (D13). Ojo: una fila vieja que viole una restricción no podrá modificarse hasta corregirla |
| Tabla existente con tipos distintos a `Tables.kt` | Estado INCOMPATIBLE: la app aborta con informe y no altera nada |
| Historial de Flyway con versiones de las 13 migraciones viejas | Detectado como INCOMPATIBLE; se resuelve con un baseline manual puntual |
| Flyway falla por permisos en Supabase (crear rol o esquema) | Usar el usuario `postgres` del proyecto; si el rol falla, crear `app_user` a mano una sola vez |
| El Excel pierde su PM al importar (D10) | Es intencional: la vista previa lo avisa fila por fila antes de confirmar |
| La app arranca "sin base" sin que lo notes | F2: abortar en producción; revisar el log de F6 |

---

## 12. Criterios de aceptación

- Con una base vacía, arrancar la app crea todo el esquema sin pasos manuales.
- Con una base con datos compatibles, arrancar la app **conserva todos los datos**, completa lo que falte y los datos siguen disponibles en la app.
- Con una base incompatible, la app no altera nada y explica qué difiere.
- Existe una prueba que aplica una migración futura sobre una base **con datos** y comprueba que siguen intactos.
- Flyway se niega a arrancar si alguien edita una migración ya aplicada.
- Volver a arrancar no cambia nada y no falla.
- Las pruebas de F4 pasan, incluidas las reglas R1–R9 y R11.
- Todas las columnas de `Tables.kt` existen con el tipo esperado.
- `vender_unidades` y `devolver_unidades` funcionan de extremo a extremo.
- Un artículo vendido calcula `ventas` y `metricas` solo, y el historial registra cada cambio.
- **Un Excel con el formato de `NEGOCIO.xlsx` se importa, la vista previa avisa del PM reemplazado y de las filas bloqueadas, y la exportación devuelve un archivo con el mismo formato.**
- **Tras importar, la app muestra los mismos precios que hay en la base.**
- No quedan scripts SQL manuales necesarios en `db/` para levantar una base nueva.

---

## 13. Entregables

Con el formato habitual, por fase: **archivos modificados completos**, **manual de cambios** (qué fragmento cambió y por qué) y **diagrama de clases actualizado** en un archivo aparte.

| Fase | Archivos |
|---|---|
| F1 | `V001__base_inventario.sql`, `V002__ventas_y_metricas.sql`, `V003__mensajeria_y_reservas.sql`, `V004__canales_e_importaciones.sql`, `R__funciones_triggers_y_vistas.sql` |
| F2 | `SchemaPreflight.kt` (nuevo), `DatabaseFactory.kt`, `Application.kt` (solo el arranque), `Tables.kt` (comentarios) |
| F3 | `ExcelPreviewService.kt`, `Application.kt` (recarga tras importar) |
| F4 | `SchemaMigrationTest.kt`, `ReglasNegocioTest.kt`, `StockAtomicoTest.kt`, `ExcelPreviewServiceTest.kt`, `ExcelImportacionDbTest.kt`, `libs.versions.toml`, `infra/build.gradle.kts` |
| F7 | `README.md`, `MANUAL_DE_USO.md`, diagrama de clases |

> `Application.kt` se modifica en dos fases distintas (arranque en F2, importación en F3). Para respetar la regla de entregar solo lo estrictamente necesario, se entregará una sola versión final que contenga ambos cambios.

### Checklist de cierre

- [ ] Decisiones D1–D14 confirmadas.
- [ ] Proyecto de Supabase creado y credenciales en `local.properties`.
- [ ] Archivos SQL escritos y probados con PostgreSQL real (base vacía, con datos e incompatible).
- [ ] `./gradlew build` en verde, con las pruebas de esquema y de Excel incluidas.
- [ ] Log de arranque con el estado del diagnóstico y "Flyway: 5 migraciones aplicadas".
- [ ] Prueba de humo manual en Supabase (incluida importación y exportación de Excel) y datos de prueba eliminados.
- [ ] Scripts viejos movidos a `docs/legacy-sql/` y documentación actualizada.
