-- =====================================================================================
-- V010: Fase 2 & 4 & 5 — Esquema V5 Completo (Reservas, Webhooks Entrantes, Outbox, Publicaciones, Audites, Check Constraints)
-- =====================================================================================

BEGIN;

-- ---------- 1. Tabla de Reservas Persistentes ----------------------------------------
CREATE TABLE IF NOT EXISTS tienda.reservas (
    reservation_id  TEXT        PRIMARY KEY,
    conversation_id TEXT        NOT NULL,
    product_key     TEXT        NOT NULL,
    quantity        INTEGER     NOT NULL DEFAULT 1 CHECK (quantity > 0),
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    expires_at      TIMESTAMPTZ NOT NULL,
    order_ref       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reservas_conversation ON tienda.reservas (conversation_id);
CREATE INDEX IF NOT EXISTS idx_reservas_product ON tienda.reservas (product_key);
CREATE INDEX IF NOT EXISTS idx_reservas_status ON tienda.reservas (status, expires_at);

-- ---------- 2. Tabla de Eventos Entrantes para Ingesta Durable -----------------------
CREATE TABLE IF NOT EXISTS tienda.eventos_entrantes (
    id                 TEXT        PRIMARY KEY,
    canal              TEXT        NOT NULL,
    evento_externo_id  TEXT        NOT NULL,
    payload            JSONB       NOT NULL,
    estado             TEXT        NOT NULL DEFAULT 'RECIBIDO',
    intentos           INTEGER     NOT NULL DEFAULT 0,
    proxima_ejecucion  TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_msg          TEXT,
    creado_en          TIMESTAMPTZ NOT NULL DEFAULT now(),
    procesado_en       TIMESTAMPTZ,
    CONSTRAINT uq_evento_canal_externo UNIQUE (canal, evento_externo_id)
);

CREATE INDEX IF NOT EXISTS idx_eventos_entrantes_estado ON tienda.eventos_entrantes (estado, proxima_ejecucion);

-- ---------- 3. Tabla de Outbox Generalizado -------------------------------------------
CREATE TABLE IF NOT EXISTS tienda.outbox (
    id                 TEXT        PRIMARY KEY,
    tipo               TEXT        NOT NULL DEFAULT 'stock_changed',
    sku                TEXT        NOT NULL,
    canal              TEXT        NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 1,
    stock_objetivo     INTEGER     NOT NULL DEFAULT 0,
    payload            JSONB,
    estado             TEXT        NOT NULL DEFAULT 'PENDING',
    intentos           INTEGER     NOT NULL DEFAULT 0,
    proxima_ejecucion  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_error       TEXT,
    creado_en          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_estado ON tienda.outbox (estado, proxima_ejecucion);
CREATE INDEX IF NOT EXISTS idx_outbox_sku_canal ON tienda.outbox (sku, canal, version);

-- ---------- 4. Tabla de Publicaciones Persistidas por Canal --------------------------
CREATE TABLE IF NOT EXISTS tienda.publicaciones (
    sku                   TEXT        NOT NULL,
    canal                 TEXT        NOT NULL,
    external_id           TEXT        NOT NULL,
    publicado             BOOLEAN     NOT NULL DEFAULT true,
    stock_publicado       INTEGER     NOT NULL DEFAULT 0,
    ultima_sincronizacion TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (sku, canal)
);

CREATE INDEX IF NOT EXISTS idx_publicaciones_canal ON tienda.publicaciones (canal, external_id);

-- ---------- 5. Tabla de Importaciones Auditables -------------------------------------
CREATE TABLE IF NOT EXISTS tienda.importaciones (
    id            TEXT        PRIMARY KEY,
    usuario       TEXT        NOT NULL,
    fecha         TIMESTAMPTZ NOT NULL DEFAULT now(),
    hash_archivo  TEXT        NOT NULL,
    estado        TEXT        NOT NULL DEFAULT 'PENDING',
    conteos       JSONB,
    errores       JSONB
);

-- ---------- 6. Columnas adicionales en Conversaciones --------------------------------
ALTER TABLE tienda.conversaciones ADD COLUMN IF NOT EXISTS current_reservation_id TEXT;
ALTER TABLE tienda.conversaciones ADD COLUMN IF NOT EXISTS mensajes JSONB;

-- ---------- 7. Índice Único Normalizado de Claves de Inventario -----------------------
CREATE UNIQUE INDEX IF NOT EXISTS idx_inventario_key_upper ON tienda.inventario (UPPER(key));

-- ---------- 8. Restricciones CHECK Condicionales e Idempotentes en Inventario ----------
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='tienda' AND table_name='inventario' AND column_name='cantidad_total') THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cantidad_total') THEN
      ALTER TABLE tienda.inventario ADD CONSTRAINT chk_cantidad_total CHECK (cantidad_total >= 0);
    END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='tienda' AND table_name='inventario' AND column_name='cantidad_reservada') THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cantidad_reservada') THEN
      ALTER TABLE tienda.inventario ADD CONSTRAINT chk_cantidad_reservada CHECK (cantidad_reservada >= 0);
    END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='tienda' AND table_name='inventario' AND column_name='cantidad_total')
     AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='tienda' AND table_name='inventario' AND column_name='cantidad_reservada') THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reservada_le_total') THEN
      ALTER TABLE tienda.inventario ADD CONSTRAINT chk_reservada_le_total CHECK (cantidad_reservada <= cantidad_total);
    END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='tienda' AND table_name='inventario' AND column_name='cantidad') THEN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cantidad_non_negative') THEN
      ALTER TABLE tienda.inventario ADD CONSTRAINT chk_cantidad_non_negative CHECK (cantidad >= 0);
    END IF;
  END IF;
END $$;

COMMIT;
