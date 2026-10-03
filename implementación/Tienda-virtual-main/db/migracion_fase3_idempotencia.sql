-- Fase 3: idempotencia persistente de webhooks.
-- La usa ExposedProcessedEventStore (insert; si la clave ya existe -> SQLState 23505 -> evento repetido).
-- Si esta tabla no existe, la app NO falla: degrada a memoria del proceso y lo avisa en consola.
-- Ejecutar una vez en Supabase (SQL Editor).
begin;

create table if not exists tienda.eventos_procesados (
  canal             text        not null,
  evento_externo_id text        not null,
  procesado_en      timestamptz not null default now(),
  primary key (canal, evento_externo_id)
);

alter table tienda.eventos_procesados enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    revoke all on tienda.eventos_procesados from anon;
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    revoke all on tienda.eventos_procesados from authenticated;
  end if;
end $$;

commit;
