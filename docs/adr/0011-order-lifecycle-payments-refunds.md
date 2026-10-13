# 0011. Payments, voids, cancellation and refunds around an immutable fiscal order

- Status: Accepted
- Date: 2026-10-13

## Context
Orders were only ever `PLACED`: no tender, no way to correct a mistake. Issued fiscal documents must
never change; corrections must be traceable documents of their own.

## Decision
- `Order` keeps its fiscal content immutable; only a lifecycle status changes:
  `PLACED → PAID → PARTIALLY_REFUNDED → REFUNDED`, `PLACED → CANCELLED`.
- `OrderAccount` (order + payments + refunds) owns every rule: split tender, no overpayment, cash change
  computed server-side, card payments record the approval reference from a standalone terminal (no card
  data → out of PCI scope).
- An abandoned split tender is **voided** while the order is still `PLACED`; only then can it be
  cancelled. Cancellation returns the stock; the number stays used (gap-free).
- A paid order is corrected by **refund documents** in their own gap-free series
  `REF-{BRANCH}-{YYYYMMDD}-{NNNN}`, per line and unit. Tax is allocated on cumulative refunded units, so
  partial refunds never round past the line tax and a full refund returns exactly the total.
- Every lifecycle call is idempotent (same claim-first pattern as checkout) and locks the order row:
  **global lock order = idempotency key → order row → inventory rows by product id → fiscal counter**.
  Checkout never locks an existing order row, so the original order is preserved.
- Kitchen tickets are still requested at placement; a cancellation sends `KitchenTicketCancelled`
  through the outbox (the inbox handles either delivery order).

## Consequences
Concurrent tenders cannot overpay; cancel racing payment has exactly one winner; concurrent refunds of a
line never exceed the sold units and REF numbers stay gap-free (`OrderLifecycleIT`, incl. a contention
test with checkouts on the same products). `OrderAccountTest` proves the refund-sum property.

## Alternatives considered
- **Mutating order lines on refund**: destroys the fiscal record. Rejected.
- **Pay-then-place in one call**: couples checkout latency to the card terminal; kiosks that pay later
  (counter pickup) would not fit.
- **Negative orders as refunds**: mixes series and breaks per-day order counts. Rejected.
