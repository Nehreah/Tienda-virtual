-- =====================================================================================
-- V006: Fase 2 — Ola N1: Catálogos + restricciones de integridad referencial
-- =====================================================================================
-- Crea: cat_condicion, cat_canal, cat_estado (completar), socio, inventario_reparto.
-- Agrega FKs: inventario.estado → cat_estado, condicion → cat_condicion, canal_venta → cat_canal.
-- ON UPDATE CASCADE en FKs existentes (reparaciones, ventas, metricas).
-- Índices en todas las columnas FK.
-- Historial genérico en las tablas nuevas.
--
-- ESTRATEGIA EXPANDIR/MIGRAR/CONTRAER:
--   1. Se crean las columnas y tablas nuevas (este script).
--   2. Se migran los datos.
--   3. Se agregan las FKs (al final de este script, después de migrar).
--   Las columnas viejas (pct_antonio, pct_javier) se mantienen hasta que el código
--   nuevo esté desplegado (se eliminan en V008).
-- =====================================================================================

BEGIN;

-- ---------- 1. Catálogo de condiciones ---------------------------------------------------
CREATE TABLE IF NOT EXISTS tienda.cat_condicion (
    condicion   TEXT PRIMARY KEY,
    descripcion TEXT
);

-- Semilla con los valores que suelen aparecer en los datos
INSERT INTO tienda.cat_condicion (condicion, descripcion) VALUES
    ('Nuevo',            'Sellado, sin uso'),
    ('Como nuevo',       'Usado con mínimas marcas, impecable'),
    ('Reacondicionado',  'Revisado y restaurado a condición funcional'),
    ('Usado',            'Usado en buen estado, con marcas normales de uso'),
    ('Para reparar',     'Requiere reparación antes de vender')
ON CONFLICT (condicion) DO NOTHING;

-- Cargar las condiciones que ya existen en los datos (auto-registrar igual que cat_tipo)
INSERT INTO tienda.cat_condicion (condicion)
SELECT DISTINCT trim(condicion)
FROM tienda.inventario
WHERE condicion IS NOT NULL AND trim(condicion) <> ''
ON CONFLICT (condicion) DO NOTHING;

ALTER TABLE tienda.cat_condicion ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='cat_condicion' AND schemaname='tienda' AND policyname='cat_condicion_app_user') THEN
    CREATE POLICY cat_condicion_app_user ON tienda.cat_condicion FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 2. Catálogo de canales -------------------------------------------------------
CREATE TABLE IF NOT EXISTS tienda.cat_canal (
    canal           TEXT PRIMARY KEY,
    es_mensajeria   BOOLEAN NOT NULL DEFAULT false,  -- whatsapp, instagram, messenger
    es_publicacion  BOOLEAN NOT NULL DEFAULT false,  -- shopify, meta_catalogo
    es_venta        BOOLEAN NOT NULL DEFAULT true,   -- si las ventas por este canal se registran
    descripcion     TEXT
);

INSERT INTO tienda.cat_canal (canal, es_mensajeria, es_publicacion, es_venta, descripcion) VALUES
    ('whatsapp',       true,  false, true,  'WhatsApp Cloud API'),
    ('instagram',      true,  false, true,  'Instagram DM'),
    ('messenger',      true,  false, true,  'Facebook Messenger'),
    ('shopify',        false, true,  true,  'Tienda Shopify'),
    ('meta_catalogo',  false, true,  false, 'Catálogo de Meta / Facebook'),
    ('presencial',     false, false, true,  'Venta presencial / en físico'),
    ('otro',           false, false, true,  'Canal no clasificado')
ON CONFLICT (canal) DO NOTHING;

-- Auto-registrar canales que ya estén en los datos
INSERT INTO tienda.cat_canal (canal)
SELECT DISTINCT lower(trim(canal_venta))
FROM tienda.inventario
WHERE canal_venta IS NOT NULL AND trim(canal_venta) <> ''
ON CONFLICT (canal) DO NOTHING;

ALTER TABLE tienda.cat_canal ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='cat_canal' AND schemaname='tienda' AND policyname='cat_canal_app_user') THEN
    CREATE POLICY cat_canal_app_user ON tienda.cat_canal FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 3. Completar cat_estado ------------------------------------------------------
-- migracion_fase2.sql ya lo creó con 5 estados. Agregar Inactivo (Fase 1.5).
INSERT INTO tienda.cat_estado (estado) VALUES
    ('Comprado'), ('Publicado'), ('Negociación'), ('Vendido'), ('Otro'), ('Inactivo')
