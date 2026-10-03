-- =====================================================================================
-- PRUEBAS DE LA BASE — Fase 2 (los 11 casos del plan + los de las decisiones D2/D7)
-- Correr DESPUÉS de db/migracion_fase2.sql, de preferencia en una COPIA de la base.
-- Todo corre en una transacción que termina en ROLLBACK: no deja ningún dato de prueba.
-- Si una prueba falla, el script se detiene con el mensaje "FALLÓ: ...".
-- Si llegas al final, verás: "TODAS LAS PRUEBAS PASARON".
--
-- NOTA: este script NO fue ejecutado (no tengo PostgreSQL en mi entorno). Si tu tabla inventario tiene
-- columnas obligatorias que aquí no lleno, el INSERT te lo dirá: agrégalas al INSERT de prueba.
-- =====================================================================================

begin;
set local app.origen = 'pruebas';
set local app.usuario = 'pruebas_fase2';

create or replace function pg_temp.ok(p_cond boolean, p_nombre text)
returns void language plpgsql as $$
begin
  if p_cond is not true then
    raise exception 'FALLÓ: %', p_nombre;
  end if;
  raise notice 'OK    %', p_nombre;
end $$;

-- Ejecuta p_sql y exige que dé error (el error se descarta con su propio savepoint).
create or replace function pg_temp.debe_fallar(p_sql text, p_nombre text)
returns void language plpgsql as $$
begin
  begin
    execute p_sql;
  exception when others then
    raise notice 'OK    % (error esperado: %)', p_nombre, sqlerrm;
    return;
  end;
  raise exception 'FALLÓ: % — debía dar error y no lo dio', p_nombre;
end $$;

-- ---------------------------------------------------------------------------------
-- CASO 1 (D1): compra 20.000 sin reparación -> costo_total 20.000, precio_minimo 30.000
-- ---------------------------------------------------------------------------------
insert into tienda.inventario (key, item, estado, fotos, precio_compra, fecha_compra, pct_antonio, pct_javier, tipo)
values ('TST-1', 'Prueba 1', 'Comprado', '', 20000, '2026-09-01', 50, 50, 'tst');

select pg_temp.ok(
  (select costo_total = 20000 and precio_minimo = 30000 from tienda.inventario where key = 'TST-1'),
  '1. compra 20.000 sin reparación: costo total 20.000 y mínimo 30.000');

select pg_temp.ok(
  exists (select 1 from tienda.cat_tipo where tipo = 'tst'),
  '1b. un tipo nuevo se registra solo en cat_tipo');

-- ---------------------------------------------------------------------------------
-- CASO 2 (D1): requiere_reparacion = true crea la fila; reparación 5.000 -> costo 25.000, mínimo 37.500
-- ---------------------------------------------------------------------------------
update tienda.inventario set requiere_reparacion = true where key = 'TST-1';

select pg_temp.ok(
  exists (select 1 from tienda.reparaciones where key = 'TST-1' and precio_reparacion = 0),
  '2a. al marcar "requiere reparación" se crea la fila de reparaciones con precio 0');

update tienda.reparaciones set precio_reparacion = 5000, detalles = 'pantalla' where key = 'TST-1';

select pg_temp.ok(
  (select costo_total = 25000 and precio_minimo = 37500 from tienda.inventario where key = 'TST-1'),
  '2b. reparación 5.000: costo total 25.000 y mínimo 37.500 (sobre el costo TOTAL, no solo la compra)');

-- ---------------------------------------------------------------------------------
-- CASO 3: no se puede quitar "requiere reparación" si la reparación tiene precio
-- ---------------------------------------------------------------------------------
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set requiere_reparacion = false where key = 'TST-1' $q$,
  '3. quitar "requiere reparación" con reparación de 5.000');

-- ---------------------------------------------------------------------------------
-- CASO 4 (D3): publicar por debajo del mínimo está bloqueado
-- ---------------------------------------------------------------------------------
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set estado = 'Publicado', precio_publicado = 35000, fecha_publicacion = '2026-09-05' where key = 'TST-1' $q$,
  '4a. publicar con precio 35.000 < mínimo 37.500');

select pg_temp.debe_fallar(
  $q$ update tienda.inventario set estado = 'Publicado' where key = 'TST-1' $q$,
  '4b. publicar sin precio de venta');

update tienda.inventario set estado = 'Publicado', precio_publicado = 40000, fecha_publicacion = '2026-09-05' where key = 'TST-1';
select pg_temp.ok(
  (select estado = 'Publicado' from tienda.inventario where key = 'TST-1'),
  '4c. publicar con precio 40.000 >= mínimo funciona');

