# DESIGN — Storefront Edge Node

## 1. Architectural boundaries

Clean architecture in one Maven module, enforced by `ArchitectureTest` (ArchUnit). Decision record:
[ADR 0002](docs/adr/0002-clean-architecture-layout.md).

| Ring | Package | Depends on | Contents |
|---|---|---|---|
| Entities | `domain.*` | `java.*` only | `Money`, `TaxRate`, `OrderDraft`, `Order`, `OrderNumber`, `UuidV7`, domain events, domain exceptions |
| Use cases | `application.port.in` | domain | `PlaceOrderUseCase`, `FindOrdersQuery`, `BrowseCatalogQuery`, `RestockUseCase`, `KitchenBoardQuery`, `OutboxStatusQuery` + command/result records |
| | `application.port.out` | domain | interfaces only: `InventoryLedger`, `FiscalSequence`, `IdempotencyStore`, `OrderRepository`, `ProductCatalog`, `Outbox`, `TransactionRunner`, `KitchenBoard`, `OutboxMonitor` |
| | `application.service` | ports, domain | `PlaceOrderService`, `OrderQueryService`, `CatalogService`, … (framework-free) |
| Interface adapters | `adapter.in.web` | `port.in`, domain | REST controllers, request/response records, `ProblemDetail` mapping |
| | `adapter.out.*` | `port.out`, domain | `persistence` (JDBC), `outbox` (relay, store, monitor), `kitchen` (KDS inbox), `hq` (HTTP sync) |
| Frameworks & wiring | `config` | everything | `StorefrontConfiguration` (`@Bean` per service), `StorefrontProperties` |

Controllers see only input-port interfaces; adapters receive settings through `@Value`, so nothing but
`config` knows concrete services. The test console (`frontend/`) uses only the documented API
(`docs/API.md`, [ADR 0009](docs/adr/0009-frontend-test-console.md)).

Why: the invariants that matter (pricing, tax, currency safety, numbering format, which items go to the kitchen)
live in immutable records and are unit-tested in milliseconds without Spring or a database. The use case
sees only interfaces, so it is also tested with in-memory fakes (`PlaceOrderServiceTest`, which also
asserts the lock order). Spring is
confined to the outer ring; the use case is wired with plain `@Bean` methods. Persistence uses explicit SQL
(`JdbcClient`) rather than JPA because the concurrency guarantees *are* the SQL statements — they should be
visible and reviewable, not generated. Schema is owned by Flyway (`V1__baseline.sql`); no DDL auto-generation.

## 2. Concurrency strategy

Every state change is **one short READ COMMITTED transaction** that acquires locks in a **fixed global
order** ([ADR 0011](docs/adr/0011-order-lifecycle-payments-refunds.md)):

1. **Idempotency key** — `INSERT … ON CONFLICT DO NOTHING` on PK `(terminal_id, key)`.
2. **Order row** — `SELECT … FOR UPDATE`, only in lifecycle use cases (pay, void, cancel, refund);
   checkout never locks an existing order.
3. **Inventory rows**, sorted by product id — `UPDATE inventory SET on_hand = on_hand - :q WHERE product_id = :p AND on_hand >= :q`
   (or an increment for restock/cancel/refund), each with its `stock_movement` journal row in the same statement.
4. **Fiscal counter** of the series (`ORD`, `REF`) — `INSERT … ON CONFLICT (branch, series, date) DO UPDATE
   SET last_value = last_value + 1, last_issued_at = GREATEST(…) RETURNING …`, then the chain head hash.
5. Insert documents, complete the idempotency row, append outbox events, commit.

**Stock (Scenario A) — atomic conditional SQL.** The check and the decrement are one statement. A waiting
writer re-evaluates `on_hand >= :q` against the newly committed row, so overselling is impossible; `0 rows
updated` → `InsufficientStockException` → rollback → HTTP 409 `OUT_OF_STOCK`. A `CHECK (on_hand >= 0)` is the
backstop. Considered alternatives:
- *Optimistic locking (version column)*: under 50-way contention on one row almost every attempt conflicts,
  producing retry storms and wasted work. Rejected for hot rows.
- *`SELECT … FOR UPDATE` then update*: correct but two round-trips while holding the lock; no benefit.
- *In-process queue/actor per SKU*: fast, but a second source of truth next to the DB and lost on crash.

