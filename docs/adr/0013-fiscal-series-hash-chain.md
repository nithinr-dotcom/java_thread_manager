# 0013. Fiscal series, tamper-evident hash chain and monotonic document time

- Status: Accepted
- Date: 2026-10-13

## Context
Refunds need their own gap-free numbering. Fiscal regimes increasingly require tamper evidence. The
edge node's clock can step backwards (NTP), which would put a later number before an earlier time.

## Decision
- `fiscal_sequence` is keyed by (branch, **series**, business date); series `ORD` and `REF`.
- Each document's `fiscal_hash = SHA-256(previous hash | canonical content)` per series and day; the
  counter row holds the chain head (`last_hash`) and the first chained number (`first_hashed_seq`).
- `last_issued_at = GREATEST(last_issued_at, now)` in the same upsert: document time never runs
  backwards; it is the time stamped on the document.
- `GET /reports/fiscal-chain` recomputes the chain and checks contiguity 1..n, count against the counter,
  no hash after the cutover may be missing, and the head hash — detecting edits, deletions, truncation
  and hash stripping. Jurisdictions with certified signing devices plug them in at the same step.
- **Migration V6 is roll-forward only**: it replaces the counter's primary key, so the previous release
  cannot run against a migrated database (its `ON CONFLICT (branch_code, business_date)` has no matching
  constraint). Rollback = restore the pre-upgrade backup (docs/OPERATIONS.md).

## Consequences
The hash is computed inside the checkout transaction while holding the counter row (a few µs).
Guards: `FiscalChainTest`, `ReportServiceTest` (tamper, truncation, stripping), `ReportsAndHousekeepingIT`.

## Alternatives considered
- **Separate REF counter table** (keeps V1's PK): rollback-compatible, but two copies of the same
  mechanism; we chose one counter abstraction and an explicit roll-forward policy.
- **Signing with a node private key**: needs key custody on store hardware; the chain gives tamper
  evidence now, and HQ holds the replicated copies.
