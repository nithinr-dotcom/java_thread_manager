# application — use cases (depends on domain only)

```
port/in/    what the outside world can ask: XxxUseCase (commands) / XxxQuery (reads) + their records
port/out/   what a use case needs from the outside: interfaces ONLY (adapters implement them)
service/    public final classes implementing port.in, wired in config.StorefrontConfiguration
readmodel/  read-only projections returned by query ports (not aggregates, no invariants)
```
- No framework types, no annotations. Transactions only via `TransactionRunner.inTransaction`.
- Global lock order (CONTRIBUTING.md invariant 2): idempotency key → order row (`lockAccount`, lifecycle use cases
  only) → inventory rows by product id → fiscal counter. `PlaceOrderService`, `PayOrderService`,
  `CancelOrderService`, `RefundOrderService`, `InventoryService` follow it; their tests assert `store.calls`.
- Business rules for pay/void/cancel/refund live in `domain.order.OrderAccount`, not in services.
- Client decimals become `Money` via `Amounts.exact` (→ `ValidationException`/400 on excess precision).
- Test services with `InMemoryStorefront` (src/test/.../application/service) — extend it for new port methods.
- Recipe for new features: "Adding a use case" in `CONTRIBUTING.md`.
