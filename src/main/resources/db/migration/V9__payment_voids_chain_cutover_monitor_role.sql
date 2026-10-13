-- 1. A tender taken for an order that is never completed (e.g. split payment, second card declined,
--    customer leaves) can be voided while the order is still awaiting payment; the order can then be
--    cancelled. Voided payments stay on record.
ALTER TABLE payment ADD COLUMN voided_at   TIMESTAMPTZ;
ALTER TABLE payment ADD COLUMN void_reason VARCHAR(200);

-- 2. Fiscal-chain cutover: the first sequence number of the day that was hash-chained. Every later
--    document must carry a hash, so stripping hashes cannot make a tampered day look "legacy".
ALTER TABLE fiscal_sequence ADD COLUMN first_hashed_seq BIGINT;

-- 3. Read-only monitoring role (Prometheus scraper) — no business permissions.
ALTER TABLE terminal DROP CONSTRAINT terminal_role_check;
ALTER TABLE terminal ADD CONSTRAINT terminal_role_check CHECK (role IN ('POS', 'KITCHEN', 'MANAGER', 'HQ', 'MONITOR'));
