# Plan de Implementación v3 — Socios dinámicos y métricas (general y por socio)

**Proyecto:** Tienda Virtual Multicanal (Kotlin / Ktor / Exposed / Flyway / PostgreSQL en Supabase / dashboard HTMX)
**Versión:** 3 — se apoya en `Plan_Implementacion_v2_Esquema_Adaptativo_y_Migraciones.md` y reemplaza su fase opcional F8 (normalización por socios).

> **Alcance de la revisión.** Estático: leí el modelo (`ReparteSocios`, `InventarioItem`, `Venta`), `FinancialMath`, `Tables.kt`, los repositorios Exposed, `DatabaseFactory`, el arranque en `Application.kt`, `DashboardRenderer`, `DashboardRoutes`, `ExcelRoutes`, `ExcelV2Parser`, `ExcelGenerator`, `db/migracion_fase2.sql` y el plan v2. **No compilé ni ejecuté nada, y el SQL de este plan no se ha probado contra PostgreSQL.** Todo el SQL es una propuesta que debe pasar por las pruebas de la fase F-S7 antes de usarse en Supabase.

---

## 1. Objetivo

1. Quitar los dos socios fijos (Antonio y Javier). Al abrir la app por primera vez hay **0 socios**.
2. Una **tabla nueva de socios**, y una opción **Socios** en el dashboard: ver cuántos hay, agregar y eliminar.
3. Cada socio agregado aparece como **una columna de porcentaje de inversión** en la tabla principal de inventario. En cada artículo, cada socio indica qué porcentaje invierte.
4. Ese porcentaje es la base de todas las métricas por socio: inversión, ingresos, ganancias, pérdidas, ROI, etc.
5. **La suma de porcentajes de un artículo nunca puede superar 100 %**, ni en la pantalla, ni en el servidor, ni en la base de datos.
6. Dos conjuntos de métricas: **generales** y **por socio**.
7. Las métricas generales incluyen: qué se vende más (por tipo y por elemento), qué se vende más rápido y más lento, qué deja más ganancia, qué tiene mejor relación inversión–ganancia, y otras relevantes.
8. Todo queda listo para Supabase: con la base vacía se crea todo; con datos existentes se adoptan y se sincronizan. **Sin archivos de migración nuevos**: los cambios se integran en los archivos del plan v2.

---

## 2. Qué encontré en el proyecto (verificado en el código)

| # | Hallazgo | Dónde | Consecuencia para este plan |
|---|---|---|---|
| H1 | Los socios son un `enum class Socio { ANTONIO, JAVIER }` | `ReparteSocios.kt` | Debe pasar a una entidad con id y nombre |
| H2 | Los porcentajes son dos columnas fijas, `pct_antonio` y `pct_javier`, con la regla "suma 0 o 100" | `Tables.kt`, `migracion_fase2.sql` (`ck_pct_suma`) | Se reemplazan por una tabla `inventario_inversion` (ver D-S1) |
| H3 | Las métricas por socio viven en `tienda.metricas` con 4 columnas fijas (`ingresos_antonio`, …) | `Tables.kt`, `sync_venta` | Se reemplaza por `venta_reparto` (una fila por venta y socio) |
| H4 | El reparto se hace con `FinancialMath.repartir`, para exactamente 2 socios | `FinancialMath.kt` | Se generaliza a N socios con el método del mayor residuo |
| H5 | Para vender se exige reparto definido (suma 100) en el modelo Kotlin **y** en el trigger R6 | `InventarioItem.registrarVenta`, `trg_inventario_antes` | Se conserva la regla, ahora con N socios y mínimo 1 |
| H6 | Un artículo vendido es inmutable, incluidos los porcentajes (R9) | trigger `trg_inventario_antes` | La inmutabilidad pasa al trigger de `inventario_inversion` |
| H7 | **El Excel no tiene columnas de socios** (ni en el parser ni en el generador) | `ExcelV2Parser`, `ExcelGenerator` | El Excel actual sigue funcionando; las columnas por socio serán una ampliación opcional |
| H8 | **El dashboard no edita inventario**: la tabla "Catálogo" es de solo lectura y se alimenta de `AppStore` (memoria), con PP, stock y canales | `DashboardRenderer`, `DashboardRoutes` | Hay que construir la edición de porcentajes en el dashboard; hoy solo se podría hacer desde el editor de Supabase |
| H9 | La vista "Métricas" actual solo muestra conversaciones y tratos; dice expresamente que las métricas de ventas están "pendientes de definir" | `DashboardRenderer`, sección `metricas` | Se reorganiza en pestañas y se agregan las métricas de ventas |
| H10 | `ventas` no guarda el precio publicado al vender | `VentasTable` | Para medir el descuento promedio hay que guardarlo (se agrega en V002 del plan v2) |
| H11 | Hay tres sitios que construyen `InventarioItem` en la importación de Excel | `ExcelRoutes.kt` (líneas ~123, ~240, ~304) | El `upsert` no debe tocar los porcentajes, o cada importación los borraría |
| H12 | Las migraciones `V001`–`V014` actuales siguen en el repositorio; el plan v2 (que las reemplaza por `V001`–`V004` + `R__`) todavía no está aplicado | `infra/src/main/resources/db/migrations/` | Este plan se integra en esos archivos del plan v2 y no crea migraciones nuevas |
| H13 | El historial genérico lee `NEW.key`, y la tabla `socio` no tiene esa columna | `trg_historial_generico` | Hay que ajustar el trigger para tablas con `id` |
| H14 | Hay pruebas que construyen `ReparteSocios(pctAntonio, pctJavier)` | `FinancialMathTest`, `ConfirmPaymentUseCaseTest`, `ArticuloLifecycleServiceTest` | Se actualizan junto con el modelo |

---

## 3. Decisiones de diseño (confirmar antes de escribir SQL)

Dejé una recomendación en cada una. Las tres con **(!)** cambian el comportamiento visible: revísalas primero.

