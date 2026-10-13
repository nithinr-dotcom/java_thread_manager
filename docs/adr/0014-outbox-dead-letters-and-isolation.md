# 0014. Per-destination relay threads, bounded HQ timeouts and dead letters

- Status: Accepted (amends 0006)
- Date: 2026-10-13

## Context
One relay thread drained kitchen then HQ; the HQ HTTP client had no timeouts, so a hung WAN call
blocked kitchen tickets (Scenario D). A poison event was retried forever without anyone noticing.

## Decision
- One relay thread **per destination**; HQ calls have connect (2 s) and read (5 s) timeouts.
- **Dead letters**: an event is dead-lettered on a permanent failure (unreadable payload, unknown type,
  HQ 400/413/422) or after `max-attempts` (20) for local destinations. **HQ is never dead-lettered on
  transient failures** (`hq-max-attempts: 0` = unlimited): days offline are normal. Operators list and
  re-queue dead letters (single or bulk per destination); `storefront_outbox_dead` alerts.
- Delivery stays at-least-once and **unordered** across events; HQ deduplicates by event id and must
  not rely on order (each event is a complete, immutable fact).
- The claim lease (10 min) outlasts a whole batch at worst-case HQ timeouts.

## Consequences
`RelayIsolationIT` proves a hung HQ cannot delay kitchen tickets; `OutboxReliabilityIT` covers dead
letters and re-queueing; `HqHttpClientTest` the timeouts and failure classification.

## Alternatives considered
- **Strict FIFO to HQ (head-of-line blocking)**: one bad event would stop all sync. Rejected.
- **Message broker on the node**: another stateful service to operate per store. Rejected.
