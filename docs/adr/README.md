# Architecture Decision Records

One file per decision, numbered, **append-only**: never rewrite an accepted ADR — supersede it with a new
one and set the old one's status to `Superseded by NNNN`. `DESIGN.md` is the current-state summary; ADRs
are the history of *why*. Template: [`0000-template.md`](0000-template.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions as ADRs | Accepted |
| [0002](0002-clean-architecture-layout.md) | Clean architecture: domain / application ports / adapters / config | Accepted |
| [0003](0003-atomic-conditional-stock-decrement.md) | Atomic conditional SQL for stock (no optimistic locking) | Accepted |
| [0004](0004-transactional-fiscal-counter.md) | Gap-free fiscal numbers from a transactional counter row, taken last | Accepted |
| [0005](0005-idempotency-key-claim-first.md) | Idempotency key claimed by `INSERT … ON CONFLICT DO NOTHING` first | Accepted |
| [0006](0006-transactional-outbox.md) | Transactional outbox with lease-based relay for kitchen and HQ | Accepted |
| [0007](0007-uuidv7-identifiers.md) | UUIDv7 for every entity id | Accepted |
| [0008](0008-mechanised-invariants.md) | Invariants enforced by tests, not only prose | Accepted |
| [0009](0009-frontend-test-console.md) | Separate React test console talking only to the public API | Accepted |
| [0010](0010-per-device-api-keys.md) | Per-device API keys and role-based access | Accepted |
| [0011](0011-order-lifecycle-payments-refunds.md) | Payments, voids, cancellation, refund documents; lock order key → order → stock → counter | Accepted |
| [0012](0012-stock-movement-journal.md) | Journal every stock change in the same statement | Accepted |
| [0013](0013-fiscal-series-hash-chain.md) | Fiscal series, hash chain, monotonic document time; V6 roll-forward only | Accepted |
| [0014](0014-outbox-dead-letters-and-isolation.md) | Per-destination relay, HQ timeouts, dead letters (amends 0006) | Accepted |
| [0015](0015-kds-live-stream-by-polling.md) | KDS workflow and SSE stream backed by DB polling | Accepted |
| [0016](0016-catalog-sync-lww-and-retention.md) | Catalog management, LWW HQ sync, retention | Accepted |
