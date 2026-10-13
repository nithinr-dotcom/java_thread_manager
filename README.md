# Storefront Edge Node

Offline-first ordering, inventory and kitchen-dispatch engine for a single store.
Java 21 · Spring Boot 3.5 · PostgreSQL · React. Design notes: [`DESIGN.md`](DESIGN.md).

## Run it (backend + frontend + database)

**Option 1: only Docker needed** (any OS):

```bash
docker compose up --build
```

**Option 2: dev mode** (Linux or macOS; needs Docker running and Node 22+; JDK 21 is optional on Linux):

```bash
./scripts/dev.sh
```

Then open **http://localhost:5173**. No login needed: the page connects by itself (it waits while the
backend starts) and opens on **Scenario tests**. The first start takes a few minutes (dependencies and
images are downloaded). Stop with Ctrl+C (Option 1: then `docker compose down -v` to remove the data).
Ports taken? Prefix either command with `BACKEND_PORT=8081 FRONTEND_PORT=5174` (and `DB_PORT=55433` for
Option 1).

Option 2 uses a local JDK 21 if there is one; otherwise it runs Maven in Docker, which works on Linux only,
so on macOS without a JDK use Option 1. On Windows use Option 1 (or Option 2 inside WSL).

## What to try

The **Scenario tests** page lists these with buttons that open the right screen.

| Scenario | How to see it |
|---|---|
| **A** Rush on limited stock | Stress lab → pick `MEALBOX-CHICKEN` (10 in stock) → run the stock race with 50 checkouts: exactly 10 succeed, 40 get `409 OUT_OF_STOCK`, stock ends at 0. *Restock* to run it again. |
| **B** Gap-free fiscal numbers | The stock race also checks the winners' numbers (`ORD-HOSP01-YYYYMMDD-0001…`): unique, consecutive, no gaps. *Back office → Reports* verifies the day's fiscal chain. |
| **C** Retries & duplicates | *Idempotency demo*: check out, then *Retry same key* → same order replayed (`200`), no extra stock or number; same key with another basket → `422`. *Duplicate storm*: N identical requests at once → one order. |
| **D** Kitchen dispatch | Sell a *Classic burger* at the *POS* → the ticket appears on the *Kitchen* display. *Back office → Operations* shows outbox delivery. |
| **E** Exact money | Totals, tax and change are computed by the server in exact decimals and only displayed by the UI (*POS* receipt, *Reports*). |

## Test

```bash
./mvnw test       # fast: domain + use-case unit tests, no Spring context, no database, no Docker
./mvnw verify     # everything: unit tests + integration tests on real PostgreSQL (Testcontainers, needs Docker)
npm --prefix frontend ci && npm --prefix frontend run check   # frontend: typecheck, lint, tests
```
Needs JDK 21. On Linux without a JDK, use `./scripts/mvn-docker.sh verify` instead of `./mvnw verify`.

## Where each requirement is proven

| Requirement | Test |
|---|---|
| A: 50 threads on 10 units, zero overselling | [`CheckoutConcurrencyIT`](src/test/java/com/fleet/storefront/it/CheckoutConcurrencyIT.java) `fiftyTerminalsRaceForTenUnits…`; restocks during a race: [`RestockConcurrencyIT`](src/test/java/com/fleet/storefront/it/RestockConcurrencyIT.java) |
| A: no deadlocks (opposite lock orders) | `CheckoutConcurrencyIT` `multiLineOrdersLockingInOppositeOrder…` |
| B: numbers unique, gap-free, chronological | `CheckoutConcurrencyIT` (same race test), [`OrderNumberTest`](src/test/java/com/fleet/storefront/domain/OrderNumberTest.java), [`FiscalChainTest`](src/test/java/com/fleet/storefront/domain/FiscalChainTest.java) |
| C: duplicates within milliseconds, retries after commit, key reuse | `CheckoutConcurrencyIT` `fiftyIdenticalSubmissions…`, `mixedRetriesUnderContention…`, `retryAfterCommit…`, `reusingAKeyForADifferentBasket…` |
| D: kitchen never blocks checkout; no lost events after a crash | [`KitchenDispatchIT`](src/test/java/com/fleet/storefront/it/KitchenDispatchIT.java), [`OutboxReliabilityIT`](src/test/java/com/fleet/storefront/it/OutboxReliabilityIT.java), [`RelayIsolationIT`](src/test/java/com/fleet/storefront/it/RelayIsolationIT.java) |
| E: exact money, no mixed currencies | [`MoneyTest`](src/test/java/com/fleet/storefront/domain/MoneyTest.java), [`TaxRateTest`](src/test/java/com/fleet/storefront/domain/TaxRateTest.java), `CheckoutConcurrencyIT` `persistedOrderRoundTripsWithExactMoney` |
| Domain unit tests without Spring or a database | [`src/test/.../domain/`](src/test/java/com/fleet/storefront/domain/), [`application/service/`](src/test/java/com/fleet/storefront/application/service/) (in-memory fakes) |
| Clean boundaries, no `double`/`float` in money | [`ArchitectureTest`](src/test/java/com/fleet/storefront/architecture/ArchitectureTest.java) (ArchUnit) |
| Flyway migrations, never edited once applied | [`MigrationLockTest`](src/test/java/com/fleet/storefront/architecture/MigrationLockTest.java), [`db/migration/`](src/main/resources/db/migration/) |

## Calling the API directly (optional)

The API itself requires a device key; the demo one is `sf_demo_manager`.

```bash
# 50 parallel checkouts for the 10 meal boxes → exactly 10× 201, 40× 409
seq 1 50 | xargs -P 50 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/orders \
  -H 'Authorization: Bearer sf_demo_manager' -H 'Content-Type: application/json' -H 'Idempotency-Key: rush-{}' \
  -d '{"items":[{"sku":"MEALBOX-CHICKEN","quantity":1}]}' | sort | uniq -c
```

Full API reference: [`docs/API.md`](docs/API.md).
