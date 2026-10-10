-- =====================================================================================
-- V1 · NÚCLEO — configuración, catálogos, historial, inventario, reparaciones, socios
-- =====================================================================================
-- Esquema único "tienda", construido desde cero (sin datos de ejemplo, sin socios).
-- Esta es la primera de 4 piezas de UN solo sistema:
--   V1 núcleo  ->  V2 clientes y reservas  ->  V3 canales y cola  ->  V4 ventas y métricas
-- Flyway ejecuta cada archivo dentro de su propia transacción: aquí no hay BEGIN/COMMIT.
--
-- Decisiones de modelo (vigentes en todo el sistema):
--   * inventario.cantidad       = unidades disponibles físicamente (única columna de stock).
--   * inventario.costo_total    = costo POR UNIDAD = precio_compra + reparación (ambos por unidad).
--   * inventario.costo_lote     = costo de TODO el lote = costo_total x cantidad_comprada.
--   * inventario.cantidad_comprada = unidades que entraron al lote (sube al reponer, no baja al vender).
--   * Los socios no vienen sembrados: se crean desde el dashboard (empiezan en 0).
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS tienda;

-- ---------- 0. Utilidad: seguridad uniforme para todas las tablas ----------------------------
-- Cada módulo llama a tienda.fn_asegurar_rls('tienda.<tabla>') para activar RLS, dar permisos al
-- rol de la app (app_user, si existe) y cerrar la API REST de Supabase (anon / authenticated).
CREATE OR REPLACE FUNCTION tienda.fn_asegurar_rls(p_tabla regclass)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
  v_esq text;
  v_tab text;
BEGIN
  SELECT n.nspname, c.relname INTO v_esq, v_tab
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.oid = p_tabla;

  EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', v_esq, v_tab);

  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                    WHERE schemaname = v_esq AND tablename = v_tab AND policyname = 'app_user_all') THEN
      EXECUTE format('CREATE POLICY app_user_all ON %I.%I FOR ALL TO app_user USING (true) WITH CHECK (true)',
                     v_esq, v_tab);
    END IF;
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO app_user', v_esq);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I.%I TO app_user', v_esq, v_tab);
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE format('REVOKE ALL ON %I.%I FROM anon', v_esq, v_tab);
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
    EXECUTE format('REVOKE ALL ON %I.%I FROM authenticated', v_esq, v_tab);
  END IF;
END $$;

-- ---------- 1. Configuración -----------------------------------------------------------------
CREATE TABLE tienda.config (
  clave       text PRIMARY KEY,
  valor       numeric NOT NULL,
  descripcion text
);
INSERT INTO tienda.config (clave, valor, descripcion) VALUES
  ('roi_minimo_pct',     50, 'ROI mínimo sobre el costo total por unidad (precio mínimo = costo x 1,5)'),
  ('periodo_corto_dias', 15, 'Venta "corta": menos de estos días desde la compra'),
  ('periodo_medio_dias', 30, 'Venta "media": hasta estos días; desde aquí es "larga"'),
  ('exigir_reparto',      0, '1 = vender a mano o por chat exige reparto de socios al 100 %. 0 = se puede vender sin socios');

CREATE OR REPLACE FUNCTION tienda.fn_config(p_clave text, p_defecto numeric)
RETURNS numeric LANGUAGE sql STABLE AS $$
  SELECT coalesce((SELECT valor FROM tienda.config WHERE clave = p_clave), p_defecto)
$$;

-- Precio mínimo = costo x (1 + ROI), redondeado hacia arriba al peso (espejo de FinancialMath.precioMinimo).
CREATE OR REPLACE FUNCTION tienda.fn_precio_minimo(p_costo_total bigint)
RETURNS bigint LANGUAGE sql STABLE AS $$
  SELECT ceil(p_costo_total::numeric * (100 + tienda.fn_config('roi_minimo_pct', 50)) / 100)::bigint
$$;

-- Periodo según días desde la compra (espejo de FinancialMath.periodo): corto < 15, medio 15-29, largo >= 30.
CREATE OR REPLACE FUNCTION tienda.fn_periodo(p_dias integer)
RETURNS text LANGUAGE sql STABLE AS $$
  SELECT CASE
    WHEN p_dias IS NULL THEN NULL
    WHEN p_dias < tienda.fn_config('periodo_corto_dias', 15) THEN 'corto'
    WHEN p_dias < tienda.fn_config('periodo_medio_dias', 30) THEN 'medio'
    ELSE 'largo'
  END
