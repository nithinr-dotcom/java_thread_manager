-- Outbox dead letters: an event that exhausted its attempts (or failed permanently) stops being
-- retried, is reported on /ops/outbox/dead and can be re-queued by an operator.
ALTER TABLE outbox_event ADD COLUMN dead_at TIMESTAMPTZ;

DROP INDEX outbox_event_due_idx;
CREATE INDEX outbox_event_deliverable_idx
    ON outbox_event (destination, next_attempt_at)
    WHERE published_at IS NULL AND dead_at IS NULL;

-- Retention purges (batched DELETEs by age).
CREATE INDEX outbox_event_published_idx ON outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX idempotency_key_created_idx ON idempotency_key (created_at);

-- Idempotency now protects payments, cancellations and refunds too; resource_id is the id of whatever
-- the key produced (order, payment or refund). order_id stays filled for orders (older releases read it).
ALTER TABLE idempotency_key ADD COLUMN resource_id UUID;
UPDATE idempotency_key SET resource_id = order_id WHERE order_id IS NOT NULL;
