-- V002__conversaciones_reservas.sql
-- Fase 5.2: Tablas para persistir conversaciones, reservas y ventas en BD.
-- Es seguro ejecutarlo en bases limpias e independientes.

CREATE SCHEMA IF NOT EXISTS tienda;

-- Tabla de conversaciones
CREATE TABLE IF NOT EXISTS tienda.conversaciones (
    conversation_id     TEXT PRIMARY KEY,
    customer_id         TEXT NOT NULL,
    channel             TEXT NOT NULL,
    state               TEXT NOT NULL DEFAULT 'IDLE',
    active_product_key  TEXT,
    agreed_price_cop    BIGINT,
    counter_offer_count INT NOT NULL DEFAULT 0,
    pending_counter_cop BIGINT,
    pending_counter_at  TIMESTAMPTZ,
    last_updated        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Índices para búsqueda por canal + cliente
CREATE INDEX IF NOT EXISTS idx_conv_channel_customer ON tienda.conversaciones (channel, customer_id);
CREATE INDEX IF NOT EXISTS idx_conv_last_updated ON tienda.conversaciones (last_updated);

-- Tabla de reservas
CREATE TABLE IF NOT EXISTS tienda.reservas (
    reservation_id   TEXT PRIMARY KEY,
    conversation_id  TEXT NOT NULL REFERENCES tienda.conversaciones(conversation_id),
    product_key      TEXT NOT NULL,
    quantity         INT NOT NULL DEFAULT 1,
    status           TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | EXPIRED | CONSUMED | CONFIRMED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT reservas_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_reservas_conversation ON tienda.reservas (conversation_id);
CREATE INDEX IF NOT EXISTS idx_reservas_product ON tienda.reservas (product_key, status);
CREATE INDEX IF NOT EXISTS idx_reservas_expires ON tienda.reservas (expires_at) WHERE status = 'ACTIVE';

-- Tabla de ventas confirmadas (inmutable — una fila por clave de producto vendida)
CREATE TABLE IF NOT EXISTS tienda.ventas (
    key              TEXT PRIMARY KEY REFERENCES tienda.inventario(key),
    conversation_id  TEXT,
    channel          TEXT NOT NULL DEFAULT 'desconocido',
    vendido_en       BIGINT NOT NULL DEFAULT 0,
    costo_total      BIGINT NOT NULL DEFAULT 0,
    ganancia_neta    BIGINT NOT NULL DEFAULT 0,
    fecha_venta      DATE NOT NULL DEFAULT CURRENT_DATE,
    precio_publicado BIGINT,
    notas            TEXT,
    confirmed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ventas_product ON tienda.ventas (key);
CREATE INDEX IF NOT EXISTS idx_ventas_confirmed ON tienda.ventas (confirmed_at DESC);

-- Habilitar RLS en las nuevas tablas
ALTER TABLE tienda.conversaciones ENABLE ROW LEVEL SECURITY;
ALTER TABLE tienda.reservas ENABLE ROW LEVEL SECURITY;
ALTER TABLE tienda.ventas ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='conversaciones' AND schemaname='tienda' AND policyname='conv_app_user') THEN
    CREATE POLICY conv_app_user ON tienda.conversaciones FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='reservas' AND schemaname='tienda' AND policyname='reservas_app_user') THEN
    CREATE POLICY reservas_app_user ON tienda.reservas FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='ventas' AND schemaname='tienda' AND policyname='ventas_app_user') THEN
    CREATE POLICY ventas_app_user ON tienda.ventas FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN
  RAISE NOTICE 'Rol app_user no existe aún, omitiendo políticas de RLS';
END $$;
