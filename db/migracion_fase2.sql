-- =====================================================================================
-- MIGRACIÓN FASE 2 — Tienda virtual (esquema "tienda", PostgreSQL / Supabase)
-- =====================================================================================
-- ANTES DE CORRER ESTO:
--   1. Haz un respaldo completo (pg_dump) o corre primero sobre una COPIA de la base.
--   2. Corre db/00_diagnostico_previo.sql y revisa qué triggers y restricciones existen hoy.
--   3. Este script NO fue ejecutado contra tu base real (no tengo tu schema.sql). Está escrito
--      sobre las tablas y columnas que usa el código Kotlin (Tables.kt). Corre con PRUEBA primero.
--
-- QUÉ HACE (todo en UNA transacción: si algo falla, no queda nada a medias):
--   A. Crea config, cat_tipo y las tablas reparaciones, ventas y metricas.
--   B. Agrega a inventario: tipo, requiere_reparacion, pct_antonio, pct_javier, canal_venta;
--      renombra costo_compra_reparacion -> costo_total.
--   C. Copia los datos de clasificacion, inversion, revision y canal_venta a las columnas/tablas nuevas.
--      (Las tablas viejas NO se borran: se borran en la Fase 2.7, cuando verifiques todo.)
--   D. Reemplaza los triggers de inventario por los nuevos (reglas R1 a R11 del plan).
--   E. Recalcula costo_total y precio_minimo de todo el inventario y genera ventas y métricas
--      de los artículos que ya estaban Vendidos.
--   F. Crea las vistas del dashboard y cierra los permisos (RLS).
--
-- DECISIONES IMPLEMENTADAS:
--   D1  precio_minimo = costo_total x 1,5, redondeado hacia arriba al peso (ROI mínimo en tienda.config)
--   D2  (CAMBIADA a tu pedido) La venta la marcas TÚ: llenas fecha_venta, vendido_en y canal_venta en
--       inventario y cambias estado a 'Vendido'. Los triggers calculan ventas y métricas solos.
--       Si cambias el estado a otro, la venta se anula. Escribir directo en ventas/metricas está bloqueado.
--   D4  tiempo_dias = días desde la COMPRA; dias_publicado = días desde la PUBLICACIÓN
--   D5  periodo: corto < 15 días, medio 15 a 29, largo 30 o más
-- =====================================================================================

begin;

-- Estas tres variables valen SOLO dentro de esta transacción.
set local app.origen = 'migracion';
set local app.usuario = 'migracion_fase2';
set local app.migracion = 'on';      -- relaja validaciones de negocio mientras se copian datos históricos

-- ---------- 0. Verificaciones previas --------------------------------------------------------
do $$
begin
  if to_regclass('tienda.inventario') is null then
    raise exception 'No existe tienda.inventario: este script es para una base que ya tiene el esquema tienda.';
  end if;
  if to_regclass('tienda.historial') is null then
    raise exception 'No existe tienda.historial: los triggers de historial necesitan esa tabla.';
  end if;
end $$;

-- ---------- A. Catálogos y configuración -----------------------------------------------------
create table if not exists tienda.config (
  clave text primary key,
  valor numeric not null
);
insert into tienda.config (clave, valor) values
  ('roi_minimo_pct',     50),   -- D1: ROI mínimo sobre el costo total
  ('periodo_corto_dias', 15),   -- D5: corto = menos de 15 días
  ('periodo_medio_dias', 30)    -- D5: medio = de 15 a 29; largo = 30 o más
on conflict (clave) do nothing;

create table if not exists tienda.cat_tipo (tipo text primary key);

create table if not exists tienda.cat_estado (estado text primary key);
insert into tienda.cat_estado (estado) values ('Comprado'), ('Publicado'), ('Negociación'), ('Vendido'), ('Otro')
on conflict (estado) do nothing;

-- ---------- B1. Quitar los triggers viejos de las tablas que cambian -------------------------
-- (incluye los de historial: se reemplazan por los de este script)
do $$
declare r record;
begin
  for r in
    select t.tgname, c.relname
    from pg_trigger t
    join pg_class c on c.oid = t.tgrelid
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'tienda'
      and c.relname in ('inventario', 'revision', 'inversion', 'clasificacion', 'canal_venta')
      and not t.tgisinternal
  loop
    execute format('drop trigger %I on tienda.%I', r.tgname, r.relname);
    raise notice 'Trigger viejo eliminado: % en tienda.%', r.tgname, r.relname;
  end loop;
