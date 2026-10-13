-- Fiscal counters per document series (ORD = orders, REF = refunds). last_issued_at keeps document
-- timestamps monotonic even if the wall clock steps backwards; last_hash is the head of the day's
-- SHA-256 hash chain (tamper evidence).
ALTER TABLE fiscal_sequence ADD COLUMN series         VARCHAR(8) NOT NULL DEFAULT 'ORD';
ALTER TABLE fiscal_sequence ADD COLUMN last_issued_at TIMESTAMPTZ;
ALTER TABLE fiscal_sequence ADD COLUMN last_hash      CHAR(64);
ALTER TABLE fiscal_sequence DROP CONSTRAINT fiscal_sequence_pkey;
ALTER TABLE fiscal_sequence ADD PRIMARY KEY (branch_code, series, business_date);

-- Order lifecycle (PLACED → PAID → PARTIALLY_REFUNDED/REFUNDED, PLACED → CANCELLED). The fiscal content
-- of an order never changes; only its status does.
ALTER TABLE customer_order ADD COLUMN fiscal_hash       CHAR(64);
ALTER TABLE customer_order ADD COLUMN cancel_reason     VARCHAR(200);
ALTER TABLE customer_order ADD COLUMN status_changed_at TIMESTAMPTZ;
CREATE INDEX customer_order_placed_at_idx ON customer_order (placed_at DESC, order_number DESC);
CREATE INDEX order_line_order_idx ON order_line (order_id);
-- Kitchen station snapshot (like name and price), so kitchen events can be rebuilt from the order.
ALTER TABLE order_line ADD COLUMN station VARCHAR(32);

CREATE TABLE payment (
    id           UUID          PRIMARY KEY,
    order_id     UUID          NOT NULL REFERENCES customer_order (id),
    method       VARCHAR(8)    NOT NULL CHECK (method IN ('CASH', 'CARD')),
    currency     CHAR(3)       NOT NULL,
    amount       NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
    tendered     NUMERIC(19,4),
    change_given NUMERIC(19,4),
    reference    VARCHAR(64),
    terminal_id  VARCHAR(64)   NOT NULL,
    received_at  TIMESTAMPTZ   NOT NULL,
    origin_node  VARCHAR(64)   NOT NULL
);
CREATE INDEX payment_order_idx ON payment (order_id);
CREATE INDEX payment_received_idx ON payment (received_at);

-- Refunds are separate, immutable fiscal documents (REF-{BRANCH}-{YYYYMMDD}-{NNNN}).
CREATE TABLE refund (
    id            UUID          PRIMARY KEY,
    refund_number VARCHAR(64)   NOT NULL UNIQUE,
    order_id      UUID          NOT NULL REFERENCES customer_order (id),
    branch_code   VARCHAR(16)   NOT NULL,
    business_date DATE          NOT NULL,
    sequence_no   BIGINT        NOT NULL,
    method        VARCHAR(8)    NOT NULL CHECK (method IN ('CASH', 'CARD')),
    reason        VARCHAR(200)  NOT NULL,
    restocked     BOOLEAN       NOT NULL,
    currency      CHAR(3)       NOT NULL,
    subtotal      NUMERIC(19,4) NOT NULL,
    tax_total     NUMERIC(19,4) NOT NULL,
    grand_total   NUMERIC(19,4) NOT NULL,
    terminal_id   VARCHAR(64)   NOT NULL,
    issued_at     TIMESTAMPTZ   NOT NULL,
    origin_node   VARCHAR(64)   NOT NULL,
    fiscal_hash   CHAR(64)      NOT NULL,
    UNIQUE (branch_code, business_date, sequence_no)
);
CREATE INDEX refund_order_idx ON refund (order_id);

CREATE TABLE refund_line (
    id        UUID          PRIMARY KEY,
    refund_id UUID          NOT NULL REFERENCES refund (id),
    line_no   INTEGER       NOT NULL,
    quantity  INTEGER       NOT NULL CHECK (quantity > 0),
    net       NUMERIC(19,4) NOT NULL,
    tax       NUMERIC(19,4) NOT NULL,
    total     NUMERIC(19,4) NOT NULL,
    UNIQUE (refund_id, line_no)
);
