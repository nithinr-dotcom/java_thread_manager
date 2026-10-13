---
status: done
---
# Initial implementation slices

Working plan used to drive the first implementation (small, verifiable slices, each ending green).
Paths below refer to the original `infrastructure` layout — see ADR 0002 for the current one.

## Slice 0 — Scaffolding
- [x] Maven project, Java 21, Spring Boot 3.5, wrapper, `.gitignore`
- [x] Contributor guide, this board
- [x] Flyway `V1__baseline.sql`, demo catalog under `db/demo` (profile `demo`)

## Slice 1 — Domain (pure Java)
- [x] `Money`, `TaxRate`, `CurrencyMismatchException` (Scenario E)
- [x] `Sku`, `Quantity`, `Product`, `OrderLine`, `OrderDraft`, `Order`, `OrderNumber` (Scenario B format)
- [x] `UuidV7` identifiers (sync readiness)
- [x] Domain events: `KitchenTicketRequested`, `OrderPlaced`
- [x] Unit tests for all of the above

## Slice 2 — Application (use case + ports)
- [x] `PlaceOrderService` with fixed lock order (idempotency → stock → fiscal counter)
- [x] Ports: catalog, inventory ledger, fiscal sequence, orders, idempotency, outbox, tx runner
- [x] In-memory fake tests for replay / fingerprint mismatch / no-number-on-failure

## Slice 3 — Infrastructure
- [x] JDBC adapters with atomic SQL (Scenario A, B, C)
- [x] Transactional outbox + lease-based relay with backoff (Scenario D)
- [x] Kitchen inbox (idempotent consumer), optional HQ sync dispatcher
- [x] REST API `/api/v1/orders`, `Idempotency-Key` + `X-Terminal-Id` headers, ProblemDetail errors

## Slice 4 — Verification
- [x] 50-thread stock contention IT (zero oversell, gap-free monotonic numbers)
- [x] Duplicate-storm IT (50 identical requests ⇒ 1 order)
- [x] Mixed retries under contention IT, opposite lock-order multi-line IT (no deadlocks)
- [x] Outbox ITs: failure isolation, retry, crash/lease redelivery, idempotent kitchen consumer
- [x] HTTP API IT
- [x] ArchUnit boundary test

Open backlog items moved to their own files in `docs/tasks/`.
