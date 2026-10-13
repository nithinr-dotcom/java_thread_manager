# Storefront Edge Node

An autonomous, offline-first **ordering, inventory and kitchen-dispatch engine** for a single store.
It runs on-premise, keeps trading with no connection to Central HQ, and syncs with HQ asynchronously
when the link comes back.

**Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Testcontainers** (+ an optional React store UI)

> **Reviewer quick path** (≈ 5 minutes):
> 1. `./mvnw verify` — runs every unit, architecture and concurrency test on real PostgreSQL.
> 2. Read [`DESIGN.md`](DESIGN.md) — architecture, concurrency strategy and trade-offs.
> 3. Open [`CheckoutConcurrencyIT`](src/test/java/com/fleet/storefront/it/CheckoutConcurrencyIT.java) —
>    the 50-thread stress test required by the brief.
> 4. Optionally start the node and try the [walkthrough](#3-try-it-by-hand) below.

---

## 1. Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| **Docker** (running) | integration tests, local run | Testcontainers starts a throwaway PostgreSQL 16 |
| **JDK 21** | building / running | *Optional* — without a JDK, use `./scripts/mvn-docker.sh` instead of `./mvnw` (runs Maven in a JDK 21 container) |
| Node 22 + npm | the React store UI only | Not needed to evaluate the backend |

No database installation is needed: the Maven wrapper is included and the database is started for you.

## 2. Run the tests

```bash
./mvnw test      # ~114 fast unit tests — domain, use cases with in-memory fakes, ArchUnit, SQL & migration guards (no Spring, no DB)
./mvnw verify    # + ~51 integration tests on real PostgreSQL via Testcontainers, + Spotless format check

# No local JDK? Same commands inside a container:
./scripts/mvn-docker.sh verify
```

What the brief asks for, and where it is proven:

| Requirement | Test |
|---|---|
| **A** — 50 terminals race for 10 units: exactly 10 succeed, 40 fail cleanly, stock = 0 | `CheckoutConcurrencyIT.fiftyTerminalsRaceForTenUnits_…` |
| **A** — no deadlocks when multi-line orders lock products in opposite order | `CheckoutConcurrencyIT.multiLineOrdersLockingInOppositeOrder_…`, `RestockConcurrencyIT`, `OrderLifecycleIT` |
| **B** — fiscal numbers unique, gap-free (1..N) and in time order under contention | `CheckoutConcurrencyIT.fiftyTerminalsRaceForTenUnits_…`, `OrderNumberTest`, `FiscalChainTest` |
| **C** — 50 identical requests within milliseconds → one order, stock −1×, one number | `CheckoutConcurrencyIT.fiftyIdenticalSubmissionsWithinMilliseconds_…` |
| **C** — 80 calls (40 checkouts × original + retry) under contention | `CheckoutConcurrencyIT.mixedRetriesUnderContention_…` |
| **C** — retry after commit returns the original order, consumes no stock/number | `CheckoutConcurrencyIT.retryAfterCommit_…` |
| **D** — kitchen outage never affects checkout; ticket delivered once it recovers | `KitchenDispatchIT.kitchenOutage_neverAffectsCheckout_…` |
| **D** — crash after commit is recovered, kitchen gets exactly one ticket | `KitchenDispatchIT.crashAfterCommit_…`, `OutboxReliabilityIT` |
| **E** — exact decimals, explicit rounding, mixed currencies rejected | `MoneyTest`, `TaxRateTest`, `OrderTest`, `ArchitectureTest.noFloatingPointAnywhere` |
| Clean architecture: domain has no framework imports | `ArchitectureTest` |
| Migrations immutable, no `SEQUENCE`/auto-increment, UUIDv7 ids only | `MigrationLockTest`, `ArchitectureTest`, `UuidV7Test` |
| Business invariants without Spring or a database | `src/test/java/.../domain/*Test`, `.../application/service/*Test` |

## 3. Try it by hand

Start the node (throwaway PostgreSQL + demo catalog + demo device keys) on `http://localhost:8080`:

```bash
./mvnw spring-boot:test-run          # or: ./scripts/mvn-docker.sh spring-boot:test-run
```
Startup takes ~30 s (it pulls PostgreSQL the first time). Port 8080 busy? Append
`-Dspring-boot.run.arguments=--server.port=8081` and use `localhost:8081` below.

Demo device keys (they exist only in this demo profile): `sf_demo_pos` (cashier), `sf_demo_kitchen`
(kitchen display), `sf_demo_manager` (back office). Demo stock: `MEALBOX-CHICKEN` = **10 units**.

```bash
POS='Authorization: Bearer sf_demo_pos'
JSON='Content-Type: application/json'
```

**Checkout** — returns `201` and a fiscal number such as `ORD-HOSP01-20261013-0001`:
```bash
curl -s -i -X POST localhost:8080/api/v1/orders -H "$POS" -H "$JSON" \
  -H 'Idempotency-Key: demo-1' -d '{"items":[{"sku":"BURGER-CLASSIC","quantity":2}]}'
```

**Scenario C — network retry.** Send the *same* request again: `200` + `Idempotent-Replayed: true`,
the same order and number, and no stock taken. Reusing the key for a different basket gives `422 IDEMPOTENCY_KEY_REUSED`.

**Scenario A — lunch rush.** 50 parallel checkouts for the 10 meal boxes:
```bash
seq 1 50 | xargs -P 50 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/orders \
  -H "$POS" -H "$JSON" -H 'Idempotency-Key: rush-{}' -d '{"items":[{"sku":"MEALBOX-CHICKEN","quantity":1}]}' \
  | sort | uniq -c
#   10 201   ← exactly the stock
#   40 409   ← clean OUT_OF_STOCK problem responses
```

**Scenario B — fiscal numbers.** List the orders: numbers are consecutive, with no gaps from the 40 failed checkouts:
```bash
curl -s 'localhost:8080/api/v1/orders?limit=20' -H "$POS" | grep -o 'ORD-[A-Z0-9]*-[0-9]*-[0-9]*' | sort
curl -s localhost:8080/api/v1/reports/fiscal-chain -H 'Authorization: Bearer sf_demo_manager'   # hash-chain check
```

**Scenario D — kitchen dispatch.** The burger order (prepared food) produced a kitchen ticket via the outbox:
```bash
curl -s localhost:8080/api/v1/kitchen/tickets -H 'Authorization: Bearer sf_demo_kitchen'
curl -s localhost:8080/api/v1/ops/outbox      -H 'Authorization: Bearer sf_demo_manager'   # backlog / delivered / dead letters
```

**Scenario E — money.** Every amount in the responses is computed on the server with `BigDecimal` in the
branch currency (e.g. `11.40 × 2` + 8.25% tax per line, half-even).

The full HTTP contract, roles and error codes are in [`docs/API.md`](docs/API.md).

### Optional: store UI
```bash
npm --prefix frontend ci && npm --prefix frontend run dev    # http://localhost:5173 (proxies /api to :8080)
```
Sign in with one of the demo keys above: POS terminal, kitchen display, and a back office with a
"stress lab" that fires concurrent checkouts and duplicate submissions from the browser.

### Optional: load test
With the node running: `docker run --rm -i --network host grafana/k6:0.54.0 run - < loadtest/checkout.js` —
50 virtual terminals; the thresholds require checkout p99 < 1 s and zero oversell.

## 4. How it works (one paragraph per scenario — details in [`DESIGN.md`](DESIGN.md))

- **A — no overselling.** Stock is taken by one conditional statement,
  `UPDATE inventory SET on_hand = on_hand - :q WHERE product_id = :p AND on_hand >= :q`, plus its
  `stock_movement` journal row in the same statement. Zero rows updated ⇒ `409 OUT_OF_STOCK`. Every transaction
  locks in one global order (idempotency key → order row → products sorted by id → fiscal counter), so
  deadlocks cannot form; `lock_timeout` and a bounded pool turn overload into a fast `503 BUSY_RETRY`.
- **B — gap-free fiscal numbers.** A counter row per (branch, series, day) is incremented by an upsert
  *inside* the order transaction, as the last lock taken: a rollback returns the number, the row lock keeps
  numbers in commit order, and no external authority is needed. Postgres `SEQUENCE`s were rejected (gaps on rollback).
- **C — retries.** `Idempotency-Key` per logical checkout. Claiming it (`INSERT … ON CONFLICT DO NOTHING`)
  is the first statement of the transaction; a concurrent duplicate blocks on the unique index until the
  original commits, then replays the stored outcome. A fingerprint of the basket detects key reuse.
- **D — kitchen dispatch.** The kitchen ticket and the HQ event are written to an `outbox_event` table in
  the same commit as the order and delivered afterwards by a relay (lease + `SKIP LOCKED`, retries with
  backoff, dead letters). The kitchen side is an idempotent consumer, so at-least-once delivery yields
  exactly one ticket, and the kitchen is never on the checkout path.
- **E — money.** `Money(BigDecimal, Currency)` with the currency's scale enforced; rounding only in named
  operations; mixing currencies throws. An ArchUnit rule bans `double`/`float` in the codebase.
- **Sync readiness.** All ids are UUIDv7 generated on the node (no collisions across nodes, time-ordered);
  documents carry their origin node; fiscal documents are immutable and owned by the issuing node.

## 5. Project layout

```
src/main/java/com/fleet/storefront
├── domain/                 pure Java: money, catalog, inventory, order (+ events), fiscal, payment, refund
├── application/
│   ├── port/in/            use-case interfaces + command/result records
│   ├── port/out/           interfaces the use cases need (InventoryLedger, FiscalSequence, IdempotencyStore, Outbox, …)
│   ├── service/            use-case implementations (framework-free)
│   └── readmodel/          read-only projections
├── adapter/
│   ├── in/web/             REST controllers, SSE stream, RFC 7807 error mapping, API-key security
│   ├── in/scheduling/      housekeeping, HQ catalog pull
│   └── out/                persistence (explicit SQL), outbox relay, kitchen inbox, HQ client
└── config/                 composition root (@Bean wiring)
src/main/resources/db/migration   Flyway migrations (no ORM DDL generation)
src/test/java/.../domain          domain unit tests (no Spring, no DB)
src/test/java/.../application     use-case tests with in-memory fakes (incl. lock order)
src/test/java/.../architecture    ArchUnit, SQL and migration guards
src/test/java/.../it              integration & concurrency tests (Testcontainers)
frontend/                         optional React store UI (POS, kitchen display, back office)
deploy/, docs/OPERATIONS.md       production compose stack and runbook
docs/adr/                         architecture decision records
```

## 6. Further reading

- [`DESIGN.md`](DESIGN.md) — architecture & trade-offs
- [`docs/API.md`](docs/API.md) — HTTP contract
- [`docs/adr/`](docs/adr/README.md) — why each decision was made
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — invariants and conventions for changes
- [`docs/OPERATIONS.md`](docs/OPERATIONS.md) — running it in a store
- [`CANDIDATE_ASSIGNMENT.md`](CANDIDATE_ASSIGNMENT.md) — the original brief
