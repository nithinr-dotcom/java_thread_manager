# Contributing — Storefront Edge Node

Read this first, then `DESIGN.md` (current design), `docs/adr/` (why), `docs/API.md` (HTTP contract) and
`docs/tasks/` (what's open). Some folders have a `README.md` with their own rules — read it when you work
there (`domain/`, `application/`, `adapter/`, `adapter/out/persistence/`, `frontend/`).

## Commands
```bash
./mvnw test                 # unit tests: domain, use cases (in-memory fakes), ArchUnit, SQL/migration guards
./mvnw verify               # + integration tests (*IT) on PostgreSQL via Testcontainers (Docker) + Spotless check
./mvnw spotless:apply       # fix import order / whitespace
./mvnw spring-boot:test-run # backend on :8080, throwaway Postgres, demo catalog + demo device keys (sf_demo_manager, sf_demo_pos, …)
npm --prefix frontend ci && npm --prefix frontend run dev   # store UI on :5173 (proxies /api to :8080)
npm --prefix frontend run check                            # typecheck + lint + format + vitest
```
No local JDK? Prefix Maven goals with `./scripts/mvn-docker.sh` instead of `./mvnw`.

## Architecture (clean architecture — enforced by `ArchitectureTest`)
```
config ──▶ adapter.in.web ─────▶ application.port.in  ◀── implements ── application.service
           adapter.out.* ──implements──▶ application.port.out ◀── uses ──┘        │
                                                          └──────────▶ domain ◀───┘
```
- `domain` imports **only** `java.*`. No Spring, Jackson, JDBC, annotations.
- `application` imports `domain` + `java.*` only. `port.in` = use-case interfaces + command/result
  records; `port.out` = interfaces only; `service` = implementations; `readmodel` = query projections.
- `adapter.in.web` depends on `port.in` interfaces only — never on `service`, `port.out` or `adapter.out`.
- `adapter.out.*` implements `port.out`; config values via `@Value`, never by importing `config`.
- `config.StorefrontConfiguration` is the composition root (`@Bean` per service). Nothing depends on it.

**Adding a use case:** input port + command/result records in `port.in` → service in `application.service`
→ any new `port.out` interface + its adapter → `@Bean` in `StorefrontConfiguration` → controller and role rule
in `SecurityConfiguration` → unit test with `InMemoryStorefront` (+ an IT if it takes locks) → `docs/API.md`.

**Adding a migration:** create the next `V{n}__*.sql`, then append `<sha256>  <path>` to
`src/test/resources/db/migration.lock` (`sha256sum <path>`). No `SEQUENCE`/`SERIAL`/`IDENTITY`.

## Invariants — never break these
Each is guarded by a test (ADR 0008). A failing guard means the code is wrong: never weaken a guard to get
green; superseding a rule needs a new ADR.
1. **No overselling**: stock is decremented only by the conditional atomic SQL in `JdbcInventoryLedger`
   (`... WHERE on_hand >= :qty`), and every stock change writes its `stock_movement` row in the same statement.
   Never read-modify-write stock in Java.
2. **Global lock order** in every transaction: idempotency key → order row (`FOR UPDATE`, lifecycle use cases
   only) → inventory rows (sorted by product id) → fiscal counter row (ADR 0011). Adding a new lock? Put it in
   this order, update DESIGN.md, add an ADR, extend `PlaceOrderServiceTest.acquiresLocksInTheFixedGlobalOrder`
   / `OrderLifecycleServiceTest`.
3. **Fiscal numbers** (series `ORD`, `REF`) are allocated *last*, inside the same transaction as the document
   insert (gap-free on rollback), with the document hash chained to the previous one (ADR 0013).
   Never use a Postgres `SEQUENCE` for it (sequences are non-transactional → gaps).
4. **Idempotency**: the claim (`INSERT … ON CONFLICT DO NOTHING`) is the *first* statement of every
   state-changing order/stock transaction; fingerprints are operation-prefixed.
5. **Kitchen/HQ notifications go through the transactional outbox only** — never call a downstream system
   from inside the checkout transaction. HTTP clients live only in `adapter.out.hq`.
6. **Money**: `BigDecimal` + `Currency` via `Money`. No `double`/`float` anywhere. Mixed currencies throw.
   The frontend never computes money totals — it displays server values.
7. **IDs**: UUIDv7 (`UuidV7`). Never `UUID.randomUUID()`, never auto-increment PKs.
8. **Auth**: the acting terminal is always the authenticated device (`@AuthenticationPrincipal`), never a
   header. New endpoint ⇒ add its role rule to `SecurityConfiguration` and `docs/API.md` (ADR 0010).
9. **Schema changes** only through new Flyway files `src/main/resources/db/migration/V{n}__*.sql`, appended
   to `src/test/resources/db/migration.lock`. Never edit an applied migration (`MigrationLockTest` fails).
   `spring.jpa`/DDL auto-gen is not used. Keep migrations backward-compatible (expand → contract); a
   migration that is not (e.g. V6) needs an ADR saying it is roll-forward only.

## Conventions
- Value objects are `record`s that validate in the compact constructor.
- Domain errors extend `DomainException`; the web layer maps them to RFC 7807 `ProblemDetail` with a stable `code`.
- Every endpoint is documented in `docs/API.md`; frontend types in `frontend/src/api/types.ts` mirror it.
- Tests: `*Test` = unit (surefire, no Spring), `*IT` = integration (failsafe, Testcontainers).
- Concurrency tests use `IntegrationTestSupport.runConcurrently` (a `CountDownLatch` start gate).
- Tasks: one file per task in `docs/tasks/` (see its README). Decisions: one ADR per decision in `docs/adr/`.

## Workflow
- Never commit to `main`; work on a branch, open a PR. CI (`.github/workflows/ci.yml`) must be green.

## Definition of done for a change
- [ ] `./mvnw verify` green; `npm --prefix frontend run check` + `build` green if `frontend/` changed
- [ ] New invariant ⇒ a domain unit test; new concurrency path ⇒ an IT under contention
- [ ] New/changed endpoint ⇒ `docs/API.md` (+ frontend types)
- [ ] Trade-off changed ⇒ new ADR + `DESIGN.md` updated
- [ ] `docs/tasks/<slug>.md` updated
