-- =====================================================================================
-- V008: Fase 2 — Ola N3: Cliente, funciones atómicas y verificación de integridad
-- =====================================================================================
-- Crea:
--   tienda.cliente           — registro mínimo de cada contacto (D13: solo id de canal)
--   tienda.verificar_integridad() — función de diagnóstico (no modifica datos)
-- Agrega:
--   tienda.conversaciones.cliente_id FK → tienda.cliente
-- Funciones atómicas:
--   tienda.reservar(p_key, p_conversation_id, p_precio_cop, p_ttl_min)
--   tienda.liberar_vencidas()
--   tienda.confirmar_pago(p_conversation_id)
--   tienda.registrar_venta_chat(p_conversation_id)
-- Limpieza (Expandir/Migrar/Contraer, fase Contraer):
--   DROP TABLE tienda.reparaciones  (reemplazada por tienda.reparacion en V007)
--   DROP TABLE tienda.metricas      (reemplazada por tienda.venta_reparto en V007)
--   DROP COLUMN inventario.pct_antonio, pct_javier (reemplazadas por inventario_reparto en V006)
-- =====================================================================================

BEGIN;

-- ---------- 1. Tabla cliente (D13: solo el id de canal que envió el primer mensaje) -----
CREATE TABLE IF NOT EXISTS tienda.cliente (
    cliente_id  TEXT        PRIMARY KEY,  -- formato: "whatsapp:573001234567"
    canal       TEXT        NOT NULL REFERENCES tienda.cat_canal(canal) ON UPDATE CASCADE,
    display     TEXT,                     -- nombre de pantalla (si lo envía el canal)
    creado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_msg  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_cliente_canal ON tienda.cliente (canal);

ALTER TABLE tienda.cliente ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies
                 WHERE tablename='cliente' AND schemaname='tienda'
                   AND policyname='cliente_app_user') THEN
    CREATE POLICY cliente_app_user ON tienda.cliente
        FOR ALL TO app_user USING (true) WITH CHECK (true);
  END IF;
EXCEPTION WHEN undefined_object THEN NULL;
END $$;

-- FK diferida en conversaciones: el cliente puede no existir aún en la BD
-- (se crea en el primer mensaje; la FK no bloquea la migración de conversaciones antiguas).
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'fk_conv_cliente'
      AND conrelid = 'tienda.conversaciones'::regclass
  ) THEN
    -- Agregar columna cliente_id si no existe (puede existir ya como customer_id con otro nombre)
    IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = 'tienda' AND table_name = 'conversaciones'
        AND column_name = 'cliente_id_fk'
    ) THEN
      ALTER TABLE tienda.conversaciones ADD COLUMN cliente_id_fk TEXT;
    END IF;

    -- Poblar cliente_id_fk desde customer_id + channel para las conversaciones existentes
    -- (formato esperado: "channel:customer_id")
    UPDATE tienda.conversaciones
       SET cliente_id_fk = channel || ':' || customer_id
     WHERE cliente_id_fk IS NULL;

    -- Crear los registros de cliente que falten (sin bloquear por datos incompletos)
    INSERT INTO tienda.cliente (cliente_id, canal)
    SELECT DISTINCT cliente_id_fk, channel
    FROM tienda.conversaciones
    WHERE cliente_id_fk IS NOT NULL
      AND channel IN (SELECT canal FROM tienda.cat_canal)
    ON CONFLICT (cliente_id) DO NOTHING;

    ALTER TABLE tienda.conversaciones
      ADD CONSTRAINT fk_conv_cliente
        FOREIGN KEY (cliente_id_fk)
          REFERENCES tienda.cliente(cliente_id)
          ON UPDATE CASCADE
          DEFERRABLE INITIALLY DEFERRED;

    RAISE NOTICE 'FK fk_conv_cliente creada en tienda.conversaciones';
  END IF;
END $$;

-- ---------- 2. Función atómica: reservar ------------------------------------------------
-- Intenta reservar un producto. Retorna el reservation_id si hay stock, NULL si no.
-- Para usar en el pipeline cuando el código Kotlin pase a guardar reservas en BD (Fase 3).
CREATE OR REPLACE FUNCTION tienda.reservar(
    p_key              TEXT,
    p_conversation_id  TEXT,
    p_precio_cop       BIGINT,
    p_ttl_min          INTEGER DEFAULT 15
)
RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    v_disponible       INTEGER;
    v_reservation_id   TEXT;