| ID | Decisión | Recomendación | Motivo |
|---|---|---|---|
| **D-S1 (!)** | **Cómo se guarda "una columna por socio"** | **Tabla `inventario_inversion` (una fila por artículo y socio).** La "columna por socio" se ve en el dashboard, en el Excel y, opcionalmente, en una vista de Supabase que se regenera sola | Agregar columnas físicas con `ALTER TABLE` cada vez que alguien agrega un socio obliga a ejecutar DDL desde la app, deja a Exposed (tablas estáticas) sin poder declararlas, hace imposible validar "la suma ≤ 100" con un CHECK normal y bloquea la tabla principal cada vez. La tabla relacional ofrece lo mismo al usuario sin esos problemas |
| **D-S2 (!)** | Sobre qué es el porcentaje | **Sobre el costo total del artículo** (compra + reparación): "qué parte de lo invertido puso este socio". Es la misma lógica que hoy usa `repartir` (ingresos y ganancia se dividen con la misma proporción) | Tu mensaje dice "respecto al precio final"; lo interpreto como el valor total que costó el artículo. Si querías decir sobre el precio de venta, el reparto de ingresos y ganancias no cambia, pero la columna "inversión" sí: dímelo |
| **D-S3 (!)** | Suma de porcentajes | **Siempre ≤ 100 %** (se rechaza cualquier cambio que la supere). **Para marcar un artículo como Vendido la suma debe ser exactamente 100 y debe haber al menos 1 socio** | Conserva la regla actual (D7/R6) y evita ventas con dinero sin dueño. Alternativa: permitir vender con suma < 100 y mostrar el resto como "sin asignar"; solo cambia la regla R6 y una columna |
| D-S4 | Eliminar un socio | **Eliminar** si nunca participó en una venta. **Archivar** si ya tiene ventas (queda en el histórico y deja de aparecer en columnas nuevas). En ambos casos se borran sus porcentajes de los artículos **no vendidos** (el dashboard avisa cuántos antes de confirmar) | Las ventas pasadas nunca cambian; los artículos en stock quedan con suma < 100 y hay que repartirlos de nuevo antes de vender |
| D-S5 | Redondeo | **Método del mayor residuo**: el reparto de ingresos y el de inversión suman exactamente el total, y la ganancia de cada socio = su ingreso − su inversión | Cuadre exacto al peso, también con ganancias negativas |
| D-S6 | Dónde se calculan las métricas | **En Kotlin (`MetricasService`)**, sobre datos leídos de la base. La base solo guarda hechos congelados al vender (`ventas`, `venta_reparto`) | El código dice "las fórmulas viven en un solo lugar" (`FinancialMath`). Además se prueban sin Docker. No se crean las vistas analíticas `v_rendimiento_tipo`, `v_periodo_tipo` y `v_resumen_mensual_socio` que proponía el plan v2, para no duplicar fórmulas |
| D-S7 | Qué es un "elemento único" | **Tres niveles:** tipo (categoría), modelo (nombre del ítem + marca, normalizado) y Key (la pieza concreta) | Como cada Key se vende una sola vez, "lo que más se vende" solo tiene sentido agrupado por tipo y modelo; las listas de rapidez y ganancia sí van por Key |
| D-S8 | Modo memoria (sin `DATABASE_URL`) | Socios y métricas **requieren base de datos**. En modo memoria el dashboard muestra un aviso. Los puertos tienen implementación en memoria solo para pruebas | Evita duplicar toda la lógica; el objetivo es Supabase |
| D-S9 | Cifras decimales | `NUMERIC(5,2)` (dos decimales). Para tres socios iguales hay un botón "Repartir en partes iguales" (33,34 / 33,33 / 33,33) | Con 33,33 × 3 = 99,99 no se podría vender |
| D-S10 | Ventas automáticas con reparto incompleto (Shopify o chat) | El pedido **no se pierde**: se registra en `eventos_sin_resolver` con motivo `REPARTO_INCOMPLETO` y el dashboard muestra una alerta | Hoy `registrarVenta` lanza error si no hay reparto; con 0 socios por defecto, eso pasaría con la primera venta. **A verificar en `ConfirmPaymentUseCase` y el webhook de Shopify en la fase F-S2** |
| D-S11 | Datos existentes de Antonio y Javier | Si la base ya tiene `pct_antonio`/`pct_javier` con datos, se **adoptan**: se crean los socios "Antonio" y "Javier" y se copian sus porcentajes. Las columnas viejas quedan sin uso (no se borran) | Cumple "si hay datos, se sincronicen" sin migraciones nuevas |
| D-S12 | Renombrar socio | Permitido (el nombre no es la clave; el id sí). Nombre único sin distinguir mayúsculas, 1 a 60 caracteres | Cambio barato y evita borrar y recrear |
| D-S13 | Límite de socios | 12 por defecto (constante configurable) | Solo por legibilidad de las tablas; la base no lo exige |

---

## 4. Cómo encaja con el plan v2 (sin migraciones nuevas)

El plan v2 todavía no está aplicado en ninguna base (el proyecto es nuevo y Supabase aún no existe). Por eso **los cambios se escriben directamente dentro de los archivos que el plan v2 ya iba a crear**, y no se agrega ninguna migración.

| Archivo del plan v2 | Qué se agrega o cambia por este plan |
|---|---|
| `V001__base_inventario.sql` | Tablas `socio` e `inventario_inversion`. **Se omiten** las columnas `pct_antonio`, `pct_javier` y los CHECK `ck_pct_rango` y `ck_pct_suma`. Bloque de adopción de datos legados (D-S11) |
| `V002__ventas_y_metricas.sql` | `ventas.precio_publicado` (nuevo). `venta_reparto` **reemplaza** a `metricas`. Bloque de adopción de `metricas` legada |
| `V003`, `V004` | Sin cambios |
| `R__funciones_triggers_y_vistas.sql` | `fn_repartir`, triggers de `inventario_inversion`, `retirar_socio`, ajustes de R6, `sync_venta`, historial, bloqueo de escritura directa y seguridad (RLS). Se eliminan las vistas analíticas (D-S6) |
| `SchemaPreflight.kt` (v2 F2) | Las tres tablas nuevas entran en la lista de estructuras esperadas |

