# HTTP API contract — `/api/v1`

Source of truth for the backend (`adapter.in.web`) and the frontend (`frontend/src/api`).
Change this file in the same PR as any endpoint change.

## Conventions

- **Authentication** ([ADR 0010](adr/0010-per-device-api-keys.md)): every `/api/v1/**` call needs
  `Authorization: Bearer <apiKey>`. A key belongs to one registered device (terminal) with one role.
  The terminal id is taken from the credential; `X-Terminal-Id` is optional and, if sent, must equal it.
- **Roles**: `POS` (cashier, kiosk), `KITCHEN` (KDS screens), `MANAGER` (back office, everything),
  `HQ` (Central HQ sync only), `MONITOR` (metrics scraping only). Each endpoint lists the roles allowed.
- **Errors** are RFC 7807 `application/problem+json` with a stable `code` property (never renamed).
- **Money** fields are JSON numbers serialised from `BigDecimal` at the currency's scale (e.g. `27.39`).
  Request money fields are parsed as decimals; more precision than the currency allows is a `400`.
  The client never computes money totals — it displays server values.
- **Timestamps** are ISO-8601 UTC strings. **IDs** are UUIDv7 strings. **Dates** are `YYYY-MM-DD`
  business dates in the branch time zone.
- **Idempotency**: every state-changing call on orders and stock (`POST /orders`, `/payments`,
  `/payments/{id}/void`, `/cancel`, `/refunds`, `/inventory/{sku}/restock`, `/inventory/{sku}/adjustments`)
  needs an `Idempotency-Key` (1..128 chars, one per logical action, reused verbatim on
  retries). A retry of a committed action returns `200` + `Idempotent-Replayed: true` with the
  resource's **current** state and has no side effects. Reusing a key for a different payload or
  action → `422 IDEMPOTENCY_KEY_REUSED`. Keys are kept for 7 days (configurable).
- Every response carries `X-Request-Id` (echoed from the request if sent, generated otherwise).

| Status | `code` | Meaning |
|---|---|---|
| 400 | `INVALID_REQUEST` | Malformed input / missing headers / out-of-range values |
| 401 | `UNAUTHENTICATED` | Missing, unknown or revoked API key |
| 403 | `FORBIDDEN` | Role not allowed for this endpoint |
| 403 | `TERMINAL_MISMATCH` | `X-Terminal-Id` differs from the authenticated terminal |
| 404 | `ORDER_NOT_FOUND`, `PAYMENT_NOT_FOUND`, `TICKET_NOT_FOUND`, `TERMINAL_NOT_FOUND`, `DEAD_LETTER_NOT_FOUND` | Unknown resource |
| 409 | `OUT_OF_STOCK` | Not enough stock; nothing committed. Extra props: `sku`, `requested` |
| 409 | `ORDER_NOT_PAYABLE`, `ORDER_NOT_CANCELLABLE`, `ORDER_NOT_REFUNDABLE` | Order is in the wrong state |
| 409 | `TICKET_STATE_CONFLICT` | Illegal or concurrent kitchen-ticket transition |
| 409 | `VERSION_CONFLICT` | Product was changed concurrently; reload and retry. Extra prop: `currentVersion` |
| 409 | `PRODUCT_EXISTS`, `TERMINAL_EXISTS`, `CANNOT_REVOKE_SELF`, `LAST_MANAGER`, `PAYMENT_ALREADY_VOIDED` | Uniqueness / safety rule |
| 422 | `UNKNOWN_PRODUCT`, `PRODUCT_INACTIVE`, `IDEMPOTENCY_KEY_REUSED`, `CURRENCY_MISMATCH` | Business rule violated |
| 422 | `PAYMENT_EXCEEDS_DUE`, `INSUFFICIENT_TENDER`, `REFUND_EXCEEDS_SOLD`, `UNKNOWN_ORDER_LINE` | Business rule violated |
| 500 | `INTERNAL_ERROR` | Bug; details only in the server log (correlate with `X-Request-Id`) |
| 503 | `BUSY_RETRY` | Lock/pool timeout; nothing committed; retry with the **same** `Idempotency-Key` (`Retry-After: 1`) |

## Session

### `GET /api/v1/me` — any role
```json
{ "terminalId": "pos-1", "name": "Front counter", "role": "POS",
  "branch": { "code": "HOSP01", "currency": "USD", "zone": "America/New_York" } }
```