end $$;

-- ---------- B2. Quitar restricciones CHECK viejas que chocan con las reglas nuevas ----------
-- Las reemplazan los triggers (PM ya no lo escribes tú; Vendido <-> datos de venta se valida en el trigger).
do $$
declare r record;
begin
  for r in
    select conname, pg_get_constraintdef(oid) as def
    from pg_constraint
    where conrelid = 'tienda.inventario'::regclass
      and contype = 'c'
      and (pg_get_constraintdef(oid) ilike '%precio_minimo%'
        or pg_get_constraintdef(oid) ilike '%vendido_en%'
        or pg_get_constraintdef(oid) ilike '%fecha_venta%')
  loop
    execute format('alter table tienda.inventario drop constraint %I', r.conname);
    raise notice 'Restricción vieja eliminada: % (%)', r.conname, r.def;
  end loop;
end $$;

-- ---------- B3. Columnas nuevas en inventario -----------------------------------------------
alter table tienda.inventario
  add column if not exists tipo                text,
  add column if not exists requiere_reparacion boolean       not null default false,
  add column if not exists pct_antonio         numeric(5,2)  not null default 0,
  add column if not exists pct_javier          numeric(5,2)  not null default 0,
  add column if not exists canal_venta         text,
  add column if not exists updated_at          timestamptz   not null default now();

do $$
begin
  if exists (select 1 from information_schema.columns
             where table_schema = 'tienda' and table_name = 'inventario'
               and column_name = 'costo_compra_reparacion') then
    alter table tienda.inventario rename column costo_compra_reparacion to costo_total;
  end if;
end $$;
alter table tienda.inventario add column if not exists costo_total bigint not null default 0;
alter table tienda.inventario add column if not exists precio_minimo bigint;

-- Restricciones nuevas (se recrean si ya existían de un intento anterior)
alter table tienda.inventario drop constraint if exists ck_pct_rango;
alter table tienda.inventario drop constraint if exists ck_pct_suma;
alter table tienda.inventario drop constraint if exists ck_inventario_montos;
alter table tienda.inventario drop constraint if exists fk_inventario_tipo;

alter table tienda.inventario
  add constraint ck_pct_rango check (pct_antonio between 0 and 100 and pct_javier between 0 and 100);
-- ck_pct_suma (suma 0 o 100) se agrega más abajo, DESPUÉS de copiar los datos de tienda.inversion.
alter table tienda.inventario
  add constraint ck_inventario_montos check (
    coalesce(vendido_en, 0) >= 0 and coalesce(precio_publicado, 0) >= 0 and coalesce(precio_minimo, 0) >= 0);
alter table tienda.inventario
  add constraint fk_inventario_tipo foreign key (tipo) references tienda.cat_tipo (tipo);

create index if not exists ix_inventario_estado on tienda.inventario (estado);
create index if not exists ix_inventario_tipo   on tienda.inventario (tipo);

-- ---------- B4. Tablas nuevas: reparaciones, ventas, métricas --------------------------------
create table if not exists tienda.reparaciones (
  key               text primary key references tienda.inventario (key) on delete cascade,
  precio_reparacion bigint not null default 0 check (precio_reparacion >= 0),
  detalles          text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);

create table if not exists tienda.ventas (
  key            text primary key references tienda.inventario (key),
  fecha_venta    date   not null,
  vendido_en     bigint not null check (vendido_en >= 0),
  canal          text,
  ingresos       bigint not null,                                   -- = vendido_en (D6)
  costo_total    bigint not null,                                   -- copia congelada al vender
  ganancia_neta  bigint not null,                                   -- puede ser negativa (pérdida)
  margen_pct     numeric(7,2),                                      -- vacío si vendido_en = 0
  tiempo_dias    integer check (tiempo_dias is null or tiempo_dias >= 0),         -- desde la COMPRA (D4); vacío sin fecha_compra
  dias_publicado integer check (dias_publicado is null or dias_publicado >= 0),   -- desde la PUBLICACIÓN (D4)
  periodo        text   check (periodo is null or periodo in ('corto', 'medio', 'largo')),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);
