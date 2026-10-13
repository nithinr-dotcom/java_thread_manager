# persistence — where the concurrency guarantees live

The SQL *is* the correctness argument; keep statements explicit, short and reviewable.

- **Stock**: only `JdbcInventoryLedger` writes `inventory` (SqlInvariantsTest). Decrement = one CTE
  `UPDATE … SET on_hand = on_hand - :qty WHERE product_id = :productId AND on_hand >= :qty` + the
  `stock_movement` insert; 0 rows ⇒ `InsufficientStockException`. Never SELECT-then-UPDATE. Increments =
  atomic `+` upsert, journaled the same way.
- **Fiscal numbers**: only `JdbcFiscalSequence` touches `fiscal_sequence` — upsert-increment on the
  (branch, series, date) row with `last_issued_at = GREATEST(…)`, then `recordHash` on the same row, inside
  the document's transaction, called last. Never a SEQUENCE.
- **Idempotency**: `INSERT … ON CONFLICT (terminal_id, idempotency_key) DO NOTHING` is the first statement
  of every state-changing transaction; it blocks on an in-flight duplicate by design. `complete` stores
  `resource_id` (order/payment/refund/stock operation).
- **Order row lock**: `lockAccount` (`SELECT … FOR UPDATE`) is the 2nd lock of lifecycle use cases.
- Nullable parameters inside expressions need an explicit `CAST(:p AS type)` (PostgreSQL cannot infer them).
- Unique-key races surface as domain exceptions (`DuplicateKeyException` → `PRODUCT_EXISTS`/`TERMINAL_EXISTS`).
- `SpringTransactionRunner` sets `lock_timeout` per transaction (fail fast → 503 `BUSY_RETRY`).
- No `@Transactional` anywhere (SqlInvariantsTest); transactions are opened by use cases via the port.
- Any change here: run the full `./mvnw verify` (contention ITs) and re-check the invariants in `CONTRIBUTING.md`.
