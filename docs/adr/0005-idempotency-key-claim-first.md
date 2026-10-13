# 0005. Idempotency key claimed by `INSERT … ON CONFLICT DO NOTHING` first

- Status: Accepted
- Date: 2026-10-13

## Context
Scenario C: retries after a lost response, and duplicates arriving milliseconds apart, must return the
original outcome without touching stock or numbers.

## Decision
`(terminal_id, idempotency_key)` primary key, claimed as the first statement. A concurrent duplicate
blocks on the unique index until the original ends: commit ⇒ replay stored order (`200`,
`Idempotent-Replayed: true`); rollback ⇒ proceed fresh. A SHA-256 basket fingerprint rejects key reuse
for a different basket (`422 IDEMPOTENCY_KEY_REUSED`). Failures are not memoised.

## Consequences
Enforced by `SqlInvariantsTest`, `PlaceOrderServiceTest.replayTakesOnlyTheIdempotencyLock`, and the
duplicate-storm / mixed-retry ITs. Key retention purge is an open task.