-- ---------------------------------------------------------------------------------
-- CASO 5 (D2): cambiar el estado a Vendido sin los datos de la venta da un error claro
-- ---------------------------------------------------------------------------------
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set estado = 'Vendido' where key = 'TST-1' $q$,
  '5a. estado = Vendido sin fecha_venta ni vendido_en');

select pg_temp.debe_fallar(
  $q$ update tienda.inventario set estado = 'Vendido', fecha_venta = '2026-09-20' where key = 'TST-1' $q$,
  '5b. estado = Vendido sin vendido_en');

-- ---------------------------------------------------------------------------------
-- CASO 6 (D2 manual): venta de 40.000, costo 20.000, Antonio 50 %
--   -> ganancia 20.000, margen 50 %, Antonio: ingreso 20.000 y ganancia 10.000
--   El usuario llena los datos primero y cambia el estado después (como en el editor de tablas).
-- ---------------------------------------------------------------------------------
insert into tienda.inventario (key, item, estado, fotos, precio_compra, fecha_compra, fecha_publicacion,
                               precio_publicado, pct_antonio, pct_javier, tipo)
values ('TST-2', 'Prueba 2', 'Publicado', '', 20000, '2026-09-01', '2026-09-03', 40000, 50, 50, 'tst');

update tienda.inventario set fecha_venta = '2026-09-13', vendido_en = 40000, canal_venta = ' Shopify ' where key = 'TST-2';
select pg_temp.ok(
  (select estado = 'Publicado' and canal_venta = 'shopify' from tienda.inventario where key = 'TST-2')
  and not exists (select 1 from tienda.ventas where key = 'TST-2'),
  '6a. con los datos llenos pero sin cambiar el estado todavía NO hay venta');

update tienda.inventario set estado = 'Vendido' where key = 'TST-2';

select pg_temp.ok(
  (select ganancia_neta = 20000 and margen_pct = 50.00 and ingresos = 40000 and costo_total = 20000
          and canal = 'shopify' from tienda.ventas where key = 'TST-2'),
  '6b. venta 40.000 con costo 20.000: ganancia 20.000, margen 50 %');

select pg_temp.ok(
  (select ingresos_antonio = 20000 and ganancias_antonio = 10000
          and ingresos_javier = 20000 and ganancias_javier = 10000
   from tienda.metricas where key = 'TST-2'),
  '6c. métricas por socio: Antonio ingreso 20.000 y ganancia 10.000');

-- D4 / D5 en esta misma venta: compra 1-sep, publicación 3-sep, venta 13-sep
select pg_temp.ok(
  (select tiempo_dias = 12 and dias_publicado = 10 and periodo = 'corto' from tienda.ventas where key = 'TST-2'),
  '6d. D4: 12 días desde la compra y 10 desde la publicación; D5: periodo corto');

-- ventas y metricas no se editan directo
select pg_temp.debe_fallar($q$ delete from tienda.ventas where key = 'TST-2' $q$, '6e. borrar una venta a mano');
select pg_temp.debe_fallar($q$ update tienda.ventas set vendido_en = 1 where key = 'TST-2' $q$, '6f. editar una venta a mano');
select pg_temp.debe_fallar($q$ update tienda.metricas set ingresos_antonio = 1 where key = 'TST-2' $q$, '6g. editar una métrica a mano');

-- Corregir el precio de una venta ya hecha: se edita vendido_en en inventario y todo se recalcula
update tienda.inventario set vendido_en = 41000 where key = 'TST-2';
select pg_temp.ok(
  (select v.ganancia_neta = 21000 and m.ingresos_antonio = 20500 and m.ganancias_antonio = 10500
   from tienda.ventas v join tienda.metricas m using (key) where key = 'TST-2'),
  '6h. corregir vendido_en de una venta ya hecha recalcula ganancia y métricas');
update tienda.inventario set vendido_en = 40000 where key = 'TST-2';

-- ---------------------------------------------------------------------------------
-- CASO 7: venta con pérdida (costo 20.000, vendido en 15.000) -> ganancia -5.000
-- ---------------------------------------------------------------------------------
insert into tienda.inventario (key, item, estado, fotos, precio_compra, fecha_compra, pct_antonio, pct_javier, tipo)
values ('TST-3', 'Prueba 3', 'Comprado', '', 20000, '2026-09-01', 50, 50, 'tst-perdida');

select tienda.registrar_venta('TST-3', 15000, date '2026-09-10', 'Meta_Catalogo');