create index if not exists ix_ventas_fecha on tienda.ventas (fecha_venta);

create table if not exists tienda.metricas (
  key               text primary key references tienda.ventas (key) on delete cascade,
  ingresos_antonio  bigint not null,
  ganancias_antonio bigint not null,
  ingresos_javier   bigint not null,
  ganancias_javier  bigint not null
);

-- ---------- C. Copiar datos de las tablas viejas ---------------------------------------------
do $$
begin
  -- clasificacion.categoria -> inventario.tipo
  if to_regclass('tienda.clasificacion') is not null then
    insert into tienda.cat_tipo (tipo)
      select distinct trim(categoria) from tienda.clasificacion
      where nullif(trim(categoria), '') is not null
    on conflict (tipo) do nothing;
    update tienda.inventario i
       set tipo = nullif(trim(c.categoria), '')
      from tienda.clasificacion c
     where c.key = i.key;
  end if;

  -- inversion -> pct_antonio / pct_javier
  if to_regclass('tienda.inversion') is not null then
    update tienda.inventario i
       set pct_antonio = v.pct_antonio, pct_javier = v.pct_javier
      from tienda.inversion v
     where v.key = i.key;
  end if;

  -- canal_venta -> inventario.canal_venta (en minúsculas, como lo guardaba la app)
  if to_regclass('tienda.canal_venta') is not null then
    update tienda.inventario i
       set canal_venta = nullif(lower(trim(c.canal)), '')
      from tienda.canal_venta c
     where c.key = i.key;
  end if;

  -- revision -> reparaciones (+ requiere_reparacion = true donde exista fila)
  if to_regclass('tienda.revision') is not null then
    insert into tienda.reparaciones (key, precio_reparacion, detalles)
      select r.key, r.reparacion, r.detalles from tienda.revision r
      where exists (select 1 from tienda.inventario i where i.key = r.key)
    on conflict (key) do update
      set precio_reparacion = excluded.precio_reparacion, detalles = excluded.detalles;
    update tienda.inventario i
       set requiere_reparacion = true
     where exists (select 1 from tienda.revision r where r.key = i.key);
  end if;
end $$;

-- Ahora que los porcentajes están copiados: ambos en 0 (sin repartir) o suma exactamente 100.
alter table tienda.inventario
  add constraint ck_pct_suma check ((pct_antonio + pct_javier) in (0, 100));

-- ---------- D. Funciones y triggers -----------------------------------------------------------

-- D.0 Configuración y fórmulas (espejo de FinancialMath.kt)
create or replace function tienda.fn_config(p_clave text, p_defecto numeric)
returns numeric language sql stable as $$
  select coalesce((select valor from tienda.config where clave = p_clave), p_defecto)
$$;

-- D1: costo total x (1 + ROI mínimo), redondeado hacia arriba al peso.
create or replace function tienda.fn_precio_minimo(p_costo_total bigint)
returns bigint language sql stable as $$
  select ceil(p_costo_total::numeric * (100 + tienda.fn_config('roi_minimo_pct', 50)) / 100)::bigint
$$;

-- D5: corto < 15 días, medio 15 a 29, largo 30 o más.
create or replace function tienda.fn_periodo(p_dias integer)
returns text language sql stable as $$
  select case
    when p_dias is null then null
    when p_dias < tienda.fn_config('periodo_corto_dias', 15) then 'corto'
    when p_dias < tienda.fn_config('periodo_medio_dias', 30) then 'medio'
    else 'largo'
  end
$$;

-- D.1 Historial genérico (R11). Funciona para cualquier tabla con columna "key".
-- Si el cambio viene del editor de Supabase (sin app.origen) queda origen = 'supabase'.
create or replace function tienda.trg_historial_generico()
returns trigger language plpgsql as $$
declare
  v_old     jsonb;
  v_new     jsonb;
  v_cambios jsonb;
  v_key     text;
