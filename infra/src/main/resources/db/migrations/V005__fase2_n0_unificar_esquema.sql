-- =====================================================================================
-- V005: Fase 2 — Ola N0: Unificar esquema y limpiar inconsistencias
-- =====================================================================================
-- Problema (Hallazgo H5): V001/V002/V003 pudieron haber creado tablas en public.
-- Este script las mueve al esquema tienda y asegura que la estructura exista.
-- Es seguro correrlo varias veces (IF NOT EXISTS / IF EXISTS en todas las operaciones).
-- =====================================================================================

BEGIN;

CREATE SCHEMA IF NOT EXISTS tienda;

-- ---------- 0. Garantizar tienda.inventario ----------------------------------------------
CREATE TABLE IF NOT EXISTS tienda.inventario (
  key                 TEXT PRIMARY KEY,
  item                TEXT NOT NULL CHECK (btrim(item) <> ''),
  link                TEXT,
  fotos               TEXT[] NOT NULL DEFAULT '{}',
  estado              TEXT NOT NULL DEFAULT 'Comprado',
  fecha_compra        DATE,
  fecha_publicacion   DATE,
  fecha_venta         DATE,
  precio_publicado    BIGINT,
  precio_minimo       BIGINT,
  precio_compra       BIGINT NOT NULL DEFAULT 0,
  vendido_en          BIGINT,
  costo_total         BIGINT NOT NULL DEFAULT 0,
  descripcion         TEXT,
  tipo                TEXT,
  marca               TEXT,
  condicion           TEXT,
  cantidad            INT NOT NULL DEFAULT 1,
  cantidad_total      INT NOT NULL DEFAULT 1,
  cantidad_reservada  INT NOT NULL DEFAULT 0,
  version             BIGINT NOT NULL DEFAULT 1,
  ubicacion           TEXT,
  requiere_reparacion BOOLEAN NOT NULL DEFAULT FALSE,
  canal_venta         TEXT,
  archivado_en        TIMESTAMPTZ,
  archivado_por       TEXT,
  motivo_archivo      TEXT
);

-- ---------- 1. Mover tablas de public → tienda si quedaron ahí --------------------------
DO $$
BEGIN
  -- conversaciones
  IF to_regclass('public.conversaciones') IS NOT NULL
     AND to_regclass('tienda.conversaciones') IS NULL THEN
    ALTER TABLE public.conversaciones SET SCHEMA tienda;
    RAISE NOTICE 'public.conversaciones → tienda.conversaciones';
  END IF;

  -- reservas
  IF to_regclass('public.reservas') IS NOT NULL
     AND to_regclass('tienda.reservas') IS NULL THEN
    ALTER TABLE public.reservas SET SCHEMA tienda;
    RAISE NOTICE 'public.reservas → tienda.reservas';
  END IF;

  -- ventas
  IF to_regclass('public.ventas') IS NOT NULL
     AND to_regclass('tienda.ventas') IS NOT NULL THEN
    DROP TABLE public.ventas;
    RAISE NOTICE 'public.ventas obsoleta eliminada';
  ELSIF to_regclass('public.ventas') IS NOT NULL
     AND to_regclass('tienda.ventas') IS NULL THEN
    ALTER TABLE public.ventas SET SCHEMA tienda;
    RAISE NOTICE 'public.ventas → tienda.ventas';
  END IF;

  -- outbox
  IF to_regclass('public.outbox') IS NOT NULL
     AND to_regclass('tienda.outbox') IS NULL THEN
    ALTER TABLE public.outbox SET SCHEMA tienda;
    RAISE NOTICE 'public.outbox → tienda.outbox';
  ELSIF to_regclass('public.outbox') IS NOT NULL
     AND to_regclass('tienda.outbox') IS NOT NULL THEN
    INSERT INTO tienda.outbox (event_id, channel, recipient_id, payload_json,
                               status, attempts, created_at, last_attempt_at, sent_at, error_message)
    SELECT event_id, channel, recipient_id, payload_json,
           status, attempts, created_at, last_attempt_at, sent_at, error_message
    FROM public.outbox
    ON CONFLICT DO NOTHING;
    DROP TABLE public.outbox;
    RAISE NOTICE 'public.outbox fusionada en tienda.outbox y eliminada';
  END IF;
