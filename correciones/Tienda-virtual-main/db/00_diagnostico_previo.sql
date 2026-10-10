-- =====================================================================================
-- DIAGNÓSTICO PREVIO (solo lee, no cambia nada). Córrelo ANTES de db/migracion_fase2.sql
-- y guarda el resultado: la migración elimina los triggers y CHECK listados aquí.
-- =====================================================================================

-- 1) Columnas actuales de las tablas que cambian (deben coincidir con infra/.../Tables.kt original)
select table_name, column_name, data_type, is_nullable, column_default
from information_schema.columns
where table_schema = 'tienda'
  and table_name in ('inventario', 'revision', 'inversion', 'clasificacion', 'canal_venta', 'historial', 'cat_estado')
order by table_name, ordinal_position;

-- 2) Triggers actuales (la migración elimina los de inventario, revision, inversion, clasificacion y canal_venta)
select c.relname as tabla, t.tgname as trigger, pg_get_triggerdef(t.oid) as definicion
from pg_trigger t
join pg_class c on c.oid = t.tgrelid
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'tienda' and not t.tgisinternal
order by c.relname, t.tgname;

-- 3) Restricciones CHECK de inventario (la migración elimina las que nombran precio_minimo, vendido_en o fecha_venta)
select conname, pg_get_constraintdef(oid) as definicion
from pg_constraint
where conrelid = 'tienda.inventario'::regclass and contype = 'c'
order by conname;

-- 4) Conteos de referencia para comparar después de migrar
select 'inventario' as tabla, count(*) from tienda.inventario
union all select 'revision',      count(*) from tienda.revision
union all select 'inversion',     count(*) from tienda.inversion
union all select 'clasificacion', count(*) from tienda.clasificacion
union all select 'canal_venta',   count(*) from tienda.canal_venta
union all select 'vendidos',      count(*) from tienda.inventario where estado = 'Vendido';
