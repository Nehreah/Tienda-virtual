-- =====================================================================================
-- V5 · LIQUIDACIONES A SOCIOS (aportes y saldos) + FORMATO DE CLAVES
-- =====================================================================================
-- Flyway ejecuta este archivo en su propia transacción: aquí no hay BEGIN/COMMIT.
--
-- 1) Formato de la Key del inventario (política de claves v11, ver ProductKey.kt).
-- 2) tienda.liquidacion: pagos que el negocio le entrega a cada socio. Se registran con funciones
--    y NO se editan ni se borran: se anulan con motivo.
-- 3) tienda.v_socio_cuenta: aportes y saldo por socio.
--
-- SUPUESTO DE NEGOCIO (confírmalo; si es otro, solo cambia la vista v_socio_cuenta):
--   * "Aporte" = la parte del costo que le corresponde a cada socio según su % de inversión en el artículo.
--       - aporte_en_stock : capital que el socio tiene en artículos aún no vendidos (costo por unidad x unidades x %).
--       - aporte_vendido  : capital que le tocó en ventas vigentes (venta_reparto.inversion).
--   * Lo vendido (venta_reparto.ingresos = capital recuperado + ganancia) pertenece al socio.
--   * "Saldo por liquidar" = ingresos repartidos al socio - liquidaciones vigentes ya pagadas.
--     Si se anula una venta después de liquidarla, el saldo puede quedar negativo: el socio debe devolver esa diferencia.
-- =====================================================================================

-- ---------- 1. Formato de la Key ---------------------------------------------------------------
-- NOT VALID: se exige a filas nuevas o modificadas, y no revisa las que ya existían (no rompe la migración).
-- Equivale a ProductKey.validar(): 1 a 64 caracteres, letras/números/./_/- y empieza con letra o número.
ALTER TABLE tienda.inventario
  ADD CONSTRAINT ck_inventario_key_formato
  CHECK (key ~ '^[[:alnum:]][[:alnum:]._-]{0,63}$') NOT VALID;

-- ---------- 2. Liquidaciones ------------------------------------------------------------------
CREATE TABLE tienda.liquidacion (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  socio_id       bigint      NOT NULL REFERENCES tienda.socio(id) ON DELETE RESTRICT,
  fecha          date        NOT NULL DEFAULT current_date,
  monto          bigint      NOT NULL CHECK (monto > 0),                 -- COP enteros
  nota           text        CHECK (nota IS NULL OR char_length(nota) <= 500),
  creado_por     text,
  creado_en      timestamptz NOT NULL DEFAULT now(),
  anulada_en     timestamptz,
  anulada_motivo text,
  CONSTRAINT ck_liquidacion_anulada CHECK (anulada_en IS NULL OR btrim(coalesce(anulada_motivo, '')) <> '')
);
CREATE INDEX ix_liquidacion_socio ON tienda.liquidacion (socio_id, fecha DESC) WHERE anulada_en IS NULL;

-- Inmutable: no se borra; solo puede pasar una vez de vigente a anulada (con motivo).
CREATE OR REPLACE FUNCTION tienda.trg_liquidacion_inmutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Las liquidaciones no se borran: anúlala indicando el motivo.';
  END IF;
  IF OLD.anulada_en IS NOT NULL THEN
    RAISE EXCEPTION 'La liquidación % ya está anulada y no se puede modificar.', OLD.id;
  END IF;
  IF (NEW.socio_id, NEW.fecha, NEW.monto, NEW.nota, NEW.creado_por, NEW.creado_en)
     IS DISTINCT FROM (OLD.socio_id, OLD.fecha, OLD.monto, OLD.nota, OLD.creado_por, OLD.creado_en) THEN
    RAISE EXCEPTION 'Una liquidación no se edita: anúlala y registra una nueva.';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_a_liquidacion_inmutable
  BEFORE UPDATE OR DELETE ON tienda.liquidacion
  FOR EACH ROW EXECUTE FUNCTION tienda.trg_liquidacion_inmutable();

