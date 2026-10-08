-- =====================================================================================
-- V3 · CANALES Y COLA DE SINCRONIZACIÓN
-- =====================================================================================
-- Depende de V1. Define cómo el stock llega a Shopify / Meta y cómo entran los eventos externos.
--   publicaciones  = dónde está publicado cada producto y con qué ID externo (única fuente de verdad)
--   outbox         = cola de avisos de stock hacia los canales (la lee OutboxWorker)
--   eventos_*      = idempotencia y bandeja de webhooks entrantes
-- El aviso de stock lo encola UN solo trigger sobre inventario.cantidad: da igual si el stock
-- cambió por una venta, una anulación o una edición en el dashboard.
-- =====================================================================================

-- ---------- 1. Publicaciones por canal --------------------------------------------------------
CREATE TABLE tienda.publicaciones (
  sku                   text NOT NULL REFERENCES tienda.inventario(key) ON UPDATE CASCADE ON DELETE CASCADE,
  canal                 text NOT NULL,                       -- 'shopify', 'meta_catalogo'
  external_id           text NOT NULL,                       -- ID del producto/ítem en el canal
  external_inventory_id text,                                -- Shopify: inventory_item_id (lo pide setStock)
  publicado             boolean NOT NULL DEFAULT true,
  stock_publicado       integer NOT NULL DEFAULT 0,          -- último stock confirmado en el canal
  ultima_sincronizacion timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (sku, canal)
);
CREATE INDEX ix_publicaciones_canal ON tienda.publicaciones (canal, external_id);

-- ---------- 2. Cola de salida hacia los canales -----------------------------------------------
-- tipo = 'stock_changed' (stock hacia un canal) u otros avisos con recipient_id (reintento de mensajes).
CREATE TABLE tienda.outbox (
  event_id          text PRIMARY KEY,
  tipo              text NOT NULL DEFAULT 'stock_changed',
  sku               text NOT NULL,
  canal             text NOT NULL,                           -- canal destino
  recipient_id      text,
  version           bigint NOT NULL DEFAULT 1,               -- inventario.version al momento del cambio
  stock_objetivo    integer NOT NULL DEFAULT 0 CHECK (stock_objetivo >= 0),
  payload_json      text,
  estado            text NOT NULL DEFAULT 'PENDING',
  intentos          integer NOT NULL DEFAULT 0 CHECK (intentos >= 0),
  proxima_ejecucion timestamptz NOT NULL DEFAULT now(),
  creado_en         timestamptz NOT NULL DEFAULT now(),
  actualizado_en    timestamptz,
  ultimo_error      text,
  CONSTRAINT ck_outbox_estado CHECK (estado IN
    ('PENDING', 'SENDING', 'SENT', 'FAILED', 'DEAD', 'SKIPPED_READ_ONLY', 'SKIPPED_NOT_PUBLISHED'))
);
CREATE INDEX ix_outbox_cola ON tienda.outbox (estado, proxima_ejecucion);
-- Un solo aviso por producto, canal y versión de stock (evita duplicados)
CREATE UNIQUE INDEX uq_outbox_stock ON tienda.outbox (sku, canal, version) WHERE tipo = 'stock_changed';

-- Encola un aviso de stock hacia cada canal donde el producto está publicado.
-- app.canal_origen (lo fija la función de ventas) evita avisar al canal donde ocurrió la venta:
-- Shopify ya descontó por su cuenta. Stock a publicar = cantidad - reservas vigentes.
CREATE OR REPLACE FUNCTION tienda.fn_encolar_stock(p_key text) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
  v_origen text := lower(coalesce(current_setting('app.canal_origen', true), ''));
  v_n integer;
BEGIN
  INSERT INTO tienda.outbox (event_id, tipo, sku, canal, version, stock_objetivo)
  SELECT 'OUT-' || gen_random_uuid()::text, 'stock_changed', i.key, p.canal, i.version,
         greatest(i.cantidad - tienda.fn_reservado(i.key), 0)
    FROM tienda.inventario i
    JOIN tienda.publicaciones p ON p.sku = i.key AND p.publicado
   WHERE i.key = p_key AND lower(p.canal) <> v_origen
  ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS v_n = ROW_COUNT;
  RETURN v_n;
END $$;

CREATE OR REPLACE FUNCTION tienda.trg_inventario_stock_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM tienda.fn_encolar_stock(NEW.key);
  RETURN NULL;
END $$;

CREATE TRIGGER trg_c_inventario_stock_outbox
  AFTER UPDATE OF cantidad ON tienda.inventario
  FOR EACH ROW WHEN (OLD.cantidad IS DISTINCT FROM NEW.cantidad)
  EXECUTE FUNCTION tienda.trg_inventario_stock_outbox();

-- ---------- 3. Eventos entrantes (webhooks) ---------------------------------------------------
CREATE TABLE tienda.eventos_procesados (                   -- idempotencia de webhooks (ExposedProcessedEventStore)
  canal             text NOT NULL,
  evento_externo_id text NOT NULL,
  estado            text NOT NULL DEFAULT 'DONE',          -- PROCESSING | DONE | FAILED
  procesado_en      timestamptz NOT NULL DEFAULT now(),
  actualizado_en    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (canal, evento_externo_id)
);

CREATE TABLE tienda.eventos_entrantes (                    -- bandeja durable (InboundWebhookWorker)
  id                text PRIMARY KEY,
  canal             text NOT NULL,
  evento_externo_id text NOT NULL,
  payload           jsonb NOT NULL,
  estado            text NOT NULL DEFAULT 'RECIBIDO',
  intentos          integer NOT NULL DEFAULT 0,
  proxima_ejecucion timestamptz NOT NULL DEFAULT now(),
  error_msg         text,
  creado_en         timestamptz NOT NULL DEFAULT now(),
  procesado_en      timestamptz,
  CONSTRAINT uq_evento_canal_externo UNIQUE (canal, evento_externo_id)
);
CREATE INDEX ix_eventos_entrantes_estado ON tienda.eventos_entrantes (estado, proxima_ejecucion);

CREATE TABLE tienda.eventos_sin_resolver (                 -- ventas/cancelaciones que no se pudieron aplicar (SIN_STOCK, etc.)
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  canal       text NOT NULL,
  tipo        text NOT NULL,
  referencia  text,
  sku         text,
  motivo      text NOT NULL,
  payload     jsonb NOT NULL,
  estado      text NOT NULL DEFAULT 'PENDIENTE',
  creado_en   timestamptz NOT NULL DEFAULT now(),
  resuelto_en timestamptz,
  CONSTRAINT ck_esr_estado CHECK (estado IN ('PENDIENTE', 'RESUELTO', 'DESCARTADO'))
);
CREATE INDEX ix_esr_estado ON tienda.eventos_sin_resolver (estado, creado_en);

CREATE TABLE tienda.importaciones (                        -- importaciones de Excel auditables
  id           text PRIMARY KEY,
  usuario      text NOT NULL,
  fecha        timestamptz NOT NULL DEFAULT now(),
  hash_archivo text NOT NULL,
  estado       text NOT NULL DEFAULT 'PENDING',
  conteos      jsonb,
  errores      jsonb
);

-- ---------- 4. Seguridad ----------------------------------------------------------------------
SELECT tienda.fn_asegurar_rls(t) FROM unnest(ARRAY[
  'tienda.publicaciones', 'tienda.outbox', 'tienda.eventos_procesados', 'tienda.eventos_entrantes',
  'tienda.eventos_sin_resolver', 'tienda.importaciones'
]::regclass[]) AS t;