**Deadlocks** cannot form because every transaction locks in the same order (idempotency → order → products
by id → counter); `OrderDraft.stockRequirements()`/`RefundDraft.stockToReturn()` sort, proven by the
opposite-order multi-line IT and a contention IT mixing cancels, restocking refunds and checkouts.
Restock/adjust take one row; housekeeping and the relay use `SKIP LOCKED`; HQ catalog batches apply in id order.
**Starvation** is bounded: each transaction sets `lock_timeout` (3 s), Hikari's pool is capped (20) with a 3 s
`connection-timeout`, and request handling uses virtual threads. Overload therefore turns into a fast,
explicitly retryable `503 BUSY_RETRY` (nothing committed), never a hung terminal.

**Fiscal numbering (Scenario B) — transactional counter row, taken last** (per series: orders `ORD`, refund
documents `REF`; [ADR 0013](docs/adr/0013-fiscal-series-hash-chain.md)). Because it is in the same
transaction, a rollback returns the number → gap-free. Because the row lock is held until commit, numbers are
committed in order, and `placed_at` is read *while holding* the lock → number order equals time order. It
needs no external authority. The counter is a serialisation point, but only for the tail of the
transaction (a few inserts, ~1 ms): stock validation, pricing and stock locks on *different* SKUs all run
in parallel before it. PostgreSQL `SEQUENCE`s were rejected because they are non-transactional (gaps on
rollback). Document time is `GREATEST(previous, now)` on the counter row, so it never runs backwards when the
wall clock steps back. Each document is SHA-256-chained to the previous one; `GET /reports/fiscal-chain`
detects edits, deletions, truncation and stripped hashes. Cancelled orders keep their number. Edge case: a checkout straddling midnight is numbered in the day its number belongs to
(`business_date` is stored explicitly).

## 3. Network retry handling (Scenario C)

Terminals send `Idempotency-Key` (one per logical checkout, reused verbatim on retries) and `X-Terminal-Id`.
The claim is the **first statement** of the transaction. PostgreSQL's unique-index semantics give exactly the
behaviour needed for duplicates arriving milliseconds apart: the second `INSERT … ON CONFLICT DO NOTHING`
*blocks* until the first transaction ends, then
- first **committed** → 0 rows inserted → we load the stored `order_id` and return the original order
  (`200`, `Idempotent-Replayed: true`) — no stock touched, no number consumed;
- first **rolled back** (e.g. out of stock) → our insert proceeds and the request is evaluated afresh.

A SHA-256 fingerprint of the normalised basket is stored with the key; reusing a key for a different
basket yields `422 IDEMPOTENCY_KEY_REUSED`. The same claim-first pattern protects payments, voids,
cancellations, refunds, restocks and adjustments (fingerprints are operation-prefixed). The terminal is the
authenticated device ([ADR 0010](docs/adr/0010-per-device-api-keys.md)), so one device cannot replay or
collide with another's keys. Keys are purged after 7 days (a retry older than that is a new request). Failed checkouts are deliberately *not* memoised, so a retry after
restock can succeed. Proven by the 50-identical-requests IT (1 order, stock −1×, counter = 1) and the
80-call mixed retry IT.

## 4. Downstream reliability (Scenario D) — transactional outbox

The checkout transaction writes `KitchenTicketRequested` (only if the order has prepared items) and
`OrderPlaced` (for HQ) to `outbox_event` **in the same commit** as the order. Hence: no order without its
event, no event without its order (no phantom tickets), and the kitchen is never on the checkout path —
a slow or dead KDS cannot delay or roll back a payment.

The relay runs **one thread per destination** (a hung HQ link cannot delay kitchen tickets; HQ calls have
2 s/5 s connect/read timeouts) and claims due rows with a **lease**: one auto-committed
`UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING`, pushing `next_attempt_at` 10 min ahead. It
dispatches *outside* any transaction and then marks `published_at`; on failure it records `last_error` and
reschedules with capped exponential backoff (1 s → 60 s). Permanent failures (unreadable payload, HQ 4xx)
and local events that failed 20 times become **dead letters** for an operator; HQ is never dead-lettered
for transient failures ([ADR 0014](docs/adr/0014-outbox-dead-letters-and-isolation.md)). If the node loses power after the
commit, the row is durable; if it dies mid-delivery, the lease expires and the row is redelivered after
restart. Delivery is thus **at-least-once**, and the kitchen inbox is an **idempotent consumer**
(`kitchen_ticket.order_id UNIQUE`, `ON CONFLICT DO NOTHING`) → effectively exactly one ticket per order.
`SKIP LOCKED` allows multiple relay workers. Covered by `KitchenDispatchIT` (outage isolation, retry,
crash/lease redelivery).

