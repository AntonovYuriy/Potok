# Handoff

_Last updated: 2026-06-10._

## Current state

- **M0 done**: repo + skills bootstrapped (ECC, agent-scripts), project conventions in `CLAUDE.md`, remote `git@github.com:AntonovYuriy/Potok.git` configured, `.env.example` and `.gitignore` in place.
- **Config pass done** (2026-06-10): `.claude/settings.json` (project-level: `bypassPermissions` mode, empty attribution), CLAUDE.md git flow rewritten to feature-branch + automated PR-then-squash-merge (no human approval gate), and `main` history rewritten to strip prior co-author footers (force-pushed).
- **M1 done** — complete, tested MVP:
  - YAML definitions (GitHub-Actions style) with validation; minimal `{{ }}` templating (dot-path, `==`/`!=`, `${ENV}`).
  - Triggers: cron (DB-driven, hot-reload on change event + 30s refresh) and webhook `POST /hooks/{path}`.
  - Execution: Postgres `job_queue` polled with `FOR UPDATE SKIP LOCKED` by virtual-thread workers; `locked_until` lease = implicit crash recovery (no startup sweep); at-least-once with idempotent steps (SUCCEEDED never re-runs); per-step `max_attempts` (default 3), fixed 30s backoff.
  - Actions SPI (Spring beans): `http` (with `fail_on_status: false` for the healthcheck pattern), `telegram` (graceful failure without token, base URL configurable for tests).
  - REST API per spec, problem+json errors, soft delete. No auth (M4).
  - Tests green (`./gradlew test`, 45): unit (parser/templating/retry) + Testcontainers integration (happy path, condition skip, healthcheck pattern, retry→FAILED, SKIP LOCKED 2-worker no-double-execution, API errors).
  - Docker: multi-stage Dockerfile (temurin, `-XX:MaxRAMPercentage=75`), compose app+postgres16, health on `/actuator/health`.
  - `examples/garbage-reminder.yaml`, `examples/healthcheck.yaml`; README with quickstart/YAML/API/architecture/roadmap; `docs/roadmap.md` M2-M4.

## Key design decisions

- Plain JDBC (`JdbcClient`), no JPA — queue needs raw SQL; jsonb via `::jsonb`.
- Action runs OUTSIDE transactions (external I/O); only the queue lease guards it. Steps that outlive the 60s lease may double-deliver — documented at-least-once contract.
- Linearity is isolated in `JobProcessor.advance` + `WorkflowDefinition.nextStep` — the M2 DAG change replaces only these.
- Templating context: `{trigger: <payload>, steps: {name: output}}`; `${ENV}` resolved at execution time, never stored.
- 5-field cron specs get a leading `0` (`YamlDefinitionParser.normalizeCron`); fire-time re-check of `enabled`.

## Verified 2026-06-10

- `./gradlew test` — 45/45 green (unit + Testcontainers integration).
- `DOCKER_BUILDKIT=0 docker build -t potok-app . && POSTGRES_PORT=5433 docker compose up -d` — health UP (5433 because another local postgres held 5432; default mapping stays 5432).
- Live e2e: examples/healthcheck.yaml created via curl, run via `/run` — httpbin happened to return 503, alert branch fired and a REAL telegram message was delivered (token came from `.env` via compose). Full chain proven: probe(fail_on_status)→condition→telegram.
- Webhook fire (202 + payload in trigger_info), GET list/executions, problem+json on bad YAML (400), DELETE → 204 → webhook 404. YAML must be POSTed with `Content-Type: text/plain` or `application/yaml` (form-urlencoded is rejected with 415 by design).

## Machine notes (this dev box)

- colima + brew docker CLI 29. Testcontainers: `~/.testcontainers.properties` points `docker.host` at the colima socket; build.gradle.kts sets `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (portable).
- `~/.docker/cli-plugins/docker-buildx` is stale v0.10.0 → `docker compose build` fails (needs ≥0.17). Workaround: `DOCKER_BUILDKIT=0 docker build -t potok-app . && docker compose up -d --no-build`. Proper fix: `brew install docker-buildx` and symlink into `~/.docker/cli-plugins/`.
- Integration tests give each Spring context its own database (cached contexts keep workers polling — they'd steal jobs). Keep this pattern when adding test configs.

## Next: M2 (see docs/roadmap.md)

1. DAG: add `needs:` to `WorkflowDefinition.Step`; replace `JobProcessor.advance` with a dependency-aware scheduler (enqueue steps whose deps SUCCEEDED).
2. Micrometer metrics (queue depth, step latency) before DAG debugging starts.
3. Richer conditions (`&&`, `<`), more actions (slack, shell), per-step timeout.

## How to use this file

- Read first thing each session.
- Update at the end of each session: what changed, what's next, blockers.
- Source of truth for project state — code + commits are authoritative for *what*, this file is for *where we are*.
