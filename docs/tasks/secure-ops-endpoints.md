---
status: done
adr: 0010
---
# Authenticate ops and restock endpoints

- [x] Decide mechanism (mTLS between terminals and node vs. per-terminal API keys) — ADR 0010: per-device API keys (mTLS can be layered at the proxy later, see `mtls-for-devices.md`)
- [x] Terminal identity bound to credentials instead of the free-form `X-Terminal-Id` header
