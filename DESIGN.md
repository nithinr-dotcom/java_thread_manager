# DESIGN — Storefront Edge Node

## 1. Architectural boundaries

Clean architecture in one Maven module, enforced by `ArchitectureTest` (ArchUnit):

| Layer | Package | May depend on |
|---|---|---|
| Domain | `domain.*` — `Money`, `Order`, `OrderNumber`, `UuidV7`, events, domain exceptions | `java.*` only |
| Use cases | `application.port.in` (use-case interfaces), `port.out` (interfaces it needs), `service` | domain |
| Adapters | `adapter.in.web` (REST), `adapter.out.*` (JDBC, outbox relay, kitchen inbox, HQ client) | ports, domain |
| Wiring | `config` — `@Bean` per service | everything |

Business rules (pricing, tax, currency safety, numbering, what goes to the kitchen) live in immutable
records, unit-tested without Spring or a database; use cases are tested with in-memory fakes, including the
lock order. Persistence is explicit SQL (`JdbcClient`), not JPA: the concurrency guarantees *are* the SQL
statements, so they should be visible and reviewable. The schema is owned by Flyway; no DDL generation.

## 2. Concurrency strategy

Every state change is **one short READ COMMITTED transaction** taking locks in a **fixed global order**:

1. **Idempotency key** — `INSERT … ON CONFLICT DO NOTHING` on `(terminal_id, key)`;
2. **Order row** — `SELECT … FOR UPDATE`, only for pay / cancel / refund (checkout locks no existing order);
3. **Inventory rows, sorted by product id** — conditional atomic update, see below;
4. **Fiscal counter row** — last; then insert documents, append outbox events, commit.

**Stock (A) — atomic conditional SQL.** `UPDATE inventory SET on_hand = on_hand - :q WHERE product_id = :p
AND on_hand >= :q`, with its `stock_movement` journal row in the same statement. A waiting writer
re-evaluates the condition against the committed row, so overselling is impossible; 0 rows ⇒ rollback ⇒
`409 OUT_OF_STOCK`. `CHECK (on_hand >= 0)` is the backstop. Rejected alternatives: *optimistic locking*
(under 50-way contention on one row nearly every attempt conflicts → retry storms); *`SELECT … FOR UPDATE`
then update* (same lock, an extra round-trip); *in-process queue per SKU* (fast, but a second source of
truth that is lost on crash).

**Deadlocks** cannot form because all transactions lock in the same order (multi-line orders sort their
products; proven by an IT that locks products in opposite orders). **Starvation** is bounded: `lock_timeout`
3 s, a capped connection pool (20, 3 s wait) and virtual threads turn overload into a fast, retryable
`503 BUSY_RETRY` with nothing committed — never a hung terminal.

**Fiscal numbers (B) — transactional counter row, taken last.** `INSERT … ON CONFLICT (branch, series, date)
DO UPDATE SET last_value = last_value + 1 RETURNING …` inside the order's transaction: a rollback returns the
number (gap-free), the row lock held until commit keeps numbers in commit order, and the timestamp is taken
while holding it (number order = time order). No external authority is needed. The counter serialises only
the tail of the transaction (a few inserts, ~1 ms); validation, pricing and stock locks on different SKUs
run in parallel before it. PostgreSQL `SEQUENCE`s were rejected: they are non-transactional and leave gaps.
Each document is also SHA-256-chained to the previous one, so edits or deletions are detectable.

## 3. Network retry handling (C)

Each logical checkout carries an `Idempotency-Key`, reused verbatim on retries; the terminal is the
authenticated device, so devices cannot collide. Claiming the key is the **first statement** of the
transaction. A duplicate arriving milliseconds later *blocks* on the unique index until the original ends:

- original **committed** → 0 rows inserted → return the stored order (`200`, `Idempotent-Replayed: true`);
  no stock touched, no number consumed;
- original **rolled back** (e.g. out of stock) → the duplicate's insert proceeds and it is evaluated afresh.

A fingerprint of the basket is stored with the key: reusing a key for a different basket gives
`422 IDEMPOTENCY_KEY_REUSED`. Failures are not memoised, so a retry after a restock can succeed. Payments,
cancellations, refunds and stock adjustments use the same claim-first pattern. Keys are purged after 7 days.

## 4. Downstream reliability (D) — transactional outbox

Checkout writes `KitchenTicketRequested` (only for prepared items) and `OrderPlaced` (for HQ) to
`outbox_event` **in the same commit** as the order: no order without its event, no event without its order,
and the kitchen is never on the checkout path — a slow or dead KDS cannot delay or roll back a payment.

A relay (one thread per destination, so a hung HQ link cannot delay kitchen tickets) claims due rows with a
**lease** (`UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)`), delivers outside any transaction, then
marks them published; failures are retried with capped exponential backoff, and permanent failures become
dead letters for an operator. If the node loses power after commit, the row is durable; if it dies
mid-delivery, the lease expires and the event is redelivered after restart. Delivery is **at-least-once**,
and the kitchen inbox is an idempotent consumer (`UNIQUE (order_id)`) ⇒ exactly one ticket per order.

## 5. Financial precision (E)

`Money(BigDecimal, Currency)`; an ArchUnit rule bans `double`/`float`. The scale is forced to the currency's
minor units with `RoundingMode.UNNECESSARY`, so accidental extra precision fails loudly; rounding happens
only in named operations (tax: per line, half-even). Mixed-currency arithmetic throws. Prices come from the
node's catalog, never the client; totals, change and refunds are computed by the server and only displayed.

## 6. Distributed sync readiness

- **Ids** are UUIDv7 generated on the node: no collisions across thousands of nodes without coordination,
  and time-ordered, so inserts at HQ stay index-friendly. No auto-increment keys anywhere.
- **Business keys** are globally unique by construction (`ORD-{BRANCH}-{DATE}-{SEQ}`); orders carry their
  origin node.
- **Ownership**: fiscal documents are immutable and owned by the issuing node (append-only ⇒ no write
  conflicts); later changes are separate events. The catalog is owned by HQ and synced last-writer-wins on a
  version number. Stock is local to the node.
- **Outbound sync** reuses the outbox: while the WAN is down events accumulate, and they drain when it
  returns — at-least-once, deduplicated at HQ by event id.

## 7. Further notes

Invariants are also enforced by tests (`ArchitectureTest`, `SqlInvariantsTest`, `MigrationLockTest` —
applied migrations are checksum-locked). Decisions and alternatives are recorded in [`docs/adr/`](docs/adr/);
operations and deployment in [`docs/OPERATIONS.md`](docs/OPERATIONS.md). Next steps: mTLS for devices, a
certified fiscal signing device where required, and per-terminal numbering if the branch counter ever
becomes the bottleneck.
