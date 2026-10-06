-- =====================================================================================
-- V007: Fase 2 — Ola N2: Entidades normalizadas (fotos, canal, reparaciones múltiples)
-- =====================================================================================
-- Crea:
--   tienda.producto_foto       — fotos de productos (reemplaza inventario.fotos como texto)
--   tienda.publicacion_canal   — publicación y mapeo en Shopify / Meta Catalog
--   tienda.reparacion          — varias reparaciones por producto (D12=varias)
--   tienda.venta_reparto       — reparto de ganancias por venta y socio
-- Migra:
--   inventario.fotos (texto con saltos de línea) → producto_foto
--   tienda.reparaciones (1 por producto) → tienda.reparacion (0..N por producto)
--   tienda.metricas (columnas socios fijos) → venta_reparto (tabla normalizada)
--
-- Patrón Expandir/Migrar/Contraer:
--   Las tablas viejas (reparaciones, metricas) se conservan hasta V008.
-- =====================================================================================

BEGIN;

-- ---------- 1. Fotos de productos --------------------------------------------------------
CREATE TABLE IF NOT EXISTS tienda.producto_foto (
    foto_id     SERIAL      PRIMARY KEY,
    key         TEXT        NOT NULL REFERENCES tienda.inventario(key)
                                ON DELETE CASCADE ON UPDATE CASCADE,
    orden       SMALLINT    NOT NULL DEFAULT 0,    -- 0 = foto principal
    url         TEXT        NOT NULL,
    creado_en   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_foto_key   ON tienda.producto_foto (key, orden);
CREATE UNIQUE INDEX IF NOT EXISTS uidx_foto_url ON tienda.producto_foto (key, url);

-- Migrar fotos del campo texto (separadas por salto de línea o coma)
INSERT INTO tienda.producto_foto (key, orden, url)
SELECT
    key,
    (ROW_NUMBER() OVER (PARTITION BY key ORDER BY idx) - 1)::SMALLINT AS orden,
    trim(url) AS url
FROM (
    SELECT key,
           generate_series(1, array_length(urls, 1)) AS idx,
           unnest(urls) AS url
    FROM (
        SELECT key,
               string_to_array(
                   regexp_replace(trim(fotos), E'[\\n\\r,]+', ',', 'g'),
                   ','
               ) AS urls
        FROM tienda.inventario
        WHERE fotos IS NOT NULL AND trim(fotos) <> ''
    ) t
) t2
WHERE trim(url) <> ''
ON CONFLICT (key, url) DO NOTHING;

ALTER TABLE tienda.producto_foto ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='producto_foto' AND schemaname='tienda' AND policyname='foto_app_user') THEN
    CREATE POLICY foto_app_user ON tienda.producto_foto FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 2. Publicaciones por canal (Shopify / Meta) ----------------------------------
-- Reemplaza channelMappings en memoria + la hoja "Canales/Publicación" del Excel.

CREATE TABLE IF NOT EXISTS tienda.publicacion_canal (
    key                 TEXT    NOT NULL REFERENCES tienda.inventario(key)
                                    ON DELETE CASCADE ON UPDATE CASCADE,
    canal               TEXT    NOT NULL REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE,
    externo_id          TEXT,                       -- ID en el canal (Shopify product_id, Meta catalog item_id)
    externo_inventory_id TEXT,                      -- Shopify inventory_item_id (necesario para setStock)
    precio_canal        BIGINT,                     -- Precio publicado en este canal (puede diferir del PP)
    estado_publicacion  TEXT    NOT NULL DEFAULT 'SIN_PUBLICAR',
    stock_sincronizado  INT,                        -- Último stock confirmado en el canal
    ultimo_sync         TIMESTAMPTZ,
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key, canal),
    CONSTRAINT pub_estado_check CHECK (estado_publicacion IN
        ('SIN_PUBLICAR','PUBLICADO','PAUSADO','ARCHIVADO','ERROR_SYNC'))
);

CREATE INDEX IF NOT EXISTS idx_pub_canal     ON tienda.publicacion_canal (canal, estado_publicacion);
CREATE INDEX IF NOT EXISTS idx_pub_key       ON tienda.publicacion_canal (key);