ON CONFLICT (estado) DO NOTHING;

-- ---------- 4. Tabla de socios -----------------------------------------------------------
-- Reemplaza las columnas duras pct_antonio / pct_javier (D7).
-- Decisión de diseño: los socios se definen aquí; agregar uno nuevo no cambia el esquema.

CREATE TABLE IF NOT EXISTS tienda.socio (
    socio_id    SERIAL PRIMARY KEY,
    nombre      TEXT NOT NULL UNIQUE,
    activo      BOOLEAN NOT NULL DEFAULT true,
    creado_en   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Semilla: los dos socios actuales
INSERT INTO tienda.socio (nombre, activo) VALUES
    ('Antonio', true),
    ('Javier',  true)
ON CONFLICT (nombre) DO NOTHING;

ALTER TABLE tienda.socio ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='socio' AND schemaname='tienda' AND policyname='socio_app_user') THEN
    CREATE POLICY socio_app_user ON tienda.socio FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 5. Tabla de reparto de inventario por socio ----------------------------------
-- Reemplaza pct_antonio / pct_javier en inventario.
-- Regla: para cada key, la suma de pct debe ser 0 (sin definir) o 100 (exacto).
-- Se valida con un trigger diferido para permitir insertar los dos socios en la misma transacción.

CREATE TABLE IF NOT EXISTS tienda.inventario_reparto (
    key         TEXT    NOT NULL REFERENCES tienda.inventario(key) ON DELETE CASCADE ON UPDATE CASCADE,
    socio_id    INTEGER NOT NULL REFERENCES tienda.socio(socio_id),
    pct         NUMERIC(5,2) NOT NULL CHECK (pct >= 0 AND pct <= 100),
    PRIMARY KEY (key, socio_id)
);

CREATE INDEX IF NOT EXISTS idx_inv_reparto_key     ON tienda.inventario_reparto (key);
CREATE INDEX IF NOT EXISTS idx_inv_reparto_socio   ON tienda.inventario_reparto (socio_id);

-- Función de validación de suma de porcentajes (diferida = corre al final del statement)
CREATE OR REPLACE FUNCTION tienda.trg_inventario_reparto_suma()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_key TEXT := COALESCE(NEW.key, OLD.key);
    v_suma NUMERIC;
BEGIN
    SELECT COALESCE(SUM(pct), 0) INTO v_suma
    FROM tienda.inventario_reparto
    WHERE key = v_key;

    IF v_suma <> 0 AND v_suma <> 100 THEN
        RAISE EXCEPTION
            'El reparto de socios para % suma % %%. Debe ser exactamente 0 (sin definir) o 100.',
            v_key, v_suma, '%';
    END IF;
    RETURN NULL;
END $$;

-- Trigger diferido: permite insertar Antonio (50%) y Javier (50%) en la misma transacción
DROP TRIGGER IF EXISTS trg_inventario_reparto_suma ON tienda.inventario_reparto;
CREATE CONSTRAINT TRIGGER trg_inventario_reparto_suma
    AFTER INSERT OR UPDATE OR DELETE ON tienda.inventario_reparto
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION tienda.trg_inventario_reparto_suma();

-- Migrar datos de pct_antonio / pct_javier → inventario_reparto
DO $$
DECLARE
    v_id_antonio INTEGER;
    v_id_javier  INTEGER;
BEGIN
    SELECT socio_id INTO v_id_antonio FROM tienda.socio WHERE nombre = 'Antonio';
    SELECT socio_id INTO v_id_javier  FROM tienda.socio WHERE nombre = 'Javier';

    -- Solo migrar filas donde la suma sea exactamente 100 (las coherentes)
    INSERT INTO tienda.inventario_reparto (key, socio_id, pct)
    SELECT key, v_id_antonio, pct_antonio
    FROM tienda.inventario
    WHERE pct_antonio + pct_javier = 100 AND pct_antonio > 0
    ON CONFLICT (key, socio_id) DO UPDATE SET pct = EXCLUDED.pct;

    INSERT INTO tienda.inventario_reparto (key, socio_id, pct)
    SELECT key, v_id_javier, pct_javier
    FROM tienda.inventario
    WHERE pct_antonio + pct_javier = 100 AND pct_javier > 0
    ON CONFLICT (key, socio_id) DO UPDATE SET pct = EXCLUDED.pct;

    RAISE NOTICE 'Reparto migrado: % filas en inventario_reparto', (SELECT count(*) FROM tienda.inventario_reparto);
END $$;

