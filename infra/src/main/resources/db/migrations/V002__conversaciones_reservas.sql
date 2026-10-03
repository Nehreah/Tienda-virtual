-- Fase 5.2: Tablas para persistir conversaciones, reservas y ventas en BD.
-- Permite recuperar el estado ante reinicios y auditar operaciones.

-- Tabla de conversaciones
CREATE TABLE IF NOT EXISTS conversaciones (
    conversation_id   TEXT PRIMARY KEY,
    customer_id       TEXT NOT NULL,
    channel           TEXT NOT NULL,
    state             TEXT NOT NULL DEFAULT 'IDLE',
    active_product_key TEXT,
    agreed_price_cop  BIGINT,
    counter_offer_count INT NOT NULL DEFAULT 0,
    pending_counter_cop BIGINT,
    pending_counter_at  TIMESTAMPTZ,
    last_updated      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Índice para búsqueda por canal + cliente
CREATE INDEX IF NOT EXISTS idx_conv_channel_customer ON conversaciones (channel, customer_id);
CREATE INDEX IF NOT EXISTS idx_conv_last_updated ON conversaciones (last_updated);

-- Tabla de reservas
CREATE TABLE IF NOT EXISTS reservas (
    reservation_id   TEXT PRIMARY KEY,
    conversation_id  TEXT NOT NULL REFERENCES conversaciones(conversation_id),
    product_key      TEXT NOT NULL,
    quantity         INT NOT NULL DEFAULT 1,
    status           TEXT NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | EXPIRED | CONSUMED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT reservas_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX IF NOT EXISTS idx_reservas_conversation ON reservas (conversation_id);
CREATE INDEX IF NOT EXISTS idx_reservas_product ON reservas (product_key, status);
CREATE INDEX IF NOT EXISTS idx_reservas_expires ON reservas (expires_at) WHERE status = 'ACTIVE';

-- Tabla de ventas confirmadas (inmutable — solo INSERT)
CREATE TABLE IF NOT EXISTS ventas (
    venta_id         TEXT PRIMARY KEY,
    conversation_id  TEXT NOT NULL,
    product_key      TEXT NOT NULL REFERENCES inventario(key),
    channel          TEXT NOT NULL,
    sold_price_cop   BIGINT NOT NULL,
    quantity         INT NOT NULL DEFAULT 1,
    confirmed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes            TEXT
);

CREATE INDEX IF NOT EXISTS idx_ventas_product ON ventas (product_key);
CREATE INDEX IF NOT EXISTS idx_ventas_confirmed ON ventas (confirmed_at DESC);

-- Habilitar RLS en las nuevas tablas
ALTER TABLE conversaciones ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservas ENABLE ROW LEVEL SECURITY;
ALTER TABLE ventas ENABLE ROW LEVEL SECURITY;

CREATE POLICY conv_app_user ON conversaciones FOR ALL TO app_user USING (true) WITH CHECK (true);
CREATE POLICY reservas_app_user ON reservas FOR ALL TO app_user USING (true) WITH CHECK (true);
CREATE POLICY ventas_app_user ON ventas FOR ALL TO app_user USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON conversaciones TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON reservas TO app_user;
GRANT SELECT, INSERT ON ventas TO app_user;
