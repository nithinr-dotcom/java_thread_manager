# Storefront Edge Node

Offline-first ordering, inventory and kitchen-dispatch engine for a single store.
Java 21 · Spring Boot 3.5 · PostgreSQL · React. Design notes: [`DESIGN.md`](DESIGN.md).

## Prerequisites

- **Docker** (running) — a throwaway PostgreSQL is started automatically
- **Node 22+** — for the frontend
- **JDK 21** — optional; without it Maven runs inside Docker

## Run backend + frontend

```bash
./scripts/dev.sh
```

Open **http://localhost:5173**. No login needed: the page connects by itself (it waits while the
backend starts) and opens on **Scenario tests**. Ctrl+C stops both.
The first start takes a minute or two (dependencies and the PostgreSQL image are downloaded).
Ports taken? Use `BACKEND_PORT=8081 FRONTEND_PORT=5174 ./scripts/dev.sh`.

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
./mvnw verify                            # backend: unit + integration tests on real PostgreSQL
npm --prefix frontend ci && npm --prefix frontend run check   # frontend: typecheck, lint, tests
```
No JDK? Use `./scripts/mvn-docker.sh verify` instead of `./mvnw verify`.

The automated concurrency stress tests (50 parallel checkouts on 10 units, duplicate submissions,
retries) are in [`CheckoutConcurrencyIT`](src/test/java/com/fleet/storefront/it/CheckoutConcurrencyIT.java).

## Calling the API directly (optional)

The API itself requires a device key; the demo one is `sf_demo_manager`.

```bash
# 50 parallel checkouts for the 10 meal boxes → exactly 10× 201, 40× 409
seq 1 50 | xargs -P 50 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/orders \
  -H 'Authorization: Bearer sf_demo_manager' -H 'Content-Type: application/json' -H 'Idempotency-Key: rush-{}' \
  -d '{"items":[{"sku":"MEALBOX-CHICKEN","quantity":1}]}' | sort | uniq -c
```

Full API reference: [`docs/API.md`](docs/API.md).
