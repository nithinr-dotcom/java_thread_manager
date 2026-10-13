-- Kitchen workflow: QUEUED → IN_PROGRESS → READY → SERVED (+ CANCELLED). updated_at drives the
-- KDS live stream.
ALTER TABLE kitchen_ticket ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE kitchen_ticket SET updated_at = received_at;
CREATE INDEX kitchen_ticket_updated_idx ON kitchen_ticket (updated_at);
CREATE INDEX kitchen_ticket_active_idx ON kitchen_ticket (received_at DESC)
    WHERE status IN ('QUEUED', 'IN_PROGRESS', 'READY');
