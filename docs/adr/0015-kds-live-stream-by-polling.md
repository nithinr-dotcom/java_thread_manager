# 0015. Live kitchen display via server-sent events backed by database polling

- Status: Accepted
- Date: 2026-10-13

## Context
KDS screens polled every 3 s and tickets had no workflow. Changes are made by the outbox relay thread
and by other screens; screens need them within about a second.

## Decision
- Ticket workflow `QUEUED → IN_PROGRESS → READY → SERVED` (+ skip-ahead, recall) as a domain rule;
  transitions are compare-and-set UPDATEs (`TICKET_STATE_CONFLICT` when two screens race).
- `GET /kitchen/stream` (SSE): snapshot of active tickets, then every change. Each stream is a virtual
  thread polling `updated_at > cursor − 5 s` once a second **in database time**, de-duplicating per
  ticket. Clients authenticate with the Authorization header (fetch streaming) and reconnect, reloading
  the board on every connect.
- Station routing: products have a station; tickets can be filtered per station.

## Consequences
Works for any writer without an in-memory event bus; one cheap indexed query per screen per second.
Guard: `KitchenStreamIT` (real HTTP), `KitchenWorkflowIT`.

## Alternatives considered
- **PostgreSQL LISTEN/NOTIFY**: lower latency, but needs a dedicated connection per node and still a
  catch-up query on reconnect; worth it only if screens grow beyond dozens.
- **WebSockets**: bidirectional not needed; SSE passes proxies more easily.