-- Migrar channelMappings (no están en BD, están en memoria — se poblará vía Excel/webhooks)
-- No hay datos para migrar desde SQL; el importador de Excel los cargará en Fase 5.

ALTER TABLE tienda.publicacion_canal ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='publicacion_canal' AND schemaname='tienda' AND policyname='pub_canal_app_user') THEN
    CREATE POLICY pub_canal_app_user ON tienda.publicacion_canal FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 3. Reparaciones múltiples (D12 = varias por producto) ------------------------
-- La tabla antigua tienda.reparaciones tenía PK = key (máximo 1 por producto).
-- La nueva tabla tienda.reparacion tiene reparacion_id propio (0..N por producto).

CREATE TABLE IF NOT EXISTS tienda.reparacion (
    reparacion_id       SERIAL      PRIMARY KEY,
    key                 TEXT        NOT NULL REFERENCES tienda.inventario(key)
                                        ON DELETE RESTRICT ON UPDATE CASCADE,
    costo               BIGINT      NOT NULL DEFAULT 0 CHECK (costo >= 0),
    descripcion         TEXT,
    fecha_reparacion    DATE,
    completada          BOOLEAN     NOT NULL DEFAULT false,
    creado_en           TIMESTAMPTZ NOT NULL DEFAULT now(),
    actualizado_en      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reparacion_key ON tienda.reparacion (key);

-- Migrar reparaciones existentes (1 por producto en la tabla vieja → 1 fila en la nueva)
INSERT INTO tienda.reparacion (key, costo, descripcion, fecha_reparacion, completada)
SELECT
    r.key,
    r.precio_reparacion,
    r.detalles,
    NULL,   -- no había fecha de reparación en el esquema viejo
    true    -- si está en la tabla vieja, ya se realizó
FROM tienda.reparaciones r
WHERE NOT EXISTS (
    SELECT 1 FROM tienda.reparacion nr WHERE nr.key = r.key
);

-- Trigger: actualizar actualizado_en automáticamente
CREATE OR REPLACE FUNCTION tienda.trg_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.actualizado_en := now();
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_reparacion_updated_at ON tienda.reparacion;
CREATE TRIGGER trg_reparacion_updated_at
    BEFORE UPDATE ON tienda.reparacion
    FOR EACH ROW EXECUTE FUNCTION tienda.trg_updated_at();

-- Trigger historial (requiere columna key — esta tabla la tiene)
DROP TRIGGER IF EXISTS trg_z_historial ON tienda.reparacion;
CREATE TRIGGER trg_z_historial
    AFTER INSERT OR UPDATE OR DELETE ON tienda.reparacion
    FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();

ALTER TABLE tienda.reparacion ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='reparacion' AND schemaname='tienda' AND policyname='reparacion_app_user') THEN
    CREATE POLICY reparacion_app_user ON tienda.reparacion FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 4. Reparto de ventas por socio -----------------------------------------------
-- Reemplaza tienda.metricas (columnas duras ingresos_antonio, ganancias_antonio…).

CREATE TABLE IF NOT EXISTS tienda.venta_reparto (
    reparto_id  SERIAL  PRIMARY KEY,
    key         TEXT    NOT NULL REFERENCES tienda.ventas(key) ON DELETE CASCADE ON UPDATE CASCADE,
    socio_id    INTEGER NOT NULL REFERENCES tienda.socio(socio_id),
    ingresos    BIGINT  NOT NULL,
    ganancias   BIGINT  NOT NULL,
    UNIQUE (key, socio_id)
);

CREATE INDEX IF NOT EXISTS idx_venta_reparto_key    ON tienda.venta_reparto (key);
CREATE INDEX IF NOT EXISTS idx_venta_reparto_socio  ON tienda.venta_reparto (socio_id);

-- Migrar datos desde tienda.metricas → venta_reparto
DO $$
DECLARE
    v_id_antonio INTEGER;
    v_id_javier  INTEGER;
