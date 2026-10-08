-- =====================================================================================
-- V015: Corrección de Idempotencia Atómica y Performance de Índice en vender_unidades
-- =====================================================================================

BEGIN;

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
    v_inserted_rows     INTEGER;
BEGIN
    -- 1. Idempotencia Atómica Concurrente: Intentar insertar primero en eventos_procesados
    IF p_event_id IS NOT NULL AND p_event_id <> '' THEN
        INSERT INTO tienda.eventos_procesados (canal, evento_externo_id, estado)
        VALUES (p_origen, p_event_id, 'DONE')
        ON CONFLICT (canal, evento_externo_id) DO NOTHING;
        
        GET DIAGNOSTICS v_inserted_rows = ROW_COUNT;
        IF v_inserted_rows = 0 THEN
            RETURN jsonb_build_object(
                'status', 'ALREADY_PROCESSED',
                'event_id', p_event_id,
                'channel', p_origen
            );
        END IF;
    END IF;

    -- 2. Si se especificó reserva, verificar la cantidad reservada
    IF p_reserva_id IS NOT NULL AND p_reserva_id <> '' THEN
        SELECT quantity INTO v_reserva_qty
        FROM tienda.reservas
        WHERE reservation_id = p_reserva_id AND status IN ('ACTIVE', 'CONFIRMED');

        IF v_reserva_qty IS NULL THEN
            v_reserva_qty := 0;
        END IF;
    END IF;

    -- 3. Actualizar Inventario Atómicamente usando el índice de la clave primaria (sin UPPER(key))
    UPDATE tienda.inventario
       SET cantidad_total = cantidad_total - p_qty,
           cantidad_reservada = GREATEST(0, cantidad_reservada - v_reserva_qty),
           version = version + 1
     WHERE key = p_key
       AND (cantidad_total - cantidad_reservada + v_reserva_qty) >= p_qty
    RETURNING cantidad_total, cantidad_reservada, version
         INTO v_updated_total, v_updated_reserv, v_updated_version;

    -- Si no se afectó ninguna fila, revertir el evento procesado y retornar SIN_STOCK
    IF v_updated_total IS NULL THEN
        IF p_event_id IS NOT NULL AND p_event_id <> '' THEN
            DELETE FROM tienda.eventos_procesados
             WHERE canal = p_origen AND evento_externo_id = p_event_id;
        END IF;
        RETURN jsonb_build_object(
            'status', 'SIN_STOCK',
            'key', p_key,
            'requested', p_qty
        );
    END IF;

    -- 4. Actualizar estado de la reserva a CONSUMED (si aplica)
    IF p_reserva_id IS NOT NULL AND p_reserva_id <> '' THEN
        UPDATE tienda.reservas
           SET status = 'CONSUMED',
               updated_at = now()
         WHERE reservation_id = p_reserva_id;
    END IF;

    -- 5. Insertar en Outbox para sincronización con canales
    INSERT INTO tienda.outbox (id, tipo, sku, canal, version, stock_objetivo, creado_en)
    SELECT gen_random_uuid()::text,
           'stock_changed',
           p_key,
           p.canal,
           v_updated_version,
           v_updated_total,
           now()
      FROM tienda.publicaciones p
     WHERE p.sku = p_key AND p.publicado = true;

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

COMMIT;