begin
  if TG_OP = 'INSERT' then
    v_old := '{}'::jsonb; v_new := to_jsonb(NEW); v_key := NEW.key;
  elsif TG_OP = 'UPDATE' then
    v_old := to_jsonb(OLD); v_new := to_jsonb(NEW); v_key := NEW.key;
  else
    v_old := to_jsonb(OLD); v_new := '{}'::jsonb; v_key := OLD.key;
  end if;

  select coalesce(jsonb_object_agg(k, jsonb_build_object('antes', v_old -> k, 'despues', v_new -> k)), '{}'::jsonb)
    into v_cambios
  from (select jsonb_object_keys(v_old || v_new) as k) t
  where k not in ('updated_at', 'created_at')
    and (v_old -> k) is distinct from (v_new -> k);

  if TG_OP = 'UPDATE' and v_cambios = '{}'::jsonb then
    return null;   -- no cambió nada relevante
  end if;

  insert into tienda.historial (tabla, key, operacion, cambios, origen, usuario)
  values (
    TG_TABLE_NAME, v_key, TG_OP, v_cambios,
    coalesce(nullif(current_setting('app.origen', true), ''), 'supabase'),
    nullif(current_setting('app.usuario', true), '')
  );
  return null;
end $$;

-- D.2 Inventario ANTES de insertar/actualizar (R1, R4, R5, R6, R9 + validaciones de venta)
create or replace function tienda.trg_inventario_antes()
returns trigger language plpgsql as $$
declare
  v_rep       bigint := 0;
  v_revisar   boolean := false;
  v_migracion boolean := coalesce(current_setting('app.migracion', true), '') = 'on';
begin
  -- Normalizar texto
  NEW.tipo := nullif(trim(NEW.tipo), '');
  NEW.canal_venta := nullif(lower(trim(NEW.canal_venta)), '');
  if NEW.tipo is not null then
    insert into tienda.cat_tipo (tipo) values (NEW.tipo) on conflict (tipo) do nothing;   -- el tipo nuevo se registra solo
  end if;

  -- Fechas coherentes
  if NEW.fecha_compra is not null and NEW.fecha_publicacion is not null
     and NEW.fecha_publicacion < NEW.fecha_compra and not v_migracion then
    raise exception 'La fecha de publicación (%) no puede ser anterior a la fecha de compra (%) en %.',
      NEW.fecha_publicacion, NEW.fecha_compra, NEW.key;
  end if;

  if TG_OP = 'UPDATE' then
    -- R9 / D8: un artículo vendido no admite cambios de compra, reparación ni porcentajes
    if OLD.estado = 'Vendido' and NEW.estado = 'Vendido' and not v_migracion then
      if NEW.precio_compra is distinct from OLD.precio_compra
         or NEW.fecha_compra is distinct from OLD.fecha_compra
         or NEW.requiere_reparacion is distinct from OLD.requiere_reparacion
         or NEW.pct_antonio is distinct from OLD.pct_antonio
         or NEW.pct_javier is distinct from OLD.pct_javier then
        raise exception 'El artículo % ya está vendido: no se puede cambiar compra, reparación ni porcentajes. Para corregir, cambia el estado a "Publicado" (anula la venta), edita y vuelve a marcarlo "Vendido".', NEW.key;
      end if;
    end if;

    -- Anulación: al dejar de estar Vendido se borran los datos de venta (quedan en el historial)
    if OLD.estado = 'Vendido' and NEW.estado <> 'Vendido' then
      NEW.fecha_venta := null;
      NEW.vendido_en  := null;
      NEW.canal_venta := null;
    end if;

    -- R4: no se puede quitar "requiere reparación" si la reparación ya tiene precio
    if OLD.requiere_reparacion and not NEW.requiere_reparacion then
      if exists (select 1 from tienda.reparaciones where key = NEW.key and precio_reparacion > 0) then
        raise exception 'El artículo % tiene una reparación de precio mayor a 0. Pon primero el precio de la reparación en 0 si de verdad no hubo reparación.', NEW.key;
      end if;
    end if;
  end if;

  -- R1: costo total y precio mínimo (D1: sobre el costo TOTAL)
  if NEW.requiere_reparacion then
    select precio_reparacion into v_rep from tienda.reparaciones where key = NEW.key;
  end if;
  NEW.costo_total   := NEW.precio_compra + coalesce(v_rep, 0);
  NEW.precio_minimo := tienda.fn_precio_minimo(NEW.costo_total);

  -- R5 / D3: Publicado o Negociación exigen precio de venta y que no sea menor al mínimo
  if NEW.estado in ('Publicado', 'Negociación') and not v_migracion then
    -- Solo se revisa al crear o cuando cambia el estado, el precio de venta o el mínimo
    -- (así una fila antigua no se bloquea por editar otro campo).
    if TG_OP = 'INSERT' then
      v_revisar := true;
    else
      v_revisar := OLD.estado is distinct from NEW.estado
                or OLD.precio_publicado is distinct from NEW.precio_publicado
                or OLD.precio_minimo is distinct from NEW.precio_minimo;
    end if;
    if v_revisar then
      if NEW.precio_publicado is null then
        raise exception 'Para pasar % a "%" primero llena precio_publicado.', NEW.key, NEW.estado;
      end if;
      if NEW.precio_publicado < NEW.precio_minimo then
        raise exception 'El precio de venta de % (%) es menor al precio mínimo (%) = costo total (%) x 1,5.',
          NEW.key, NEW.precio_publicado, NEW.precio_minimo, NEW.costo_total;
      end if;
    end if;
  end if;

  -- R6 / D2 / D7: marcar Vendido a mano exige los datos de la venta y el reparto de socios
  if NEW.estado = 'Vendido' and not v_migracion then
    if NEW.fecha_venta is null or NEW.vendido_en is null then
      raise exception 'Para marcar % como Vendido primero llena fecha_venta y vendido_en (y canal_venta si quieres), y luego cambia el estado.', NEW.key;
    end if;
    if NEW.pct_antonio + NEW.pct_javier <> 100 then
      raise exception 'Para vender % los porcentajes de Antonio y Javier deben sumar 100 (hoy suman %).',
        NEW.key, NEW.pct_antonio + NEW.pct_javier;
    end if;
    if NEW.fecha_compra is not null and NEW.fecha_venta < NEW.fecha_compra then
      raise exception 'La fecha de venta (%) no puede ser anterior a la fecha de compra (%) en %.',
        NEW.fecha_venta, NEW.fecha_compra, NEW.key;
    end if;
    if NEW.fecha_publicacion is not null and NEW.fecha_venta < NEW.fecha_publicacion then
      raise exception 'La fecha de venta (%) no puede ser anterior a la fecha de publicación (%) en %.',
        NEW.fecha_venta, NEW.fecha_publicacion, NEW.key;
    end if;
  end if;

  NEW.updated_at := now();
  return NEW;
