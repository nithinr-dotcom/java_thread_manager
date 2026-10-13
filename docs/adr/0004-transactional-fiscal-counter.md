# 0004. Gap-free fiscal numbers from a transactional counter row, taken last

- Status: Accepted
- Date: 2026-10-13

## Context
Scenario B: `ORD-{BRANCH}-{YYYYMMDD}-{NNNN}` must be unique, chronological and gap-free, offline, and
must not serialise whole checkouts.

## Decision
One `fiscal_sequence` row per (branch, business date), upsert-incremented inside the checkout transaction
as the **last** lock; the timestamp is read while holding it. Rollback returns the number.

## Consequences
Serialisation is limited to the transaction tail (a few inserts). Enforced by `SqlInvariantsTest` (only
`JdbcFiscalSequence` writes the table, no `nextval`), `MigrationLockTest` (no SEQUENCE/SERIAL),
`PlaceOrderServiceTest.acquiresLocksInTheFixedGlobalOrder`, and the 50-thread IT (1..10 contiguous).

## Alternatives considered
PostgreSQL `SEQUENCE` (non-transactional → gaps); numbering at HQ (violates offline requirement);
per-terminal sequences (only if regulation allows — noted as the scaling escape hatch).
