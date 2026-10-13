# 0008. Invariants enforced by tests, not only prose

- Status: Accepted
- Date: 2026-10-13

## Context
Written rules are followed most of the time, not all of the time. A single slip (a read-modify-write of
stock, an edited migration, a `double`) silently breaks a financial or compliance guarantee.

## Decision
Every CONTRIBUTING.md invariant that can be checked mechanically is:

| Invariant | Guard |
|---|---|
| Layering / clean architecture | `ArchitectureTest` |
| 1 No overselling | `SqlInvariantsTest`, `CheckoutConcurrencyIT`, `RestockConcurrencyIT` |
| 2 Lock order | `PlaceOrderServiceTest.acquiresLocksInTheFixedGlobalOrder`, opposite-order IT |
| 3 Fiscal gap-free | `SqlInvariantsTest`, `MigrationLockTest`, contention IT |
| 4 Idempotency first | `SqlInvariantsTest`, `replayTakesOnlyTheIdempotencyLock`, duplicate-storm IT |
| 5 Outbox only | `ArchitectureTest` (HTTP clients, use-case deps), `KitchenDispatchIT` |
| 6 Money | `ArchitectureTest.noFloatingPointAnywhere`, `MoneyTest` |
| 7 UUIDv7 | `ArchitectureTest.idsAreUuidV7`, `MigrationLockTest` |
| 8 Immutable migrations | `MigrationLockTest` |
| Formatting drift | Spotless `check` in `verify` |

CI runs all of it on every PR, independent of who wrote the change.

## Consequences
Guards must be fixed by fixing the code; loosening a guard requires a superseding ADR.