**Única excepción:** si para cuando implementes esto ya aplicaste `V001`–`V004` del plan v2 en algún Supabase, esos archivos quedaron congelados (Flyway valida su checksum). En ese caso, y solo en ese caso, el contenido de la sección 5 va en un `V005` (expandir) con la copia de datos incluida, siguiendo la regla de evolución del plan v2 §10.

Movimientos de archivos (igual que en el plan v2 F1): `db/migracion_fase2.sql`, `db/pruebas_fase2.sql` y las `V001`–`V014` actuales pasan a `docs/legacy-sql/`.

---

## 5. Modelo de datos propuesto

### 5.1 `V001` — socios y porcentajes de inversión

```sql
create table if not exists tienda.socio (
  id           bigint generated always as identity primary key,
  nombre       text not null check (btrim(nombre) <> '' and char_length(nombre) <= 60),
  activo       boolean not null default true,
  creado_en    timestamptz not null default now(),
  archivado_en timestamptz
);
-- Nombre único entre los socios activos, sin distinguir mayúsculas ni espacios sobrantes
create unique index if not exists ux_socio_nombre_activo
  on tienda.socio (lower(btrim(nombre))) where activo;

create table if not exists tienda.inventario_inversion (
  key       text   not null references tienda.inventario(key)
                     on delete cascade on update cascade,
  socio_id  bigint not null references tienda.socio(id) on delete restrict,
  pct       numeric(5,2) not null check (pct >= 0 and pct <= 100),
  primary key (key, socio_id)
);
create index if not exists ix_inversion_socio on tienda.inventario_inversion (socio_id);
```

Una celda vacía en el dashboard equivale a "sin fila" o a `pct = 0`. La aplicación borra la fila cuando el porcentaje queda en 0.

**Adopción de datos legados (D-S11), al final de `V001`.** Idempotente; solo actúa si la base trae las columnas viejas:

```sql
do $$
begin
  if exists (select 1 from information_schema.columns
             where table_schema='tienda' and table_name='inventario' and column_name='pct_antonio') then
    if exists (select 1 from tienda.inventario where pct_antonio > 0 or pct_javier > 0) then
      insert into tienda.socio (nombre)
        select n from (values ('Antonio'), ('Javier')) v(n)
        where not exists (select 1 from tienda.socio where lower(nombre) = lower(n));
      insert into tienda.inventario_inversion (key, socio_id, pct)
        select i.key, s.id, i.pct_antonio from tienda.inventario i
        join tienda.socio s on lower(s.nombre) = 'antonio' where i.pct_antonio > 0
      on conflict do nothing;
      insert into tienda.inventario_inversion (key, socio_id, pct)
        select i.key, s.id, i.pct_javier from tienda.inventario i
        join tienda.socio s on lower(s.nombre) = 'javier' where i.pct_javier > 0
      on conflict do nothing;
    end if;
  end if;
end $$;
```

Con una base vacía (tu caso) este bloque no hace nada y se arranca con 0 socios.

### 5.2 `V002` — ventas y reparto congelado

```sql
-- En tienda.ventas se agrega una columna (precio publicado al momento de vender):
--   precio_publicado bigint            -- para medir el descuento promedio frente al PP

create table if not exists tienda.venta_reparto (
  key        text   not null references tienda.ventas(key) on delete cascade,
  socio_id   bigint not null references tienda.socio(id) on delete restrict,
  pct        numeric(5,2) not null check (pct > 0 and pct <= 100),
  inversion  bigint not null check (inversion >= 0),   -- parte del costo total
  ingresos   bigint not null check (ingresos >= 0),    -- parte de "vendido en"
  ganancia   bigint not null,                          -- ingresos − inversión (puede ser negativa)
  primary key (key, socio_id)
);
create index if not exists ix_venta_reparto_socio on tienda.venta_reparto (socio_id);
```

`venta_reparto` es la versión por socio de la antigua `metricas`: se llena con triggers y nadie la edita directamente. Si la base trae la tabla `metricas` legada, un bloque `do $$ ... $$` idempotente copia sus filas hacia `venta_reparto` (con `inversion = ingresos − ganancia`) usando los socios creados en `V001`.

### 5.3 `R__` — funciones y triggers

**Reparto exacto entre N socios (método del mayor residuo):**

```sql
create or replace function tienda.fn_repartir(p_valor bigint, p_key text)
returns table (socio_id bigint, monto bigint) language sql stable as $$
  with t as (
    select coalesce(sum(pct), 0) as total
      from tienda.inventario_inversion where key = p_key and pct > 0
  ),
  b as (
    select ii.socio_id, (p_valor::numeric * ii.pct / t.total) as exacto
      from tienda.inventario_inversion ii, t
     where ii.key = p_key and ii.pct > 0 and t.total > 0
  ),
  f as (select socio_id, floor(exacto)::bigint as piso, exacto - floor(exacto) as resto from b),
  faltan as (select p_valor - coalesce(sum(piso), 0) as n from f),
  o as (select f.*, row_number() over (order by resto desc, socio_id) as pos from f)
  select o.socio_id, o.piso + case when o.pos <= (select n from faltan) then 1 else 0 end
    from o;
$$;
```

**La suma nunca supera 100** (trigger de restricción diferido: permite mover porcentajes entre socios dentro de una misma transacción y valida al confirmar; bloquea la fila del artículo para que dos transacciones simultáneas no se pasen de 100):

```sql
create or replace function tienda.trg_inversion_suma() returns trigger language plpgsql as $$
declare v_key text; v_suma numeric;
begin
  v_key := case when TG_OP = 'DELETE' then OLD.key else NEW.key end;
  perform 1 from tienda.inventario where key = v_key for update;
  select coalesce(sum(pct), 0) into v_suma from tienda.inventario_inversion where key = v_key;
  if v_suma > 100 then
    raise exception 'Los porcentajes de inversión de % no pueden superar 100 (suman %).', v_key, v_suma;
  end if;
  return null;
end $$;

drop trigger if exists trg_inversion_suma on tienda.inventario_inversion;
create constraint trigger trg_inversion_suma
  after insert or update or delete on tienda.inventario_inversion
  deferrable initially deferred for each row execute function tienda.trg_inversion_suma();
```

