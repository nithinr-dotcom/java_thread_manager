# 0001. Record architecture decisions as ADRs

- Status: Accepted
- Date: 2026-10-13

## Context
Several people work on this repo in parallel. A single `DESIGN.md` that everyone
edits loses the history of *why* a trade-off was made, and causes merge conflicts.

## Decision
Every decision that changes a trade-off, an invariant or a boundary gets a numbered, append-only ADR in
`docs/adr/`. `DESIGN.md` stays as the concise current-state summary and links to ADRs.

## Consequences
Contributors can read the reasoning behind a rule before "simplifying" it. Changing a rule requires a new ADR
that supersedes the old one, which is visible in review.