-- ---------- 3. Cuenta por socio ----------------------------------------------------------------
CREATE OR REPLACE VIEW tienda.v_socio_cuenta AS
WITH stock AS (
  SELECT ii.socio_id,
         sum(round(i.costo_total::numeric * i.cantidad * ii.pct / 100))::bigint AS aporte_en_stock
    FROM tienda.inventario_inversion ii
    JOIN tienda.inventario i ON i.key = ii.key
   WHERE i.archivado_en IS NULL
     AND i.estado NOT IN ('Vendido', 'Inactivo')
     AND ii.pct > 0
   GROUP BY ii.socio_id
), vendido AS (
  SELECT vr.socio_id,
         sum(vr.inversion)::bigint AS aporte_vendido,
         sum(vr.ingresos)::bigint  AS ingresos,
         sum(vr.ganancia)::bigint  AS ganancia,
         count(*)                  AS ventas
    FROM tienda.venta_reparto vr
    JOIN tienda.v_ventas v ON v.venta_id = vr.venta_id
   GROUP BY vr.socio_id
), pagado AS (
  SELECT l.socio_id, sum(l.monto)::bigint AS liquidado, count(*) AS liquidaciones
    FROM tienda.liquidacion l
   WHERE l.anulada_en IS NULL
   GROUP BY l.socio_id
)
SELECT s.id                                                     AS socio_id,
       s.nombre,
       s.activo,
       coalesce(stock.aporte_en_stock, 0)                       AS aporte_en_stock,
       coalesce(vendido.aporte_vendido, 0)                      AS aporte_vendido,
       coalesce(vendido.ingresos, 0)                            AS ingresos,
       coalesce(vendido.ganancia, 0)                            AS ganancia,
       coalesce(vendido.ventas, 0)                              AS ventas,
       coalesce(pagado.liquidado, 0)                            AS liquidado,
       coalesce(pagado.liquidaciones, 0)                        AS liquidaciones,
       coalesce(vendido.ingresos, 0) - coalesce(pagado.liquidado, 0) AS saldo_por_liquidar
  FROM tienda.socio s
  LEFT JOIN stock   ON stock.socio_id   = s.id
  LEFT JOIN vendido ON vendido.socio_id = s.id
  LEFT JOIN pagado  ON pagado.socio_id  = s.id;

-- ---------- 4. Funciones -----------------------------------------------------------------------
-- Registra un pago a un socio. Devuelve JSON (como texto) con el id y el saldo que queda.
-- Bloquea la fila del socio para que dos pagos simultáneos no se pasen del saldo.
CREATE OR REPLACE FUNCTION tienda.registrar_liquidacion(
  p_socio_id bigint,
  p_monto    bigint,
  p_fecha    date DEFAULT NULL,
  p_nota     text DEFAULT NULL,
  p_usuario  text DEFAULT NULL
) RETURNS text LANGUAGE plpgsql AS $$
DECLARE
  v_saldo bigint;
  v_id    bigint;
  v_nota  text := nullif(btrim(coalesce(p_nota, '')), '');
BEGIN
  IF p_monto IS NULL OR p_monto <= 0 THEN
    RAISE EXCEPTION 'El monto de la liquidación debe ser mayor que cero.';
  END IF;
  -- Se tolera 1 día de diferencia entre la zona horaria del servidor y la de la persona.
  IF p_fecha IS NOT NULL AND p_fecha > current_date + 1 THEN
    RAISE EXCEPTION 'La fecha de la liquidación no puede ser futura.';
  END IF;

  PERFORM 1 FROM tienda.socio WHERE id = p_socio_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'El socio % no existe.', p_socio_id;
  END IF;

  SELECT saldo_por_liquidar INTO v_saldo FROM tienda.v_socio_cuenta WHERE socio_id = p_socio_id;
  v_saldo := coalesce(v_saldo, 0);
  IF p_monto > v_saldo THEN
    RAISE EXCEPTION 'El monto (%) supera el saldo por liquidar del socio (%).', p_monto, v_saldo;
  END IF;

  INSERT INTO tienda.liquidacion (socio_id, fecha, monto, nota, creado_por)
  VALUES (p_socio_id, coalesce(p_fecha, current_date), p_monto, v_nota, nullif(btrim(coalesce(p_usuario, '')), ''))
  RETURNING id INTO v_id;

  RETURN jsonb_build_object('ok', true, 'id', v_id, 'saldo', v_saldo - p_monto)::text;
END $$;

-- Anula una liquidación vigente (el motivo es obligatorio). El dinero vuelve al saldo del socio.
CREATE OR REPLACE FUNCTION tienda.anular_liquidacion(p_id bigint, p_motivo text) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  IF btrim(coalesce(p_motivo, '')) = '' THEN
    RAISE EXCEPTION 'El motivo es obligatorio para anular una liquidación.';
  END IF;
  UPDATE tienda.liquidacion
     SET anulada_en = now(), anulada_motivo = btrim(p_motivo)
   WHERE id = p_id AND anulada_en IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'La liquidación % no existe o ya estaba anulada.', p_id;
  END IF;
  RETURN jsonb_build_object('ok', true, 'id', p_id)::text;
END $$;

-- Retirar un socio: también se archiva (no se elimina) si tiene liquidaciones registradas.
CREATE OR REPLACE FUNCTION tienda.retirar_socio(p_id bigint) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM tienda.venta_reparto WHERE socio_id = p_id)
     OR EXISTS (SELECT 1 FROM tienda.inventario_inversion WHERE socio_id = p_id)
     OR EXISTS (SELECT 1 FROM tienda.liquidacion WHERE socio_id = p_id) THEN
    UPDATE tienda.socio SET activo = false, archivado_en = now() WHERE id = p_id;
    RETURN 'ARCHIVADO';
  END IF;
  DELETE FROM tienda.socio WHERE id = p_id;
  RETURN 'ELIMINADO';
END $$;

-- ---------- 5. Seguridad -----------------------------------------------------------------------
SELECT tienda.fn_asegurar_rls('tienda.liquidacion'::regclass);