Kitchen tickets have a workflow (`QUEUED → IN_PROGRESS → READY → SERVED`, compare-and-set transitions)
and reach screens through an SSE stream ([ADR 0015](docs/adr/0015-kds-live-stream-by-polling.md)).
Cancelling an order sends `KitchenTicketCancelled`; the inbox handles either arrival order.

## 5. Financial precision (Scenario E)

`Money(BigDecimal, Currency)` — no `double` anywhere. The scale is forced to the currency's minor units
(USD 2, JPY 0, BHD 3) with `RoundingMode.UNNECESSARY`, so accidental extra precision fails loudly; rounding
only happens in named operations (tax: per line, half-even). Any cross-currency `plus/minus/compareTo` throws
`CurrencyMismatchException`, and an `OrderDraft` rejects lines not in the branch currency. Prices come from
the node's catalog, never from the client, and are snapshotted onto order lines. DB columns are
`NUMERIC(19,4)` + `CHAR(3)`. Cash change and refund amounts are computed by the server (refund tax is
allocated on cumulative units, so a full refund returns exactly the total); the UI only displays them.

## 6. Distributed sync readiness

- **Identifiers**: every entity id is a **UUIDv7** generated on the node — collision-free across thousands
  of nodes without coordination, and time-ordered so B-tree inserts stay append-friendly at HQ.
- **Business keys** are globally unique by construction: `ORD-{BRANCH}-{DATE}-{SEQ}`, `REF-{BRANCH}-{DATE}-{SEQ}`.
- **Provenance**: orders carry `origin_node`; HQ requests carry `X-Origin-Node`.
- **Ownership model**: fiscal documents (orders, refunds) are immutable and owned by the issuing node
  (append-only → no write conflicts on sync); lifecycle changes are separate events. The catalog is owned by
  HQ: products carry an epoch-millisecond `version` and inbound sync (push or pull) is last-writer-wins
  ([ADR 0016](docs/adr/0016-catalog-sync-lww-and-retention.md)). Stock is local to the node.
- **Outbound sync** reuses the outbox (`destination = HQ`: orders, payments, voids, cancellations, refunds).
  With no HQ URL configured or the WAN down, events accumulate and drain when connectivity returns —
  **at-least-once and not ordered**; HQ deduplicates on `Idempotency-Key` = outbox event id and treats every
  event as a complete fact.

## 7. Mechanised guardrails

Invariants are enforced mechanically, not just documented ([ADR 0008](docs/adr/0008-mechanised-invariants.md)):
`ArchitectureTest` (layers, no `double`/`float`, no `UUID.randomUUID`, HTTP clients only in the HQ adapter),
`SqlInvariantsTest` (only the ledger writes stock and every decrement is guarded; only the fiscal adapter
allocates numbers; idempotency claim shape; no `@Transactional`), `MigrationLockTest` (applied migrations
immutable via a checksum lock, contiguous versions, no SEQUENCE/SERIAL/IDENTITY), Spotless, and CI running
all of it on every PR.

## 8. Security and operations
Devices authenticate with per-device API keys and roles (ADR 0010); TLS is terminated by the nginx proxy that
also serves the UI. Production runs from `deploy/` (node image, PostgreSQL with WAL archiving and nightly
dumps, proxy). Observability: Prometheus metrics (order operation latency/outcome, outbox backlog/lag/dead
letters, Hikari), JSON logs with request id / terminal / idempotency key, liveness/readiness probes. Runbook
and alert thresholds: [`docs/OPERATIONS.md`](docs/OPERATIONS.md). Migration V6 is roll-forward only (ADR 0013).

## 9. Known limitations / next steps
Open items live in `docs/tasks/` (one file each): mTLS for devices, a certified fiscal signing device where
the jurisdiction requires one, LISTEN/NOTIFY for the KDS if screens grow beyond dozens, and — if the
per-branch counter ever becomes the bottleneck, partition numbering per terminal
(`ORD-{BRANCH}-{TERMINAL}-…`) if regulations allow.
