# Operations runbook — storefront edge node

For store IT / fleet operations. Design background: [`DESIGN.md`](../DESIGN.md), decisions: [`adr/`](adr/README.md).

## 1. What runs in a store

| Container | Image | Purpose |
|---|---|---|
| `db` | `postgres:16` | The node's database — **the only copy of fiscal records until they reach HQ** |
| `app` | `storefront-edge-node` (root `Dockerfile`) | API, outbox relay, schedulers. Profile `prod` |
| `proxy` | `storefront-ui` (`frontend/Dockerfile`) | nginx: TLS, the UI (POS, kitchen display, back office), `/api` proxy |
| `backup` | `postgres:16` | Nightly `pg_dump` with retention (`scripts/backup.sh`) |

Hardware: SSD, UPS (power loss must not corrupt the disk — PostgreSQL runs with `fsync=on`,
`synchronous_commit=on`, `full_page_writes=on`; never disable them to gain speed), wired LAN for the node.

## 2. First installation

```bash
docker build -t storefront-edge-node:1.0.0 .
docker build -t storefront-ui:1.0.0 frontend
cd deploy
cp .env.example .env                       # fill in branch code, currency, zone, node id
mkdir -p secrets tls && openssl rand -base64 32 > secrets/db_password   # same value as STOREFRONT_DB_PASSWORD
# TLS: put the store certificate as tls/tls.crt + tls/tls.key (issued by your internal CA)
STOREFRONT_BOOTSTRAP_MANAGER_KEY=$(openssl rand -base64 33) ; echo "$STOREFRONT_BOOTSTRAP_MANAGER_KEY"  # put in .env
STOREFRONT_IMAGE=storefront-edge-node:1.0.0 STOREFRONT_UI_IMAGE=storefront-ui:1.0.0 docker compose up -d
```

Then on a back-office browser: open `https://<node>/`, sign in with the bootstrap key, **Back office →
Devices**: register a named manager device, one device per POS/kiosk/KDS screen (each key is shown once —
enter it on the device), and a `MONITOR` device for the metrics scraper. Revoke `manager-bootstrap` and
remove `STOREFRONT_BOOTSTRAP_MANAGER_KEY` from `.env`. A lost/stolen device: revoke it; its key stops working
immediately (open kitchen streams end within 30 min).

The `prod` profile refuses to start without database, branch and node settings — there are no defaults.

## 3. Configuration (environment)

| Variable | Meaning |
|---|---|
| `STOREFRONT_BRANCH_CODE`, `_CURRENCY`, `_ZONE` | Fiscal branch code, trading currency, time zone defining the business day |
| `STOREFRONT_NODE_ID` | Unique node identity stamped on every record sent to HQ |
| `STOREFRONT_DB_URL`, `_USER`, `_PASSWORD` | Database |
| `STOREFRONT_HQ_URL`, `STOREFRONT_HQ_API_KEY` | Central HQ; empty = offline mode (events queue in the outbox) |
| `STOREFRONT_BOOTSTRAP_MANAGER_KEY` | First start only (≥ 32 chars) |

Tunables (`application.yml`, override with env `STOREFRONT_…`): checkout `lock-timeout` (3 s), Hikari pool
(20), outbox poll (200 ms), retention (7 days), KDS stream poll (1 s).

## 4. Monitoring and alerts

Scrape `http://127.0.0.1:8080/actuator/prometheus` with the `MONITOR` device key as bearer token.
Probes: `/actuator/health/liveness`, `/actuator/health/readiness` (readiness includes the database).
Logs are JSON (ECS) on stdout with `requestId`, `terminalId`, `idempotencyKey`; every response carries
`X-Request-Id` — ask staff for it when they report an error.