**Vendido es inmutable y no se asignan porcentajes a socios archivados:**

```sql
create or replace function tienda.trg_inversion_bloqueo() returns trigger language plpgsql as $$
declare v_key text := case when TG_OP = 'DELETE' then OLD.key else NEW.key end;
begin
  if exists (select 1 from tienda.inventario where key = v_key and estado = 'Vendido') then
    raise exception 'El artículo % ya está vendido: no se pueden cambiar los porcentajes. Anula la venta, edita y vuelve a vender.', v_key;
  end if;
  if TG_OP in ('INSERT','UPDATE') and not exists
       (select 1 from tienda.socio where id = NEW.socio_id and activo) then
    raise exception 'El socio está archivado: no se le pueden asignar porcentajes.';
  end if;
  return case when TG_OP = 'DELETE' then OLD else NEW end;
end $$;
-- trigger BEFORE insert/update/delete en tienda.inventario_inversion (nombre trg_a_inversion_bloqueo)
```

**Cambios en funciones existentes del plan v2:**

| Función | Cambio |
|---|---|
| `trg_inventario_antes` (R6) | Reemplaza la comprobación `pct_antonio + pct_javier <> 100` por: `select coalesce(sum(pct),0) from tienda.inventario_inversion where key = NEW.key`; si no es 100, error: "Para vender % los porcentajes de inversión de los socios deben sumar 100 (hoy suman %). Agrega socios y reparte el porcentaje en el catálogo." |
| `trg_inventario_antes` (R9) | Se quita la comparación de `pct_*` (la inmutabilidad pasa a `trg_inversion_bloqueo`) |
| `sync_venta` | Guarda `ventas.precio_publicado`. Después de calcular la venta: `delete from venta_reparto where key = i.key;` y luego inserta con `fn_repartir(i.vendido_en, i.key)` (ingresos) y `fn_repartir(i.costo_total, i.key)` (inversión), con `ganancia = ingresos − inversión` |
| `trg_historial_generico` | Calcula la clave con `coalesce(to_jsonb(NEW)->>'key', to_jsonb(NEW)->>'id')` (y lo mismo con `OLD`), para poder registrar `socio` |
| `trg_bloquear_escritura_directa` | También en `venta_reparto` (reemplaza a `metricas`) |
| Historial | Trigger `trg_z_historial` en `socio`, `inventario_inversion` y `venta_reparto` |
| Seguridad | `ENABLE ROW LEVEL SECURITY`, `REVOKE` a `anon`/`authenticated` y permisos a `app_user` en las tres tablas nuevas, igual que el resto |

**Retirar un socio (D-S4)**, una sola función para que sea atómico:

```sql
create or replace function tienda.retirar_socio(p_id bigint) returns text
language plpgsql as $$
begin
  delete from tienda.inventario_inversion ii using tienda.inventario i
   where ii.socio_id = p_id and ii.key = i.key and i.estado <> 'Vendido';
  if exists (select 1 from tienda.venta_reparto where socio_id = p_id)
     or exists (select 1 from tienda.inventario_inversion where socio_id = p_id) then
    update tienda.socio set activo = false, archivado_en = now() where id = p_id;
    return 'ARCHIVADO';
  end if;
  delete from tienda.socio where id = p_id;
  return 'ELIMINADO';
end $$;
```

### 5.4 Vista opcional para el editor de Supabase

Quien edite desde el *Table Editor* de Supabase vería filas en `inventario_inversion` en lugar de columnas. Para dar la columna por socio también ahí, una función `tienda.refrescar_vista_socios()` regenera la vista `tienda.v_inventario_socios` (un `pivot` con una columna `"% <nombre>"` por socio activo). Cambia solo una **vista** (nunca datos ni tablas) y la app la llama después de agregar o retirar un socio. Es opcional (fase F-S7): el dashboard ya cubre la edición.

---

## 6. Reglas de negocio nuevas

| Regla | Comportamiento | Dónde se aplica |
|---|---|---|
| S1 | Con 0 socios no se puede vender. Se pueden crear, publicar y negociar artículos normalmente | Modelo Kotlin + trigger R6 |
| S2 | Cada porcentaje está entre 0 y 100, con 2 decimales | Pantalla, servidor, CHECK |
| S3 | La suma de porcentajes de un artículo es ≤ 100 | Pantalla (aviso), servidor (rechazo con mensaje), trigger diferido (última línea) |
| S4 | Vender exige suma = 100 y al menos 1 socio | Modelo + R6 |
| S5 | Un artículo vendido no admite cambios de porcentajes. Para corregir: anular la venta, editar, volver a vender | `trg_inversion_bloqueo` |
| S6 | Anular una venta borra `ventas` y `venta_reparto`; los porcentajes del artículo se conservan y vuelven a ser editables | `sync_venta` |
| S7 | La suma por socios (más "sin asignar", si existiera) cuadra exacto con los totales generales | Mayor residuo + prueba de cuadre |
| S8 | Un socio con ventas no se elimina: se archiva. Sus ventas pasadas no cambian | `retirar_socio` + FK `restrict` |
| S9 | No se asignan porcentajes a socios archivados. Un socio archivado puede reactivarse | `trg_inversion_bloqueo` |
| S10 | Nombre de socio único (activos), sin distinguir mayúsculas | Índice único parcial |
| S11 | Cada alta, cambio o retiro queda en `historial` con usuario y origen | Triggers de historial |

---

## 7. Cambios en el código Kotlin

### 7.1 Módulo `commerce` (dominio, sin dependencias de base de datos)

