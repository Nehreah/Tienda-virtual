-- V016__dynamic_partners_and_metrics.sql
-- Fase F-S1: Tabla de socios dinámicos, porcentajes de inversión por socio y repartos de venta

-- 1. Tabla de socios
create table if not exists tienda.socio (
  id           bigint generated always as identity primary key,
  nombre       text not null check (btrim(nombre) <> '' and char_length(nombre) <= 60),
  activo       boolean not null default true,
  creado_en    timestamptz not null default now(),
  archivado_en timestamptz
);

create unique index if not exists ux_socio_nombre_activo
  on tienda.socio (lower(btrim(nombre))) where activo;

-- 2. Tabla de porcentajes de inversión por artículo
create table if not exists tienda.inventario_inversion (
  key       text   not null references tienda.inventario(key)
                     on delete cascade on update cascade,
  socio_id  bigint not null references tienda.socio(id) on delete restrict,
  pct       numeric(5,2) not null check (pct >= 0 and pct <= 100),
  primary key (key, socio_id)
);
create index if not exists ix_inversion_socio on tienda.inventario_inversion (socio_id);

-- 3. Adopción de datos legados de Antonio y Javier si existen
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

-- 4. Columna de precio publicado en ventas
alter table tienda.ventas add column if not exists precio_publicado bigint;

-- 5. Tabla de reparto de ventas por socio
create table if not exists tienda.venta_reparto (
  key        text   not null references tienda.ventas(key) on delete cascade,
  socio_id   bigint not null references tienda.socio(id) on delete restrict,
  pct        numeric(5,2) not null check (pct > 0 and pct <= 100),
  inversion  bigint not null check (inversion >= 0),
  ingresos   bigint not null check (ingresos >= 0),
  ganancia   bigint not null,
  primary key (key, socio_id)
);
create index if not exists ix_venta_reparto_socio on tienda.venta_reparto (socio_id);

-- 6. Función de reparto proporcional exacto (método del mayor residuo)
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

-- 7. Trigger de restricción diferido para validar que la suma por artículo no supere 100%
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

-- 8. Trigger BEFORE para inmutabilidad de artículos vendidos y bloqueo de socios archivados
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

drop trigger if exists trg_a_inversion_bloqueo on tienda.inventario_inversion;
create trigger trg_a_inversion_bloqueo
  before insert or update or delete on tienda.inventario_inversion
  for each row execute function tienda.trg_inversion_bloqueo();

-- 9. Función para retirar socio (eliminar si sin ventas, archivar si con ventas)
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

-- 10. RLS y Permisos
alter table tienda.socio enable row level security;
alter table tienda.inventario_inversion enable row level security;
alter table tienda.venta_reparto enable row level security;

grant select, insert, update, delete on tienda.socio to app_user;
grant select, insert, update, delete on tienda.inventario_inversion to app_user;
grant select, insert, update, delete on tienda.venta_reparto to app_user;
