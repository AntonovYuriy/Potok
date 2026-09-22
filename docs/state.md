# State

Updated: 2026-09-21, commit — see `git log -1`.

## In progress

- Adopting the shared development standard (issue #1, branch
  `chore/1-adopt-standard`). Infrastructure only: `Makefile`, `gate.yml`,
  `scripts/gate-retry.sh`, this file, `.claude/skills/troubleshooting.md`,
  `CLAUDE.md`. Product code, `Dockerfile` and `docker-compose.yml` are
  deliberately untouched.
  Not finished: `test-integration` is still an honest stub, see below.

## Blocked

- Nothing.

## Decided, not yet an ADR

- **`lint` checks defects, not formatting.** Spotless runs
  `removeUnusedImports`, `trimTrailingWhitespace` and `endWithNewline` in check
  mode. A full formatter is deliberately absent: `googleJavaFormat` disagrees
  with 159 of 161 files and `palantirJavaFormat` with 150, because the project
  indents with four spaces and both want their own. Adopting one means
  reformatting the tree in a single unreviewable diff — a separate task, and
  the owner's call, not a side effect of turning the linter on.
- **`test-integration` is a stub.** `src/test` carries no `@Tag`, so the
  Testcontainers cases cannot be addressed separately; they run inside
  `test`. Splitting them edits test sources — also its own task.
- **`test` runs `cleanTest test`.** The runner reuses its work directory and
  Gradle would otherwise report `:test UP-TO-DATE` and hand the gate a green
  it never earned.
- **Skips are red.** `make test` counts `skipped` in the JUnit XML and fails
  on anything above zero.

## Traps

- **Everything merged to `main` becomes public.** The mirror to the public
  GitHub repository runs on a nightly timer in server-infra, so the delay is
  hours, not minutes — but it is a delay, not a filter. Schedule and
  exceptions live there; the per-mirror state is visible in Gitea's
  push-mirror settings.
- **`.github/workflows/ci.yml` is not a working CI.** It looks live and is
  not: GitHub Actions is disabled on the mirror since 2026-08-02, so the
  workflow has not run since and will not run again. It is kept as the record
  of how the image the frozen Koyeb showcase still serves (`b93e1c2`) was
  built. The gate for this repository is `.gitea/workflows/gate.yml`.
- **Tests need a running Docker.** Testcontainers starts a real Postgres.
- **Editing `templates/` without `./gradlew renderExamples`** leaves
  `examples/` stale and turns the drift tests red.
- The `smoke` and `verify` targets expect `.env` next to
  `docker-compose.yml`; it is not in git by design.
- **`make verify` remaps the host ports** (`18080`, `55432`) through an
  override on stdin, because 8080 and 5432 on this machine belong to other
  projects. `docker-compose.yml` itself is never edited for this.
- **`make verify` runs in its own compose project** (`-p potok-verify`).
  Without it the project name came from the directory, and `down` reached
  whatever shared that name. Its own project is also what makes `down -v`
  safe: the one named volume belongs to verify and to nothing else, so each
  run gets a fresh database instead of yesterday's rows.
- **Teardown in `verify` runs whatever happened, and is proved.** `up`,
  `smoke` and `down` share one block with an `rc`; afterwards
  `scripts/stop-and-prove.sh` checks both published ports went quiet, because
  `docker compose down` exiting 0 is not proof that nothing answers. The rule
  is `STANDARD.md` §4; the script is a copy of
  `server-infra/templates/stop-and-prove.sh` and is maintained there.