BEGIN
    SELECT socio_id INTO v_id_antonio FROM tienda.socio WHERE nombre = 'Antonio';
    SELECT socio_id INTO v_id_javier  FROM tienda.socio WHERE nombre = 'Javier';

    -- Antonio
    INSERT INTO tienda.venta_reparto (key, socio_id, ingresos, ganancias)
    SELECT key, v_id_antonio, ingresos_antonio, ganancias_antonio
    FROM tienda.metricas
    ON CONFLICT (key, socio_id) DO UPDATE
        SET ingresos = EXCLUDED.ingresos, ganancias = EXCLUDED.ganancias;

    -- Javier
    INSERT INTO tienda.venta_reparto (key, socio_id, ingresos, ganancias)
    SELECT key, v_id_javier, ingresos_javier, ganancias_javier
    FROM tienda.metricas
    ON CONFLICT (key, socio_id) DO UPDATE
        SET ingresos = EXCLUDED.ingresos, ganancias = EXCLUDED.ganancias;

    RAISE NOTICE 'Reparto de ventas migrado: % filas en venta_reparto', (SELECT count(*) FROM tienda.venta_reparto);
END $$;

ALTER TABLE tienda.venta_reparto ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='venta_reparto' AND schemaname='tienda' AND policyname='venta_reparto_app_user') THEN
    CREATE POLICY venta_reparto_app_user ON tienda.venta_reparto FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- ---------- 5. Vista v_stock calculada (reemplaza contadores en memoria) -----------------
-- stock_disponible = cantidad - reservado_activo - vendido
-- La tabla ventas tiene PK = key (D7=no, cada key es único), así que vendido = 0 o 1.
CREATE OR REPLACE VIEW tienda.v_stock AS
SELECT
    i.key,
    i.item,
    i.estado,
    i.cantidad                                          AS stock_maestro,
    COALESCE(r.reservado, 0)                            AS reservado,
    CASE WHEN v.key IS NOT NULL THEN 1 ELSE 0 END       AS vendido,
    i.cantidad
        - COALESCE(r.reservado, 0)
        - CASE WHEN v.key IS NOT NULL THEN 1 ELSE 0 END AS disponible
FROM tienda.inventario i
LEFT JOIN (
    SELECT product_key AS key, COUNT(*) AS reservado
    FROM tienda.reservas
    WHERE status = 'ACTIVE'
    GROUP BY product_key
) r ON r.key = i.key
LEFT JOIN tienda.ventas v ON v.key = i.key;

COMMENT ON VIEW tienda.v_stock IS
    'Stock calculado desde cantidad - reservas activas - ventas. No hay contadores almacenados.';

