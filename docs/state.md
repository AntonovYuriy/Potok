# State

Updated: 2026-09-21, commit — see `git log -1`.

## In progress

- Adopting the shared development standard (issue #1, branch
  `chore/1-adopt-standard`). Infrastructure only: `Makefile`, `gate.yml`,
  `scripts/gate-retry.sh`, this file, `.claude/skills/troubleshooting.md`,
  `CLAUDE.md`. Product code, `Dockerfile` and `docker-compose.yml` are
  deliberately untouched.
  Not finished: two gate targets are honest stubs, see below.

## Blocked

- Nothing.

## Decided, not yet an ADR

- **`lint` is a stub.** The build declares no spotless and no checkstyle, so
  there is nothing to run. Faking the target with a command that checks
  nothing would be worse than admitting the gap. Wiring a linter in edits
  `build.gradle.kts` and is a task of its own.
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
- **`.github/workflows/ci.yml` is live, not an inert artifact.** Once the
  mirror carries commits over, it builds, tests and pushes
  `ghcr.io/antonovyuriy/potok` images on pushes to `main`. It is not the gate
  for this repository, but it does run, and it consumes GitHub Actions
  minutes.
- **Tests need a running Docker.** Testcontainers starts a real Postgres.
- **Editing `templates/` without `./gradlew renderExamples`** leaves
  `examples/` stale and turns the drift tests red.
- The `smoke` and `verify` targets expect `.env` next to
  `docker-compose.yml`; it is not in git by design.
- **`make verify` remaps the host ports** (`18080`, `55432`) through an
  override on stdin, because 8080 and 5432 on this machine belong to other
  projects. `docker-compose.yml` itself is never edited for this.