END $$;

-- ---------- 2. Asegurar que tienda.conversaciones, reservas y outbox existan ---------------
CREATE TABLE IF NOT EXISTS tienda.conversaciones (
    conversation_id     TEXT PRIMARY KEY,
    customer_id         TEXT NOT NULL,
    channel             TEXT NOT NULL,
    state               TEXT NOT NULL DEFAULT 'IDLE',
    active_product_key  TEXT,
    agreed_price_cop    BIGINT,
    counter_offer_count INT  NOT NULL DEFAULT 0,
    pending_counter_cop BIGINT,
    pending_counter_at  TIMESTAMPTZ,
    last_updated        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conv_channel_customer ON tienda.conversaciones (channel, customer_id);
CREATE INDEX IF NOT EXISTS idx_conv_last_updated     ON tienda.conversaciones (last_updated);

CREATE TABLE IF NOT EXISTS tienda.reservas (
    reservation_id  TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES tienda.conversaciones(conversation_id),
    product_key     TEXT NOT NULL,
    quantity        INT  NOT NULL DEFAULT 1,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT reservas_quantity_positive CHECK (quantity > 0),
    CONSTRAINT reservas_status_check CHECK (status IN ('ACTIVE','EXPIRED','CONSUMED','CONFIRMED'))
);

CREATE INDEX IF NOT EXISTS idx_reservas_conversation ON tienda.reservas (conversation_id);
CREATE INDEX IF NOT EXISTS idx_reservas_product      ON tienda.reservas (product_key, status);
CREATE INDEX IF NOT EXISTS idx_reservas_expires      ON tienda.reservas (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS tienda.outbox (
    event_id        TEXT PRIMARY KEY,
    channel         TEXT NOT NULL,
    recipient_id    TEXT NOT NULL,
    payload_json    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    attempts        INT  NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    error_message   TEXT,
    CONSTRAINT outbox_status_check    CHECK (status   IN ('PENDING','SENT','FAILED')),
    CONSTRAINT outbox_attempts_check  CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON tienda.outbox (created_at ASC) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_outbox_channel ON tienda.outbox (channel, status);

-- ---------- 3. Agregar CONFIRMED al status de reservas si no existía ---------------------
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'reservas_status_check'
      AND conrelid = 'tienda.reservas'::regclass
  ) THEN
    ALTER TABLE tienda.reservas DROP CONSTRAINT IF EXISTS reservas_status_check;
    ALTER TABLE tienda.reservas ADD CONSTRAINT reservas_status_check
      CHECK (status IN ('ACTIVE','EXPIRED','CONSUMED','CONFIRMED'));
  END IF;
END $$;

-- ---------- 4. RLS en las tablas movidas --------------------------------------------------
ALTER TABLE tienda.conversaciones ENABLE ROW LEVEL SECURITY;
ALTER TABLE tienda.reservas       ENABLE ROW LEVEL SECURITY;
ALTER TABLE tienda.outbox         ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='conversaciones' AND schemaname='tienda' AND policyname='conv_app_user') THEN
    CREATE POLICY conv_app_user    ON tienda.conversaciones FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='reservas' AND schemaname='tienda' AND policyname='reservas_app_user') THEN
    CREATE POLICY reservas_app_user ON tienda.reservas      FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='outbox' AND schemaname='tienda' AND policyname='outbox_app_user') THEN
    CREATE POLICY outbox_app_user   ON tienda.outbox         FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN
  RAISE NOTICE 'Rol app_user no existe aún, omitiendo políticas de RLS';
END $$;

COMMENT ON SCHEMA tienda IS
  'Esquema único de la Tienda Virtual. Todas las tablas operativas viven aquí.';

COMMIT;