end $$;

-- D.3 Venta: calcula ventas y métricas a partir de inventario (R7, R8). Solo la llaman triggers.
create or replace function tienda.sync_venta(p_key text)
returns void language plpgsql as $$
declare
  i          tienda.inventario%rowtype;
  v_ganancia bigint;
  v_tiempo   integer;
  v_dias_pub integer;
  v_total    numeric;
  v_ing_a    bigint;
  v_gan_a    bigint;
begin
  select * into i from tienda.inventario where key = p_key;

  -- Ya no está vendido (o no existe): se borra la venta; metricas se borra en cascada.
  if not found or i.estado <> 'Vendido' then
    delete from tienda.ventas where key = p_key;
    return;
  end if;

  v_ganancia := i.vendido_en - i.costo_total;
  v_tiempo   := case when i.fecha_compra is null then null
                     else greatest(i.fecha_venta - i.fecha_compra, 0) end;            -- D4: desde la compra
  v_dias_pub := case when i.fecha_publicacion is null then null
                     else greatest(i.fecha_venta - i.fecha_publicacion, 0) end;       -- D4: desde la publicación

  insert into tienda.ventas (key, fecha_venta, vendido_en, canal, ingresos, costo_total,
                             ganancia_neta, margen_pct, tiempo_dias, dias_publicado, periodo)
  values (i.key, i.fecha_venta, i.vendido_en, i.canal_venta, i.vendido_en, i.costo_total,
          v_ganancia,
          case when i.vendido_en = 0 then null else round(v_ganancia::numeric * 100 / i.vendido_en, 2) end,
          v_tiempo, v_dias_pub, tienda.fn_periodo(v_tiempo))
  on conflict (key) do update set
    fecha_venta    = excluded.fecha_venta,
    vendido_en     = excluded.vendido_en,
    canal          = excluded.canal,
    ingresos       = excluded.ingresos,
    costo_total    = excluded.costo_total,
    ganancia_neta  = excluded.ganancia_neta,
    margen_pct     = excluded.margen_pct,
    tiempo_dias    = excluded.tiempo_dias,
    dias_publicado = excluded.dias_publicado,
    periodo        = excluded.periodo,
    updated_at     = now();

  -- Reparto entre socios: Antonio redondeado al peso, Javier = el resto (la suma siempre cuadra exacto,
  -- igual que FinancialMath.repartir). Sin reparto definido (solo posible con datos históricos) no hay métricas.
  v_total := i.pct_antonio + i.pct_javier;
  if v_total > 0 then
    v_ing_a := round(i.vendido_en::numeric * i.pct_antonio / v_total)::bigint;
    v_gan_a := round(v_ganancia::numeric   * i.pct_antonio / v_total)::bigint;
    insert into tienda.metricas (key, ingresos_antonio, ganancias_antonio, ingresos_javier, ganancias_javier)
    values (i.key, v_ing_a, v_gan_a, i.vendido_en - v_ing_a, v_ganancia - v_gan_a)
    on conflict (key) do update set
      ingresos_antonio  = excluded.ingresos_antonio,
      ganancias_antonio = excluded.ganancias_antonio,
      ingresos_javier   = excluded.ingresos_javier,
      ganancias_javier  = excluded.ganancias_javier;
  else
    delete from tienda.metricas where key = i.key;
  end if;
