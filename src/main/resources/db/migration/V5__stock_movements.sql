-- Append-only stock journal. Every change to inventory.on_hand writes one row here in the same SQL
-- statement (JdbcInventoryLedger), so the journal always explains the current stock.
CREATE TABLE stock_movement (
    id            UUID         PRIMARY KEY,
    product_id    UUID         NOT NULL REFERENCES product (id),
    delta         INTEGER      NOT NULL CHECK (delta <> 0),
    reason        VARCHAR(24)  NOT NULL
                  CHECK (reason IN ('SALE', 'CANCEL', 'REFUND', 'RESTOCK', 'WASTE', 'DAMAGE', 'COUNT_CORRECTION')),
    reference_id  UUID,
    terminal_id   VARCHAR(64),
    note          VARCHAR(200),
    on_hand_after INTEGER      NOT NULL CHECK (on_hand_after >= 0),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX stock_movement_product_idx ON stock_movement (product_id, created_at DESC);
