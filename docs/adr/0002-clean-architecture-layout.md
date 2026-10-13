# 0002. Clean architecture: domain / application ports / adapters / config

- Status: Accepted (supersedes the earlier `domain / application / infrastructure` layout)
- Date: 2026-10-13

## Context
The brief demands business logic fully decoupled from frameworks, databases and delivery mechanisms.
The first layout had one `infrastructure` package that mixed inbound (web), outbound (JDBC, outbox,
HQ) and the composition root, and controllers depended on concrete service classes.

## Decision
```
domain                      entities, value objects, domain events, DomainException   (java.* only)
application.port.in         use-case interfaces (XxxUseCase / XxxQuery) + command/result records
application.port.out        interfaces the use cases need (repositories, ledger, outbox, tx runner)
application.service         use-case implementations (framework-free, public final)
application.readmodel       read-only projections returned by query ports
adapter.in.web              REST controllers → depend on port.in only
adapter.out.persistence     JDBC implementations of port.out
adapter.out.outbox|kitchen|hq   outbox relay and its dispatchers
config                      composition root (@Bean wiring, properties) — nothing depends on it
```
Adapters receive configuration through `@Value`, not by importing `config`.

## Consequences
- `ArchitectureTest` enforces: layer direction; domain/application framework-free; output ports are
  interfaces; adapters never touch `application.service`; inbound adapters never touch outbound ones or
  output ports; nothing depends on `config`.
- Use cases are unit-tested with one in-memory fake (`InMemoryStorefront`).
- Slightly more files per feature (input port + service); the recipe is in `CONTRIBUTING.md`.

## Alternatives considered
- *Package-by-feature with internal layers*: better at large scale, but this node has one bounded
  context; revisit when a second one (e.g. payments) arrives.
- *Multi-module Maven build* (compile-time enforcement): stronger, but heavier to navigate;
  ArchUnit gives equivalent feedback in the unit-test phase.
