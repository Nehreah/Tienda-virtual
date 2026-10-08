-- V001__rls_inventario.sql
-- Fase 1: Esquema base de la tienda (tienda), inventario, config e historial.
-- Es seguro ejecutarlo tanto en bases limpias desde cero como en bases existentes.

CREATE SCHEMA IF NOT EXISTS tienda;

CREATE TABLE IF NOT EXISTS tienda.config (
  clave          TEXT PRIMARY KEY,
  valor          TEXT NOT NULL,
  descripcion    TEXT,
  actualizado_en TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS tienda.historial (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tabla         TEXT NOT NULL,
  key           TEXT NOT NULL,
  operacion     TEXT NOT NULL,
  origen        TEXT NOT NULL,
  usuario       TEXT,
  fecha         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  datos_antes   JSONB,
  datos_despues JSONB
);

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

-- RLS en la tabla de inventario
ALTER TABLE tienda.inventario ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='inventario' AND schemaname='tienda' AND policyname='inventario_app_user') THEN
    CREATE POLICY inventario_app_user ON tienda.inventario FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN
  RAISE NOTICE 'Rol app_user no existe aún, omitiendo políticas de RLS';
END $$;
