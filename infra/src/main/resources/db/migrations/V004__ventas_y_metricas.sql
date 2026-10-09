-- =====================================================================================
-- V4 · VENTAS Y MÉTRICAS
-- =====================================================================================
-- Depende de V1, V2 y V3. Todas las ventas, de cualquier canal, entran por UNA función:
--   tienda.registrar_venta_linea(...)   y se corrigen con   tienda.anular_venta_linea(...)
-- Una fila de `ventas` por LÍNEA vendida (un producto de varias unidades puede venderse varias veces).
-- Las métricas salen de `ventas`, `venta_reparto` y las vistas v_*; nada se calcula en memoria.
--
-- Reglas:
--   * Las ventas solo se escriben con estas funciones (un trigger bloquea el INSERT/UPDATE/DELETE directo).
--     Las funciones fijan app.venta_interna con set_config(..., true) (local a la transacción) y la restauran.
--   * Idempotencia: por (canal, pedido, línea), por (deal, producto) o por (canal, evento, producto, línea),
--     entre ventas vigentes. NO se usa eventos_procesados (esa tabla es de la capa de webhooks).
--   * Fecha por defecto = fecha de Colombia (America/Bogota), no la de UTC del servidor.
--   * Costo congelado al vender: costo_unitario = inventario.costo_total (por unidad) en ese momento.
--   * Si no hay socios o no suman 100, la venta se registra igual; el reparto se normaliza entre los socios que
--     existan (fn_repartir). Los artículos con reparto incompleto se listan en tienda.v_reparto_pendiente (V1) y
--     en verificar_integridad(); config.exigir_reparto = 1 impide vender a mano o por chat sin reparto completo.
-- =====================================================================================

