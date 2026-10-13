# 0003. Atomic conditional SQL for stock (no optimistic locking)

- Status: Accepted
- Date: 2026-10-13

## Context
Scenario A: 30–50 terminals race for the last units; zero overselling, sub-second responses, no
deadlocks or thread starvation.

## Decision
`UPDATE inventory SET on_hand = on_hand - :qty WHERE product_id = :id AND on_hand >= :qty` — check and
decrement in one statement; 0 rows ⇒ `OUT_OF_STOCK` and rollback. `CHECK (on_hand >= 0)` is the backstop.
Restock is the symmetric atomic increment.

## Consequences
Enforced by `SqlInvariantsTest` (only `JdbcInventoryLedger` writes `inventory`; every decrement guarded)
and `CheckoutConcurrencyIT` / `RestockConcurrencyIT`.

## Alternatives considered
Optimistic `version` column (retry storms on hot rows); `SELECT … FOR UPDATE` then update (two round
trips under lock, no benefit); per-SKU in-memory actor (second source of truth, lost on crash).
