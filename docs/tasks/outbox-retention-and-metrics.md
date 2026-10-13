---
status: done
adr: 0014, 0016
---
# Outbox retention and metrics

- [x] Purge published `outbox_event` rows older than N days (batched, `SKIP LOCKED`)
- [x] Micrometer gauges: pending, dead, oldest pending age per destination; delivery counters (`/actuator/prometheus`)
- [x] Alert thresholds documented (`docs/OPERATIONS.md` §4)
