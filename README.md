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

Then open **http://localhost:5173** and sign in with a demo key. Ctrl+C stops both.
The first start takes a minute or two (dependencies and the PostgreSQL image are downloaded).
Ports taken? Use `BACKEND_PORT=8081 FRONTEND_PORT=5174 ./scripts/dev.sh`.

| Demo key | Role | Can use |
|---|---|---|
| `sf_demo_manager` | Manager | back office, POS, kitchen — everything |
| `sf_demo_pos` | Cashier | POS (checkout, payments) |
| `sf_demo_kitchen` | Kitchen | kitchen display |

Demo stock: `MEALBOX-CHICKEN` has 10 units — useful for trying the oversell protection.

## Test

```bash
./mvnw verify                            # backend: unit + integration tests on real PostgreSQL
npm --prefix frontend ci && npm --prefix frontend run check   # frontend: typecheck, lint, tests
```
No JDK? Use `./scripts/mvn-docker.sh verify` instead of `./mvnw verify`.

The concurrency stress tests (50 parallel checkouts on 10 units, duplicate submissions, retries) are in
[`CheckoutConcurrencyIT`](src/test/java/com/fleet/storefront/it/CheckoutConcurrencyIT.java).

## Try the API directly

With the app running:

```bash
# Checkout (201). Run it again with the same Idempotency-Key → 200 + Idempotent-Replayed: true
curl -i -X POST localhost:8080/api/v1/orders -H 'Authorization: Bearer sf_demo_pos' \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
  -d '{"items":[{"sku":"BURGER-CLASSIC","quantity":2}]}'

# 50 parallel checkouts for the 10 meal boxes → exactly 10× 201, 40× 409
seq 1 50 | xargs -P 50 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/orders \
  -H 'Authorization: Bearer sf_demo_pos' -H 'Content-Type: application/json' -H 'Idempotency-Key: rush-{}' \
  -d '{"items":[{"sku":"MEALBOX-CHICKEN","quantity":1}]}' | sort | uniq -c
```

Full API reference: [`docs/API.md`](docs/API.md).
