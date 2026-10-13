## What & why
<!-- One paragraph. Link the docs/tasks/*.md file and any ADR. -->

## Invariants touched (CONTRIBUTING.md)
<!-- List the invariants this change touches and how they stay intact, or "none". -->

## Definition of done
- [ ] `./mvnw verify` green (unit + ArchUnit + guards + ITs + Spotless)
- [ ] `npm --prefix frontend run check && npm --prefix frontend run build` green (if `frontend/` changed)
- [ ] New invariant ⇒ domain unit test; new concurrency path ⇒ IT under contention
- [ ] New endpoint / changed payload ⇒ `docs/API.md` (+ `frontend/src/api/types.ts`)
- [ ] Trade-off changed ⇒ new ADR in `docs/adr/` and `DESIGN.md` updated
- [ ] Schema change ⇒ new `V{n}__*.sql` + line appended to the migration lock file
- [ ] `docs/tasks/<slug>.md` status updated
