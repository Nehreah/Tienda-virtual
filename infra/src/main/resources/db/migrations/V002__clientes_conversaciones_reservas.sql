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
    DELETE FROM tienda.conversacion_mensajes 
     WHERE conversation_id IN (SELECT conversation_id FROM tienda.conversaciones WHERE cliente_id = p_cliente_id);
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
  atencion               text NOT NULL DEFAULT 'BOT' CHECK (atencion IN ('BOT', 'HUMANO')),
  atendido_por           text,
  atencion_desde         timestamptz,
  requiere_atencion      boolean NOT NULL DEFAULT false,
  ultimo_entrante_en     timestamptz,
  last_updated           timestamptz NOT NULL DEFAULT now(),
  created_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_conv_channel_customer ON tienda.conversaciones (channel, customer_id);
CREATE INDEX ix_conv_last_updated     ON tienda.conversaciones (last_updated);
CREATE INDEX ix_conv_cliente          ON tienda.conversaciones (cliente_id);
CREATE INDEX ix_conv_atencion         ON tienda.conversaciones (atencion, requiere_atencion);

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

-- ---------- 2.1 Mensajes de Conversación (Solo Inserción) ------------------------------------
CREATE TABLE tienda.conversacion_mensajes (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id text NOT NULL REFERENCES tienda.conversaciones(conversation_id) ON DELETE CASCADE,
  canal_msg_id    text,
  emisor          text NOT NULL CHECK (emisor IN ('CLIENTE', 'BOT', 'HUMANO', 'SISTEMA')),
  contenido       text NOT NULL,
  estado_envio    text NOT NULL DEFAULT 'ENVIADO' CHECK (estado_envio IN ('RECIBIDO', 'ENVIANDO', 'ENVIADO', 'FALLO')),
  error           text,
  creado_en       timestamptz NOT NULL DEFAULT now(),
  enviado_en      timestamptz
);
CREATE INDEX ix_conv_msg_conv ON tienda.conversacion_mensajes (conversation_id, id);
CREATE UNIQUE INDEX uq_conv_msg_canal ON tienda.conversacion_mensajes (conversation_id, canal_msg_id) WHERE canal_msg_id IS NOT NULL;

-- ---------- 2.2 Eventos de Chat para SSE en Tiempo Real ---------------------------------------
CREATE TABLE tienda.chat_eventos (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id text NOT NULL,
  tipo            text NOT NULL CHECK (tipo IN ('mensaje', 'envio', 'atencion', 'bandeja')),
  ref_id          bigint,
  creado_en       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_chat_eventos_id ON tienda.chat_eventos (id);

CREATE OR REPLACE FUNCTION tienda.trg_chat_notificar_evento() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_TABLE_NAME = 'conversacion_mensajes' THEN
    INSERT INTO tienda.chat_eventos (conversation_id, tipo, ref_id)
    VALUES (NEW.conversation_id, CASE WHEN NEW.estado_envio IN ('ENVIANDO', 'FALLO') THEN 'envio' ELSE 'mensaje' END, NEW.id);
  ELSIF TG_TABLE_NAME = 'conversaciones' THEN
    INSERT INTO tienda.chat_eventos (conversation_id, tipo, ref_id)
    VALUES (NEW.conversation_id, 'atencion', NULL);
  END IF;
  
  -- Limpieza de eventos viejos (estadísticamente 1 de cada 100 veces para no bloquear)
  IF random() < 0.01 THEN
    DELETE FROM tienda.chat_eventos WHERE creado_en < now() - interval '7 days';
  END IF;
  
  RETURN NULL;
END $$;

CREATE TRIGGER trg_chat_msg_evento
  AFTER INSERT OR UPDATE ON tienda.conversacion_mensajes
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_chat_notificar_evento();

CREATE TRIGGER trg_chat_conv_evento
  AFTER UPDATE OF atencion, requiere_atencion, state ON tienda.conversaciones
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_chat_notificar_evento();

-- ---------- 2.3 Funciones SQL para Control Humano y Mensajería -------------------------------
CREATE OR REPLACE FUNCTION tienda.tomar_conversacion(p_conv_id text, p_operador text)
RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  v_enviando boolean := false;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM tienda.conversacion_mensajes
     WHERE conversation_id = p_conv_id AND emisor = 'BOT' AND estado_envio = 'ENVIANDO'
  ) INTO v_enviando;

  UPDATE tienda.conversaciones
     SET atencion = 'HUMANO',
         atendido_por = coalesce(p_operador, 'dashboard'),
         atencion_desde = now(),
         requiere_atencion = false,
         last_updated = now()
   WHERE conversation_id = p_conv_id;

  RETURN jsonb_build_object(
    'status', 'HUMANO',
    'conversation_id', p_conv_id,
    'bot_tenia_mensaje_en_envio', v_enviando
  );
END $$;