## Orders

Order `status`: `PLACED` (issued, awaiting payment) → `PAID` → `PARTIALLY_REFUNDED` → `REFUNDED`;
`PLACED` with no (unvoided) payment → `CANCELLED`. The fiscal content (number, lines, totals) never changes.

### `POST /api/v1/orders` — checkout — `POS`, `MANAGER`
Headers: `Idempotency-Key`. Body:
```json
{ "items": [ { "sku": "BURGER-CLASSIC", "quantity": 2 } ] }
```
`items` 1..100, `quantity` 1..999. → `201` + `Idempotent-Replayed: false` with `Order`, or `200` + `true` on replay.
Errors: `409 OUT_OF_STOCK`, `422 UNKNOWN_PRODUCT | PRODUCT_INACTIVE | IDEMPOTENCY_KEY_REUSED`, `503 BUSY_RETRY`.

`Order`:
```json
{
  "id": "0192…", "orderNumber": "ORD-HOSP01-20261013-0001", "status": "PLACED",
  "terminalId": "pos-1", "placedAt": "2026-10-13T12:00:00.123456Z", "currency": "USD",
  "subtotal": 25.30, "taxTotal": 2.09, "total": 27.39,
  "amountPaid": 0.00, "amountDue": 27.39, "amountRefunded": 0.00,
  "fiscalHash": "9f2c…(64 hex)",
  "lines": [ { "lineNo": 1, "sku": "BURGER-CLASSIC", "name": "Classic burger", "quantity": 2,
               "unitPrice": 11.40, "taxRatePercent": 8.25, "tax": 1.88, "total": 24.68,
               "refundedQuantity": 0 } ],
  "payments": [ Payment ],
  "refunds": [ Refund ]
}
```
`fiscalHash` is `null` for documents issued before hash chaining was introduced.

### `GET /api/v1/orders/{id}` — `POS`, `MANAGER` → `200 Order` | `404 ORDER_NOT_FOUND`

### `GET /api/v1/orders?limit=20&status=PAID` — `POS`, `MANAGER` → `200 Order[]`
Most recent first (by `placedAt`, then order number). `limit` 1..200 (default 20); `status` optional filter.

### `POST /api/v1/orders/{id}/payments` — `POS`, `MANAGER`
Headers: `Idempotency-Key`. Body:
```json
{ "method": "CASH", "amount": 27.39, "tendered": 30.00 }
{ "method": "CARD", "amount": 27.39, "reference": "AUTH-123456" }
```
- `method`: `CASH` | `CARD`. `amount` > 0 and ≤ `amountDue` (split tender = several payments).
- `CASH`: `tendered` required, ≥ `amount`; the server computes `change`.
- `CARD`: `reference` (1..64 chars) = approval code from the payment terminal; `tendered` must be absent.
- When payments cover the total the order becomes `PAID`.

→ `201` (or `200` replay) `{ "payment": Payment, "order": Order }`.
Errors: `404 ORDER_NOT_FOUND`, `409 ORDER_NOT_PAYABLE`, `422 PAYMENT_EXCEEDS_DUE | INSUFFICIENT_TENDER | CURRENCY_MISMATCH`.

`Payment`:
```json
{ "id": "0192…", "method": "CASH", "amount": 27.39, "tendered": 30.00, "change": 2.61,
  "reference": null, "terminalId": "pos-1", "receivedAt": "2026-10-13T12:00:05Z",
  "voidedAt": null, "voidReason": null }
```
Voided payments stay in the list (with `voidedAt`/`voidReason`) but no longer count towards `amountPaid`.

### `POST /api/v1/orders/{id}/payments/{paymentId}/void` — `POS`, `MANAGER`
Headers: `Idempotency-Key`. Body `{ "reason": "Second card declined, cash returned" }` (1..200 chars).
Hands a tender back while the order is still `PLACED` (an abandoned split tender), so the order can then
be cancelled. A `PAID` order's money goes back only through a refund document.
→ `200 Order`. Errors: `404 ORDER_NOT_FOUND | PAYMENT_NOT_FOUND`, `409 ORDER_NOT_PAYABLE | PAYMENT_ALREADY_VOIDED`.