select pg_temp.ok(
  (select ganancia_neta = -5000 and canal = 'meta_catalogo' from tienda.ventas where key = 'TST-3'),
  '7a. venta con costo 20.000 y precio 15.000: ganancia -5.000');

select pg_temp.ok(
  (select ganancias_antonio = -2500 and ganancias_javier = -2500 from tienda.metricas where key = 'TST-3'),
  '7b. la pérdida también se reparte entre socios');

select pg_temp.ok(
  (select ventas_con_perdida = 1 and monto_perdidas = -5000 from tienda.v_rendimiento_tipo where tipo = 'tst-perdida'),
  '7c. aparece en v_rendimiento_tipo.ventas_con_perdida');

-- ---------------------------------------------------------------------------------
-- CASO 8: reparto 33,33 / 66,67 con valor impar -> la suma de los socios es exactamente el total
-- ---------------------------------------------------------------------------------
insert into tienda.inventario (key, item, estado, fotos, precio_compra, pct_antonio, pct_javier, tipo)
values ('TST-4', 'Prueba 4', 'Comprado', '', 0, 33.33, 66.67, 'tst-reparto');   -- sin fecha_compra a propósito

select tienda.registrar_venta('TST-4', 12345, date '2026-09-10', 'whatsapp');

select pg_temp.ok(
  (select ingresos_antonio = 4114 and ingresos_antonio + ingresos_javier = 12345
          and ganancias_antonio + ganancias_javier = 12345
   from tienda.metricas where key = 'TST-4'),
  '8a. reparto 33,33/66,67 de 12.345: la suma de los socios cuadra exacto');

select pg_temp.ok(
  (select tiempo_dias is null and periodo is null from tienda.ventas where key = 'TST-4'),
  '8b. sin fecha de compra, tiempo y periodo quedan vacíos (no se inventan)');

-- ---------------------------------------------------------------------------------
-- CASO 9 (D5): periodo -> 14 corto · 15 medio · 29 medio · 30 largo (días desde la COMPRA)
-- ---------------------------------------------------------------------------------
insert into tienda.inventario (key, item, estado, fotos, precio_compra, fecha_compra, pct_antonio, pct_javier, tipo)
select 'TST-P' || d, 'Periodo ' || d, 'Comprado', '', 100, date '2026-08-01', 50, 50, 'tst-periodo'
from unnest(array[14, 15, 29, 30]) as d;

select tienda.registrar_venta('TST-P' || d, 1000, date '2026-08-01' + d, 'test')
from unnest(array[14, 15, 29, 30]) as d;

select pg_temp.ok(
  (select string_agg(key || '=' || periodo, ' ' order by key) = 'TST-P14=corto TST-P15=medio TST-P29=medio TST-P30=largo'
   from tienda.ventas where key like 'TST-P%'),
  '9. 14 días corto, 15 medio, 29 medio, 30 largo');

-- ---------------------------------------------------------------------------------
-- CASO 10 (D8): vendido bloquea compra / reparación / porcentajes.
--               Para corregir: anular (estado = Publicado), editar y volver a vender.
-- ---------------------------------------------------------------------------------
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set precio_compra = 21000 where key = 'TST-2' $q$,
  '10a. editar precio_compra de un artículo vendido');
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set pct_antonio = 60, pct_javier = 40 where key = 'TST-2' $q$,
  '10b. editar los porcentajes de un artículo vendido');

-- Artículo con reparación, ya vendido: tampoco se toca la reparación
insert into tienda.inventario (key, item, estado, fotos, precio_compra, fecha_compra, pct_antonio, pct_javier, tipo, requiere_reparacion)
values ('TST-R', 'Con reparación', 'Comprado', '', 10000, '2026-09-01', 50, 50, 'tst-rep', true);
update tienda.reparaciones set precio_reparacion = 2000 where key = 'TST-R';
select tienda.registrar_venta('TST-R', 30000, date '2026-09-20', 'shopify');
select pg_temp.ok(
  (select costo_total = 12000 and ganancia_neta = 18000 from tienda.ventas where key = 'TST-R'),
  '10c. el costo de la venta incluye la reparación (10.000 + 2.000)');
select pg_temp.debe_fallar(
  $q$ update tienda.reparaciones set precio_reparacion = 3000 where key = 'TST-R' $q$,
  '10d. editar la reparación de un artículo vendido');
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set requiere_reparacion = false where key = 'TST-R' $q$,
  '10e. quitar "requiere reparación" de un artículo vendido');

