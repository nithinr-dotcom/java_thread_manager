-- PARTIALLY_REFUNDED (18 chars) does not fit the original VARCHAR(16) status column.
ALTER TABLE customer_order ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check
    CHECK (status IN ('PLACED', 'PAID', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED'));