### `POST /api/v1/orders/{id}/cancel` — `POS`, `MANAGER`
Headers: `Idempotency-Key`. Body `{ "reason": "Customer left" }` (1..200 chars).
Only a `PLACED` order without unvoided payments (void them first). Returns the stock, cancels the kitchen ticket. The fiscal
number stays used (the document is marked cancelled, never deleted).
→ `200 Order`. Errors: `404 ORDER_NOT_FOUND`, `409 ORDER_NOT_CANCELLABLE`.

### `POST /api/v1/orders/{id}/refunds` — `MANAGER`
Headers: `Idempotency-Key`. Body:
```json
{ "lines": [ { "lineNo": 1, "quantity": 1 } ], "restock": true, "method": "CASH", "reason": "Cold food" }
```
Only `PAID` / `PARTIALLY_REFUNDED` orders. Each refund is its own fiscal document numbered
`REF-{BRANCH}-{YYYYMMDD}-{NNNN}`. Amounts are computed by the server; the last refund of a line gets
the exact remainder, so fully refunding an order always returns exactly its total.
→ `201` (or `200` replay) `{ "refund": Refund, "order": Order }`.
Errors: `404 ORDER_NOT_FOUND`, `409 ORDER_NOT_REFUNDABLE`, `422 REFUND_EXCEEDS_SOLD | UNKNOWN_ORDER_LINE`.

`Refund`:
```json
{ "id": "0192…", "refundNumber": "REF-HOSP01-20261013-0001", "method": "CASH", "reason": "Cold food",
  "restocked": true, "issuedAt": "2026-10-13T13:00:00Z", "terminalId": "mgr-1",
  "subtotal": 11.40, "taxTotal": 0.94, "total": 12.34, "fiscalHash": "…",
  "lines": [ { "lineNo": 1, "sku": "BURGER-CLASSIC", "name": "Classic burger", "quantity": 1,
               "net": 11.40, "tax": 0.94, "total": 12.34 } ] }
```

## Catalog

### `GET /api/v1/catalog?includeInactive=false` — any role except `HQ` → `200 CatalogItem[]` (sorted by `sku`)
```json
[ { "productId": "0192…", "sku": "BURGER-CLASSIC", "name": "Classic burger", "unitPrice": 11.40,
    "currency": "USD", "taxRatePercent": 8.25, "requiresPreparation": true, "station": "GRILL",
    "category": "Mains", "active": true, "version": 1760356800000, "onHand": 50 } ]
```

### `POST /api/v1/catalog/products` — `MANAGER`
```json
{ "sku": "SOUP-DAY", "name": "Soup of the day", "unitPrice": 5.90, "taxRatePercent": 8.25,
  "requiresPreparation": true, "station": "KITCHEN", "category": "Mains" }
```
`sku` `[A-Z0-9][A-Z0-9_-]{0,63}` (upper-cased), `name` 1..200, `unitPrice` ≥ 0 in the branch
currency, `taxRatePercent` 0..100, `station`/`category` 1..32 / 1..64 chars (defaults `KITCHEN` / `General`).
New products start with 0 stock. → `201 CatalogItem`. Errors: `409 PRODUCT_EXISTS`.

### `PUT /api/v1/catalog/products/{sku}` — `MANAGER`
Same body as create without `sku`, plus `"active": true` and the `"version"` last read (optimistic
concurrency). → `200 CatalogItem` with a new `version`. Errors: `409 VERSION_CONFLICT`, `422 UNKNOWN_PRODUCT`.
Price changes never alter issued orders (prices are snapshotted on order lines).
Deactivated products are hidden from `GET /catalog` and rejected at checkout (`PRODUCT_INACTIVE`).

## Inventory

### `POST /api/v1/inventory/{sku}/restock` — `MANAGER`
Headers: `Idempotency-Key` (a retried restock never adds the units twice).
`{ "quantity": 10, "note": "Delivery #4411" }` — `quantity` 1..100000, `note` optional ≤ 200.
→ `200 { "sku": "BURGER-CLASSIC", "onHand": 60 }` + `Idempotent-Replayed` (on replay `onHand` is the
current stock) | `422 UNKNOWN_PRODUCT` | `400 INVALID_REQUEST`

### `POST /api/v1/inventory/{sku}/adjustments` — `MANAGER`
Headers: `Idempotency-Key`. `{ "delta": -3, "reason": "WASTE", "note": "Expired" }` — `delta` ≠ 0, −100000..100000;
`reason` `WASTE` | `DAMAGE` | `COUNT_CORRECTION`. A negative delta never takes stock below zero.
→ `200 { "sku": …, "onHand": … }` | `409 OUT_OF_STOCK` | `422 UNKNOWN_PRODUCT`

