# 0012. Journal every stock change in the same statement as the change

- Status: Accepted
- Date: 2026-10-13

## Context
`inventory.on_hand` was a bare number: a restock, waste or sale left no trace, so a stock discrepancy
could not be explained or audited.

## Decision
Every write to `inventory` in `JdbcInventoryLedger` is a single CTE statement that also inserts one
`stock_movement` row (delta, reason, reference document, terminal, note, on-hand after). Decrements keep
the guarded form `… WHERE on_hand >= :qty` (ADR 0003). Reasons: SALE, CANCEL, REFUND, RESTOCK, WASTE,
DAMAGE, COUNT_CORRECTION. Restock/adjust are idempotent (Idempotency-Key, claim first) so a retried
request can never create phantom stock.

## Consequences
`sum(delta)` over the journal always equals the stock change (asserted in `CatalogAndStockIT`); one
extra insert per line on checkout. `SqlInvariantsTest` still guarantees only the ledger writes stock.

## Alternatives considered
- **Trigger-maintained journal**: invisible in code review and harder to test. Rejected.
- **Event-sourced stock** (derive on_hand from the journal): heavier reads on the hottest path.