| Archivo | Cambio |
|---|---|
| `model/ReparteSocios.kt` | Se elimina el `enum Socio`. Nueva `data class Socio(id: Long, nombre: String, activo: Boolean)`. `ReparteSocios(porSocio: Map<Long, BigDecimal>)` valida cada valor en 0–100 y **suma ≤ 100**; expone `suma`, `sinAsignar` y `definida` (= suma exactamente 100) |
| `model/InventarioItem.kt` | `reparte` se mantiene como campo. `registrarVenta` actualiza el mensaje de error (S1/S4) |
| `model/Venta.kt` | `Metrica` se reemplaza por `VentaReparto(key, socioId, pct, inversion, ingresos, ganancia)`. `Venta` agrega `precioPublicado: Money?` |
| `service/FinancialMath.kt` | `repartir(valor, Map<Long, BigDecimal>): Map<Long, Long>?` con mayor residuo (espejo de `fn_repartir`). Se eliminan las versiones de 2 socios |
| `service/SocioService.kt` *(nuevo)* | Validación de nombre, límite de socios, resultado de retirar (`ELIMINADO`/`ARCHIVADO` + artículos afectados) |
| `service/MetricasService.kt` *(nuevo)* | Todas las fórmulas de la sección 10 como funciones puras sobre `DatosMetricas(items, ventas, repartos, socios, hoy, filtro)` |
| `ports/SocioRepository.kt` *(nuevo)* | `listar(incluirArchivados)`, `crear`, `renombrar`, `retirar`, `reactivar`, `impacto(id)` |
| `ports/InversionRepository.kt` *(nuevo)* | `todas(): Map<ProductKey, Map<Long, BigDecimal>>`, `guardar(key, mapa)` (reemplazo atómico), `fijar(key, socioId, pct)` |
| `ports/VentaRepository.kt` | `metricaDe` → `repartosDe(key)` y `repartos(): List<VentaReparto>` |
| `ports/InMemory*.kt` | Implementaciones en memoria solo para pruebas |

### 7.2 Módulo `infra`

| Archivo | Cambio |
|---|---|
| `db/Tables.kt` | Nuevas `SociosTable`, `InventarioInversionTable`, `VentaRepartoTable`. Se quitan `pctAntonio`, `pctJavier` y `MetricasTable`. `VentasTable.precioPublicado` |
| `db/ExposedInventarioRepository.kt` | `findAll()` carga los porcentajes con **una sola consulta** adicional (sin N+1). **`upsert` no escribe porcentajes** (los gestiona `InversionRepository`), igual que `costo_total` y `precio_minimo` (H11) |
| `db/ExposedSocioRepository.kt` *(nuevo)* | Usa `tienda.retirar_socio`. Traduce errores de la base (`P0001`) a mensajes en español |
| `db/ExposedInversionRepository.kt` *(nuevo)* | Todo dentro de `DatabaseFactory.tx(origen, usuario)` para que el historial registre quién cambió qué. Maneja el rechazo del trigger diferido, que ocurre **al confirmar la transacción** |
| `db/ExposedVentaRepository.kt` | Lee `venta_reparto` |
| `db/SchemaPreflight.kt` (v2) | Incluye las tablas nuevas |
| `excel/ExcelGenerator.kt`, `ExcelV2Parser.kt`, `ExcelPreviewService.kt` | Ver sección 9 |

### 7.3 Módulo `app`

| Archivo | Cambio |
|---|---|
| `dashboard/SociosRenderer.kt` *(nuevo)* | HTML de la vista Socios y del formulario de alta |
| `dashboard/InventarioRenderer.kt` *(nuevo)* | Tabla de inventario con columnas de porcentaje por socio |
| `dashboard/MetricasRenderer.kt` *(nuevo)* | Pestañas de métricas |
| `dashboard/DashboardRenderer.kt` | Nueva entrada de menú **Socios**; la vista Métricas pasa a tener pestañas; se cargan los fragmentos con HTMX. Se evita seguir creciendo este archivo (ya pesa ~71 KB) |
| `routes/SociosRoutes.kt`, `routes/InversionRoutes.kt`, `routes/MetricasRoutes.kt` *(nuevos)* | Rutas de la sección 8, registradas en `Application.kt` |
| `routes/ExcelRoutes.kt` | Tras el `upsert`, aplica los porcentajes del archivo (si trae columnas de socios) dentro de la misma transacción |
| `Application.kt` | Registrar rutas y repositorios; en modo memoria, desactivar socios y métricas con aviso |
| Pruebas existentes | Actualizar `FinancialMathTest`, `ConfirmPaymentUseCaseTest`, `ArticuloLifecycleServiceTest` al nuevo `ReparteSocios` |

---

## 8. Dashboard

### 8.1 Menú y vistas

El menú lateral actual (Inicio, APIs, Mensajería, Catálogo, Métricas, Automatización) gana **Socios**. La vista **Métricas** pasa a tener tres pestañas: **Generales**, **Por socio** y **Conversaciones** (lo que hoy existe, sin cambios).

### 8.2 Vista Socios

- Contador: "N socios activos" (y "M archivados" si los hay, con un conmutador para verlos).
- Tabla: nombre, inversión actual en stock, cantidad de artículos en los que participa, acciones.
- Botón **Agregar socio** (campo de nombre con validación).
- Botón **Eliminar**: primero pide el impacto (`GET /api/socios/{id}/impacto`) y muestra un aviso del tipo *"Tiene porcentaje en 14 artículos sin vender (quedarán por repartir) y participó en 3 ventas, por lo que se archivará en lugar de eliminarse"*. Solo entonces ejecuta.
- **Reactivar** en los archivados. **Renombrar** en la fila.
- Con 0 socios: tarjeta de bienvenida "Agrega a tu primer socio para repartir la inversión".

### 8.3 Inventario con una columna por socio

Nueva pestaña **Inventario** dentro de Catálogo (la tabla actual de canales se conserva). Cada fila muestra: Key, producto, tipo, estado, costo total, PM, PP y **una columna `% <socio>` por socio activo**, más:

- **Suma** con color: verde = 100, ámbar = menos de 100, y no puede quedar en rojo porque más de 100 se rechaza.
- **Sin asignar** = 100 − suma.
- Celdas numéricas (`min=0 max=100 step=0.01`) que al salir del campo envían `PUT /api/inventario/{key}/inversion/{socioId}`; la respuesta devuelve la fila actualizada (HTMX `outerHTML`).
- Si el valor haría pasar de 100, el servidor responde con un mensaje claro (*"Solo puedes asignar hasta 25,00 % más a este artículo"*) y la celda vuelve a su valor anterior.
- Los artículos **vendidos** muestran los porcentajes congelados y deshabilitados.
- Acciones por fila: **Repartir en partes iguales** y **Limpiar**.
- Aviso superior si hay 0 socios, o si hay artículos publicados sin reparto completo.
- Filtros por estado, tipo y "reparto incompleto"; paginación para inventarios grandes.

