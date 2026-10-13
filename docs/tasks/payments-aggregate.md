---
status: done
adr: 0011
---
# Payment capture and refunds as their own aggregate

- [x] Domain: `Payment` (cash with server-computed change, card with approval reference, voids), `Refund` documents with their own `REF` fiscal series, `OrderAccount` rules
- [x] Use cases + ports + outbox events to HQ (`PaymentRecorded`, `PaymentVoided`, `OrderCancelled`, `RefundIssued`)
- [x] ADR on how refunds interact with stock: restock is an explicit per-refund choice (ADR 0011)
