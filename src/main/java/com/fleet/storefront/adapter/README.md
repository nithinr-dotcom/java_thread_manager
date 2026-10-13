# adapter — the only place that knows Spring, SQL and HTTP

- `in/web/security`: API-key filter + role rules (ADR 0010). Every endpoint needs a rule there.
- `in/scheduling`: driving adapters on a timer (housekeeping, HQ catalog pull, bootstrap manager); they call
  `port.in` use cases only.
- Request data → commands only inside `Requests.parse(...)`: an `IllegalArgumentException` there is a 400;
  anywhere else it is a bug (500 `INTERNAL_ERROR`).
- `in/web`: controllers depend on `application.port.in` interfaces + domain types only. Never on
  `application.service`, `application.port.out` or `adapter.out` (ArchitectureTest). DTOs are records
  local to this package; every endpoint is documented in `docs/API.md`.
- `out/*`: implement `application.port.out` interfaces. Package-private `@Repository` classes, explicit SQL
  via `JdbcClient` (no JPA). Config values via `@Value("${storefront…}")` — never import `config.*`.
- `out/outbox`: relay (one thread per destination) + store + dispatcher SPI + metrics; `out/kitchen` and
  `out/hq` plug in as `OutboxDispatcher`s. Dispatchers run outside any transaction, must be idempotent
  (at-least-once, unordered) and throw `NonRetryableDispatchException` for failures retrying cannot fix.
- HTTP clients (`RestClient`, …) only in `out/hq` (ArchitectureTest).