BEGIN
    -- Verificar stock disponible (view v_stock ya resta reservas y ventas)
    SELECT disponible INTO v_disponible
    FROM tienda.v_stock
    WHERE key = p_key;

    IF NOT FOUND OR v_disponible < 1 THEN
        RETURN NULL;  -- sin stock
    END IF;

    -- Crear la reserva
    v_reservation_id := p_conversation_id || ':' || extract(epoch FROM now())::bigint::text;

    INSERT INTO tienda.reservas (
        reservation_id, conversation_id, product_key, quantity,
        status, expires_at
    ) VALUES (
        v_reservation_id, p_conversation_id, p_key, 1,
        'ACTIVE', now() + (p_ttl_min || ' minutes')::interval
    )
    ON CONFLICT (reservation_id) DO NOTHING;

    -- Actualizar la conversación con la reserva activa y el precio acordado
    UPDATE tienda.conversaciones
       SET state           = 'PAYMENT_PENDING',
           agreed_price_cop = p_precio_cop,
           last_updated     = now()
     WHERE conversation_id = p_conversation_id;

    RETURN v_reservation_id;
END $$;

-- ---------- 3. Función atómica: liberar_vencidas ----------------------------------------
-- Libera reservas vencidas y vuelve las conversaciones a NEGOTIATING.
-- Espejo del sweepExpired() de Kotlin, para cuando las reservas se persistan en BD.
CREATE OR REPLACE FUNCTION tienda.liberar_vencidas()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
    v_count INTEGER;
BEGIN
    -- Marcar reservas vencidas
    UPDATE tienda.reservas
       SET status = 'EXPIRED'
     WHERE status = 'ACTIVE'
       AND expires_at < now();

    GET DIAGNOSTICS v_count = ROW_COUNT;

    -- Resetear conversaciones afectadas
    UPDATE tienda.conversaciones c
       SET state            = 'NEGOTIATING',
           agreed_price_cop  = NULL,
           last_updated      = now()
     FROM tienda.reservas r
     WHERE r.conversation_id = c.conversation_id
       AND r.status = 'EXPIRED'
       AND c.state = 'PAYMENT_PENDING';

    RAISE NOTICE 'liberar_vencidas: % reservas liberadas', v_count;
    RETURN v_count;
END $$;