-- ---------- 6. Actualizar sync_venta para usar venta_reparto ----------------------------
-- Ampliar tienda.sync_venta para insertar en venta_reparto además de metricas.
-- La tabla metricas se mantiene por compatibilidad hasta V008.
CREATE OR REPLACE FUNCTION tienda.sync_venta(p_key text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    i          tienda.inventario%rowtype;
    v_ganancia BIGINT;
    v_tiempo   INTEGER;
    v_dias_pub INTEGER;
    v_total    NUMERIC;
    v_ing_a    BIGINT;
    v_gan_a    BIGINT;
    v_id_a     INTEGER;
    v_id_j     INTEGER;
BEGIN
    SELECT * INTO i FROM tienda.inventario WHERE key = p_key;

    -- Ya no está vendido: borrar la venta (y en cascada venta_reparto)
    IF NOT FOUND OR i.estado <> 'Vendido' THEN
        DELETE FROM tienda.ventas WHERE key = p_key;
        RETURN;
    END IF;

    v_ganancia := i.vendido_en - i.costo_total;
    v_tiempo   := CASE WHEN i.fecha_compra IS NULL THEN NULL
                       ELSE GREATEST(i.fecha_venta - i.fecha_compra, 0) END;
    v_dias_pub := CASE WHEN i.fecha_publicacion IS NULL THEN NULL
                       ELSE GREATEST(i.fecha_venta - i.fecha_publicacion, 0) END;

    INSERT INTO tienda.ventas (key, fecha_venta, vendido_en, canal, ingresos, costo_total,
                               ganancia_neta, margen_pct, tiempo_dias, dias_publicado, periodo)
    VALUES (i.key, i.fecha_venta, i.vendido_en, i.canal_venta, i.vendido_en, i.costo_total,
            v_ganancia,
            CASE WHEN i.vendido_en = 0 THEN NULL
                 ELSE ROUND(v_ganancia::NUMERIC * 100 / i.vendido_en, 2) END,
            v_tiempo, v_dias_pub, tienda.fn_periodo(v_tiempo))
    ON CONFLICT (key) DO UPDATE SET
        fecha_venta    = EXCLUDED.fecha_venta,
        vendido_en     = EXCLUDED.vendido_en,
        canal          = EXCLUDED.canal,
        ingresos       = EXCLUDED.ingresos,
        costo_total    = EXCLUDED.costo_total,
        ganancia_neta  = EXCLUDED.ganancia_neta,
        margen_pct     = EXCLUDED.margen_pct,
        tiempo_dias    = EXCLUDED.tiempo_dias,
        dias_publicado = EXCLUDED.dias_publicado,
        periodo        = EXCLUDED.periodo,
        updated_at     = now();

    -- Reparto: desde inventario_reparto (tabla normalizada)
    SELECT socio_id INTO v_id_a FROM tienda.socio WHERE nombre = 'Antonio';
    SELECT socio_id INTO v_id_j FROM tienda.socio WHERE nombre = 'Javier';

    v_total := i.pct_antonio + i.pct_javier;  -- columnas viejas, compatibilidad hasta V008

    IF v_total > 0 THEN
        v_ing_a := ROUND(i.vendido_en::NUMERIC * i.pct_antonio / v_total)::BIGINT;
        v_gan_a := ROUND(v_ganancia::NUMERIC   * i.pct_antonio / v_total)::BIGINT;

        -- metricas (tabla vieja, compatibilidad)
        INSERT INTO tienda.metricas (key, ingresos_antonio, ganancias_antonio, ingresos_javier, ganancias_javier)
        VALUES (i.key, v_ing_a, v_gan_a, i.vendido_en - v_ing_a, v_ganancia - v_gan_a)
        ON CONFLICT (key) DO UPDATE SET
            ingresos_antonio  = EXCLUDED.ingresos_antonio,
            ganancias_antonio = EXCLUDED.ganancias_antonio,
            ingresos_javier   = EXCLUDED.ingresos_javier,
            ganancias_javier  = EXCLUDED.ganancias_javier;

        -- venta_reparto (tabla nueva)
        INSERT INTO tienda.venta_reparto (key, socio_id, ingresos, ganancias)
        VALUES (i.key, v_id_a, v_ing_a, v_gan_a)
        ON CONFLICT (key, socio_id) DO UPDATE
            SET ingresos = EXCLUDED.ingresos, ganancias = EXCLUDED.ganancias;

        INSERT INTO tienda.venta_reparto (key, socio_id, ingresos, ganancias)
        VALUES (i.key, v_id_j, i.vendido_en - v_ing_a, v_ganancia - v_gan_a)
        ON CONFLICT (key, socio_id) DO UPDATE
            SET ingresos = EXCLUDED.ingresos, ganancias = EXCLUDED.ganancias;
    ELSE
        DELETE FROM tienda.metricas      WHERE key = i.key;
        DELETE FROM tienda.venta_reparto WHERE key = i.key;
    END IF;
END $$;

-- ---------- 7. Resumen ------------------------------------------------------------------
DO $$
DECLARE n_fotos INT; n_pub INT; n_rep INT; n_vr INT;
BEGIN
    SELECT count(*) INTO n_fotos FROM tienda.producto_foto;
    SELECT count(*) INTO n_pub   FROM tienda.publicacion_canal;
    SELECT count(*) INTO n_rep   FROM tienda.reparacion;
    SELECT count(*) INTO n_vr    FROM tienda.venta_reparto;
    RAISE NOTICE 'Ola N2: fotos=% · publicacion_canal=% · reparaciones=% · venta_reparto=%',
        n_fotos, n_pub, n_rep, n_vr;
END $$;

COMMIT;