-- ---------- 1. Ventas (una fila por línea) -----------------------------------------------------
CREATE TABLE tienda.ventas (
  venta_id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  key              text    NOT NULL REFERENCES tienda.inventario(key) ON UPDATE CASCADE,
  fecha_venta      date    NOT NULL,
  cantidad         integer NOT NULL CHECK (cantidad > 0),
  precio_unitario  bigint  NOT NULL CHECK (precio_unitario >= 0),   -- neto, por unidad
  vendido_en       bigint  NOT NULL,                                -- = precio_unitario (compatibilidad)
  ingresos         bigint  NOT NULL,                                -- cantidad x precio_unitario
  costo_unitario   bigint  NOT NULL,                                -- congelado al vender
  costo_total      bigint  NOT NULL,                                -- costo DE LA LÍNEA = costo_unitario x cantidad
  ganancia_neta    bigint  NOT NULL,                                -- puede ser negativa
  margen_pct       numeric(7,2),
  tiempo_dias      integer CHECK (tiempo_dias IS NULL OR tiempo_dias >= 0),       -- desde la compra
  dias_publicado   integer CHECK (dias_publicado IS NULL OR dias_publicado >= 0), -- desde la publicación
  periodo          text,
  precio_publicado bigint,
  canal            text    REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE,
  origen           text    NOT NULL CHECK (origen IN ('chat', 'shopify', 'manual', 'migracion')),
  deal_id          text,
  order_id         text,
  line_id          text,
  event_id         text,
  reserva_id       text,
  cliente_id       text    REFERENCES tienda.cliente(cliente_id) ON UPDATE CASCADE ON DELETE SET NULL,
  anulada_en       timestamptz,
  anulada_motivo   text,
  estado_logistico text    NOT NULL DEFAULT 'SIN_ENVIO' CHECK (estado_logistico IN ('SIN_ENVIO', 'PENDIENTE', 'PENDIENTE_ENVIO', 'EN_PREPARACION', 'ENVIADO', 'ENTREGADO', 'CANCELADO')),
  fecha_envio      timestamptz,
  fecha_entrega    timestamptz,
  guia_envio       text,
  transportadora   text,
  nota             text,
  canal_detalle    text,
  sync_version     bigint  NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ventas_key     ON tienda.ventas (key);
CREATE INDEX ix_ventas_fecha   ON tienda.ventas (fecha_venta) WHERE anulada_en IS NULL;
CREATE INDEX ix_ventas_cliente ON tienda.ventas (cliente_id)  WHERE anulada_en IS NULL;
-- Llaves de idempotencia entre ventas vigentes (una anulada libera su llave)
CREATE UNIQUE INDEX uq_ventas_order_line ON tienda.ventas (coalesce(canal, ''), order_id, coalesce(line_id, ''))
  WHERE order_id IS NOT NULL AND anulada_en IS NULL;
CREATE UNIQUE INDEX uq_ventas_deal ON tienda.ventas (deal_id, key)
  WHERE deal_id IS NOT NULL AND anulada_en IS NULL;
CREATE UNIQUE INDEX uq_ventas_event ON tienda.ventas (coalesce(canal, ''), event_id, key, coalesce(line_id, ''))
  WHERE event_id IS NOT NULL AND anulada_en IS NULL;

COMMENT ON COLUMN tienda.ventas.costo_total IS 'Costo de la LÍNEA (costo_unitario x cantidad). Ojo: en inventario, costo_total es por unidad.';

-- Reparto de cada línea entre los socios que tenían inversión en el artículo.
CREATE TABLE tienda.venta_reparto (
  venta_id  bigint NOT NULL REFERENCES tienda.ventas(venta_id) ON DELETE CASCADE,
  key       text   NOT NULL,
  socio_id  bigint NOT NULL REFERENCES tienda.socio(id) ON DELETE RESTRICT,
  pct       numeric(5,2) NOT NULL CHECK (pct > 0 AND pct <= 100),
  inversion bigint NOT NULL CHECK (inversion >= 0),   -- costo de la línea que le tocó
  ingresos  bigint NOT NULL CHECK (ingresos >= 0),
  ganancia  bigint NOT NULL,                          -- ingresos - inversion (suma exacta por venta)
  PRIMARY KEY (venta_id, socio_id)
);
CREATE INDEX ix_venta_reparto_socio ON tienda.venta_reparto (socio_id);
CREATE INDEX ix_venta_reparto_key   ON tienda.venta_reparto (key);

-- Solo las funciones de ventas (o una carga de migración) pueden escribir aquí.
CREATE OR REPLACE FUNCTION tienda.trg_ventas_solo_funciones() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF coalesce(current_setting('app.venta_interna', true), '') <> 'on'
     AND coalesce(current_setting('app.migracion', true), '') <> 'on' THEN
    RAISE EXCEPTION 'tienda.% no se edita directo. Usa tienda.registrar_venta_linea / tienda.anular_venta_linea.', TG_TABLE_NAME;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_a_ventas_solo_funciones
  BEFORE INSERT OR UPDATE OR DELETE ON tienda.ventas
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_ventas_solo_funciones();
CREATE TRIGGER trg_a_venta_reparto_solo_funciones
  BEFORE INSERT OR UPDATE OR DELETE ON tienda.venta_reparto
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_ventas_solo_funciones();
CREATE TRIGGER trg_z_historial
  AFTER INSERT OR UPDATE OR DELETE ON tienda.ventas
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();
CREATE TRIGGER trg_z_historial
  AFTER INSERT OR UPDATE OR DELETE ON tienda.venta_reparto
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_historial_generico();

-- ---------- 2. Registrar una línea de venta ---------------------------------------------------
-- p_origen: 'chat' | 'shopify' | 'manual' | 'migracion'. p_canal: whatsapp, instagram, messenger, shopify, ...
-- Devuelve JSONB con status: SUCCESS | ALREADY_PROCESSED | SIN_STOCK | NO_ENCONTRADO | NO_DISPONIBLE |
--                            FECHA_INVALIDA | DATOS_INVALIDOS
-- Bloquea la fila del producto, comprueba idempotencia y stock, inserta la venta y su reparto, descuenta el stock,
-- consume la reserva y, si la cantidad llega a 0, marca el artículo Vendido.
-- Para origen 'shopify' solo cuenta el stock físico (la venta ya ocurrió); para chat y manual también se
-- respetan las reservas ajenas. No se avisa al canal donde ocurrió la venta (app.canal_origen).
CREATE OR REPLACE FUNCTION tienda.registrar_venta_linea(
  p_key text, p_cantidad integer, p_precio_unit bigint,
  p_canal text DEFAULT NULL, p_fecha date DEFAULT NULL, p_origen text DEFAULT 'chat',
  p_event_id text DEFAULT NULL, p_deal_id text DEFAULT NULL, p_order_id text DEFAULT NULL, p_line_id text DEFAULT NULL,
  p_reserva_id text DEFAULT NULL, p_cliente_id text DEFAULT NULL, p_usuario text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  i            tienda.inventario%ROWTYPE;
  v_res        tienda.reservas%ROWTYPE;
  v_hay_res    boolean := false;
  v_canal      text := nullif(lower(btrim(p_canal)), '');
  v_origen     text := lower(coalesce(nullif(btrim(p_origen), ''), 'chat'));
  v_event      text := nullif(btrim(p_event_id), '');
  v_deal       text := nullif(btrim(p_deal_id), '');
  v_order      text := nullif(btrim(p_order_id), '');
  v_line       text := nullif(btrim(p_line_id), '');
  v_reserva    text := nullif(btrim(p_reserva_id), '');
  v_cliente    text := nullif(btrim(p_cliente_id), '');
  v_migracion  boolean := coalesce(current_setting('app.migracion', true), '') = 'on';
  v_prev_int   text := coalesce(current_setting('app.venta_interna', true), '');
  v_prev_ori   text := coalesce(current_setting('app.canal_origen', true), '');
  v_fecha      date;
  v_disponible integer;
  v_id         bigint;
  v_ing        bigint;
  v_costo      bigint;
  v_gan        bigint;
  v_tiempo     integer;
  v_dias_pub   integer;
  v_restante   integer;
  v_n_reparto  integer := 0;
BEGIN
  IF p_cantidad IS NULL OR p_cantidad <= 0 OR p_precio_unit IS NULL OR p_precio_unit < 0
     OR v_origen NOT IN ('chat', 'shopify', 'manual', 'migracion') THEN
    RETURN jsonb_build_object('status', 'DATOS_INVALIDOS');
  END IF;

  -- Candado del producto: desde aquí las ventas del mismo producto se serializan.
  SELECT * INTO i FROM tienda.inventario WHERE key = p_key FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('status', 'NO_ENCONTRADO', 'key', p_key);
  END IF;

  -- Idempotencia (bajo el candado, sin carreras)
  SELECT venta_id INTO v_id FROM tienda.ventas
   WHERE anulada_en IS NULL
     AND ( (v_order IS NOT NULL AND order_id = v_order AND coalesce(line_id, '') = coalesce(v_line, '')
            AND coalesce(canal, '') = coalesce(v_canal, ''))
        OR (v_deal  IS NOT NULL AND deal_id = v_deal AND key = i.key)
        OR (v_event IS NOT NULL AND event_id = v_event AND key = i.key AND coalesce(line_id, '') = coalesce(v_line, '')
            AND coalesce(canal, '') = coalesce(v_canal, '')) )
   LIMIT 1;
  IF FOUND THEN
    RETURN jsonb_build_object('status', 'ALREADY_PROCESSED', 'venta_id', v_id, 'key', i.key);
  END IF;

  -- Disponibilidad del artículo (la venta de Shopify ya ocurrió: solo importa el stock físico)
  IF v_origen <> 'shopify' AND (i.archivado_en IS NOT NULL OR i.estado = 'Inactivo') THEN
    RETURN jsonb_build_object('status', 'NO_DISPONIBLE', 'key', i.key, 'estado', i.estado);
  END IF;

  IF v_reserva IS NOT NULL THEN
    SELECT * INTO v_res FROM tienda.reservas
     WHERE reservation_id = v_reserva AND product_key = i.key AND status IN ('ACTIVE', 'CONFIRMED')
     FOR UPDATE;
    v_hay_res := FOUND;
  END IF;

  IF v_origen = 'shopify' THEN
    v_disponible := i.cantidad;
  ELSE
    v_disponible := i.cantidad - tienda.fn_reservado(i.key, CASE WHEN v_hay_res THEN v_res.reservation_id END);
  END IF;
  IF v_disponible < p_cantidad THEN
    RETURN jsonb_build_object('status', 'SIN_STOCK', 'key', i.key, 'requested', p_cantidad,
                              'disponible', greatest(v_disponible, 0));
  END IF;

  -- Fecha (Colombia) y coherencia con las fechas del artículo
  v_fecha := coalesce(p_fecha, (now() AT TIME ZONE 'America/Bogota')::date);
  IF NOT v_migracion AND ( (i.fecha_compra IS NOT NULL AND v_fecha < i.fecha_compra)
                        OR (i.fecha_publicacion IS NOT NULL AND v_fecha < i.fecha_publicacion) ) THEN
    RETURN jsonb_build_object('status', 'FECHA_INVALIDA', 'key', i.key, 'fecha', v_fecha,
                              'fecha_compra', i.fecha_compra, 'fecha_publicacion', i.fecha_publicacion);
  END IF;

  -- Cliente: el recibido o, si hay reserva, el de su conversación (solo si existe en `cliente`)
  IF v_cliente IS NULL AND v_hay_res THEN
    SELECT cliente_id INTO v_cliente FROM tienda.conversaciones WHERE conversation_id = v_res.conversation_id;
  END IF;
  IF v_cliente IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tienda.cliente WHERE cliente_id = v_cliente) THEN
    v_cliente := NULL;
  END IF;

  IF v_canal IS NOT NULL THEN
    INSERT INTO tienda.cat_canal (canal) VALUES (v_canal) ON CONFLICT DO NOTHING;
  END IF;

  -- Cálculos (costo congelado: por unidad del artículo en este momento)
  v_ing      := p_cantidad::bigint * p_precio_unit;
  v_costo    := p_cantidad::bigint * i.costo_total;
  v_gan      := v_ing - v_costo;
  v_tiempo   := CASE WHEN i.fecha_compra      IS NULL THEN NULL ELSE greatest(v_fecha - i.fecha_compra, 0) END;
  v_dias_pub := CASE WHEN i.fecha_publicacion IS NULL THEN NULL ELSE greatest(v_fecha - i.fecha_publicacion, 0) END;

  PERFORM set_config('app.venta_interna', 'on', true);
  PERFORM set_config('app.canal_origen', coalesce(v_canal, ''), true);

  INSERT INTO tienda.ventas (key, fecha_venta, cantidad, precio_unitario, vendido_en, ingresos, costo_unitario,
                             costo_total, ganancia_neta, margen_pct, tiempo_dias, dias_publicado, periodo,
                             precio_publicado, canal, origen, deal_id, order_id, line_id, event_id, reserva_id, cliente_id)
  VALUES (i.key, v_fecha, p_cantidad, p_precio_unit, p_precio_unit, v_ing, i.costo_total,
          v_costo, v_gan,
          CASE WHEN v_ing = 0 THEN NULL ELSE round(v_gan::numeric * 100 / v_ing, 2) END,
          v_tiempo, v_dias_pub, tienda.fn_periodo(v_tiempo),
          i.precio_publicado, v_canal, v_origen, v_deal, v_order, v_line, v_event, v_reserva, v_cliente)
  RETURNING venta_id INTO v_id;

  -- Reparto entre socios (mayor residuo): ingresos e inversión se reparten; ganancia = ingresos - inversión
  INSERT INTO tienda.venta_reparto (venta_id, key, socio_id, pct, inversion, ingresos, ganancia)
  SELECT v_id, i.key, ii.socio_id, ii.pct, c.monto, g.monto, g.monto - c.monto
    FROM tienda.inventario_inversion ii
    JOIN tienda.fn_repartir(v_ing,   i.key) g ON g.socio_id = ii.socio_id
    JOIN tienda.fn_repartir(v_costo, i.key) c ON c.socio_id = ii.socio_id
   WHERE ii.key = i.key AND ii.pct > 0;
  GET DIAGNOSTICS v_n_reparto = ROW_COUNT;

  -- Descontar stock; si no queda ninguna unidad, el artículo pasa a Vendido
  v_restante := i.cantidad - p_cantidad;
  UPDATE tienda.inventario
     SET cantidad    = v_restante,
         fecha_venta = v_fecha,
         vendido_en  = p_precio_unit,
         canal_venta = v_canal,
         estado      = CASE WHEN v_restante = 0 THEN 'Vendido' ELSE estado END
   WHERE key = i.key;

  IF v_hay_res THEN
    UPDATE tienda.reservas SET status = 'CONSUMED', updated_at = now() WHERE reservation_id = v_res.reservation_id;
  END IF;

  PERFORM set_config('app.venta_interna', v_prev_int, true);
  PERFORM set_config('app.canal_origen', v_prev_ori, true);

  RETURN jsonb_build_object('status', 'SUCCESS', 'venta_id', v_id, 'key', i.key, 'quantity_sold', p_cantidad,
                            'remaining_total', v_restante, 'ingresos', v_ing, 'ganancia', v_gan,
                            'reparto_socios', v_n_reparto);
END $$;

-- ---------- 3. Anular líneas de venta ----------------------------------------------------------
-- Modos (el primero que se pase): p_venta_id | (p_order_id [+ p_canal] [+ p_line_id]; sin línea = pedido completo) |
-- p_key (solo la ÚLTIMA línea vigente de ese producto; para otras, usa p_venta_id).
-- Marca la anulación (no borra la venta), borra su reparto y devuelve las unidades al stock. Si el artículo estaba
-- Vendido, se reabre como Publicado (o Comprado si su precio publicado no llega al mínimo).
-- Aquí NO se suprime el aviso al canal de origen: se publica el stock real y, si el canal ya repuso por su cuenta,
-- el valor coincide.
-- status: SUCCESS | VENTA_NO_ENCONTRADA | DATOS_INVALIDOS
CREATE OR REPLACE FUNCTION tienda.anular_venta_linea(
  p_venta_id bigint DEFAULT NULL, p_canal text DEFAULT NULL, p_order_id text DEFAULT NULL, p_line_id text DEFAULT NULL,
  p_key text DEFAULT NULL, p_motivo text DEFAULT NULL, p_usuario text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  v_canal    text := nullif(lower(btrim(p_canal)), '');
  v_order    text := nullif(btrim(p_order_id), '');
  v_line     text := nullif(btrim(p_line_id), '');
  v_key      text := nullif(btrim(p_key), '');
  v_motivo   text := coalesce(nullif(btrim(p_motivo), ''), 'anulada');
  v_prev_int text := coalesce(current_setting('app.venta_interna', true), '');
  r          record;
  i          tienda.inventario%ROWTYPE;
  v_estado   text;
  v_f        date;
  v_p        bigint;
  v_c        text;
  v_n        integer := 0;
  v_unidades integer := 0;
BEGIN
  IF p_venta_id IS NULL AND v_order IS NULL AND v_key IS NULL THEN
    RETURN jsonb_build_object('status', 'DATOS_INVALIDOS');
  END IF;

  PERFORM set_config('app.venta_interna', 'on', true);

  FOR r IN
    SELECT v.venta_id, v.key, v.cantidad
      FROM tienda.ventas v
     WHERE v.anulada_en IS NULL
       AND ( (p_venta_id IS NOT NULL AND v.venta_id = p_venta_id)
          OR (p_venta_id IS NULL AND v_order IS NOT NULL AND v.order_id = v_order
              AND (v_canal IS NULL OR v.canal = v_canal) AND (v_line IS NULL OR v.line_id = v_line))
          OR (p_venta_id IS NULL AND v_order IS NULL AND v_key IS NOT NULL
              AND v.venta_id = (SELECT max(v2.venta_id) FROM tienda.ventas v2
                                 WHERE v2.key = v_key AND v2.anulada_en IS NULL)) )
     ORDER BY v.key, v.venta_id
  LOOP
    SELECT * INTO i FROM tienda.inventario WHERE key = r.key FOR UPDATE;

    UPDATE tienda.ventas
       SET anulada_en = now(), anulada_motivo = v_motivo, updated_at = now()
     WHERE venta_id = r.venta_id AND anulada_en IS NULL;
    IF NOT FOUND THEN CONTINUE; END IF;          -- otra transacción ya la anuló

    DELETE FROM tienda.venta_reparto WHERE venta_id = r.venta_id;

    v_estado := i.estado;
    IF i.estado = 'Vendido' THEN
      v_estado := CASE WHEN i.precio_publicado IS NOT NULL
                        AND i.precio_publicado >= tienda.fn_precio_minimo(i.costo_total)
                       THEN 'Publicado' ELSE 'Comprado' END;
    END IF;
    UPDATE tienda.inventario SET cantidad = cantidad + r.cantidad, estado = v_estado WHERE key = r.key;

    -- Los datos de "última venta" del artículo pasan a ser los de la última línea vigente (o quedan vacíos)
    v_f := NULL; v_p := NULL; v_c := NULL;
    SELECT fecha_venta, precio_unitario, canal INTO v_f, v_p, v_c
      FROM tienda.ventas WHERE key = r.key AND anulada_en IS NULL ORDER BY venta_id DESC LIMIT 1;
    UPDATE tienda.inventario SET fecha_venta = v_f, vendido_en = v_p, canal_venta = v_c WHERE key = r.key;

    v_n := v_n + 1;
    v_unidades := v_unidades + r.cantidad;
  END LOOP;

  PERFORM set_config('app.venta_interna', v_prev_int, true);

  IF v_n = 0 THEN
    RETURN jsonb_build_object('status', 'VENTA_NO_ENCONTRADA');
  END IF;
  RETURN jsonb_build_object('status', 'SUCCESS', 'lineas_anuladas', v_n, 'unidades_devueltas', v_unidades);
END $$;

-- ---------- 4. Compatibilidad con el código actual (mismas firmas) ------------------------------
-- El Kotlin de hoy llama a estas funciones y solo mira si el JSON contiene "SUCCESS".
-- vender_unidades: p_origen es el canal (shopify, whatsapp, ...); las cadenas vacías cuentan como NULL.
CREATE OR REPLACE FUNCTION tienda.vender_unidades(
  p_key text, p_qty integer, p_event_id text, p_origen text, p_precio_unit bigint,
  p_reserva_id text DEFAULT NULL, p_order_id text DEFAULT NULL, p_line_id text DEFAULT NULL
) RETURNS jsonb LANGUAGE sql AS $$
  SELECT tienda.registrar_venta_linea(
    p_key => p_key, p_cantidad => p_qty, p_precio_unit => p_precio_unit,
    p_canal => p_origen,
    p_origen => CASE WHEN lower(btrim(coalesce(p_origen, ''))) = 'shopify' THEN 'shopify' ELSE 'chat' END,
    p_event_id => p_event_id, p_order_id => p_order_id, p_line_id => p_line_id, p_reserva_id => p_reserva_id)
$$;

-- devolver_unidades: anula la línea completa (p_qty se ignora: una devolución parcial no está soportada).
CREATE OR REPLACE FUNCTION tienda.devolver_unidades(p_order_id text, p_line_id text, p_qty integer)
RETURNS jsonb LANGUAGE sql AS $$
  SELECT CASE WHEN nullif(btrim(p_order_id), '') IS NULL THEN jsonb_build_object('status', 'INVALID_ORDER_ID')
              ELSE tienda.anular_venta_linea(p_canal => 'shopify', p_order_id => p_order_id, p_line_id => p_line_id,
                                             p_motivo => 'devolución / cancelación') END
$$;

-- registrar_venta / anular_venta: las funciones de comodidad de la migración anterior.
CREATE OR REPLACE FUNCTION tienda.registrar_venta(
  p_key text, p_vendido_en bigint, p_fecha date DEFAULT NULL, p_canal text DEFAULT NULL
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_cant integer; r jsonb;
BEGIN
  SELECT cantidad INTO v_cant FROM tienda.inventario WHERE key = p_key;
  IF NOT FOUND OR v_cant <= 0 THEN
    RAISE EXCEPTION 'No se puede registrar la venta de %: no existe o no tiene unidades.', p_key;
  END IF;
  r := tienda.registrar_venta_linea(p_key => p_key, p_cantidad => v_cant, p_precio_unit => p_vendido_en,
                                    p_canal => p_canal, p_fecha => p_fecha, p_origen => 'manual');
  IF r->>'status' <> 'SUCCESS' THEN
    RAISE EXCEPTION 'No se pudo registrar la venta de %: %', p_key, r::text;
  END IF;
END $$;

CREATE OR REPLACE FUNCTION tienda.anular_venta(p_key text, p_estado text DEFAULT 'Publicado')
RETURNS void LANGUAGE plpgsql AS $$
DECLARE r jsonb;
BEGIN
  IF p_estado = 'Vendido' THEN RAISE EXCEPTION 'El nuevo estado no puede ser Vendido.'; END IF;
  r := tienda.anular_venta_linea(p_key => p_key, p_motivo => 'anular_venta');
  IF r->>'status' <> 'SUCCESS' THEN
    RAISE EXCEPTION 'No se puede anular la venta de %: %', p_key, r::text;
  END IF;
  UPDATE tienda.inventario SET estado = p_estado WHERE key = p_key AND estado <> p_estado;
END $$;

-- ---------- 5. Vía manual: editar el estado en el inventario -----------------------------------
-- Marcar Vendido a mano registra una línea con las unidades que quedaban (usa fecha_venta, vendido_en y
-- canal_venta que V1 ya exige). Sacar de Vendido anula SOLO la última línea vigente (no todas).
-- Si el artículo tiene unidades reservadas por otros o ninguna unidad, la edición se rechaza con el motivo.
CREATE OR REPLACE FUNCTION tienda.trg_inventario_vendido_manual() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;
BEGIN
  IF coalesce(current_setting('app.venta_interna', true), '') = 'on'
     OR coalesce(current_setting('app.migracion', true), '') = 'on' THEN
    RETURN NULL;                                  -- lo está haciendo una función de ventas o una carga
  END IF;
  IF NEW.estado = 'Vendido' AND OLD.estado <> 'Vendido' THEN
    IF NEW.cantidad <= 0 THEN
      RAISE EXCEPTION 'El artículo % no tiene unidades para vender.', NEW.key;
    END IF;
    r := tienda.registrar_venta_linea(p_key => NEW.key, p_cantidad => NEW.cantidad, p_precio_unit => NEW.vendido_en,
                                      p_canal => NEW.canal_venta, p_fecha => NEW.fecha_venta, p_origen => 'manual',
                                      p_usuario => nullif(current_setting('app.usuario', true), ''));
    IF r->>'status' <> 'SUCCESS' THEN
      RAISE EXCEPTION 'No se pudo registrar la venta de %: %', NEW.key, r::text;
    END IF;
  ELSIF OLD.estado = 'Vendido' AND NEW.estado <> 'Vendido' THEN
    PERFORM tienda.anular_venta_linea(p_key => NEW.key, p_motivo => 'cambio manual de estado',
                                      p_usuario => nullif(current_setting('app.usuario', true), ''));
  END IF;
  RETURN NULL;
END $$;

CREATE TRIGGER trg_d_inventario_vendido_manual
  AFTER UPDATE OF estado ON tienda.inventario
  FOR EACH ROW WHEN (OLD.estado IS DISTINCT FROM NEW.estado)
  EXECUTE FUNCTION tienda.trg_inventario_vendido_manual();

-- Un artículo Vendido no puede recibir stock a mano: primero se cambia su estado (eso anula la última línea).
CREATE OR REPLACE FUNCTION tienda.trg_inventario_vendido_stock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF coalesce(current_setting('app.venta_interna', true), '') = 'on'
     OR coalesce(current_setting('app.migracion', true), '') = 'on' THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'El artículo % está Vendido: no se le puede subir la cantidad. Cambia primero su estado (p. ej. a Publicado) y luego repón el stock.', NEW.key;
END $$;

CREATE TRIGGER trg_a_vendido_stock
  BEFORE UPDATE OF cantidad ON tienda.inventario
  FOR EACH ROW WHEN (NEW.estado = 'Vendido' AND NEW.cantidad > OLD.cantidad)
  EXECUTE FUNCTION tienda.trg_inventario_vendido_stock();

-- ---------- 6. Vistas de métricas (leen solo ventas vigentes) -----------------------------------
CREATE OR REPLACE VIEW tienda.v_ventas AS
SELECT * FROM tienda.ventas WHERE anulada_en IS NULL;

CREATE OR REPLACE VIEW tienda.v_ventas_detalle AS
SELECT v.*, i.item, i.tipo, date_trunc('month', v.fecha_venta)::date AS mes
  FROM tienda.v_ventas v
  JOIN tienda.inventario i ON i.key = v.key;

-- Dinámica: une el reparto con los socios que existan hoy (sin socios queda vacía).
CREATE OR REPLACE VIEW tienda.v_resumen_mensual_socio AS
SELECT date_trunc('month', v.fecha_venta)::date AS mes,
       s.id AS socio_id, s.nombre AS socio,
       count(*)         AS ventas,
       sum(vr.ingresos) AS ingresos,
       sum(vr.ganancia) AS ganancias
  FROM tienda.v_ventas v
  JOIN tienda.venta_reparto vr ON vr.venta_id = v.venta_id
  JOIN tienda.socio s ON s.id = vr.socio_id
 GROUP BY 1, 2, 3;

CREATE OR REPLACE VIEW tienda.v_rendimiento_tipo AS
SELECT coalesce(i.tipo, 'Sin tipo')                                   AS tipo,
       count(*)                                                       AS ventas,
       sum(v.cantidad)                                                AS unidades,
       round(sum(v.tiempo_dias * v.cantidad)::numeric
             / nullif(sum(v.cantidad) FILTER (WHERE v.tiempo_dias IS NOT NULL), 0), 1)    AS dias_promedio,
       round(sum(v.dias_publicado * v.cantidad)::numeric
             / nullif(sum(v.cantidad) FILTER (WHERE v.dias_publicado IS NOT NULL), 0), 1) AS dias_publicado_promedio,
       sum(v.ganancia_neta)                                           AS ganancia_total,
       round(avg(v.ganancia_neta))                                    AS ganancia_promedio,
       round(sum(v.ganancia_neta)::numeric * 100 / nullif(sum(v.ingresos), 0), 1)          AS margen_promedio_pct,
       count(*) FILTER (WHERE v.ganancia_neta < 0)                    AS ventas_con_perdida,
       coalesce(sum(v.ganancia_neta) FILTER (WHERE v.ganancia_neta < 0), 0)                AS monto_perdidas
  FROM tienda.v_ventas v
  JOIN tienda.inventario i ON i.key = v.key
 GROUP BY 1;

CREATE OR REPLACE VIEW tienda.v_periodo_tipo AS
SELECT coalesce(i.tipo, 'Sin tipo') AS tipo, v.periodo, count(*) AS ventas, sum(v.cantidad) AS unidades
  FROM tienda.v_ventas v
  JOIN tienda.inventario i ON i.key = v.key
 GROUP BY 1, 2;

CREATE OR REPLACE VIEW tienda.v_resumen_canal AS
SELECT coalesce(v.canal, 'sin canal') AS canal, count(*) AS ventas, sum(v.cantidad) AS unidades,
       sum(v.ingresos) AS ingresos, sum(v.ganancia_neta) AS ganancia
  FROM tienda.v_ventas v
 GROUP BY 1;

-- Para /api/clientes: compras, unidades y total gastado por cliente.
CREATE OR REPLACE VIEW tienda.v_cliente_resumen AS
SELECT c.cliente_id, c.canal, c.display, c.telefono, c.email, c.consentimiento_en, c.anonimizado,
       c.creado_en, c.ultimo_msg,
       count(v.venta_id)                AS compras,
       coalesce(sum(v.cantidad), 0)     AS unidades,
       coalesce(sum(v.ingresos), 0)     AS total_gastado,
       max(v.fecha_venta)               AS ultima_compra
  FROM tienda.cliente c
  LEFT JOIN tienda.v_ventas v ON v.cliente_id = c.cliente_id
 GROUP BY c.cliente_id;

-- ---------- 6b. Funciones de Ventas Manuales y Logística ---------------------------------------
CREATE OR REPLACE FUNCTION tienda.registrar_venta_manual(
  p_key text,
  p_cantidad integer,
  p_precio_unit bigint,
  p_canal text,
  p_canal_detalle text DEFAULT NULL,
  p_fecha date DEFAULT NULL,
  p_order_id text DEFAULT NULL,
  p_idempotency_key text DEFAULT NULL,
  p_estado_logistico text DEFAULT 'SIN_ENVIO',
  p_guia_envio text DEFAULT NULL,
  p_transportadora text DEFAULT NULL,
  p_nota text DEFAULT NULL,
  p_usuario text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  v_res jsonb;
  v_venta_id bigint;
  v_canal text := lower(btrim(p_canal));
  v_version bigint;
BEGIN
  IF v_canal IS NULL OR v_canal NOT IN ('shopify', 'facebook_marketplace', 'whatsapp', 'instagram', 'messenger', 'presencial', 'otro') THEN
    RETURN jsonb_build_object('status', 'DATOS_INVALIDOS', 'message', 'Canal inválido');
  END IF;

  IF v_canal = 'shopify' AND (p_order_id IS NULL OR btrim(p_order_id) = '') THEN
    RETURN jsonb_build_object('status', 'DATOS_INVALIDOS', 'message', 'Canal shopify requiere número de pedido (order_id)');
  END IF;

  IF v_canal = 'otro' AND (p_canal_detalle IS NULL OR btrim(p_canal_detalle) = '') THEN
    RETURN jsonb_build_object('status', 'DATOS_INVALIDOS', 'message', 'Canal otro requiere canal_detalle');
  END IF;

  SELECT version INTO v_version FROM tienda.inventario WHERE key = p_key;

  v_res := tienda.registrar_venta_linea(
    p_key => p_key,
    p_cantidad => p_cantidad,
    p_precio_unit => p_precio_unit,
    p_canal => v_canal,
    p_fecha => p_fecha,
    p_origen => 'manual',
    p_event_id => p_idempotency_key,
    p_order_id => p_order_id,
    p_usuario => p_usuario
  );

  IF v_res->>'status' = 'SUCCESS' OR v_res->>'status' = 'ALREADY_PROCESSED' THEN
    v_venta_id := (v_res->>'venta_id')::bigint;
    PERFORM set_config('app.venta_interna', 'on', true);
    UPDATE tienda.ventas
       SET estado_logistico = coalesce(p_estado_logistico, 'SIN_ENVIO'),
           guia_envio = p_guia_envio,
           transportadora = p_transportadora,
           nota = p_nota,
           canal_detalle = p_canal_detalle,
           sync_version = coalesce(v_version, 1),
           updated_at = now()
     WHERE venta_id = v_venta_id;
    PERFORM set_config('app.venta_interna', '', true);
  END IF;

  RETURN v_res;
END $$;

CREATE OR REPLACE FUNCTION tienda.actualizar_logistica_venta(
  p_venta_id bigint,
  p_estado_logistico text,
  p_guia_envio text DEFAULT NULL,
  p_transportadora text DEFAULT NULL,
  p_nota text DEFAULT NULL,
  p_usuario text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  v_estado text := upper(btrim(p_estado_logistico));
  v_actual text;
BEGIN
  IF v_estado NOT IN ('SIN_ENVIO', 'PENDIENTE', 'PENDIENTE_ENVIO', 'EN_PREPARACION', 'ENVIADO', 'ENTREGADO', 'CANCELADO') THEN
    RETURN jsonb_build_object('status', 'DATOS_INVALIDOS', 'message', 'Estado logístico inválido');
  END IF;

  SELECT estado_logistico INTO v_actual FROM tienda.ventas WHERE venta_id = p_venta_id AND anulada_en IS NULL;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('status', 'VENTA_NO_ENCONTRADA');
  END IF;

  PERFORM set_config('app.venta_interna', 'on', true);
  UPDATE tienda.ventas
     SET estado_logistico = v_estado,
         guia_envio = coalesce(p_guia_envio, guia_envio),
         transportadora = coalesce(p_transportadora, transportadora),
         nota = coalesce(p_nota, nota),
         fecha_envio = CASE WHEN v_estado IN ('ENVIADO', 'ENTREGADO') AND fecha_envio IS NULL THEN now() ELSE fecha_envio END,
         fecha_entrega = CASE WHEN v_estado = 'ENTREGADO' AND fecha_entrega IS NULL THEN now() ELSE fecha_entrega END,
         updated_at = now()
   WHERE venta_id = p_venta_id;
  PERFORM set_config('app.venta_interna', '', true);

  RETURN jsonb_build_object('status', 'SUCCESS', 'venta_id', p_venta_id, 'estado_logistico', v_estado);
END $$;

CREATE OR REPLACE FUNCTION tienda.asignar_reparto_venta(
  p_venta_id bigint,
  p_porcentajes jsonb
) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE
  v_v tienda.ventas%ROWTYPE;
  v_suma numeric(7,2) := 0;
  elem record;
  v_n_reparto integer := 0;
  v_prev_int text := coalesce(current_setting('app.venta_interna', true), '');
BEGIN
  SELECT * INTO v_v FROM tienda.ventas WHERE venta_id = p_venta_id AND anulada_en IS NULL FOR UPDATE;
  IF NOT FOUND THEN
    RETURN jsonb_build_object('status', 'VENTA_NO_ENCONTRADA');
  END IF;

  IF EXISTS (SELECT 1 FROM tienda.venta_reparto WHERE venta_id = p_venta_id) THEN
    RETURN jsonb_build_object('status', 'ALREADY_PROCESSED', 'message', 'La venta ya tiene reparto asignado');
  END IF;

  FOR elem IN SELECT * FROM jsonb_to_recordset(p_porcentajes) AS (socio_id bigint, pct numeric) LOOP
    IF NOT EXISTS (SELECT 1 FROM tienda.socio WHERE id = elem.socio_id AND activo = true) THEN
      RETURN jsonb_build_object('status', 'SOCIO_INVALIDO', 'socio_id', elem.socio_id);
    END IF;
    v_suma := v_suma + elem.pct;
  END LOOP;

  IF round(v_suma, 2) <> 100.00 THEN
    RETURN jsonb_build_object('status', 'SUMA_INVALIDA', 'suma', v_suma);
  END IF;

  PERFORM set_config('app.venta_interna', 'on', true);

  WITH b AS (
    SELECT elem.socio_id, elem.pct, (v_v.ingresos::numeric * elem.pct / 100.0) AS exacto_ing, (v_v.costo_total::numeric * elem.pct / 100.0) AS exacto_costo
      FROM jsonb_to_recordset(p_porcentajes) AS elem(socio_id bigint, pct numeric)
  ),
  f AS (
    SELECT socio_id, pct,
           floor(exacto_ing)::bigint AS piso_ing, exacto_ing - floor(exacto_ing) AS resto_ing,
           floor(exacto_costo)::bigint AS piso_costo, exacto_costo - floor(exacto_costo) AS resto_costo
      FROM b
  ),
  faltan_ing AS (SELECT v_v.ingresos - coalesce(sum(piso_ing), 0) AS n FROM f),
  faltan_costo AS (SELECT v_v.costo_total - coalesce(sum(piso_costo), 0) AS n FROM f),
  o AS (
    SELECT f.*,
           row_number() OVER (ORDER BY resto_ing DESC, socio_id) AS pos_ing,
           row_number() OVER (ORDER BY resto_costo DESC, socio_id) AS pos_costo
      FROM f
  )
  INSERT INTO tienda.venta_reparto (venta_id, key, socio_id, pct, inversion, ingresos, ganancia)
  SELECT p_venta_id, v_v.key, o.socio_id, o.pct,
         o.piso_costo + CASE WHEN o.pos_costo <= (SELECT n FROM faltan_costo) THEN 1 ELSE 0 END,
         o.piso_ing + CASE WHEN o.pos_ing <= (SELECT n FROM faltan_ing) THEN 1 ELSE 0 END,
         (o.piso_ing + CASE WHEN o.pos_ing <= (SELECT n FROM faltan_ing) THEN 1 ELSE 0 END) -
         (o.piso_costo + CASE WHEN o.pos_costo <= (SELECT n FROM faltan_costo) THEN 1 ELSE 0 END)
    FROM o;

  GET DIAGNOSTICS v_n_reparto = ROW_COUNT;

  PERFORM set_config('app.venta_interna', v_prev_int, true);

  RETURN jsonb_build_object('status', 'SUCCESS', 'venta_id', p_venta_id, 'filas_reparto', v_n_reparto);
END $$;

-- Vistas adicionales de sincronización y logística
CREATE OR REPLACE VIEW tienda.v_venta_sync AS
SELECT v.venta_id, v.key, v.fecha_venta, v.canal, v.origen, v.estado_logistico, v.sync_version,
       o.event_id, o.canal AS canal_destino, o.estado AS estado_outbox, o.stock_objetivo,
       p.stock_publicado, o.ultimo_error, o.accion_manual_requerida, o.accion_manual_tipo, o.accion_manual_resuelta
  FROM tienda.ventas v
  LEFT JOIN tienda.publicaciones p ON p.sku = v.key
  LEFT JOIN tienda.outbox o ON o.sku = v.key AND o.canal = p.canal AND o.version = v.sync_version
 WHERE v.anulada_en IS NULL;

CREATE OR REPLACE VIEW tienda.v_ventas_logistica AS
SELECT coalesce(v.canal, 'sin_canal') AS canal,
       v.estado_logistico,
       count(*) AS total_ventas,
       sum(v.cantidad) AS total_unidades,
       sum(v.ingresos) AS total_ingresos,
       sum(v.ganancia_neta) AS total_ganancia
  FROM tienda.v_ventas v
 GROUP BY 1, 2;

CREATE OR REPLACE VIEW tienda.v_acciones_manuales_pendientes AS
SELECT o.event_id, o.sku, o.canal, o.version, o.stock_objetivo, o.accion_manual_tipo, o.accion_manual_detalle, o.creado_en
  FROM tienda.outbox o
 WHERE o.accion_manual_requerida = true AND o.accion_manual_resuelta = false;

CREATE OR REPLACE VIEW tienda.v_ventas_sin_reparto AS
SELECT v.*
  FROM tienda.v_ventas v
 WHERE NOT EXISTS (SELECT 1 FROM tienda.venta_reparto vr WHERE vr.venta_id = v.venta_id);

-- ---------- 7. Verificación de integridad (dashboard) -----------------------------------------
CREATE OR REPLACE FUNCTION tienda.verificar_integridad()
RETURNS TABLE (chequeo text, cantidad bigint, detalle text) LANGUAGE sql STABLE AS $$
  SELECT 'ventas sin reparto'::text, count(*)::bigint,
         'Ventas vigentes sin filas en venta_reparto (normal si todavía no hay socios).'::text
    FROM tienda.v_ventas v
   WHERE NOT EXISTS (SELECT 1 FROM tienda.venta_reparto r WHERE r.venta_id = v.venta_id)
  UNION ALL
  SELECT 'reparto que no cuadra', count(*),
         'La suma de ingresos o ganancia por socio no coincide con la venta.'
    FROM tienda.v_ventas v
    JOIN (SELECT venta_id, sum(ingresos) AS ing, sum(ganancia) AS gan
            FROM tienda.venta_reparto GROUP BY venta_id) r ON r.venta_id = v.venta_id
   WHERE r.ing <> v.ingresos OR r.gan <> v.ganancia_neta
  UNION ALL
  SELECT 'stock negativo', count(*), 'Artículos con cantidad menor que 0.'
    FROM tienda.inventario WHERE cantidad < 0
  UNION ALL
  SELECT 'vendido con stock', count(*), 'Artículos en estado Vendido que todavía tienen unidades.'
    FROM tienda.inventario WHERE estado = 'Vendido' AND cantidad > 0
  UNION ALL
  SELECT 'agotado sin estado vendido', count(*), 'Artículos sin unidades, con ventas, cuyo estado no es Vendido/Inactivo.'
    FROM tienda.inventario i
   WHERE i.cantidad = 0 AND i.estado NOT IN ('Vendido', 'Inactivo')
     AND EXISTS (SELECT 1 FROM tienda.v_ventas v WHERE v.key = i.key)
  UNION ALL
  SELECT 'reservas vencidas sin liberar', count(*), 'Reservas ACTIVE con plazo vencido (corre liberar_reservas_vencidas).'
    FROM tienda.reservas WHERE status = 'ACTIVE' AND expires_at <= now()
  UNION ALL
  SELECT 'avisos DEAD', count(*), 'Avisos de stock que agotaron sus reintentos.'
    FROM tienda.outbox WHERE estado = 'DEAD'
  UNION ALL
  SELECT 'eventos sin resolver', count(*), 'Ventas o cancelaciones que no se pudieron aplicar (p. ej. SIN_STOCK).'
    FROM tienda.eventos_sin_resolver WHERE estado = 'PENDIENTE'
  UNION ALL
  SELECT 'reparto incompleto', count(*), 'Artículos activos cuyo reparto de socios no suma 100 % (ver v_reparto_pendiente; normal si aún no hay socios).'
    FROM tienda.v_reparto_pendiente
$$;

-- ---------- 8. Seguridad ----------------------------------------------------------------------
SELECT tienda.fn_asegurar_rls(t) FROM unnest(ARRAY['tienda.ventas', 'tienda.venta_reparto']::regclass[]) AS t;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_user') THEN
    EXECUTE 'GRANT SELECT ON tienda.v_stock, tienda.v_reparto_pendiente, tienda.v_ventas, tienda.v_ventas_detalle, '
         || 'tienda.v_resumen_mensual_socio, tienda.v_rendimiento_tipo, tienda.v_periodo_tipo, '
         || 'tienda.v_resumen_canal, tienda.v_cliente_resumen, tienda.v_venta_sync, tienda.v_ventas_logistica, '
         || 'tienda.v_acciones_manuales_pendientes, tienda.v_ventas_sin_reparto TO app_user';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
    EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA tienda FROM anon';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
    EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA tienda FROM authenticated';
  END IF;
END $$;
