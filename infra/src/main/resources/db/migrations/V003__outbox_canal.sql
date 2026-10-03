-- Fase 5.5: Tabla outbox para reintentos de envío a canales de mensajería.
-- Si el envío a WhatsApp/Instagram falla, el mensaje queda en outbox y se reintenta.

CREATE TABLE IF NOT EXISTS outbox (
    event_id       TEXT PRIMARY KEY,
    channel        TEXT NOT NULL,          -- 'whatsapp', 'instagram', 'messenger'
    recipient_id   TEXT NOT NULL,          -- número de teléfono o ID de cuenta
    payload_json   TEXT NOT NULL,          -- JSON del mensaje a enviar
    status         TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED
    attempts       INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ,
    sent_at        TIMESTAMPTZ,
    error_message  TEXT,
    CONSTRAINT outbox_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT outbox_attempts_positive CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (created_at ASC)
    WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_outbox_channel ON outbox (channel, status);

ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY outbox_app_user ON outbox FOR ALL TO app_user USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON outbox TO app_user;

-- NOTA: El worker de reintentos consulta:
--   SELECT * FROM outbox WHERE status = 'PENDING' AND attempts < 5
--   ORDER BY created_at ASC LIMIT 10
-- y actualiza attempts, last_attempt_at y status.