CREATE OR REPLACE FUNCTION tienda.devolver_conversacion(
  p_conv_id text, p_operador text, p_nuevo_estado text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql AS $$
BEGIN
  UPDATE tienda.conversaciones
     SET atencion = 'BOT',
         atendido_por = NULL,
         atencion_desde = NULL,
         state = coalesce(p_nuevo_estado, CASE WHEN state = 'ESCALATED_HUMAN' THEN 'IDLE' ELSE state END),
         last_updated = now()
   WHERE conversation_id = p_conv_id;

  RETURN jsonb_build_object(
    'status', 'BOT',
    'conversation_id', p_conv_id
  );
END $$;

CREATE OR REPLACE FUNCTION tienda.registrar_mensaje_cliente(
  p_conv_id text, p_canal_msg_id text, p_contenido text
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
  v_id bigint;
BEGIN
  IF p_canal_msg_id IS NOT NULL THEN
    SELECT id INTO v_id FROM tienda.conversacion_mensajes WHERE conversation_id = p_conv_id AND canal_msg_id = p_canal_msg_id;
    IF v_id IS NOT NULL THEN RETURN v_id; END IF;
  END IF;

  INSERT INTO tienda.conversacion_mensajes (conversation_id, canal_msg_id, emisor, contenido, estado_envio, enviado_en)
  VALUES (p_conv_id, p_canal_msg_id, 'CLIENTE', p_contenido, 'RECIBIDO', now())
  RETURNING id INTO v_id;

  UPDATE tienda.conversaciones
     SET ultimo_entrante_en = now(),
         last_updated = now()
   WHERE conversation_id = p_conv_id;

  RETURN v_id;
END $$;

CREATE OR REPLACE FUNCTION tienda.registrar_mensaje_bot(
  p_conv_id text, p_contenido text
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
  v_id bigint;
  v_atencion text;
BEGIN
  SELECT atencion INTO v_atencion FROM tienda.conversaciones WHERE conversation_id = p_conv_id;
  IF v_atencion <> 'BOT' THEN
    RETURN NULL; -- Compuerta: descarte si el chat pasó a humano
  END IF;

  INSERT INTO tienda.conversacion_mensajes (conversation_id, emisor, contenido, estado_envio)
  VALUES (p_conv_id, 'BOT', p_contenido, 'ENVIANDO')
  RETURNING id INTO v_id;

  RETURN v_id;
END $$;

CREATE OR REPLACE FUNCTION tienda.registrar_mensaje_humano(
  p_conv_id text, p_contenido text, p_operador text
) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
  v_id bigint;
  v_atencion text;
BEGIN
  SELECT atencion INTO v_atencion FROM tienda.conversaciones WHERE conversation_id = p_conv_id;
  IF v_atencion <> 'HUMANO' THEN
    RAISE EXCEPTION 'El chat % no está en modo de atención HUMANO.', p_conv_id;
  END IF;

  INSERT INTO tienda.conversacion_mensajes (conversation_id, emisor, contenido, estado_envio)
  VALUES (p_conv_id, 'HUMANO', p_contenido, 'ENVIANDO')
  RETURNING id INTO v_id;

  UPDATE tienda.conversaciones
     SET requiere_atencion = false,
         last_updated = now()
   WHERE conversation_id = p_conv_id;

  RETURN v_id;
END $$;

CREATE OR REPLACE FUNCTION tienda.marcar_envio(
  p_msg_id bigint, p_estado text, p_error text DEFAULT NULL, p_canal_msg_id text DEFAULT NULL
) RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
  UPDATE tienda.conversacion_mensajes
     SET estado_envio = p_estado,
         error = p_error,
         canal_msg_id = coalesce(p_canal_msg_id, canal_msg_id),
         enviado_en = CASE WHEN p_estado = 'ENVIADO' THEN now() ELSE enviado_en END
   WHERE id = p_msg_id;
  RETURN FOUND;
END $$;

-- Vista para la Bandeja de Conversaciones en Dashboard
CREATE OR REPLACE VIEW tienda.v_bandeja AS
SELECT c.conversation_id,
       c.channel,
       c.customer_id,
       c.state,
       c.atencion,
       c.atendido_por,
       c.atencion_desde,
       c.requiere_atencion,
       c.ultimo_entrante_en,
       c.active_product_key,
       c.agreed_price_cop,
       cl.display AS cliente_nombre,
       cl.telefono AS cliente_telefono,
       m.contenido AS ultimo_mensaje,
       m.emisor AS ultimo_emisor,
       m.creado_en AS ultimo_mensaje_en
  FROM tienda.conversaciones c
  LEFT JOIN tienda.cliente cl ON c.cliente_id = cl.cliente_id
  LEFT JOIN LATERAL (
    SELECT contenido, emisor, creado_en
      FROM tienda.conversacion_mensajes
     WHERE conversation_id = c.conversation_id
     ORDER BY id DESC
     LIMIT 1
  ) m ON true;

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
  'tienda.cliente', 'tienda.conversaciones', 'tienda.conversacion_mensajes', 'tienda.chat_eventos', 'tienda.reservas'
]::regclass[]) AS t;