ALTER TABLE tienda.inventario_reparto ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='inventario_reparto' AND schemaname='tienda' AND policyname='inv_reparto_app_user') THEN
    CREATE POLICY inv_reparto_app_user ON tienda.inventario_reparto FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 6. Agregar FKs a inventario (expandir: las columnas viejas se mantienen) ------

-- FK: inventario.estado → cat_estado
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='fk_inventario_estado' AND conrelid='tienda.inventario'::regclass
  ) THEN
    ALTER TABLE tienda.inventario
      ADD CONSTRAINT fk_inventario_estado FOREIGN KEY (estado)
        REFERENCES tienda.cat_estado(estado) ON UPDATE CASCADE;
    RAISE NOTICE 'FK fk_inventario_estado creada';
  END IF;
END $$;

-- FK: inventario.condicion → cat_condicion (auto-registra valores nuevos)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='fk_inventario_condicion' AND conrelid='tienda.inventario'::regclass
  ) THEN
    ALTER TABLE tienda.inventario
      ADD CONSTRAINT fk_inventario_condicion FOREIGN KEY (condicion)
        REFERENCES tienda.cat_condicion(condicion) ON UPDATE CASCADE;
    RAISE NOTICE 'FK fk_inventario_condicion creada';
  END IF;
END $$;

-- FK: inventario.canal_venta → cat_canal
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='fk_inventario_canal_venta' AND conrelid='tienda.inventario'::regclass
  ) THEN
    ALTER TABLE tienda.inventario
      ADD CONSTRAINT fk_inventario_canal_venta FOREIGN KEY (canal_venta)
        REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE;
    RAISE NOTICE 'FK fk_inventario_canal_venta creada';
  END IF;
END $$;

-- FK: ventas.canal → cat_canal
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='fk_ventas_canal' AND conrelid='tienda.ventas'::regclass
  ) THEN
    ALTER TABLE tienda.ventas
      ADD CONSTRAINT fk_ventas_canal FOREIGN KEY (canal)
        REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE;
    RAISE NOTICE 'FK fk_ventas_canal creada';
  END IF;
END $$;

-- ON UPDATE CASCADE en las FKs existentes de reparaciones → inventario
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname='reparaciones_key_fkey' AND conrelid='tienda.reparaciones'::regclass
  ) THEN
    ALTER TABLE tienda.reparaciones DROP CONSTRAINT reparaciones_key_fkey;
    ALTER TABLE tienda.reparaciones
      ADD CONSTRAINT reparaciones_key_fkey FOREIGN KEY (key)
        REFERENCES tienda.inventario(key) ON DELETE CASCADE ON UPDATE CASCADE;
    RAISE NOTICE 'FK reparaciones_key_fkey actualizada con ON UPDATE CASCADE';
  END IF;
END $$;

-- ---------- 7. Índices en columnas FK de inventario -------------------------------------
CREATE INDEX IF NOT EXISTS ix_inventario_condicion  ON tienda.inventario (condicion);
CREATE INDEX IF NOT EXISTS ix_inventario_canal_venta ON tienda.inventario (canal_venta);

-- ---------- 8. Trigger de historial en las tablas nuevas --------------------------------
DROP TRIGGER IF EXISTS trg_z_historial ON tienda.cat_condicion;
CREATE TRIGGER trg_z_historial
    AFTER INSERT OR UPDATE OR DELETE ON tienda.cat_condicion
    FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();

DROP TRIGGER IF EXISTS trg_z_historial ON tienda.cat_canal;
-- Nota: cat_canal no tiene columna key, el trigger genérico usa NEW.key
-- Para tablas sin key se omite el historial genérico (no aplica la función actual).
-- Se puede extender en Fase 6 con un trigger específico para catálogos.

-- ---------- 9. Resumen de la migración --------------------------------------------------
DO $$
DECLARE
    n_condicion INTEGER; n_canal INTEGER; n_estado INTEGER;
    n_socios INTEGER; n_reparto INTEGER;
BEGIN
    SELECT count(*) INTO n_condicion FROM tienda.cat_condicion;
    SELECT count(*) INTO n_canal     FROM tienda.cat_canal;
    SELECT count(*) INTO n_estado    FROM tienda.cat_estado;
    SELECT count(*) INTO n_socios    FROM tienda.socio;
    SELECT count(*) INTO n_reparto   FROM tienda.inventario_reparto;
    RAISE NOTICE 'Ola N1 completada: cat_condicion=% · cat_canal=% · cat_estado=% · socios=% · reparto=% filas',
        n_condicion, n_canal, n_estado, n_socios, n_reparto;
END $$;

COMMIT;
