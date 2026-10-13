# Contributing — frontend/ (Storefront store app)

Rules for working in this folder. The backend rules in `../CONTRIBUTING.md` still apply to
anything outside `frontend/`. Overview, roles and run instructions: `README.md`.

## Layering (imports point down only)

```
app ──▶ features ──▶ shared ──▶ api ──▶ domain (pure; api/stream.ts uses sse + backoff)
             └───────────────────────────▶ domain
```

- `api/` — `types.ts` must match `../docs/API.md` exactly (field names, nullability, status
  codes, problem `code`s, roles). Change the contract doc first, then the types. `client.ts`
  never throws: it returns `ApiResult` (`ok:true` data | `ok:false` RFC 7807 problem; status 0 =
  no response: `NETWORK_ERROR` or `TIMEOUT`). It adds `Authorization: Bearer <key>` (set via
  `setApiKey`) and reports 401s through `onUnauthorized`. `endpoints.ts` has one function per
  endpoint; `stream.ts` reads the KDS SSE stream with `fetch` (never `EventSource`: no headers).
- `domain/` — pure TypeScript: no React, no `fetch`, no DOM (enforced by ESLint
  `no-restricted-imports`); `import type` from `api/types` is fine. Every module has a `*.test.ts`.
- `features/<app>/` — React screens; call `api/endpoints.ts`, put logic worth testing in `domain/`.
  `features/common/` holds cross-app pieces (pending actions, receipt, `go()` navigation).
- `shared/` — hooks, i18n, formatting, storage, session/reachability contexts, UI primitives. No
  feature-specific logic.
- Routing is hash-based (`domain/routes.ts`); which apps a device sees comes from its role
  (`appsForRole`). Don't add a router dependency.

## Non-negotiables

- **Never compute money totals client-side.** Display server values only (`subtotal`,
  `taxTotal`, `total`, `amountDue`, `change`, refund amounts, report figures), formatted with
  `formatMoney(amount, currency)`. Comparing a server amount (`> 0`) is fine; adding, subtracting
  or rounding is not. User-typed amounts are parsed with `domain/decimal.ts` and validated by the
  server.
- **Idempotency**: every order/stock mutation (checkout, payment, payment void, cancel, refund,
  restock, adjustment) goes through `usePendingAction` — the action + key are persisted **before**
  sending, the key is reused verbatim on retries and rotated only after a definitive outcome (2xx,
  4xx except 408/429). Never call the mutating endpoints (`checkout`, `payOrder`, `voidPayment`,
  `cancelOrder`, `refundOrder`, `restock`, `adjustStock`) directly from a screen (developer tools
  excepted, they demonstrate the raw semantics).
- `localStorage` access goes through `shared/storage.ts` (`STORAGE_KEYS`, try/catch).
- User-facing strings go through `t()` (`shared/i18n.ts`, keys in `shared/messages.en.ts`).
- No `window.confirm/alert/prompt` — use `ConfirmButton` (in-page confirmation).
- Accessibility: every input has a label, actions are `<button>`, tables have `<th>` headers,
  results use `role="status"`/`aria-live`, POS/KDS touch targets ≥ 44px (`.btn-lg`, `.btn-xl`,
  `.tile`, `.step-btn`, `.btn-bump`).
- Pinned dependency versions (no ranges). Commit `package-lock.json`.

## Tests

- Unit tests next to `domain/` modules; component tests with a routed `fetch` mock
  (`src/test/fakeServer.ts`), fixtures in `src/test/fixtures.ts`, and `renderAs(ui, me)` from
  `src/test/render.tsx` for screens that need a session.
- E2E (`e2e/*.spec.ts`, Playwright) need a running backend with the demo profile; they are not part
  of `npm run check` and are excluded from Vitest. Keep them type-checking (`tsconfig` includes
  `e2e/`).

## Done means

- `npm run check` (typecheck + lint + format:check + tests) and `npm run build` are green.
- New pure logic ⇒ unit test; new screen behaviour ⇒ component test with mocked `fetch`;
  new user flow ⇒ consider an E2E spec.