-- ---------- 4. Función atómica: confirmar_pago ------------------------------------------
-- Marca la reserva como CONFIRMED (no vencerá más) y la conversación como PAID_CONFIRMED.
-- Espejo de InboundMessagePipeline.confirmPayment() de Kotlin.
CREATE OR REPLACE FUNCTION tienda.confirmar_pago(p_conversation_id TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    v_reservation_id TEXT;
BEGIN
    -- Buscar la reserva activa de esta conversación
    SELECT r.reservation_id INTO v_reservation_id
    FROM tienda.reservas r
    JOIN tienda.conversaciones c ON c.conversation_id = r.conversation_id
    WHERE r.conversation_id = p_conversation_id
      AND r.status = 'ACTIVE'
    LIMIT 1;

    IF v_reservation_id IS NULL THEN
        RETURN false;
    END IF;

    -- Extender la reserva 1 año (no expira en la práctica)
    UPDATE tienda.reservas
       SET status     = 'CONFIRMED',
           expires_at = now() + INTERVAL '365 days'
     WHERE reservation_id = v_reservation_id;

    UPDATE tienda.conversaciones
       SET state        = 'PAID_CONFIRMED',
           last_updated = now()
     WHERE conversation_id = p_conversation_id;

    RETURN true;
END $$;

-- ---------- 5. Función atómica: registrar_venta_chat ------------------------------------
-- Registra la venta de un producto vendido por chat (reserva → venta → liberar reserva).
-- Complementa tienda.registrar_venta() que es para ventas marcadas manualmente en inventario.
CREATE OR REPLACE FUNCTION tienda.registrar_venta_chat(p_conversation_id TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    v_conv     tienda.conversaciones%ROWTYPE;
    v_res_id   TEXT;
    v_venta_id TEXT;
BEGIN
    SELECT * INTO v_conv
    FROM tienda.conversaciones
    WHERE conversation_id = p_conversation_id;

    IF NOT FOUND OR v_conv.state NOT IN ('PAID_CONFIRMED', 'PAYMENT_PENDING') THEN
        RETURN false;
    END IF;

    -- Obtener la reserva activa o confirmada
    SELECT reservation_id INTO v_res_id
    FROM tienda.reservas
    WHERE conversation_id = p_conversation_id
      AND status IN ('ACTIVE', 'CONFIRMED')
    LIMIT 1;

    IF v_res_id IS NULL OR v_conv.active_product_key IS NULL THEN
        RETURN false;
    END IF;

    -- Registrar la venta en el inventario (dispara sync_venta vía trigger)
    -- Usa la función ya existente; la fecha de venta es hoy.
    PERFORM tienda.registrar_venta(
        v_conv.active_product_key,
        v_conv.agreed_price_cop,
        CURRENT_DATE,
        SPLIT_PART(v_conv.conversation_id, ':', 1)  -- canal = primer segmento del conversation_id
    );

    -- Marcar la reserva como CONSUMED
    UPDATE tienda.reservas
       SET status = 'CONSUMED'
     WHERE reservation_id = v_res_id;

    -- Cerrar la conversación
    UPDATE tienda.conversaciones
       SET state        = 'CLOSED',
           last_updated = now()
     WHERE conversation_id = p_conversation_id;

    RETURN true;
END $$;

-- ---------- 6. Función de diagnóstico de integridad -------------------------------------
CREATE OR REPLACE FUNCTION tienda.verificar_integridad()
RETURNS TABLE (
    categoria   TEXT,
    problema    TEXT,
    afectados   INTEGER
) LANGUAGE sql STABLE AS $$
    -- R1: inventario con costo_total inconsistente vs precio_compra + reparacion
    SELECT 'costo_total_inconsistente'::TEXT,
           'costo_total <> precio_compra + reparacion'::TEXT,
           count(*)::INTEGER
    FROM tienda.inventario i
    LEFT JOIN tienda.reparacion r ON r.key = i.key AND NOT r.completada
    WHERE i.costo_total <>
          i.precio_compra + COALESCE(r.costo, 0)

    UNION ALL

    -- Precio mínimo desactualizado
    SELECT 'precio_minimo_desactualizado',
           'precio_minimo no coincide con fn_precio_minimo(costo_total)',
           count(*)::INTEGER
    FROM tienda.inventario
    WHERE precio_minimo IS NOT NULL
      AND precio_minimo <> tienda.fn_precio_minimo(costo_total)

    UNION ALL

    -- Publicados/Negociación con precio por debajo del mínimo
    SELECT 'precio_publicado_bajo_minimo',
           'estado Publicado/Negociación con precio_publicado < precio_minimo',
           count(*)::INTEGER
    FROM tienda.inventario
    WHERE estado IN ('Publicado', 'Negociación')
      AND precio_publicado IS NOT NULL
      AND precio_publicado < precio_minimo

    UNION ALL

    -- Repartos que no suman 0 ni 100
    SELECT 'reparto_suma_incorrecta',
           'suma de pct por key no es 0 ni 100',
           count(*)::INTEGER
    FROM (
        SELECT key, SUM(pct) AS total
        FROM tienda.inventario_reparto
        GROUP BY key
        HAVING SUM(pct) NOT IN (0, 100)
    ) t

    UNION ALL

    -- Fotos sin URL válida
    SELECT 'foto_url_invalida',
           'url de foto vacía o NULL',
           count(*)::INTEGER
    FROM tienda.producto_foto
    WHERE url IS NULL OR trim(url) = ''

    UNION ALL

    -- Reservas ACTIVE vencidas (no barridas)
    SELECT 'reservas_vencidas_sin_barrer',
           'reservas con status=ACTIVE y expires_at en el pasado',
           count(*)::INTEGER
    FROM tienda.reservas
    WHERE status = 'ACTIVE'
      AND expires_at < now()

    UNION ALL

    -- Ventas sin reparto de socios
    SELECT 'venta_sin_reparto',
           'ventas confirmadas sin filas en venta_reparto',
           count(*)::INTEGER
    FROM tienda.ventas v
    WHERE NOT EXISTS (
        SELECT 1 FROM tienda.venta_reparto vr WHERE vr.key = v.key
    );
$$;

COMMENT ON FUNCTION tienda.verificar_integridad() IS
    'Diagnóstico de integridad: solo SELECT, no modifica datos. '
    'Retorna filas solo para las categorías con problemas. '
    'Usar con: SELECT * FROM tienda.verificar_integridad() WHERE afectados > 0;';

-- ---------- 7. Contraer — eliminar tablas reemplazadas ----------------------------------

-- reparaciones (1 por producto) → reemplazada por reparacion (0..N) en V007
DROP TABLE IF EXISTS tienda.reparaciones CASCADE;

-- metricas (columnas socios fijos) → reemplazada por venta_reparto en V007
DROP TABLE IF EXISTS tienda.metricas CASCADE;

-- Eliminar columnas redundantes de inventario (datos migrados en V006)
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema='tienda' AND table_name='inventario'
               AND column_name='pct_antonio') THEN
    ALTER TABLE tienda.inventario DROP COLUMN IF EXISTS pct_antonio;
    ALTER TABLE tienda.inventario DROP COLUMN IF EXISTS pct_javier;
    RAISE NOTICE 'Columnas pct_antonio y pct_javier eliminadas de tienda.inventario';
  END IF;

  -- Actualizar sync_venta para que ya no use pct_antonio/pct_javier (ahora usa inventario_reparto)
END $$;

-- Actualizar sync_venta sin las columnas viejas
CREATE OR REPLACE FUNCTION tienda.sync_venta(p_key TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    i          tienda.inventario%ROWTYPE;
    v_ganancia BIGINT;
    v_tiempo   INTEGER;
    v_dias_pub INTEGER;
    rec        RECORD;
    v_total    NUMERIC;
    v_ing_soc  BIGINT;
    v_gan_soc  BIGINT;
    first_soc  BOOLEAN := true;
    v_ing_acc  BIGINT := 0;
    v_gan_acc  BIGINT := 0;
BEGIN
    SELECT * INTO i FROM tienda.inventario WHERE key = p_key;

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
        fecha_venta    = EXCLUDED.fecha_venta,  vendido_en     = EXCLUDED.vendido_en,
        canal          = EXCLUDED.canal,         ingresos       = EXCLUDED.ingresos,
        costo_total    = EXCLUDED.costo_total,   ganancia_neta  = EXCLUDED.ganancia_neta,
        margen_pct     = EXCLUDED.margen_pct,    tiempo_dias    = EXCLUDED.tiempo_dias,
        dias_publicado = EXCLUDED.dias_publicado, periodo       = EXCLUDED.periodo,
        updated_at     = now();

    -- Reparto desde inventario_reparto (tabla normalizada, N socios)
    -- Calcular el total de pct para distribuir proporcionalmente
    SELECT SUM(pct) INTO v_total
    FROM tienda.inventario_reparto
    WHERE key = p_key;

    IF COALESCE(v_total, 0) > 0 THEN
        DELETE FROM tienda.venta_reparto WHERE key = p_key;

        FOR rec IN
            SELECT ir.socio_id, ir.pct
            FROM tienda.inventario_reparto ir
            WHERE ir.key = p_key
            ORDER BY ir.socio_id
        LOOP
            -- El último socio toma el residuo exacto (evita decimales por redondeo)
            v_ing_soc := ROUND(i.vendido_en::NUMERIC * rec.pct / v_total)::BIGINT;
            v_gan_soc := ROUND(v_ganancia::NUMERIC   * rec.pct / v_total)::BIGINT;
            v_ing_acc := v_ing_acc + v_ing_soc;
            v_gan_acc := v_gan_acc + v_gan_soc;

            INSERT INTO tienda.venta_reparto (key, socio_id, ingresos, ganancias)
            VALUES (p_key, rec.socio_id, v_ing_soc, v_gan_soc)
            ON CONFLICT (key, socio_id) DO UPDATE
                SET ingresos = EXCLUDED.ingresos, ganancias = EXCLUDED.ganancias;
        END LOOP;
    ELSE
        DELETE FROM tienda.venta_reparto WHERE key = p_key;
    END IF;
END $$;

-- ---------- 8. Resumen -----------------------------------------------------------------
DO $$
DECLARE n_cli INT; n_res INT;
BEGIN
    SELECT count(*) INTO n_cli FROM tienda.cliente;
    SELECT count(*) INTO n_res FROM tienda.reservas;
    RAISE NOTICE 'Ola N3 completada: clientes=% · reservas=%', n_cli, n_res;
    RAISE NOTICE 'Tablas eliminadas: tienda.reparaciones, tienda.metricas';
    RAISE NOTICE 'Columnas eliminadas: inventario.pct_antonio, inventario.pct_javier';
    RAISE NOTICE 'Para verificar integridad: SELECT * FROM tienda.verificar_integridad() WHERE afectados > 0;';
END $$;

COMMIT;