$$;

-- ---------- 2. Catálogos ----------------------------------------------------------------------
CREATE TABLE tienda.cat_estado (estado text PRIMARY KEY);
INSERT INTO tienda.cat_estado (estado) VALUES
  ('Comprado'), ('Publicado'), ('Negociación'), ('Vendido'), ('Inactivo'), ('Otro');

CREATE TABLE tienda.cat_tipo (tipo text PRIMARY KEY);   -- se llena sola al usar un tipo nuevo

CREATE TABLE tienda.cat_condicion (condicion text PRIMARY KEY, descripcion text);
INSERT INTO tienda.cat_condicion (condicion, descripcion) VALUES
  ('Nuevo',           'Sellado, sin uso'),
  ('Como nuevo',      'Usado con mínimas marcas, impecable'),
  ('Reacondicionado', 'Revisado y restaurado a condición funcional'),
  ('Usado',           'Usado en buen estado, con marcas normales de uso'),
  ('Para reparar',    'Requiere reparación antes de vender');

CREATE TABLE tienda.cat_canal (
  canal          text PRIMARY KEY,
  es_mensajeria  boolean NOT NULL DEFAULT false,   -- whatsapp, instagram, messenger
  es_publicacion boolean NOT NULL DEFAULT false,   -- shopify, meta_catalogo (reciben stock)
  es_venta       boolean NOT NULL DEFAULT true,    -- por este canal se registran ventas
  descripcion    text
);
INSERT INTO tienda.cat_canal (canal, es_mensajeria, es_publicacion, es_venta, descripcion) VALUES
  ('whatsapp',      true,  false, true,  'WhatsApp Cloud API'),
  ('instagram',     true,  false, true,  'Instagram DM'),
  ('messenger',     true,  false, true,  'Facebook Messenger'),
  ('shopify',       false, true,  true,  'Tienda Shopify'),
  ('meta_catalogo', false, true,  false, 'Catálogo de Meta / Facebook (solo refleja stock)'),
  ('presencial',    false, false, true,  'Venta presencial'),
  ('otro',          false, false, true,  'Canal no clasificado');