### `GET /api/v1/inventory/{sku}/movements?limit=50` — `MANAGER` → `200 StockMovement[]` (newest first)
```json
[ { "id": "0192…", "sku": "BURGER-CLASSIC", "delta": -2, "reason": "SALE", "referenceId": "0192…(order id)",
    "terminalId": "pos-1", "note": null, "onHandAfter": 48, "createdAt": "2026-10-13T12:00:00Z" } ]
```
`reason`: `SALE`, `CANCEL`, `REFUND`, `RESTOCK`, `WASTE`, `DAMAGE`, `COUNT_CORRECTION`.

## Kitchen (KDS)

Ticket `status`: `QUEUED` → `IN_PROGRESS` → `READY` → `SERVED`; `CANCELLED` when the order is cancelled.
Allowed manual transitions: `QUEUED→IN_PROGRESS`, `QUEUED→READY`, `IN_PROGRESS→READY`, `READY→SERVED`,
`READY→IN_PROGRESS` (recall).

`KitchenTicket`:
```json
{ "id": "0192…", "orderId": "0192…", "orderNumber": "ORD-HOSP01-20261013-0001",
  "terminalId": "pos-1", "status": "QUEUED", "receivedAt": "2026-10-13T12:00:00.4Z",
  "updatedAt": "2026-10-13T12:00:00.4Z",
  "items": [ { "sku": "BURGER-CLASSIC", "name": "Classic burger", "quantity": 2, "station": "GRILL" } ] }
```

### `GET /api/v1/kitchen/tickets?limit=50&active=true&station=GRILL` — `KITCHEN`, `MANAGER` → `200 KitchenTicket[]`
Most recent first. `limit` 1..200 (default 50). `active=true` → only `QUEUED`/`IN_PROGRESS`/`READY`.
`station` → only tickets with at least one item for that station.

### `POST /api/v1/kitchen/tickets/{id}/status` — `KITCHEN`, `MANAGER`
`{ "status": "IN_PROGRESS" }` → `200 KitchenTicket` | `404 TICKET_NOT_FOUND` | `409 TICKET_STATE_CONFLICT`

### `GET /api/v1/kitchen/stream?station=GRILL` — `KITCHEN`, `MANAGER` — `text/event-stream`
On connect: one `event: ticket` per active ticket (snapshot). Then one `event: ticket` (data =
`KitchenTicket` JSON) whenever a ticket is created or changes status; `: ping` comment every 15 s.
Clients must authenticate with the `Authorization` header (use `fetch` streaming, not `EventSource`)
and reconnect with backoff when the stream ends.

## Devices (terminals) — `MANAGER`

### `GET /api/v1/terminals` → `200 Terminal[]`
```json
[ { "terminalId": "pos-1", "name": "Front counter", "role": "POS", "active": true,
    "createdAt": "…", "revokedAt": null } ]
```

### `POST /api/v1/terminals`
`{ "terminalId": "pos-2", "name": "Kiosk 2", "role": "POS" }` — `terminalId` `[A-Za-z0-9._:-]{1,64}`.
→ `201 { …Terminal, "apiKey": "sf_…" }` — the key is shown **once**; only its SHA-256 is stored.
Errors: `409 TERMINAL_EXISTS`.

### `POST /api/v1/terminals/{terminalId}/revoke` → `200 Terminal`
Errors: `404 TERMINAL_NOT_FOUND`, `409 CANNOT_REVOKE_SELF | LAST_MANAGER` (the last active manager
cannot be revoked).

## Operations — `MANAGER`

### `GET /api/v1/ops/outbox` → `200 OutboxStatus`
One entry per destination that has ever had events (`KITCHEN`, `HQ`), sorted by destination.
```json
{ "destinations": [
  { "destination": "HQ", "pending": 12, "published": 0, "failing": 0, "dead": 0,
    "oldestPendingAt": "2026-10-13T12:00:00Z", "lastError": null } ] }
```
`pending` excludes dead letters; `failing` = pending events whose last attempt errored.
With no HQ URL configured (offline), HQ events stay pending by design.

