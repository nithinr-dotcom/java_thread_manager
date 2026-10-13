---
status: done
adr: 0002, 0008, 0009
---
# Clean architecture, mechanised invariants, test console

- [x] Restructure into `domain / application.port.{in,out} / application.service / adapter.{in,out} / config`
- [x] Controllers depend on input-port interfaces only; adapters no longer import `config`
- [x] ArchUnit: clean-architecture rules + no float/double + no `UUID.randomUUID` + HTTP clients only in HQ adapter
- [x] `SqlInvariantsTest`, `MigrationLockTest` (+ lock file), lock-order unit test
- [x] Read/ops API: recent orders, catalog with stock, restock, kitchen tickets, outbox status (`docs/API.md`)
- [x] `RestockConcurrencyIT` (restocks racing checkouts on the same row)
- [x] `CONTRIBUTING.md` + per-layer README rules
- [x] CI (backend verify + frontend check/build), CODEOWNERS, PR template, Dependabot, Spotless
- [x] `frontend/` React test console (POS, stress lab, orders, KDS, ops)