end $$;

-- D.4 Inventario DESPUÉS de insertar/actualizar (R3, R4, R7, R8)
create or replace function tienda.trg_inventario_despues()
returns trigger language plpgsql as $$
begin
  -- R3: al marcar "requiere reparación" se crea la fila lista para llenar (precio 0)
  if NEW.requiere_reparacion
     and not exists (select 1 from tienda.reparaciones where key = NEW.key) then
    insert into tienda.reparaciones (key) values (NEW.key);
  end if;

  -- R4: al volver a "no", se borra la fila de reparación vacía (si tuviera precio, el trigger BEFORE ya dio error)
  if not NEW.requiere_reparacion then
    delete from tienda.reparaciones where key = NEW.key and precio_reparacion = 0;
  end if;

  -- R7 / R8: crear, actualizar o anular la venta
  if NEW.estado = 'Vendido' then
    perform tienda.sync_venta(NEW.key);
  elsif TG_OP = 'UPDATE' then
    if OLD.estado = 'Vendido' then
      perform tienda.sync_venta(NEW.key);   -- ya no está vendido: borra la venta
    end if;
  end if;

  return null;
end $$;

-- D.5 Reparaciones: bloqueos (R9) y recálculo del costo (R2)
create or replace function tienda.trg_reparaciones_antes()
returns trigger language plpgsql as $$
declare
  v_key       text := case when TG_OP = 'DELETE' then OLD.key else NEW.key end;
  v_migracion boolean := coalesce(current_setting('app.migracion', true), '') = 'on';
begin
  -- Se bloquea la edición DIRECTA (profundidad < 2). Los cambios que hace el propio trigger de
  -- inventario (crear/borrar la fila vacía, profundidad >= 2) sí pasan.
  if not v_migracion and pg_trigger_depth() < 2 then
    if exists (select 1 from tienda.inventario where key = v_key and estado = 'Vendido') then
      raise exception 'El artículo % ya está vendido: no se puede cambiar la reparación. Anula la venta primero (estado = "Publicado").', v_key;
    end if;
  end if;

  if TG_OP <> 'DELETE' then
    if not exists (select 1 from tienda.inventario where key = NEW.key and requiere_reparacion) then
      raise exception 'Primero marca requiere_reparacion = true en el artículo % para poder cargarle una reparación.', NEW.key;
    end if;
    NEW.updated_at := now();
    return NEW;
  end if;
  return OLD;
end $$;

create or replace function tienda.trg_reparaciones_despues()
returns trigger language plpgsql as $$
declare
  v_key     text;
  v_cambio  boolean := false;
