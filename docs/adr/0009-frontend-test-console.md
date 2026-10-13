# 0009. Separate React test console talking only to the public API

- Status: Accepted
- Date: 2026-10-13

## Context
Humans need to see scenarios A–E working end to end (contention, numbering, retries, kitchen, offline
HQ backlog) without writing curl scripts.

## Decision
`frontend/` is an independent Vite + React + TypeScript app (own `npm run check`, own CI job) that only
uses the documented `/api/v1` endpoints (`docs/API.md`), proxied by the Vite dev server. It is a test
console, not the production POS. Read/ops endpoints were added behind input ports (`FindOrdersQuery`,
`BrowseCatalogQuery`, `RestockUseCase`, `KitchenBoardQuery`, `OutboxStatusQuery`).

## Consequences
The backend build does not depend on Node. The console never computes money totals — it displays the
server's `BigDecimal` values. The ops/restock endpoints are unauthenticated: acceptable on the store LAN
for testing, but must be put behind auth before production (open task).