-- ---------- 3. Historial genérico de cambios --------------------------------------------------
CREATE TABLE tienda.historial (
  id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tabla     text        NOT NULL,
  key       text        NOT NULL,
  operacion text        NOT NULL,
  cambios   jsonb       NOT NULL DEFAULT '{}'::jsonb,
  origen    text        NOT NULL,
  usuario   text,
  creado_en timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_historial_tabla_key ON tienda.historial (tabla, key, creado_en DESC);

-- Funciona en cualquier tabla con columna "key". Origen y usuario salen de set_config('app.origen'/'app.usuario')
-- que fija DatabaseFactory.tx; si el cambio viene del editor de Supabase queda origen = 'supabase'.
CREATE OR REPLACE FUNCTION tienda.trg_historial_generico()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_old jsonb; v_new jsonb; v_cambios jsonb; v_key text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    v_old := '{}'::jsonb; v_new := to_jsonb(NEW); v_key := NEW.key;
  ELSIF TG_OP = 'UPDATE' THEN
    v_old := to_jsonb(OLD); v_new := to_jsonb(NEW); v_key := NEW.key;
  ELSE
    v_old := to_jsonb(OLD); v_new := '{}'::jsonb; v_key := OLD.key;
  END IF;

  SELECT coalesce(jsonb_object_agg(k, jsonb_build_object('antes', v_old -> k, 'despues', v_new -> k)), '{}'::jsonb)
    INTO v_cambios
    FROM (SELECT jsonb_object_keys(v_old || v_new) AS k) t
   WHERE k NOT IN ('updated_at', 'created_at', 'version')
     AND (v_old -> k) IS DISTINCT FROM (v_new -> k);

  IF TG_OP = 'UPDATE' AND v_cambios = '{}'::jsonb THEN
    RETURN NULL;
  END IF;

  INSERT INTO tienda.historial (tabla, key, operacion, cambios, origen, usuario)
  VALUES (TG_TABLE_NAME, v_key, TG_OP, v_cambios,
          coalesce(nullif(current_setting('app.origen', true), ''), 'supabase'),
          nullif(current_setting('app.usuario', true), ''));
  RETURN NULL;
END $$;

-- ---------- 4. Inventario ---------------------------------------------------------------------
CREATE TABLE tienda.inventario (
  key                 text PRIMARY KEY,
  item                text   NOT NULL CHECK (btrim(item) <> ''),
  link                text,
  fotos               text   NOT NULL DEFAULT '',              -- URLs separadas por salto de línea
  estado              text   NOT NULL DEFAULT 'Comprado' REFERENCES tienda.cat_estado(estado) ON UPDATE CASCADE,
  tipo                text   REFERENCES tienda.cat_tipo(tipo) ON UPDATE CASCADE,
  marca               text,
  condicion           text   REFERENCES tienda.cat_condicion(condicion) ON UPDATE CASCADE,
  descripcion         text,
  ubicacion           text,
  fecha_compra        date,
  fecha_publicacion   date,
  fecha_venta         date,
  precio_compra       bigint NOT NULL DEFAULT 0 CHECK (precio_compra >= 0),      -- POR UNIDAD
  requiere_reparacion boolean NOT NULL DEFAULT false,
  costo_total         bigint NOT NULL DEFAULT 0,               -- POR UNIDAD (lo calcula el trigger)
  precio_minimo       bigint,                                  -- lo calcula el trigger
  precio_publicado    bigint CHECK (precio_publicado IS NULL OR precio_publicado >= 0),   -- POR UNIDAD
  vendido_en          bigint CHECK (vendido_en IS NULL OR vendido_en >= 0),               -- POR UNIDAD (última venta)
  canal_venta         text   REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE,
  cantidad            integer NOT NULL DEFAULT 1 CHECK (cantidad >= 0),          -- stock disponible
  cantidad_comprada   integer NOT NULL DEFAULT 1 CHECK (cantidad_comprada >= 0), -- tamaño del lote
  costo_lote          bigint NOT NULL DEFAULT 0,               -- costo_total x cantidad_comprada (trigger)
  version             bigint NOT NULL DEFAULT 1,               -- sube cada vez que cambia el stock
  archivado_en        timestamptz,
  archivado_por       text,
  motivo_archivo      text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_inventario_key_upper ON tienda.inventario (upper(key));
CREATE INDEX ix_inventario_estado ON tienda.inventario (estado);
CREATE INDEX ix_inventario_tipo   ON tienda.inventario (tipo);

COMMENT ON COLUMN tienda.inventario.costo_total IS 'Costo POR UNIDAD = precio_compra + reparación (por unidad).';
COMMENT ON COLUMN tienda.inventario.costo_lote  IS 'Costo de TODO el lote = costo_total x cantidad_comprada.';
COMMENT ON COLUMN tienda.inventario.cantidad    IS 'Unidades disponibles ahora. Única columna de stock del sistema.';

-- ---------- 5. Reparaciones (1 por artículo; costo POR UNIDAD) ---------------------------------
CREATE TABLE tienda.reparaciones (
  key               text PRIMARY KEY REFERENCES tienda.inventario(key) ON DELETE CASCADE ON UPDATE CASCADE,
  precio_reparacion bigint NOT NULL DEFAULT 0 CHECK (precio_reparacion >= 0),
  detalles          text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

-- ---------- 6. Socios dinámicos (empiezan en 0) -----------------------------------------------
CREATE TABLE tienda.socio (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  nombre       text NOT NULL CHECK (btrim(nombre) <> '' AND char_length(nombre) <= 60),
  activo       boolean NOT NULL DEFAULT true,
  creado_en    timestamptz NOT NULL DEFAULT now(),
  archivado_en timestamptz
);
CREATE UNIQUE INDEX ux_socio_nombre_activo ON tienda.socio (lower(btrim(nombre))) WHERE activo;

-- % de inversión de cada socio en cada artículo (la suma por artículo no puede pasar de 100).
CREATE TABLE tienda.inventario_inversion (
  key      text   NOT NULL REFERENCES tienda.inventario(key) ON DELETE CASCADE ON UPDATE CASCADE,
  socio_id bigint NOT NULL REFERENCES tienda.socio(id) ON DELETE RESTRICT,
  pct      numeric(5,2) NOT NULL CHECK (pct >= 0 AND pct <= 100),
  PRIMARY KEY (key, socio_id)
);
CREATE INDEX ix_inversion_socio ON tienda.inventario_inversion (socio_id);

-- Reparto proporcional exacto (método del mayor residuo): la suma de los montos siempre cuadra con p_valor.
-- Sin socios con % > 0 devuelve cero filas (la venta se registra igual, solo queda "sin reparto").
CREATE OR REPLACE FUNCTION tienda.fn_repartir(p_valor bigint, p_key text)
RETURNS TABLE (socio_id bigint, monto bigint) LANGUAGE sql STABLE AS $$
  WITH t AS (
    SELECT coalesce(sum(pct), 0) AS total FROM tienda.inventario_inversion WHERE key = p_key AND pct > 0
  ),
  b AS (
    SELECT ii.socio_id, (p_valor::numeric * ii.pct / t.total) AS exacto
      FROM tienda.inventario_inversion ii, t
     WHERE ii.key = p_key AND ii.pct > 0 AND t.total > 0
  ),
  f AS (SELECT socio_id, floor(exacto)::bigint AS piso, exacto - floor(exacto) AS resto FROM b),
  faltan AS (SELECT p_valor - coalesce(sum(piso), 0) AS n FROM f),
  o AS (SELECT f.*, row_number() OVER (ORDER BY resto DESC, socio_id) AS pos FROM f)
  SELECT o.socio_id, o.piso + CASE WHEN o.pos <= (SELECT n FROM faltan) THEN 1 ELSE 0 END FROM o;
$$;

-- ¿El artículo tiene reparto completo (socios que suman exactamente 100)?
CREATE OR REPLACE FUNCTION tienda.fn_reparto_completo(p_key text)
RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT coalesce(sum(pct), 0) = 100 FROM tienda.inventario_inversion WHERE key = p_key
$$;

-- Artículos que todavía no tienen el reparto de socios completo (suma distinta de 100 %).
-- Solo informativa: no bloquea nada ni descarta artículos (el bloqueo, si se quiere, es config.exigir_reparto).
-- Con 0 socios aparecen todos los artículos activos. La usan verificar_integridad() (V4) y el dashboard.
CREATE OR REPLACE VIEW tienda.v_reparto_pendiente AS
SELECT i.key, i.item, i.estado, i.cantidad,
       coalesce(sum(ii.pct), 0)         AS pct_asignado,
       100 - coalesce(sum(ii.pct), 0)   AS pct_faltante
  FROM tienda.inventario i
  LEFT JOIN tienda.inventario_inversion ii ON ii.key = i.key
 WHERE i.archivado_en IS NULL
   AND i.estado NOT IN ('Vendido', 'Inactivo')
 GROUP BY i.key, i.item, i.estado, i.cantidad
HAVING coalesce(sum(ii.pct), 0) <> 100;

CREATE OR REPLACE FUNCTION tienda.trg_inversion_suma() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_key text := CASE WHEN TG_OP = 'DELETE' THEN OLD.key ELSE NEW.key END; v_suma numeric;
BEGIN
  PERFORM 1 FROM tienda.inventario WHERE key = v_key FOR UPDATE;
  SELECT coalesce(sum(pct), 0) INTO v_suma FROM tienda.inventario_inversion WHERE key = v_key;
  IF v_suma > 100 THEN
    RAISE EXCEPTION 'Los porcentajes de inversión de % no pueden superar 100 (suman %).', v_key, v_suma;
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_inversion_suma
  AFTER INSERT OR UPDATE OR DELETE ON tienda.inventario_inversion
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tienda.trg_inversion_suma();

-- Un artículo ya vendido no cambia su reparto; un socio archivado no recibe porcentajes nuevos.
CREATE OR REPLACE FUNCTION tienda.trg_inversion_bloqueo() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_key text := CASE WHEN TG_OP = 'DELETE' THEN OLD.key ELSE NEW.key END;
BEGIN
  IF EXISTS (SELECT 1 FROM tienda.inventario WHERE key = v_key AND estado = 'Vendido') THEN
    RAISE EXCEPTION 'El artículo % ya está vendido: no se pueden cambiar los porcentajes. Anula la venta, edita y vuelve a vender.', v_key;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') AND NOT EXISTS (SELECT 1 FROM tienda.socio WHERE id = NEW.socio_id AND activo) THEN
    RAISE EXCEPTION 'El socio está archivado: no se le pueden asignar porcentajes.';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER trg_a_inversion_bloqueo
  BEFORE INSERT OR UPDATE OR DELETE ON tienda.inventario_inversion
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_inversion_bloqueo();

-- Retirar un socio: se elimina si nunca participó; se archiva si ya tiene ventas o inversiones en artículos vendidos.
CREATE OR REPLACE FUNCTION tienda.retirar_socio(p_id bigint) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM tienda.venta_reparto WHERE socio_id = p_id)
     OR EXISTS (SELECT 1 FROM tienda.inventario_inversion WHERE socio_id = p_id) THEN
    UPDATE tienda.socio SET activo = false, archivado_en = now() WHERE id = p_id;
    RETURN 'ARCHIVADO';
  END IF;
  DELETE FROM tienda.socio WHERE id = p_id;
  RETURN 'ELIMINADO';
END $$;

-- ---------- 7. Triggers de inventario ---------------------------------------------------------
-- app.venta_interna = 'on' lo fija únicamente tienda.registrar_venta_linea / anular_venta_linea (V4):
-- así el trigger distingue "la función de ventas está cambiando el stock" de "alguien edita a mano".
CREATE OR REPLACE FUNCTION tienda.trg_inventario_antes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_rep       bigint := 0;
  v_revisar   boolean := false;
  v_migracion boolean := coalesce(current_setting('app.migracion', true), '') = 'on';
  v_interna   boolean := coalesce(current_setting('app.venta_interna', true), '') = 'on';
BEGIN
  -- Normalizar texto y registrar solos los valores nuevos de los catálogos
  NEW.tipo        := nullif(trim(NEW.tipo), '');
  NEW.condicion   := nullif(trim(NEW.condicion), '');
  NEW.canal_venta := nullif(lower(trim(NEW.canal_venta)), '');
  IF NEW.tipo IS NOT NULL THEN
    INSERT INTO tienda.cat_tipo (tipo) VALUES (NEW.tipo) ON CONFLICT DO NOTHING;
  END IF;
  IF NEW.condicion IS NOT NULL THEN
    INSERT INTO tienda.cat_condicion (condicion) VALUES (NEW.condicion) ON CONFLICT DO NOTHING;
  END IF;
  IF NEW.canal_venta IS NOT NULL THEN
    INSERT INTO tienda.cat_canal (canal) VALUES (NEW.canal_venta) ON CONFLICT DO NOTHING;
  END IF;

  IF NEW.fecha_compra IS NOT NULL AND NEW.fecha_publicacion IS NOT NULL
     AND NEW.fecha_publicacion < NEW.fecha_compra AND NOT v_migracion THEN
    RAISE EXCEPTION 'La fecha de publicación (%) no puede ser anterior a la fecha de compra (%) en %.',
      NEW.fecha_publicacion, NEW.fecha_compra, NEW.key;
  END IF;

  -- Lote: al crear = unidades iniciales; al reponer a mano sube; vender o anular no lo tocan.
  IF TG_OP = 'INSERT' THEN
    NEW.cantidad_comprada := greatest(NEW.cantidad_comprada, NEW.cantidad);
  ELSE
    IF NEW.cantidad > OLD.cantidad AND NOT v_interna THEN
      NEW.cantidad_comprada := NEW.cantidad_comprada + (NEW.cantidad - OLD.cantidad);
    END IF;
    NEW.cantidad_comprada := greatest(NEW.cantidad_comprada, NEW.cantidad);
    IF NEW.cantidad IS DISTINCT FROM OLD.cantidad THEN
      NEW.version := OLD.version + 1;          -- el stock cambió: la cola de canales usa esta versión
    END IF;
  END IF;

  IF TG_OP = 'UPDATE' THEN
    -- Un artículo vendido no admite cambios de compra ni reparación
    IF OLD.estado = 'Vendido' AND NEW.estado = 'Vendido' AND NOT v_migracion THEN
      IF NEW.precio_compra IS DISTINCT FROM OLD.precio_compra
         OR NEW.fecha_compra IS DISTINCT FROM OLD.fecha_compra
         OR NEW.requiere_reparacion IS DISTINCT FROM OLD.requiere_reparacion THEN
        RAISE EXCEPTION 'El artículo % ya está vendido: no se puede cambiar compra ni reparación. Para corregir, cambia el estado a "Publicado" (anula la venta), edita y vuelve a marcarlo "Vendido".', NEW.key;
      END IF;
    END IF;

    -- Al dejar de estar Vendido se borran los datos de la última venta (quedan en el historial)
    IF OLD.estado = 'Vendido' AND NEW.estado <> 'Vendido' THEN
      NEW.fecha_venta := NULL;
      NEW.vendido_en  := NULL;
      NEW.canal_venta := NULL;
    END IF;

    IF OLD.requiere_reparacion AND NOT NEW.requiere_reparacion THEN
      IF EXISTS (SELECT 1 FROM tienda.reparaciones WHERE key = NEW.key AND precio_reparacion > 0) THEN
        RAISE EXCEPTION 'El artículo % tiene una reparación de precio mayor a 0. Pon primero el precio de la reparación en 0 si de verdad no hubo reparación.', NEW.key;
      END IF;
    END IF;
  END IF;

  -- Costo por unidad, costo del lote y precio mínimo
  IF NEW.requiere_reparacion THEN
    SELECT precio_reparacion INTO v_rep FROM tienda.reparaciones WHERE key = NEW.key;
  END IF;
  NEW.costo_total   := NEW.precio_compra + coalesce(v_rep, 0);
  NEW.costo_lote    := NEW.costo_total * NEW.cantidad_comprada;
  NEW.precio_minimo := tienda.fn_precio_minimo(NEW.costo_total);

  -- Publicado / Negociación exigen precio de venta y que no sea menor al mínimo
  IF NEW.estado IN ('Publicado', 'Negociación') AND NOT v_migracion THEN
    IF TG_OP = 'INSERT' THEN
      v_revisar := true;
    ELSE
      v_revisar := OLD.estado IS DISTINCT FROM NEW.estado
                OR OLD.precio_publicado IS DISTINCT FROM NEW.precio_publicado
                OR OLD.precio_minimo IS DISTINCT FROM NEW.precio_minimo;
    END IF;
    IF v_revisar THEN
      IF NEW.precio_publicado IS NULL THEN
        RAISE EXCEPTION 'Para pasar % a "%" primero llena precio_publicado.', NEW.key, NEW.estado;
      END IF;
      IF NEW.precio_publicado < NEW.precio_minimo THEN
        RAISE EXCEPTION 'El precio de venta de % (%) es menor al precio mínimo (%) = costo total (%) x 1,5.',
          NEW.key, NEW.precio_publicado, NEW.precio_minimo, NEW.costo_total;
      END IF;
    END IF;
  END IF;

  -- Marcar Vendido exige los datos de la venta (y reparto al 100 % si la configuración lo pide)
  IF NEW.estado = 'Vendido' AND NOT v_migracion THEN
    IF NEW.fecha_venta IS NULL OR NEW.vendido_en IS NULL THEN
      RAISE EXCEPTION 'Para marcar % como Vendido primero llena fecha_venta y vendido_en (y canal_venta si quieres), y luego cambia el estado.', NEW.key;
    END IF;
    IF NOT v_interna AND tienda.fn_config('exigir_reparto', 0) = 1 AND NOT tienda.fn_reparto_completo(NEW.key) THEN
      RAISE EXCEPTION 'Para vender % los porcentajes de los socios deben sumar 100.', NEW.key;
    END IF;
    IF NEW.fecha_compra IS NOT NULL AND NEW.fecha_venta < NEW.fecha_compra THEN
      RAISE EXCEPTION 'La fecha de venta (%) no puede ser anterior a la fecha de compra (%) en %.',
        NEW.fecha_venta, NEW.fecha_compra, NEW.key;
    END IF;
    IF NEW.fecha_publicacion IS NOT NULL AND NEW.fecha_venta < NEW.fecha_publicacion THEN
      RAISE EXCEPTION 'La fecha de venta (%) no puede ser anterior a la fecha de publicación (%) en %.',
        NEW.fecha_venta, NEW.fecha_publicacion, NEW.key;
    END IF;
  END IF;

  NEW.updated_at := now();
  RETURN NEW;
END $$;

-- Reparación: al marcar "requiere reparación" se crea la fila vacía; al desmarcar se borra si no tiene precio.
CREATE OR REPLACE FUNCTION tienda.trg_inventario_despues() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.requiere_reparacion AND NOT EXISTS (SELECT 1 FROM tienda.reparaciones WHERE key = NEW.key) THEN
    INSERT INTO tienda.reparaciones (key) VALUES (NEW.key);
  END IF;
  IF NOT NEW.requiere_reparacion THEN
    DELETE FROM tienda.reparaciones WHERE key = NEW.key AND precio_reparacion = 0;
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER trg_a_inventario_antes
  BEFORE INSERT OR UPDATE ON tienda.inventario
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_inventario_antes();
CREATE TRIGGER trg_b_inventario_despues
  AFTER INSERT OR UPDATE ON tienda.inventario
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_inventario_despues();
CREATE TRIGGER trg_z_historial
  AFTER INSERT OR UPDATE OR DELETE ON tienda.inventario
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();

-- ---------- 8. Triggers de reparaciones -------------------------------------------------------
-- La edición directa se bloquea si el artículo está vendido. pg_trigger_depth() >= 2 significa
-- "lo hace el trigger de inventario" (crear/borrar la fila vacía) y eso sí se permite.
CREATE OR REPLACE FUNCTION tienda.trg_reparaciones_antes() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  v_key       text := CASE WHEN TG_OP = 'DELETE' THEN OLD.key ELSE NEW.key END;
  v_migracion boolean := coalesce(current_setting('app.migracion', true), '') = 'on';
BEGIN
  IF NOT v_migracion AND pg_trigger_depth() < 2 THEN
    IF EXISTS (SELECT 1 FROM tienda.inventario WHERE key = v_key AND estado = 'Vendido') THEN
      RAISE EXCEPTION 'El artículo % ya está vendido: no se puede cambiar la reparación. Anula la venta primero (estado = "Publicado").', v_key;
    END IF;
  END IF;
  IF TG_OP <> 'DELETE' THEN
    IF NOT EXISTS (SELECT 1 FROM tienda.inventario WHERE key = NEW.key AND requiere_reparacion) THEN
      RAISE EXCEPTION 'Primero marca requiere_reparacion = true en el artículo % para poder cargarle una reparación.', NEW.key;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
  END IF;
  RETURN OLD;
END $$;

-- Si el costo de reparación cambió, "tocar" el artículo recalcula costo_total, costo_lote y precio_minimo.
CREATE OR REPLACE FUNCTION tienda.trg_reparaciones_despues() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_key text; v_cambio boolean := false;
BEGIN
  IF TG_OP = 'INSERT' THEN v_key := NEW.key; v_cambio := NEW.precio_reparacion > 0;
  ELSIF TG_OP = 'UPDATE' THEN v_key := NEW.key; v_cambio := NEW.precio_reparacion IS DISTINCT FROM OLD.precio_reparacion;
  ELSE v_key := OLD.key; v_cambio := OLD.precio_reparacion > 0;
  END IF;
  IF v_cambio THEN
    UPDATE tienda.inventario SET costo_total = costo_total WHERE key = v_key;
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER trg_a_reparaciones_antes
  BEFORE INSERT OR UPDATE OR DELETE ON tienda.reparaciones
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_reparaciones_antes();
CREATE TRIGGER trg_b_reparaciones_despues
  AFTER INSERT OR UPDATE OR DELETE ON tienda.reparaciones
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_reparaciones_despues();
CREATE TRIGGER trg_z_historial
  AFTER INSERT OR UPDATE OR DELETE ON tienda.reparaciones
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();
CREATE TRIGGER trg_z_historial
  AFTER INSERT OR UPDATE OR DELETE ON tienda.inventario_inversion
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();

-- ---------- 9. Seguridad ----------------------------------------------------------------------
SELECT tienda.fn_asegurar_rls(t) FROM unnest(ARRAY[
  'tienda.config', 'tienda.cat_estado', 'tienda.cat_tipo', 'tienda.cat_condicion', 'tienda.cat_canal',
  'tienda.historial', 'tienda.inventario', 'tienda.reparaciones', 'tienda.socio', 'tienda.inventario_inversion'
]::regclass[]) AS t;