### `GET /api/v1/ops/outbox/dead?limit=50` → `200 DeadLetter[]`
Events that exhausted their delivery attempts or failed permanently (e.g. unreadable payload).
```json
[ { "id": "0192…", "destination": "KITCHEN", "eventType": "KitchenTicketRequested",
    "aggregateId": "0192…", "attempts": 20, "lastError": "…", "createdAt": "…", "deadAt": "…" } ]
```

### `POST /api/v1/ops/outbox/{id}/retry` → `204` | `404 DEAD_LETTER_NOT_FOUND`
Re-queues a dead letter for immediate delivery with a fresh attempt budget.

### `POST /api/v1/ops/outbox/dead/retry?destination=HQ` → `200 { "requeued": 37 }`
Re-queues every dead letter of a destination (e.g. after fixing HQ credentials).

Dead-letter policy: local destinations (`KITCHEN`) after `storefront.outbox.max-attempts` (20) failed
attempts; `HQ` never on transient failures (WAN outages of days are normal), only on permanent ones
(unreadable payload, HQ answers 400/413/422).

## Reports — `MANAGER`

### `GET /api/v1/reports/daily?date=2026-10-13` → `200 DailyReport` (default: today, branch zone)
End-of-day (Z) report. Cancelled orders are counted but excluded from sales.
```json
{ "businessDate": "2026-10-13", "branchCode": "HOSP01", "currency": "USD",
  "orders": { "issued": 120, "cancelled": 2, "firstNumber": "ORD-HOSP01-20261013-0001",
              "lastNumber": "ORD-HOSP01-20261013-0120" },
  "sales": { "subtotal": 1500.00, "taxTotal": 123.75, "total": 1623.75 },
  "taxByRate": [ { "taxRatePercent": 8.25, "net": 1500.00, "tax": 123.75 } ],
  "payments": [ { "method": "CASH", "count": 40, "amount": 500.00 },
                { "method": "CARD", "count": 78, "amount": 1100.00 } ],
  "refunds": { "count": 1, "total": 12.34 },
  "unpaidTotal": 11.41 }
```

### `GET /api/v1/reports/fiscal-chain?date=2026-10-13` → `200 FiscalChainReport`
Recomputes the SHA-256 hash chain of the day's documents and cross-checks it with the counter row:
numbers must run 1..n without gaps and n must equal the counter (deleted documents are detected), every
document from the first chained number on must carry a matching hash (stripped hashes are detected),
and the last hash must equal the counter's chain head (a truncated tail is detected). Order-line names
are part of the hashed content.
```json
{ "businessDate": "2026-10-13",
  "series": [ { "series": "ORD", "documents": 120, "valid": true, "firstBrokenNumber": null },
              { "series": "REF", "documents": 1, "valid": true, "firstBrokenNumber": null } ] }
```

## Central HQ sync — `HQ`, `MANAGER`

### `PUT /api/v1/sync/catalog`
Inbound catalog/price changes, last-writer-wins on `version` (HQ uses epoch-millisecond versions).
```json
{ "products": [ { "productId": "0192…", "sku": "…", "name": "…", "unitPrice": 11.40, "currency": "USD",
                  "taxRatePercent": 8.25, "requiresPreparation": true, "station": "GRILL",
                  "category": "Mains", "active": true, "version": 1760356800000 } ] }
```
A product is applied only if its `version` (> 0) is greater than the local one (or it is new). A product
whose SKU belongs to a different local product id is skipped and logged (resolve in the back office).
→ `200 { "applied": 3, "skipped": 1 }`. Errors: `422 CURRENCY_MISMATCH` (whole batch rejected).

The node also **pulls** the same feed from `GET {hq}/api/v1/sync/catalog?branch={code}&sinceVersion={v}`
when `storefront.hq.base-url` is set, and **pushes** its own events (orders, payments, cancellations,
refunds) from the outbox to `POST {hq}/api/v1/sync/events/{type}` with `Idempotency-Key` = event id,
`X-Origin-Node` and `X-Event-Created-At`. Delivery is at-least-once and **not ordered** across
events; HQ must deduplicate by event id and must not depend on arrival order (every event is a
complete, immutable fact keyed by UUIDv7 ids).

## Actuator

- `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` — public.
- `GET /actuator/prometheus` — `MONITOR` or `MANAGER` bearer key. `/actuator/info` — `MANAGER`.
