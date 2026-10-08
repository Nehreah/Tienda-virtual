-- =====================================================================================
-- V014: Fase F1 — Clave idempotente para confirmar-pago y registro de tratos por deal_id
-- =====================================================================================

BEGIN;

ALTER TABLE tienda.ventas 
    ADD COLUMN IF NOT EXISTS deal_id TEXT UNIQUE;

CREATE INDEX IF NOT EXISTS idx_ventas_deal_id ON tienda.ventas (deal_id);

COMMIT;
