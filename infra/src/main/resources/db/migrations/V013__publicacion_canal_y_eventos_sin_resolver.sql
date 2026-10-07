-- =====================================================================================
-- V013: Fase C0 — Base de datos coherente para publicaciones y eventos sin resolver
-- =====================================================================================
-- 1. Copia publicaciones de 'publicaciones' (V010) a 'publicacion_canal' (V007).
-- 2. Modifica la FK de publicacion_canal a ON DELETE RESTRICT (N11).
-- 3. Añade columnas de archivado en tienda.inventario (C0).
-- 4. Crea la tabla tienda.eventos_sin_resolver para webhooks (N8).
-- =====================================================================================

BEGIN;

-- 1. Migrar datos de publicaciones (si existieran en la tabla V010) a publicacion_canal (V007)
INSERT INTO tienda.publicacion_canal (key, canal, externo_id, estado_publicacion, stock_sincronizado, ultimo_sync)
SELECT 
    p.sku AS key, 
    p.canal, 
    p.external_id AS externo_id,
    CASE WHEN p.publicado THEN 'PUBLICADO' ELSE 'PAUSADO' END AS estado_publicacion,
    p.stock_publicado AS stock_sincronizado,
    p.ultima_sincronizacion AS ultimo_sync
FROM tienda.publicaciones p
ON CONFLICT (key, canal) DO UPDATE SET
    externo_id = EXCLUDED.externo_id,
    estado_publicacion = EXCLUDED.estado_publicacion,
    stock_sincronizado = EXCLUDED.stock_sincronizado,
    ultimo_sync = EXCLUDED.ultimo_sync;

-- 2. Cambiar FK a ON DELETE RESTRICT en tienda.publicacion_canal (Hallazgo N11)
ALTER TABLE tienda.publicacion_canal 
    DROP CONSTRAINT IF EXISTS publicacion_canal_key_fkey;

ALTER TABLE tienda.publicacion_canal 
    ADD CONSTRAINT publicacion_canal_key_fkey 
    FOREIGN KEY (key) REFERENCES tienda.inventario(key) 
    ON DELETE RESTRICT ON UPDATE CASCADE;

-- 3. Columnas de archivado en inventario
ALTER TABLE tienda.inventario 
    ADD COLUMN IF NOT EXISTS archivado_en TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archivado_por TEXT,
    ADD COLUMN IF NOT EXISTS motivo_archivo TEXT;

-- 4. Tabla de eventos sin resolver para webhooks (Hallazgo N8)
CREATE TABLE IF NOT EXISTS tienda.eventos_sin_resolver (
    id            BIGSERIAL   PRIMARY KEY,
    canal         TEXT        NOT NULL,
    tipo          TEXT        NOT NULL,
    referencia    TEXT,
    sku           TEXT,
    motivo        TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    estado        TEXT        NOT NULL DEFAULT 'PENDIENTE',
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resuelto_en   TIMESTAMPTZ,
    CONSTRAINT chk_esr_estado CHECK (estado IN ('PENDIENTE', 'RESUELTO', 'DESCARTADO'))
);

CREATE INDEX IF NOT EXISTS idx_esr_estado ON tienda.eventos_sin_resolver (estado, creado_en);

COMMIT;
