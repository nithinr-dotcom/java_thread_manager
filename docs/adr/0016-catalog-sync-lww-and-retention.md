# 0016. Catalog management with last-writer-wins HQ sync; data retention

- Status: Accepted
- Date: 2026-10-13

## Context
The catalog was seed data only. HQ owns the chain's catalog but the node must keep selling (and
occasionally edit prices) while offline. Idempotency keys and delivered outbox rows grew forever.

## Decision
- Products gain station, category, `active`; `version` is an epoch-millisecond LWW version shared with
  HQ (local edits use `max(version + 1, now)`). Back-office edits are optimistic (`VERSION_CONFLICT`).
- HQ pushes (`PUT /sync/catalog`, role HQ) or the node pulls (`/sync/catalog?sinceVersion`, high-water
  mark in `sync_state`). Batches are applied in product-id order (no deadlocks between push and pull);
  a newer version wins; a product whose SKU belongs to another local id is skipped and logged; a whole
  batch in a foreign currency is rejected. Order lines snapshot prices, so issued orders never change.
- Retention (hourly, batched, `SKIP LOCKED`): idempotency keys after 7 days (a retry older than that is
  a new request — documented), delivered outbox events after 7 days. Dead letters are kept.

## Consequences
Guards: `CatalogServiceTest`, `CatalogAndStockIT`, `HousekeepingServiceTest`, `ReportsAndHousekeepingIT`.

## Alternatives considered
- **Vector clocks per field**: precise merges, unnecessary while HQ is the catalog authority.
- **Keeping idempotency keys forever**: unbounded table growth on a small edge disk.
