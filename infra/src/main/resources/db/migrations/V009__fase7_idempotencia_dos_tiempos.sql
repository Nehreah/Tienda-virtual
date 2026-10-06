-- =====================================================================================
-- V009: Fase 7 — Idempotencia en dos tiempos (PROCESSING -> DONE / FAILED)
-- =====================================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS tienda.eventos_procesados (
    canal             TEXT        NOT NULL,
    evento_externo_id TEXT        NOT NULL,
    procesado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (canal, evento_externo_id)
);

ALTER TABLE tienda.eventos_procesados ADD COLUMN IF NOT EXISTS estado TEXT NOT NULL DEFAULT 'DONE';
ALTER TABLE tienda.eventos_procesados ADD COLUMN IF NOT EXISTS actualizado_en TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE tienda.eventos_procesados ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='eventos_procesados' AND schemaname='tienda' AND policyname='eventos_proc_app_user') THEN
    CREATE POLICY eventos_proc_app_user ON tienda.eventos_procesados FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

COMMIT;
