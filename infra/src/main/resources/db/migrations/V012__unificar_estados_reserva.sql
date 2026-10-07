-- =====================================================================================
-- V012: Unificación de estados de reservas (Fase F1 - Hallazgo H4)
-- =====================================================================================
-- Unifica la enumeración de estados entre Kotlin (ReservationState) y SQL.
-- Estados soportados: ACTIVE, CONFIRMED, CANCELLED, EXPIRED, CONSUMED.
-- =====================================================================================

BEGIN;

-- Actualizar estados viejos o incoherentes si existieren
UPDATE tienda.reservas
SET status = 'CONFIRMED'
WHERE status = 'CONFIRMED_SALE';

-- Asegurar restricción CHECK para validar los 5 estados permitidos
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_reservas_status') THEN
    ALTER TABLE tienda.reservas DROP CONSTRAINT chk_reservas_status;
  END IF;
  ALTER TABLE tienda.reservas ADD CONSTRAINT chk_reservas_status 
    CHECK (status IN ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'CONSUMED'));
END $$;

COMMIT;