-- Anular, corregir y volver a vender TST-2
select tienda.anular_venta('TST-2');
select pg_temp.ok(
  (select estado = 'Publicado' and fecha_venta is null and vendido_en is null and canal_venta is null
   from tienda.inventario where key = 'TST-2')
  and not exists (select 1 from tienda.ventas where key = 'TST-2')
  and not exists (select 1 from tienda.metricas where key = 'TST-2'),
  '10f. anular la venta: vuelve a Publicado, se limpian los datos de venta y se borran venta y métricas');

update tienda.inventario set precio_compra = 21000 where key = 'TST-2';
select pg_temp.ok(
  (select costo_total = 21000 and precio_minimo = 31500 from tienda.inventario where key = 'TST-2'),
  '10g. ya sin la venta se puede editar la compra (costo 21.000, mínimo 31.500)');

select pg_temp.debe_fallar(
  $q$ update tienda.inventario set precio_compra = 30000 where key = 'TST-2' $q$,
  '10h. D3: subir el costo hasta que el mínimo (45.000) supere el precio de venta publicado (40.000)');

select tienda.registrar_venta('TST-2', 40000, date '2026-09-13', 'shopify');
select pg_temp.ok(
  (select costo_total = 21000 and ganancia_neta = 19000 from tienda.ventas where key = 'TST-2'),
  '10i. al volver a vender, la venta usa el costo corregido (ganancia 19.000)');

-- ---------------------------------------------------------------------------------
-- Reglas de venta (D7 y fechas)
-- ---------------------------------------------------------------------------------
insert into tienda.inventario (key, item, estado, fotos, precio_compra, fecha_compra, tipo)
values ('TST-5', 'Sin reparto', 'Comprado', '', 5000, '2026-09-10', 'tst');   -- porcentajes 0 / 0

select pg_temp.debe_fallar(
  $q$ select tienda.registrar_venta('TST-5', 9000, date '2026-09-20', 'shopify') $q$,
  'D7. no se puede vender sin reparto de socios (0 / 0)');

update tienda.inventario set pct_antonio = 70, pct_javier = 30 where key = 'TST-5';
select pg_temp.debe_fallar(
  $q$ select tienda.registrar_venta('TST-5', 9000, date '2026-09-01', 'shopify') $q$,
  'fechas. no se puede vender antes de la fecha de compra');
select pg_temp.debe_fallar(
  $q$ update tienda.inventario set pct_antonio = 70, pct_javier = 20 where key = 'TST-5' $q$,
  'D7b. porcentajes que no suman 100 (ni 0)');
select pg_temp.debe_fallar(
  $q$ select tienda.registrar_venta('TST-R', 1, date '2026-09-21', 'shopify') $q$,
  'doble venta. vender dos veces el mismo artículo (TST-R ya está vendido)');
select pg_temp.debe_fallar(
  $q$ select tienda.anular_venta('TST-5') $q$,
  'anular una venta que no existe (TST-5 no está vendido)');

-- ---------------------------------------------------------------------------------
-- CASO 11: cada operación deja su línea en historial con origen y usuario
-- ---------------------------------------------------------------------------------
select pg_temp.ok(
  exists (select 1 from tienda.historial
          where tabla = 'inventario' and key = 'TST-1' and operacion = 'INSERT'
            and origen = 'pruebas' and usuario = 'pruebas_fase2'),
  '11a. el alta del artículo quedó en historial con origen y usuario');

select pg_temp.ok(
  exists (select 1 from tienda.historial
          where tabla = 'inventario' and key = 'TST-2' and operacion = 'UPDATE'
            and cambios -> 'vendido_en' ->> 'antes' = '40000' and cambios -> 'vendido_en' ->> 'despues' = '41000'),
  '11b. la corrección de vendido_en quedó en historial con valor anterior y nuevo');

select pg_temp.ok(
  exists (select 1 from tienda.historial where tabla = 'ventas' and key = 'TST-2')
  and exists (select 1 from tienda.historial where tabla = 'metricas' and key = 'TST-2')
  and exists (select 1 from tienda.historial where tabla = 'reparaciones' and key = 'TST-1'),
  '11c. ventas, métricas y reparaciones también quedan en historial');

-- Una edición sin app.origen (como en el editor de tablas de Supabase) queda con origen "supabase"
set local app.origen = '';
update tienda.inventario set descripcion = 'editado a mano' where key = 'TST-1';
select pg_temp.ok(
  (select origen = 'supabase' from tienda.historial where tabla = 'inventario' and key = 'TST-1' order by id desc limit 1),
  '11d. una edición fuera de la app queda con origen = supabase');
set local app.origen = 'pruebas';

select 'TODAS LAS PRUEBAS PASARON' as resultado;

rollback;   -- no deja ningún dato de prueba