### 8.4 Rutas HTTP

| Método y ruta | Función |
|---|---|
| `GET /api/socios` | Fragmento con la lista y el contador |
| `POST /api/socios` | Alta (nombre) |
| `PUT /api/socios/{id}` | Renombrar |
| `GET /api/socios/{id}/impacto` | Cuenta de artículos y ventas afectados |
| `DELETE /api/socios/{id}` | Eliminar o archivar (responde cuál de las dos) |
| `POST /api/socios/{id}/reactivar` | Reactivar un archivado |
| `GET /api/inventario/tabla` | Tabla con columnas por socio (filtros y paginación) |
| `PUT /api/inventario/{key}/inversion/{socioId}` | Fijar un porcentaje |
| `POST /api/inventario/{key}/inversion/repartir-igual` | Partes iguales |
| `GET /api/metricas/generales`, `/rankings`, `/socios` | Fragmentos de métricas con filtro de fechas |

### 8.5 Seguridad (siguiendo lo que ya hace el proyecto)

- Todas las rutas con `requireDashboardAuth(...)`; las que modifican, con `mutating = true` (CSRF y `OriginGuard`).
- **Escapar siempre con `escapeHtml`**: el nombre del socio lo escribe el usuario y aparece en cabeceras de tabla y atributos HTML.
- Validar en el servidor aunque la pantalla ya valide (no confiar en `min`/`max` del navegador).
- Las tres tablas nuevas con RLS y sin acceso a `anon`/`authenticated`.

---

## 9. Excel

Se respeta la decisión D12 del plan v2 (formato sin cambios), con una **ampliación opcional y compatible hacia atrás**:

| Aspecto | Comportamiento |
|---|---|
| Exportar | Después de las columnas actuales se agrega una columna `% <nombre del socio>` por cada socio activo y una columna `% Suma` |
| Importar sin esas columnas | Funciona como hoy y **no toca** los porcentajes existentes |
| Importar con esas columnas | Reconoce cabeceras `% <nombre>` que coincidan (sin distinguir mayúsculas) con un socio activo. Celda vacía = no cambiar; `0` = limpiar |
| Cabecera de un socio que no existe | Aviso en la vista previa ("el socio X no existe; agrégalo en Socios"); esa columna se ignora |
| Suma mayor a 100 | La fila se bloquea en la vista previa con el motivo |
| Fila de un artículo ya Vendido con porcentajes distintos | Bloqueada (S5) |
| Filas con estado Vendido | Siguen bloqueadas por el plan v2 (D11) |
| Transacción | Los porcentajes se escriben en la **misma transacción** que el `upsert`: todo o nada |

---

## 10. Métricas

### 10.1 Definiciones base (todas viven en `FinancialMath`/`MetricasService`)

| Concepto | Fórmula |
|---|---|
| Ganancia neta | Vendido en − costo total (puede ser negativa) |
| ROI | Ganancia / costo total (vacío si costo = 0) |
| Margen | Ganancia / vendido en (vacío si vendido en = 0) |
| Días de venta | Fecha de venta − fecha de compra |
| Días en publicación | Fecha de venta − fecha de publicación |
| Ganancia por día | Ganancia / máx(días, 1) |
| Descuento vs PP | (PP al vender − vendido en) / PP al vender |
| Antigüedad del stock | Hoy − fecha de compra, para artículos sin vender |
| Todo lo no calculable | Vacío; **nunca** un valor inventado (regla ya vigente en `FinancialMath`) |

### 10.2 Tabla de métricas generales

Con filtro de fechas (todo / año / 90 días / mes / rango) y por tipo.

| Grupo | Métricas |
|---|---|
| Resultado | Ingresos, costo vendido, ganancia neta, margen, ROI, ticket promedio, ganancia promedio por venta |
| Volumen | Artículos vendidos, en stock, publicados, en negociación; tasa de rotación = vendidos / (vendidos + en stock) |
| Velocidad | Días promedio y mediana de compra→venta y de publicación→venta; distribución corto / medio / largo; ganancia por día |
| Pérdidas | Cantidad de ventas con pérdida, monto total perdido, % de ventas con pérdida |
| Capital | Capital inmovilizado (costo del stock), valor del stock a PP, ganancia potencial, antigüedad promedio del stock, artículos "envejecidos" (más días que el límite medio) |
| Precio | Descuento promedio frente al PP, % de ventas por encima del PM |
| Canal | Ventas, ingresos y ganancia por canal de venta |
| Tendencia | Ingresos y ganancia por mes (barras en CSS o SVG en línea, sin librerías) |

### 10.3 Rankings generales (lo que pediste)

| Pregunta | Agrupación | Ordenado por |
|---|---|---|
| ¿Qué se vende más? | Por **tipo** y por **modelo** | Cantidad vendida (y unidades de `venta_items` si el artículo tiene cantidad > 1) |
| ¿Qué se vende más rápido? | Por tipo, modelo y Key | Menos días de compra→venta |
| ¿Qué se vende más lento? | Igual, **más** los artículos aún sin vender | Más días (vendidos) y mayor antigüedad (en stock) |
| ¿Qué da más ganancia? | Tipo, modelo y Key | Ganancia total, y también ganancia por día |
| ¿Mejor relación inversión–ganancia? | Tipo, modelo y Key | ROI, con el capital invertido a la vista |
| ¿Qué da pérdidas? | Tipo, modelo y Key | Ganancia más negativa |
| Otros útiles | Marca, condición, canal | Ganancia, ROI y días |

Reglas para que no engañen: los grupos con **menos de 3 ventas** se marcan "muestra baja"; el ROI se muestra siempre junto al capital; los rankings por tipo y por modelo permiten ordenar por cada columna.

### 10.4 Tabla de métricas por socio

