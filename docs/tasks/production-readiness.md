---
status: done
adr: 0010, 0011, 0012, 0013, 0014, 0015, 0016
---
# Production readiness

- [x] Fixes: per-destination outbox relay + HQ timeouts; `IllegalArgumentException` no longer masks bugs as 400; dead letters
- [x] Device authentication and roles; terminal identity from the credential
- [x] Payments, voids, cancellation, refund documents; stock movement journal; idempotent restock/adjust
- [x] Fiscal series + hash chain + monotonic document time; chain verification report; daily Z report
- [x] Kitchen workflow, stations, live SSE stream; catalog management + HQ sync; retention
- [x] Observability (metrics, JSON logs, request ids, probes), Dockerfiles, `deploy/`, backups, runbook, k6 load test
- [x] Role-based frontend (POS, KDS, back office, dev tools), durable idempotency, Playwright E2E
- [x] Independent invariant review; findings fixed (V8, V9)
