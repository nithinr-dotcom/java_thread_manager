# Storefront store app (frontend)

The in-store web application for the storefront edge node: a touch-first **POS**, a **Kitchen
display (KDS)**, a manager **Back office** and **Developer tools**. One build serves every device;
what a device sees is decided by the **role of its API key**. The HTTP contract is
`../docs/API.md` (source of truth; `src/api/types.ts` mirrors it).

Stack: Vite 6 + React 18 + TypeScript (strict), hand-written CSS (light/dark, print), Vitest +
Testing Library, Playwright (E2E), ESLint (flat config) + Prettier. No UI library, no router, no
runtime dependency besides React.

## Run it

```bash
# 1. backend (repo root) — throwaway Postgres + demo catalog + demo API keys
./mvnw spring-boot:test-run

# 2. app
cd frontend
npm ci
npm run dev            # http://localhost:5173 (proxies /api and /actuator to :8080)
```

Override the backend with `BACKEND_URL=http://host:port npm run dev`. If the backend is down the
dev proxy answers `502` + `X-Proxy-Error`; the app shows an "unreachable" banner and keeps retrying.

| Script            | What it does                                                 |
| ----------------- | ------------------------------------------------------------ |
| `npm run dev`     | Vite dev server on :5173 with the API proxy                  |
| `npm run build`   | `tsc -b && vite build` → `dist/`                             |
| `npm run preview` | Serve `dist/` on :4173 (same proxy)                          |
| `npm run check`   | typecheck + lint + format:check + unit/component tests       |
| `npm test`        | Vitest once (`npm run test:watch` to watch)                  |
| `npm run e2e`     | Playwright against a running backend (see [E2E](#e2e-tests)) |
| `npm run format`  | Prettier write                                               |

## Signing in (device pairing) and roles

Every API call carries `Authorization: Bearer <apiKey>`. On first start the device shows **Pair
this device**: enter the key a manager issued for it (Back office → Devices). The key is checked
with `GET /api/v1/me` and stored on the device (`localStorage`, via `shared/storage.ts`) together
with its identity; **Sign out** removes it. A `401` from any call (key revoked) signs the device
out. When the node is unreachable the stored identity keeps working (offline-first).

| Role      | Apps                                                     |
| --------- | -------------------------------------------------------- |
| `POS`     | POS                                                      |
| `KITCHEN` | Kitchen display                                          |
| `MANAGER` | POS, Kitchen display, Back office, Developer tools       |
| `HQ`      | none — "not a UI role" (machine-to-machine catalog sync) |
| `MONITOR` | none — "not a UI role" (metrics scraping)                |

Demo keys seeded by the backend demo profile: `sf_demo_manager`, `sf_demo_pos`,
`sf_demo_kitchen`, `sf_demo_hq` (terminals `demo-manager`, `demo-pos`, …).

## Apps

**POS** (`#/pos`) — large product tiles with category tabs and search; out-of-stock / inactive
products are disabled. Cart with +/− steppers and unit prices; the total is **never computed on
the client** ("Total calculated at checkout"). Checkout → the server returns the order (subtotal,
tax, total, amount due) → **Payment**: cash (amount received + quick buttons _Exact_ = server
`amountDue`, 5/10/20/50) or card (approval code from the card terminal). Lowering the amount splits
the bill; each payment returns the new `amountDue`. Change is computed by the server. → **Receipt**
(printable: branch, order number, lines, totals, payments, change, short fiscal hash) → New sale.
Payments of an order still awaiting payment (an abandoned split tender) can be voided (in-page
confirmation + reason; voided payments are struck through and no longer count, the new `amountDue`
comes from the server); an order without unvoided payments can be cancelled with a reason. While the node is unreachable checkout is
disabled and a banner says so. An order being paid survives a reload.

**Kitchen display** (`#/kitchen`) — columns Queued / In progress / Ready, big bump buttons
(Start, Ready, Served, Recall — exactly the transitions in API.md), a per-ticket age timer
(amber > 5 min, red > 10 min), a station filter (from the catalog, remembered per device) and an
optional new-ticket beep (off by default). Tickets arrive over `GET /kitchen/stream` (SSE read with
`fetch` + `ReadableStream`, because `EventSource` cannot send the `Authorization` header), with
exponential-backoff reconnects and a REST resync on every (re)connect; after 3 failed connections
it falls back to polling `GET /kitchen/tickets?active=true` until the stream is back. Cancelled
tickets are shown struck through for a few seconds. A `409 TICKET_STATE_CONFLICT` refreshes the
board.

**Back office** (`#/backoffice/…`, MANAGER)

- _Orders_ — status filter, detail (lines with refunded quantity, payments, refunds), cancel
  (awaiting payment, no unvoided payments), void payment, refund (line quantities, restock, method, reason; amounts by the
  server), reprint receipt.
- _Catalog_ — products table, create, edit (sends `version`; on `409 VERSION_CONFLICT`: "changed
  by someone else — reload"), activate / deactivate.
- _Inventory_ — restock and adjustment (± delta, reason, note) as durable idempotent actions,
  stock-movement history.
- _Devices_ — list, register any role incl. `MONITOR` (the new API key is shown **once** with a copy button), revoke
  (in-page confirmation; the last active manager cannot be revoked).
- _Operations_ — outbox per destination (pending / published / failing / dead / oldest age / last
  error), dead letters with Retry, and "Retry all dead letters" per destination.
- _Reports_ — daily Z-report for a business date (branch time zone) and fiscal hash-chain
  verification; all values come from the server.

**Developer tools** (`#/devtools`, MANAGER) — clearly labelled test tools that create real
(unpaid) orders: the _Idempotency demo_ (Checkout / Retry same key / Same key, different basket /
New key, showing status and `Idempotent-Replayed`) and the _Stress lab_ (stock race and duplicate
storm with automatic checks).

## Durable idempotency (no double charges, no lost sales)

Every state-changing order or stock call (checkout, payment, payment void, cancel, refund,
restock, stock adjustment) is a **pending action**
(`domain/idempotency.ts`, `features/common/usePendingAction.ts`):

1. A fresh `Idempotency-Key` is generated and the action (payload + key) is written to storage
   **before** the request is sent.
2. A **definitive** outcome — `2xx`, or a `4xx` other than `408`/`429` — removes it; the next action
   gets a new key.
3. An **ambiguous** outcome — network error, timeout (10 s, reported as status 0 / `TIMEOUT`),
   `408`, `429`, `5xx`, `503 BUSY_RETRY` — keeps it with the **same key**; it is retried
   automatically with exponential backoff (1 s, 2 s, 4 s), then manually.
4. After a reload or crash a banner offers **Retry** (same key → the server replays the committed
   result, `Idempotent-Replayed: true`) or **Discard** (with an in-page confirmation).

## Production serving

Build once (`npm run build`) and serve `dist/` from the **same origin** as the API, e.g. nginx in
front of the edge node, so no CORS is needed and the API key never leaves the store network:

```nginx
server {
  listen 80;
  root /srv/storefront/dist;

  location / { try_files $uri /index.html; }           # SPA (hash routes)
  location /assets/ { expires 1y; add_header Cache-Control "public, immutable"; }

  location /api/v1/kitchen/stream {                    # SSE: no buffering, long-lived
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
  }
  location /api/      { proxy_pass http://127.0.0.1:8080; }
  location /actuator/ { proxy_pass http://127.0.0.1:8080; }
}
```

The app polls the public `/actuator/health` to show online / offline.

## E2E tests

Playwright specs in `e2e/` run against a real backend started with the demo profile (seeded keys
and catalog SKUs `MEALBOX-CHICKEN`, `WATER-SPARKLING`, `BURGER-CLASSIC`, `LATTE-LARGE`):

```bash
./mvnw spring-boot:test-run          # repo root, backend on :8080
npx playwright install chromium      # once
npm run e2e                          # starts `npm run dev` unless one is running
E2E_BASE_URL=https://store.local npm run e2e   # against another deployment (no dev server)

# other ports, e.g. backend on :18080 and Vite on :5199:
BACKEND_URL=http://localhost:18080 npx vite --port 5199
E2E_BASE_URL=http://localhost:5199 npm run e2e
```

Covered: sign-in per role, checkout + cash payment + receipt with change, replay safety (response
lost after commit → reload → retry with the same key returns the same order), a kitchen ticket
arriving live and bumped to served, and a manager refund.

## Layout

```
src/
  api/       types.ts (DTOs per docs/API.md), client.ts (never-throwing fetch: auth header, timeout,
             401 hook), endpoints.ts (one function per endpoint), stream.ts (KDS SSE over fetch)
  domain/    pure TS + unit tests: cart, idempotency (keys + durable pending actions), sse (parser),
             backoff, kitchen (transitions, ages, board), refund (selection rules), decimal (input
             parsing), routes (roles → apps, hash routes), session, dates, stress
  features/  auth/, pos/, kitchen/, backoffice/{orders,catalog,inventory,devices,operations,reports}/,
             devtools/, stress/, common/ (pending actions, receipt, navigation)
  shared/    i18n (+ messages.en.ts), formatting (Intl money/dates), storage, session & reachability
             contexts, usePolling, useNow, UI primitives
  app/       App.tsx (sign-in, shell, routing, focus management), ErrorBoundary, styles.css
e2e/         Playwright specs + support.ts
```
