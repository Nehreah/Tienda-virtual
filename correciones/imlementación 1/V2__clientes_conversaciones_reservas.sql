-- =====================================================================================
-- V2 · CLIENTES, CONVERSACIONES Y RESERVAS
-- =====================================================================================
-- Depende de V1. Aquí vive todo lo que ocurre ANTES de vender por chat:
-- quién escribe (cliente), la conversación y la reserva temporal del producto.
-- El cliente se crea solo cuando se guarda una conversación (trigger): el código no tiene
-- que llamar a nada extra y la tabla nunca queda desfasada.
-- =====================================================================================

-- ---------- 1. Clientes -----------------------------------------------------------------------
CREATE TABLE tienda.cliente (
  cliente_id        text PRIMARY KEY,                       -- "canal:idDelCanal", ej. whatsapp:573001234567
  canal             text NOT NULL REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE,
  display           text,
  telefono          text,
  email             text,
  consentimiento_en timestamptz,                            -- cuándo aceptó la política de privacidad
  anonimizado       boolean NOT NULL DEFAULT false,
  creado_en         timestamptz NOT NULL DEFAULT now(),
  ultimo_msg        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_cliente_canal ON tienda.cliente (canal);

-- Crea o actualiza un contacto. Respeta a los anonimizados y nunca borra un dato con NULL.
CREATE OR REPLACE FUNCTION tienda.upsert_cliente(
  p_cliente_id text, p_canal text, p_display text DEFAULT NULL,
  p_telefono text DEFAULT NULL, p_email text DEFAULT NULL
) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE v_canal text := lower(trim(p_canal));
BEGIN
  IF p_cliente_id IS NULL OR btrim(p_cliente_id) = '' THEN RETURN false; END IF;
  IF NOT EXISTS (SELECT 1 FROM tienda.cat_canal WHERE canal = v_canal) THEN RETURN false; END IF;

  INSERT INTO tienda.cliente (cliente_id, canal, display, telefono, email)
  VALUES (p_cliente_id, v_canal, p_display, p_telefono, p_email)
  ON CONFLICT (cliente_id) DO UPDATE SET
    display    = CASE WHEN tienda.cliente.anonimizado THEN tienda.cliente.display
                      ELSE coalesce(EXCLUDED.display,  tienda.cliente.display)  END,
    telefono   = CASE WHEN tienda.cliente.anonimizado THEN NULL
                      ELSE coalesce(EXCLUDED.telefono, tienda.cliente.telefono) END,
    email      = CASE WHEN tienda.cliente.anonimizado THEN NULL
                      ELSE coalesce(EXCLUDED.email,    tienda.cliente.email)    END,
    ultimo_msg = now();
  RETURN true;
END $$;

-- Habeas Data: borra los datos personales del cliente (y, si se pide, los mensajes de sus chats).
-- La venta y sus montos quedan intactos: solo se pierde el vínculo con la persona.
CREATE OR REPLACE FUNCTION tienda.anonimizar_cliente(p_cliente_id text, p_borrar_mensajes boolean DEFAULT true)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
  UPDATE tienda.cliente
     SET display = 'CLIENTE_ANONIMIZADO', telefono = NULL, email = NULL, anonimizado = true
   WHERE cliente_id = p_cliente_id;
  IF NOT FOUND THEN RETURN false; END IF;
  IF p_borrar_mensajes THEN
    UPDATE tienda.conversaciones SET mensajes = '[]'::jsonb WHERE cliente_id = p_cliente_id;
  END IF;
  RETURN true;
END $$;

-- ---------- 2. Conversaciones -----------------------------------------------------------------
CREATE TABLE tienda.conversaciones (
  conversation_id        text PRIMARY KEY,                  -- "canal:idCliente"
  customer_id            text NOT NULL,
  channel                text NOT NULL,
  cliente_id             text REFERENCES tienda.cliente(cliente_id) ON UPDATE CASCADE ON DELETE SET NULL,
  state                  text NOT NULL DEFAULT 'IDLE',
  active_product_key     text,
  agreed_price_cop       bigint,
  counter_offer_count    integer NOT NULL DEFAULT 0,
  pending_counter_cop    bigint,
  pending_counter_at     timestamptz,
  current_reservation_id text,
  mensajes               jsonb,
  last_updated           timestamptz NOT NULL DEFAULT now(),
  created_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_conv_channel_customer ON tienda.conversaciones (channel, customer_id);
CREATE INDEX ix_conv_last_updated     ON tienda.conversaciones (last_updated);
CREATE INDEX ix_conv_cliente          ON tienda.conversaciones (cliente_id);

-- Al guardar una conversación se registra (o refresca) su cliente automáticamente.
CREATE OR REPLACE FUNCTION tienda.trg_conversacion_cliente() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_canal text := lower(trim(NEW.channel));
BEGIN
  IF EXISTS (SELECT 1 FROM tienda.cat_canal WHERE canal = v_canal) THEN
    NEW.cliente_id := v_canal || ':' || NEW.customer_id;
    PERFORM tienda.upsert_cliente(NEW.cliente_id, v_canal, NULL,
                                  CASE WHEN v_canal = 'whatsapp' THEN NEW.customer_id END, NULL);
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_a_conversacion_cliente
  BEFORE INSERT OR UPDATE ON tienda.conversaciones
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_conversacion_cliente();

-- ---------- 3. Reservas -----------------------------------------------------------------------
CREATE TABLE tienda.reservas (
  reservation_id  text PRIMARY KEY,
  conversation_id text NOT NULL,
  product_key     text NOT NULL REFERENCES tienda.inventario(key) ON UPDATE CASCADE,
  quantity        integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
  status          text NOT NULL DEFAULT 'ACTIVE',
  expires_at      timestamptz NOT NULL,
  order_ref       text,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_reservas_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'CONSUMED'))
);
CREATE INDEX ix_reservas_conversation ON tienda.reservas (conversation_id);
CREATE INDEX ix_reservas_product      ON tienda.reservas (product_key, status);
CREATE INDEX ix_reservas_vencimiento  ON tienda.reservas (expires_at) WHERE status = 'ACTIVE';
-- Una conversación no puede tener dos reservas activas del mismo producto
CREATE UNIQUE INDEX uq_reserva_activa ON tienda.reservas (conversation_id, product_key) WHERE status = 'ACTIVE';

-- Unidades retenidas por reservas vigentes (ACTIVE sin vencer o CONFIRMED), sin contar p_excluir.
CREATE OR REPLACE FUNCTION tienda.fn_reservado(p_key text, p_excluir text DEFAULT NULL)
RETURNS integer LANGUAGE sql STABLE AS $$
  SELECT coalesce(sum(quantity), 0)::int
    FROM tienda.reservas
   WHERE product_key = p_key
     AND reservation_id IS DISTINCT FROM p_excluir
     AND (status = 'CONFIRMED' OR (status = 'ACTIVE' AND expires_at > now()))
$$;

-- Stock calculado: lo que realmente se puede ofrecer = cantidad física - reservas vigentes.
CREATE OR REPLACE VIEW tienda.v_stock AS
SELECT i.key, i.item, i.estado,
       i.cantidad                                      AS stock_fisico,
       tienda.fn_reservado(i.key)                      AS reservado,
       greatest(i.cantidad - tienda.fn_reservado(i.key), 0) AS disponible
  FROM tienda.inventario i;

-- ---------- 4. Seguridad ----------------------------------------------------------------------
SELECT tienda.fn_asegurar_rls(t) FROM unnest(ARRAY[
  'tienda.cliente', 'tienda.conversaciones', 'tienda.reservas'
]::regclass[]) AS t;
