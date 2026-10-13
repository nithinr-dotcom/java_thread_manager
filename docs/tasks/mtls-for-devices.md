---
status: todo
adr: 0010
---
# Mutual TLS for store devices

API keys (ADR 0010) authenticate devices today. Add client-certificate auth at the nginx proxy for
fixed devices (POS, KDS) once the fleet has an internal CA with automated rotation.

- [ ] CA + enrolment/rotation process for store devices
- [ ] nginx `ssl_verify_client` for device routes; map certificate subject to the registered terminal