begin
  -- Solo si el costo cambió de verdad. "Tocar" el artículo vuelve a disparar el cálculo de costo_total y precio_minimo.
  if TG_OP = 'INSERT' then
    v_key := NEW.key;  v_cambio := NEW.precio_reparacion > 0;
  elsif TG_OP = 'UPDATE' then
    v_key := NEW.key;  v_cambio := NEW.precio_reparacion is distinct from OLD.precio_reparacion;
  else
    v_key := OLD.key;  v_cambio := OLD.precio_reparacion > 0;
  end if;
  if v_cambio then
    update tienda.inventario set costo_total = costo_total where key = v_key;
  end if;
  return null;
end $$;

-- D.6 ventas y metricas son CALCULADAS: nadie las edita directo (solo los triggers de inventario).
-- pg_trigger_depth() >= 2 significa "me llamó otro trigger" (es decir, el de inventario).
create or replace function tienda.trg_bloquear_escritura_directa()
returns trigger language plpgsql as $$
begin
  if pg_trigger_depth() < 2 then
    raise exception 'tienda.% se calcula sola. Para registrar o corregir una venta edita fecha_venta, vendido_en, canal_venta y estado en tienda.inventario; para anularla cambia el estado a "Publicado".', TG_TABLE_NAME;
  end if;
  if TG_OP = 'DELETE' then return OLD; end if;
  return NEW;
end $$;

-- D.7 Funciones de comodidad (hacen lo mismo que editar inventario a mano, en una sola sentencia)
create or replace function tienda.registrar_venta(
  p_key text, p_vendido_en bigint, p_fecha date default current_date, p_canal text default null)
returns void language plpgsql as $$
begin
  update tienda.inventario
     set fecha_venta = p_fecha, vendido_en = p_vendido_en,
         canal_venta = coalesce(nullif(lower(trim(p_canal)), ''), canal_venta), estado = 'Vendido'
   where key = p_key and estado <> 'Vendido';
  if not found then
    raise exception 'No se puede registrar la venta de %: no existe o ya está vendido.', p_key;
  end if;
end $$;

create or replace function tienda.anular_venta(p_key text, p_estado text default 'Publicado')
returns void language plpgsql as $$
begin
  if p_estado = 'Vendido' then
    raise exception 'El nuevo estado no puede ser Vendido.';
  end if;
  update tienda.inventario set estado = p_estado where key = p_key and estado = 'Vendido';
  if not found then
    raise exception 'No se puede anular la venta de %: no existe o no está vendido.', p_key;
  end if;
end $$;

-- D.8 Crear los triggers (el prefijo decide el orden: BEFORE y AFTER corren por orden alfabético)
create trigger trg_a_inventario_antes
  before insert or update on tienda.inventario
  for each row execute function tienda.trg_inventario_antes();

create trigger trg_b_inventario_despues
  after insert or update on tienda.inventario
  for each row execute function tienda.trg_inventario_despues();

create trigger trg_z_historial
  after insert or update or delete on tienda.inventario
  for each row execute function tienda.trg_historial_generico();

create trigger trg_a_reparaciones_antes
  before insert or update or delete on tienda.reparaciones
  for each row execute function tienda.trg_reparaciones_antes();

create trigger trg_b_reparaciones_despues
  after insert or update or delete on tienda.reparaciones
  for each row execute function tienda.trg_reparaciones_despues();

create trigger trg_z_historial
  after insert or update or delete on tienda.reparaciones
  for each row execute function tienda.trg_historial_generico();

create trigger trg_a_ventas_solo_triggers
  before insert or update or delete on tienda.ventas
  for each row execute function tienda.trg_bloquear_escritura_directa();

create trigger trg_z_historial
  after insert or update or delete on tienda.ventas
  for each row execute function tienda.trg_historial_generico();

create trigger trg_a_metricas_solo_triggers
  before insert or update or delete on tienda.metricas
  for each row execute function tienda.trg_bloquear_escritura_directa();

create trigger trg_z_historial
  after insert or update or delete on tienda.metricas
  for each row execute function tienda.trg_historial_generico();

