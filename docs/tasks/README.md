# Tasks

One file per task, so parallel work never conflicts on a shared board.

- File name: a kebab-case slug (`outbox-retention-purge.md`). No numbers — two people would pick the same one.
- Front matter: `status: todo | doing | done`, optional `owner:` (person/branch) and `adr:`.
- Pick up: set `status: doing` + `owner:` in the same PR as the first commit of the work.
- Finish: set `status: done` and tick the acceptance criteria in the PR that completes it.

```bash
grep -l "^status: todo"  docs/tasks/*.md   # what's open
grep -l "^status: doing" docs/tasks/*.md   # what's in flight
```