Una fila por socio (activos y archivados con actividad), con fila de totales y fila de **"sin asignar"** cuando aplique:

| Métrica | Cálculo |
|---|---|
| Participación | Su inversión / inversión total del negocio |
| Inversión total | Σ `inversion` de sus ventas + Σ costo × % de su stock |
| Capital inmovilizado ahora | Σ costo total × % de los artículos sin vender |
| Ingresos | Σ `ingresos` de `venta_reparto` |
| Ganancia neta | Σ `ganancia` |
| Pérdidas | Σ de las `ganancia` negativas, y cantidad de ventas con pérdida |
| ROI | Ganancia / inversión de lo vendido |
| Margen | Ganancia / ingresos |
| Artículos | En los que participa y vendidos |
| Retorno del capital | Días promedio de compra→venta ponderados por su inversión |
| Ganancia por día | Ganancia / días ponderados |
| Mejor y peor artículo | Por ganancia |
| Evolución mensual | Ingresos y ganancia por mes |

**Cuadre obligatorio:** Σ ganancias de los socios = ganancia general de lo vendido con reparto. Se verifica con una prueba y se muestra una marca de "cuadra" en pantalla.

### 10.5 Escala

Las métricas leen todas las ventas y se calculan en memoria. Para una tienda con decenas de miles de filas es suficiente. Si el volumen crece mucho, la migración natural es mover las agregaciones a vistas SQL; las definiciones de esta sección sirven de especificación.

---

## 11. Tecnologías

No se agrega ninguna dependencia nueva.

| Necesidad | Tecnología (ya presente en el proyecto) |
|---|---|
| Lógica y fórmulas | Kotlin (módulo `commerce`, puro y probable sin Docker) |
| Servidor y rutas | Ktor / Netty |
| Acceso a datos | Exposed + HikariCP |
| Creación y evolución del esquema | Flyway sobre PostgreSQL de Supabase (`V001`/`V002`/`R__` del plan v2) |
| Dashboard | HTML generado en Kotlin + HTMX 1.9.10 (ya fijado con SRI) |
| Gráficas | Barras en CSS (ya usadas) y SVG en línea; sin librerías |
| Excel | Apache POI (ya usado) |
| Pruebas | JUnit; Testcontainers `postgres:16` (se introduce en la fase F4 del plan v2) |

---

## 12. Fases de implementación

Cada fase = una rama = un PR, con el build en verde antes y después. Se coordinan con las fases del plan v2 así: **F-S1 se hace dentro de la F1 del v2; F-S3 junto con la F2; F-S6 junto con la F3; F-S7 junto con las F4 y F5.**

| Fase | Contenido | Esfuerzo |
|---|---|---|
| **F-S0** Decisiones | Confirmar D-S1 a D-S13 (sobre todo las marcadas con (!)). Confirmar en `ConfirmPaymentUseCase` y el webhook de Shopify qué ocurre hoy al vender sin reparto (D-S10) | S |
| **F-S1** SQL | Escribir `V001`, `V002` y `R__` incluyendo las secciones 5.1 a 5.3. Actualizar los mensajes de error | M |
| **F-S2** Dominio Kotlin | `Socio`, `ReparteSocios`, `FinancialMath.repartir` con N socios, `VentaReparto`, puertos, `SocioService`. Actualizar las pruebas existentes (H14). Política de ventas automáticas con reparto incompleto (D-S10) | M |
| **F-S3** Infraestructura | `Tables.kt`, repositorios Exposed, `SchemaPreflight` con las tablas nuevas, lectura de porcentajes en una sola consulta, manejo del error de commit del trigger diferido | M |
| **F-S4** Socios e inventario en el dashboard | Menú Socios, vista Socios, pestaña Inventario con columnas por socio y edición, rutas y seguridad | L |
| **F-S5** Métricas | `MetricasService` con todas las fórmulas, rankings y cuadre; pestañas Generales y Por socio; filtros de fecha | L |
| **F-S6** Excel | Columnas por socio al exportar; lectura, avisos y bloqueos al importar; porcentajes en la misma transacción | S |
| **F-S7** Pruebas con PostgreSQL real | Las pruebas de la sección 13, ejecutadas con Testcontainers; ajustar el SQL hasta que pasen. (Opcional: vista `v_inventario_socios`, 5.4) | M |
| **F-S8** Aplicar y documentar | Probar en Supabase vacío (humo manual); actualizar `README.md`, `MANUAL_DE_USO.md`, `DIAGRAMA_DE_CLASES`, y escribir el manual de cambios (con la estructura del `MANUAL_DE_CAMBIOS_v5.md`) | S |

**Orden recomendado:** F-S0 → F-S1 → F-S2 → F-S3 → F-S7 (primero la base y sus pruebas) → F-S4 → F-S5 → F-S6 → F-S8. Las pantallas se construyen cuando las reglas ya están probadas.

---

## 13. Pruebas

**Sin Docker (dominio):**

- `ReparteSocios`: rechaza valores fuera de 0–100 y sumas mayores que 100; `definida` solo con 100 exacto.
- `FinancialMath.repartir`: para 1 a 12 socios y valores positivos, cero y negativos, la suma de los repartos es **exactamente** el valor; un caso con tres partes iguales (33,34 / 33,33 / 33,33).
- `MetricasService`: un conjunto de datos pequeño con resultados calculados a mano (todas las métricas de la sección 10), casos sin ventas, sin fecha de compra y con costo 0; el **cuadre** socios = general.
- `SocioService`: nombres vacíos, repetidos (sin importar mayúsculas), demasiado largos, límite de socios.
- Renderizadores: un nombre de socio con `<script>` sale escapado; sin socios aparece la tarjeta de bienvenida.

**Con PostgreSQL real (Testcontainers):**

