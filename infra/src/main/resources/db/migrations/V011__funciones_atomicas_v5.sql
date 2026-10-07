-- =====================================================================================
-- V011: Fase 5 & 6 — Funciones SQL Atómicas e Idempotentes (vender_unidades, devolver_unidades, reservas)
-- =====================================================================================

BEGIN;

-- ---------- 1. Función Atómica de Venta e Idempotencia (Fase 5, Hallazgo C1, C5, A2, A5, M4) --
CREATE OR REPLACE FUNCTION tienda.vender_unidades(
    p_key          TEXT,
    p_qty          INTEGER,
    p_event_id     TEXT,
    p_origen       TEXT,
    p_precio_unit  BIGINT,
    p_reserva_id   TEXT DEFAULT NULL,
    p_order_id     TEXT DEFAULT NULL,
    p_line_id      TEXT DEFAULT NULL
)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    v_reserva_qty       INTEGER := 0;
    v_updated_total     INTEGER;
    v_updated_reserv    INTEGER;
    v_updated_version   BIGINT;
    v_venta_id          TEXT;
BEGIN
    -- 1. Idempotencia: Verificar si el event_id ya fue procesado
    IF p_event_id IS NOT NULL AND p_event_id <> '' THEN
        IF EXISTS (SELECT 1 FROM tienda.eventos_procesados WHERE evento_externo_id = p_event_id) THEN
            RETURN jsonb_build_object(
                'status', 'ALREADY_PROCESSED',
                'event_id', p_event_id
            );
        END IF;
    END IF;

    -- 2. Si se especificó reserva, verificar y descontar la cantidad reservada
    IF p_reserva_id IS NOT NULL AND p_reserva_id <> '' THEN
        SELECT quantity INTO v_reserva_qty
        FROM tienda.reservas
        WHERE reservation_id = p_reserva_id AND status IN ('ACTIVE', 'CONFIRMED');

        IF v_reserva_qty IS NULL THEN
            v_reserva_qty := 0;
        END IF;
    END IF;

    -- 3. Actualizar Inventario Atómicamente (atomic UPDATE con guardias de cantidad)
    UPDATE tienda.inventario
       SET cantidad_total = cantidad_total - p_qty,
           cantidad_reservada = GREATEST(0, cantidad_reservada - v_reserva_qty),
           version = version + 1
     WHERE UPPER(key) = UPPER(p_key)
       AND (cantidad_total - cantidad_reservada + v_reserva_qty) >= p_qty
    RETURNING cantidad_total, cantidad_reservada, version
         INTO v_updated_total, v_updated_reserv, v_updated_version;

    -- Si no se afectó ninguna fila, significa stock insuficiente
    IF v_updated_total IS NULL THEN
        RETURN jsonb_build_object(
            'status', 'SIN_STOCK',
            'key', p_key,
            'requested', p_qty
        );
    END IF;

    -- 4. Registrar evento de idempotencia
    IF p_event_id IS NOT NULL AND p_event_id <> '' THEN
        INSERT INTO tienda.eventos_procesados (canal, evento_externo_id, estado)
        VALUES (p_origen, p_event_id, 'DONE')
        ON CONFLICT (canal, evento_externo_id) DO NOTHING;
    END IF;

    -- 5. Actualizar estado de la reserva a CONSUMED (si aplica)
    IF p_reserva_id IS NOT NULL AND p_reserva_id <> '' THEN
        UPDATE tienda.reservas
           SET status = 'CONSUMED',
               updated_at = now()
         WHERE reservation_id = p_reserva_id;
    END IF;

    -- 6. Insertar en Outbox para sincronización con canales
    INSERT INTO tienda.outbox (id, tipo, sku, canal, version, stock_objetivo, creado_en)
    SELECT gen_random_uuid()::text,
           'stock_changed',
           p_key,
           p.canal,
           v_updated_version,
           v_updated_total,
           now()
      FROM tienda.publicaciones p
     WHERE UPPER(p.sku) = UPPER(p_key) AND p.publicado = true;

    RETURN jsonb_build_object(
        'status', 'SUCCESS',
        'key', p_key,
        'quantity_sold', p_qty,
        'remaining_total', v_updated_total,
        'remaining_reserved', v_updated_reserv,
        'version', v_updated_version
    );
END;
$$;

-- ---------- 2. Función Atómica de Devolución de Unidades (Fase 5, Hallazgo M4) --------
CREATE OR REPLACE FUNCTION tienda.devolver_unidades(
    p_order_id TEXT,
    p_line_id  TEXT,
    p_qty      INTEGER
)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    v_sku            TEXT;
    v_updated_total  INTEGER;
    v_updated_ver    BIGINT;
BEGIN
    IF p_order_id IS NULL OR p_order_id = '' THEN
        RETURN jsonb_build_object('status', 'INVALID_ORDER_ID');
    END IF;

    -- Buscar SKU por order_id y line_id en venta_items
    SELECT vi.product_key INTO v_sku
      FROM tienda.venta_items vi
     WHERE vi.order_id = p_order_id
       AND (p_line_id IS NULL OR vi.line_id = p_line_id)
     LIMIT 1;

    IF v_sku IS NULL THEN
        RETURN jsonb_build_object('status', 'VENTA_NO_ENCONTRADA', 'order_id', p_order_id);
    END IF;

    -- Devolver stock a inventario
    UPDATE tienda.inventario
       SET cantidad_total = cantidad_total + p_qty,
           version = version + 1
     WHERE UPPER(key) = UPPER(v_sku)
    RETURNING cantidad_total, version INTO v_updated_total, v_updated_ver;

    -- Notificar outbox
    INSERT INTO tienda.outbox (id, tipo, sku, canal, version, stock_objetivo, creado_en)
    SELECT gen_random_uuid()::text, 'stock_changed', v_sku, p.canal, v_updated_ver, v_updated_total, now()
      FROM tienda.publicaciones p
     WHERE UPPER(p.sku) = UPPER(v_sku) AND p.publicado = true;

    RETURN jsonb_build_object(
        'status', 'SUCCESS',
        'sku', v_sku,
        'returned_qty', p_qty,
        'new_total', v_updated_total
    );
END;
$$;

COMMIT;
