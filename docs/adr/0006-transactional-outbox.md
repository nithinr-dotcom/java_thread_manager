# 0006. Transactional outbox with lease-based relay for kitchen and HQ

- Status: Accepted (amended by 0014: dead letters, per-destination relay)
- Date: 2026-10-13

## Context
Scenario D: kitchen notification must never be lost (even on power loss right after commit) and must
never delay or roll back checkout. HQ sync must tolerate days offline.

## Decision
Events are inserted into `outbox_event` in the checkout transaction. A scheduled relay claims due rows
with a lease (`UPDATE … FOR UPDATE SKIP LOCKED RETURNING`), dispatches outside any transaction, marks
published, or backs off exponentially forever. Consumers are idempotent (`kitchen_ticket.order_id UNIQUE`).

## Consequences
At-least-once + idempotent consumer ⇒ effectively exactly-once tickets. `ArchitectureTest` forbids HTTP
clients outside `adapter.out.hq` and any dependency from use cases on delivery code. Backlog is
observable via `GET /api/v1/ops/outbox`. Published rows are not yet purged (open task).
