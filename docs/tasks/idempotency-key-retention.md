---
status: done
adr: 0016
---
# Purge old idempotency keys

- [x] Retention property + scheduled purge (`HousekeepingScheduler` → `HousekeepingUseCase` → `JdbcHousekeeping`)
- [x] IT: purge never deletes a key younger than retention; replay inside retention still works (`ReportsAndHousekeepingIT`)
- [x] Index on `created_at` via a new migration (V2)
