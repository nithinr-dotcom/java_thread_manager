# domain — entities & business rules (innermost ring)

- Imports `java.*` and `domain.*` only. No Spring, Jackson, JDBC, annotations, logging (ArchitectureTest).
- Value objects are `record`s that validate in the compact constructor; aggregates (`Order`) are final
  classes with private constructors and named factories (`place`, `restore`).
- Money: `Money` (BigDecimal + Currency). No `double`/`float` anywhere; rounding only via a named
  `RoundingMode` (tax = per line, HALF_EVEN). Mixed currencies throw `CurrencyMismatchException`.
- IDs: `UuidV7.next()`. Never `UUID.randomUUID()`.
- Business-rule violations extend `DomainException` with a stable UPPER_SNAKE `code` — the web layer and
  the frontend key off that code, so never rename an existing one.
- Every new rule gets a fast unit test in `src/test/java/.../domain` (no Spring context).