-- ---------- E. Recalcular todo lo existente ---------------------------------------------------
-- "Tocar" cada fila dispara los triggers: recalcula costo_total y precio_minimo (D1) y crea
-- ventas + métricas de los artículos que ya estaban Vendidos. Queda en historial con origen 'migracion'.
update tienda.inventario set costo_total = costo_total;

-- Resumen de la migración (aparece como NOTICE en el cliente SQL)
do $$
declare
  n_inv int; n_rep int; n_ven int; n_met int; n_tipos int;
begin
  select count(*) into n_inv   from tienda.inventario;
  select count(*) into n_rep   from tienda.reparaciones;
  select count(*) into n_ven   from tienda.ventas;
  select count(*) into n_met   from tienda.metricas;
  select count(*) into n_tipos from tienda.cat_tipo;
  raise notice 'Inventario: % filas · Reparaciones: % · Ventas: % · Métricas: % · Tipos: %',
    n_inv, n_rep, n_ven, n_met, n_tipos;
end $$;

-- ---------- F. Vistas para el dashboard (Fase 5 las consume) ----------------------------------
create or replace view tienda.v_ventas_detalle as
select v.*, i.item, i.tipo,
       date_trunc('month', v.fecha_venta)::date as mes
from tienda.ventas v
join tienda.inventario i using (key);

create or replace view tienda.v_resumen_mensual_socio as
select date_trunc('month', v.fecha_venta)::date as mes,
       s.socio,
       count(*)        as ventas,
       sum(s.ingreso)  as ingresos,
       sum(s.ganancia) as ganancias
from tienda.ventas v
join tienda.metricas m using (key)
cross join lateral (values
    ('Antonio', m.ingresos_antonio, m.ganancias_antonio),
    ('Javier',  m.ingresos_javier,  m.ganancias_javier)
) as s(socio, ingreso, ganancia)
group by 1, 2;

create or replace view tienda.v_rendimiento_tipo as
select coalesce(i.tipo, 'Sin tipo')                                  as tipo,
       count(*)                                                      as ventas,
       round(avg(v.tiempo_dias), 1)                                  as dias_promedio,
       round(avg(v.dias_publicado), 1)                               as dias_publicado_promedio,
       sum(v.ganancia_neta)                                          as ganancia_total,
       round(avg(v.ganancia_neta))                                   as ganancia_promedio,
       round(avg(v.margen_pct), 1)                                   as margen_promedio_pct,
       count(*) filter (where v.ganancia_neta < 0)                   as ventas_con_perdida,
       coalesce(sum(v.ganancia_neta) filter (where v.ganancia_neta < 0), 0) as monto_perdidas
from tienda.ventas v
join tienda.inventario i using (key)
group by 1;

create or replace view tienda.v_periodo_tipo as
select coalesce(i.tipo, 'Sin tipo') as tipo, v.periodo, count(*) as ventas
from tienda.ventas v
join tienda.inventario i using (key)
group by 1, 2;

-- ---------- G. Seguridad: la API REST de Supabase no debe leer estas tablas -------------------
alter table tienda.reparaciones enable row level security;
alter table tienda.ventas       enable row level security;
alter table tienda.metricas     enable row level security;
alter table tienda.config       enable row level security;
alter table tienda.cat_tipo     enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    revoke all on all tables in schema tienda from anon;
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    revoke all on all tables in schema tienda from authenticated;
  end if;
end $$;

-- ---------- Revisión posterior (solo informativa; no cambia nada) -----------------------------
-- 1) Artículos Publicado/Negociación cuyo precio de venta quedó POR DEBAJO del nuevo mínimo (D1/D3).
--    La migración no los bloquea, pero no podrás volver a publicarlos hasta subir su precio.
select key, item, estado, precio_publicado, precio_minimo, costo_total
from tienda.inventario
where estado in ('Publicado', 'Negociación')
  and (precio_publicado is null or precio_publicado < precio_minimo)
order by key;

-- 2) Artículos ya Vendidos que no tienen reparto de socios (sin métricas por socio).
select i.key, i.item
from tienda.inventario i
where i.estado = 'Vendido' and i.pct_antonio + i.pct_javier = 0
order by i.key;

commit;   -- Para ensayar sin guardar nada, cambia esta línea por: rollback;