| Alert | Expression (PromQL sketch) | Meaning / first action |
|---|---|---|
| Kitchen lag | `storefront_outbox_oldest_pending_age_seconds{destination="KITCHEN"} > 30` | Tickets not reaching the kitchen: check app logs for `outbox-relay-kitchen`, DB health |
| Dead letters | `storefront_outbox_dead > 0` | Back office → Operations → inspect `lastError`; fix cause; Retry |
| HQ backlog | `storefront_outbox_oldest_pending_age_seconds{destination="HQ"} > 86400` | WAN or HQ down > 1 day — expected offline tolerance, but tell HQ |
| Checkout latency | `histogram_quantile(0.99, sum by (le) (rate(storefront_orders_operation_seconds_bucket{operation="checkout"}[5m]))) > 1` | SLO breach; check `BUSY_RETRY` rate, DB load |
| Shedding load | `rate(storefront_orders_operation_seconds_count{outcome="BUSY_RETRY"}[5m]) > 1` | Lock/pool timeouts; terminals retry safely, but investigate (slow disk, long transactions) |
| Pool saturation | `hikaricp_connections_pending > 0` for 5 m | Increase pool only together with DB `max_connections` |
| Node down | readiness probe failing | Restart container; check DB volume / disk space |

## 5. Backups and restore

- **Continuous**: WAL is archived every ≤ 60 s to the `wal-archive` volume → point-in-time recovery
  (with a base backup: `pg_basebackup -D /backups/base-$(date +%F) -Ft -z -X stream` weekly).
- **Nightly**: `backup` container → `/backups/storefront-<UTC stamp>.dump`, verified readable, 14 days kept.
- **Copy off the node**: sync the `backups` and `wal-archive` volumes to store NAS/HQ storage when the WAN is
  up (the node itself must never depend on it). Data on the node alone is lost if the disk dies.
- **Restore** (node stopped): create an empty database, `scripts/restore.sh <dump>`, start the node. Flyway
  validates the schema; the outbox re-sends anything not yet published (consumers are idempotent). Then
  check `GET /api/v1/reports/fiscal-chain` for the affected days. **Test a restore quarterly.**

## 6. Upgrades and rollback

- Images are versioned; roll out to a pilot store, then waves. Upgrade outside opening hours when possible:
  `docker compose pull && docker compose up -d app` (graceful shutdown finishes in-flight checkouts; the
  relay stops cleanly; unfinished deliveries resume after start).
- **Take a backup before every upgrade.** Migrations run automatically on start and are forward-only.
- Migrations are written expand → contract so the previous release keeps working on the new schema —
  **except V6** (fiscal series): after V6 the pre-V6 release cannot run. Rollback across V6 = restore the
  pre-upgrade backup (orders taken in between must be re-entered, so pilot first). ADR 0013.
- Terminals keep working through an app restart: ambiguous requests are retried with the same
  Idempotency-Key, which replays instead of duplicating.

## 7. Day-to-day procedures

- **End of day**: Back office → Reports → daily (Z) report and fiscal-chain check for the day. An invalid
  chain means the database was modified outside the application: preserve a backup and escalate.
- **Unpaid orders at close**: the daily report shows `unpaidTotal`; void abandoned tenders and cancel the
  orders (stock is returned).
- **Stock**: deliveries via Restock (journaled); waste/damage/count corrections via Adjustments with a note.
- **HQ outage**: nothing to do; orders keep flowing, HQ events queue. After a long outage watch the HQ
  backlog drain. If HQ rejected events (dead letters), fix and **Retry all** for `HQ`.
- **Clock**: keep NTP on. If the clock steps back, document times stay monotonic (ADR 0013); a large step
  around midnight can assign documents to the previous business day — avoid manual clock changes.

## 8. Load testing

`loadtest/checkout.js` (k6) runs a 50-terminal rush on a 10-unit item plus steady mixed traffic and fails
on p99 > 1 s or any unexpected status. Run it against a staging node with the demo profile before every
release:
```bash
docker run --rm -i --network host -e BASE_URL=http://localhost:8080 -e API_KEY=sf_demo_pos \
  grafana/k6:0.54.0 run - < loadtest/checkout.js
```
