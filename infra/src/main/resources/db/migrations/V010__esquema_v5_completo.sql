-- =====================================================================================
-- V010: Fase 2 & 4 & 5 — Esquema V5 Completo (Reservas, Webhooks Entrantes, Outbox, Publicaciones, Audites, Check Constraints)
-- =====================================================================================

BEGIN;

-- ---------- 1. Tabla de Reservas Persistentes (Fase 4 & 6, Hallazgo C3, A4, M8) ------
CREATE TABLE IF NOT EXISTS tienda.reservas (
    reservation_id  TEXT        PRIMARY KEY, -- UUID en formato string
    conversation_id TEXT        NOT NULL,
    product_key     TEXT        NOT NULL,
    quantity        INTEGER     NOT NULL DEFAULT 1 CHECK (quantity > 0),
    status          TEXT        NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, CONFIRMED, CONSUMED, EXPIRED, CANCELLED
    expires_at      TIMESTAMPTZ NOT NULL,
    order_ref       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reservas_conversation ON tienda.reservas (conversation_id);
CREATE INDEX IF NOT EXISTS idx_reservas_product ON tienda.reservas (product_key);
CREATE INDEX IF NOT EXISTS idx_reservas_status ON tienda.reservas (status, expires_at);

-- ---------- 2. Tabla de Eventos Entrantes para Ingesta Durable (Fase 7, Hallazgo A1) --
CREATE TABLE IF NOT EXISTS tienda.eventos_entrantes (
    id                 TEXT        PRIMARY KEY, -- UUID o hash único
    canal              TEXT        NOT NULL,
    evento_externo_id  TEXT        NOT NULL,
    payload            JSONB       NOT NULL,
    estado             TEXT        NOT NULL DEFAULT 'RECIBIDO', -- RECIBIDO, PROCESSING, DONE, FAILED, DEAD
    intentos           INTEGER     NOT NULL DEFAULT 0,
    proxima_ejecucion  TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_msg          TEXT,
    creado_en          TIMESTAMPTZ NOT NULL DEFAULT now(),
    procesado_en       TIMESTAMPTZ,
    CONSTRAINT uq_evento_canal_externo UNIQUE (canal, evento_externo_id)
);

CREATE INDEX IF NOT EXISTS idx_eventos_entrantes_estado ON tienda.eventos_entrantes (estado, proxima_ejecucion);

-- ---------- 3. Tabla de Outbox Generalizado (Fase 8, Hallazgo C4, A10) ----------------
CREATE TABLE IF NOT EXISTS tienda.outbox (
    id                 TEXT        PRIMARY KEY, -- UUID
    tipo               TEXT        NOT NULL DEFAULT 'stock_changed', -- stock_changed, outbound_message
    sku                TEXT        NOT NULL,
    canal              TEXT        NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 1,
    stock_objetivo     INTEGER     NOT NULL DEFAULT 0,
    payload            JSONB,
    estado             TEXT        NOT NULL DEFAULT 'PENDING', -- PENDING, SENDING, SENT, FAILED, DEAD
    intentos           INTEGER     NOT NULL DEFAULT 0,
    proxima_ejecucion  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_error       TEXT,
    creado_en          TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_estado ON tienda.outbox (estado, proxima_ejecucion);
CREATE INDEX IF NOT EXISTS idx_outbox_sku_canal ON tienda.outbox (sku, canal, version);

-- ---------- 4. Tabla de Publicaciones Persistidas por Canal (Fase 8, Hallazgo C4) ----
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

-- ---------- 5. Tabla de Importaciones Auditables (Fase 10, Hallazgo C6, C7) ----------
CREATE TABLE IF NOT EXISTS tienda.importaciones (
    id            TEXT        PRIMARY KEY,
    usuario       TEXT        NOT NULL,
    fecha         TIMESTAMPTZ NOT NULL DEFAULT now(),
    hash_archivo  TEXT        NOT NULL,
    estado        TEXT        NOT NULL DEFAULT 'PENDING', -- PENDING, SUCCESS, FAILED
    conteos       JSONB,
    errores       JSONB
);

-- ---------- 6. Columnas adicionales en Conversaciones y Venta Items ------------------
ALTER TABLE tienda.conversaciones ADD COLUMN IF NOT EXISTS current_reservation_id TEXT;
ALTER TABLE tienda.conversaciones ADD COLUMN IF NOT EXISTS mensajes JSONB;

ALTER TABLE tienda.venta_items ADD COLUMN IF NOT EXISTS order_id TEXT;
ALTER TABLE tienda.venta_items ADD COLUMN IF NOT EXISTS line_id TEXT;

-- ---------- 7. Índice Único Normalizado de Claves de Inventario (Fase 10, Hallazgo A6) -
CREATE UNIQUE INDEX IF NOT EXISTS idx_inventario_key_upper ON tienda.inventario (UPPER(key));

-- ---------- 8. Restricciones CHECK en Inventario (Fase 4, Hallazgo M1) ----------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cantidad_total') THEN
    ALTER TABLE tienda.inventario ADD CONSTRAINT chk_cantidad_total CHECK (cantidad_total >= 0);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_cantidad_reservada') THEN
    ALTER TABLE tienda.inventario ADD CONSTRAINT chk_cantidad_reservada CHECK (cantidad_reservada >= 0);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reservada_le_total') THEN
    ALTER TABLE tienda.inventario ADD CONSTRAINT chk_reservada_le_total CHECK (cantidad_reservada <= cantidad_total);
  END IF;
END $$;

COMMIT;
