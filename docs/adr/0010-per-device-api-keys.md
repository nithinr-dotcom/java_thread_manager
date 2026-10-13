# 0010. Authenticate devices with per-device API keys and role-based access

- Status: Accepted
- Date: 2026-10-13

## Context
Every endpoint was open and `X-Terminal-Id` was free text: any device on the store LAN could restock,
read every order or act as another terminal. Store devices (POS, kiosks, KDS screens, back office, the
HQ sync client) are long-lived, provisioned by staff, and must keep working offline — no central
identity provider can be on the request path.

## Decision
- Each device is registered (`terminal` table) with a role `POS | KITCHEN | MANAGER | HQ | MONITOR` and an
  API key `sf_` + 256 random bits. Only the SHA-256 of the key is stored; the key is shown once.
- Requests carry `Authorization: Bearer <key>` (Spring Security, stateless, no sessions/cookies → no CSRF).
  The terminal id on every order/payment/refund/movement is the authenticated device, never a header;
  `X-Terminal-Id`, if sent, must match (`403 TERMINAL_MISMATCH`).
- Role rules live in one place (`SecurityConfiguration`) and mirror `docs/API.md`. Refunds, catalog,
  stock, devices, reports and operations are `MANAGER`-only; `MONITOR` may only scrape metrics.
- A fresh node gets its first manager from `STOREFRONT_BOOTSTRAP_MANAGER_KEY` (ignored once any manager
  exists). The last active manager cannot be revoked (`LAST_MANAGER`).
- Transport security: TLS terminated by the reverse proxy in front of the node (`deploy/`, nginx).

## Consequences
Credentials work fully offline (one indexed lookup per request). Revocation is immediate for new
requests; open KDS streams end within `stream-max-age`. Guards: `SecurityIT` (401/403/mismatch/roles/
revocation/metrics), `TerminalServiceTest`.

## Alternatives considered
- **mTLS per device**: strongest, but certificate issuance/rotation on hundreds of stores' tablets is a
  PKI project of its own; it can be layered on later at the proxy without changing the app.
- **OAuth2/OIDC against HQ**: needs the WAN on the auth path — violates offline autonomy.
- **Shared store password**: no per-device identity, revocation or audit.