- Base vacía: el arranque crea todo y hay **0 socios**.
- Idempotencia: arrancar dos veces no cambia nada.
- Base con datos legados (`pct_antonio`/`pct_javier`): se crean "Antonio" y "Javier", se copian porcentajes y ventas, nada se pierde.
- Un artículo con 60 % + 50 % **falla al confirmar**; mover 60/40 a 40/60 dentro de una transacción **funciona**.
- Dos transacciones simultáneas que suman 60 + 60 al mismo artículo: una falla.
- Vender con 0 socios, o con suma 90, falla con el mensaje esperado; con suma 100 funciona y crea `venta_reparto` con suma exacta.
- Editar porcentajes de un artículo vendido falla; anular la venta borra `venta_reparto` y conserva los porcentajes.
- `retirar_socio`: sin ventas → `ELIMINADO`; con ventas → `ARCHIVADO`; en ambos casos se limpian los artículos sin vender y las ventas pasadas no cambian.
- Asignar porcentaje a un socio archivado falla.
- El historial registra altas, cambios y retiros de socios y porcentajes con usuario y origen.
- Excel: exportación con columnas de socios; importación con y sin ellas; columna de socio inexistente; suma mayor a 100.
- Rutas: sin autenticación o sin CSRF las rutas que modifican rechazan la petición.

---

## 14. Criterios de aceptación

- Al abrir por primera vez, el dashboard muestra **0 socios** y una invitación a agregar el primero.
- Agregar un socio crea su columna de porcentaje en la tabla de inventario; retirarlo la quita de las columnas nuevas y, si tenía ventas, queda archivado con su histórico intacto.
- Ninguna ruta, pantalla, importación ni escritura directa en la base puede dejar un artículo con suma de porcentajes mayor a 100.
- No se puede vender un artículo sin que la suma sea 100.
- La tabla de métricas generales y la de métricas por socio existen, responden al filtro de fechas y **cuadran entre sí**.
- Los rankings responden: qué se vende más (tipo y modelo), más rápido, más lento, más ganancia, mejor relación inversión–ganancia y pérdidas.
- Con Supabase vacío, arrancar la app crea todo sin pasos manuales; con datos legados, los adopta sin perder nada.
- No hay ningún archivo de migración nuevo: los cambios viven en `V001`, `V002` y `R__` (o en un único `V005` si esos ya se aplicaron).
- Las pruebas de la sección 13 pasan.

---

## 15. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| El SQL nunca se ha ejecutado contra PostgreSQL | F-S7 lo prueba antes de tocar Supabase; mientras no se aplique, los archivos se pueden reescribir |
| El trigger diferido falla en el *commit* y no en la sentencia, y el error llega tarde | `ExposedInversionRepository` captura el fallo del commit y lo traduce; además el servidor valida antes de escribir, así que el trigger es la última línea de defensa |
| Ventas automáticas (Shopify/chat) rechazadas por reparto incompleto | D-S10: se registran en `eventos_sin_resolver` y el dashboard avisa; se verifica en F-S0 |
| Usuarios que no repartan porcentajes y no puedan vender | Alertas en el inventario, filtro "reparto incompleto" y el botón "Repartir en partes iguales" |
| Tres socios y 33,33 % que suman 99,99 | D-S9: el botón reparte 33,34 / 33,33 / 33,33 |
| Un socio archivado deja artículos en stock con suma menor a 100 | Aviso previo con el número de artículos afectados (D-S4) |
| Cambiar una fórmula de métricas | Solo existe en `MetricasService`/`FinancialMath`; una sola prueba de cuadre detecta desvíos |
| Métricas lentas con mucho volumen | Sección 10.5: pasar a vistas SQL; las definiciones ya están escritas |
| Datos legados con `pct_antonio` + `pct_javier` que no sumaban 0 o 100 | Se copian tal cual; si alguno supera 100 el trigger lo rechaza y el informe de diagnóstico del plan v2 lo reporta |
| `V001`–`V004` ya aplicados antes de implementar esto | Se usa un `V005` con las mismas definiciones (sección 4) |

---

## 16. Entregables por fase

Con el formato habitual: **archivos modificados completos**, **manual de cambios** (qué fragmento cambió y por qué) y **diagrama de clases actualizado** en archivo aparte.

| Fase | Archivos |
|---|---|
| F-S1 | `V001__base_inventario.sql`, `V002__ventas_y_metricas.sql`, `R__funciones_triggers_y_vistas.sql` |
| F-S2 | `ReparteSocios.kt`, `InventarioItem.kt`, `Venta.kt`, `FinancialMath.kt`, `SocioService.kt`, `MetricasService.kt`, puertos nuevos y pruebas actualizadas |
| F-S3 | `Tables.kt`, `ExposedInventarioRepository.kt`, `ExposedSocioRepository.kt`, `ExposedInversionRepository.kt`, `ExposedVentaRepository.kt`, `SchemaPreflight.kt` |
| F-S4 | `SociosRenderer.kt`, `InventarioRenderer.kt`, `DashboardRenderer.kt`, `SociosRoutes.kt`, `InversionRoutes.kt`, `Application.kt` |
| F-S5 | `MetricasRenderer.kt`, `MetricasRoutes.kt` |
| F-S6 | `ExcelGenerator.kt`, `ExcelV2Parser.kt`, `ExcelPreviewService.kt`, `ExcelRoutes.kt` |
| F-S7 | `SociosSchemaTest`, `InversionReglasTest`, `RetirarSocioTest`, `MetricasCuadreTest`, ampliación de las pruebas de Excel |
| F-S8 | `README.md`, `MANUAL_DE_USO.md`, `DIAGRAMA_DE_CLASES_v5.md`, `MANUAL_DE_CAMBIOS_v6.md` |

---

## 17. Preguntas para confirmar antes de empezar

1. **Base del porcentaje (D-S2):** ¿es sobre el costo total del artículo (compra + reparación), como asumí, o sobre el precio de venta?
2. **Suma al vender (D-S3):** ¿debe ser exactamente 100 %, o prefieres poder vender con menos de 100 y ver el resto como "sin asignar"?
3. **"Elemento único" (D-S7):** ¿te sirve agrupar por modelo (nombre + marca) además de por Key?
4. **Eliminar socios (D-S4):** ¿está bien que un socio con ventas se archive en vez de borrarse?
5. **Ventas automáticas sin reparto (D-S10):** ¿prefieres que queden como alerta pendiente (mi recomendación) o que la venta se rechace?
