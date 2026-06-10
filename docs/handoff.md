# Handoff

_Last updated: 2026-06-10 (M2 done + audited)._

## Current state

- **M2 done — reliability, observability, CI/GHCR, deploy-ready** (2026-06-10, PR #2, squash `4896db4`):
  - Name reuse: partial unique index `workflow(name) WHERE enabled`; deleted names reusable, history intact.
  - Retry: exponential backoff + full jitter (base 10s, ×2, cap 10min); per-step `retry: {max_attempts, base_delay, max_delay}`; legacy `max_attempts` works.
  - DLQ: `dead_letter` table with input+trigger snapshot; GET /api/dlq (paged), requeue, delete; optional Telegram alert `POTOK_DLQ_TELEGRAM=true`, rate-limited 1/min.
  - Graceful shutdown: SIGTERM → grace (`POTOK_SHUTDOWN_GRACE`, 20s) → lease release → another instance continues immediately. JVM exits 143 (128+SIGTERM) — standard, accepted by k8s/Koyeb.
  - Observability: /actuator/prometheus (potok_* meters; counters in-memory — reset on restart, gauges DB-backed), JSON logs via `POTOK_LOG_JSON`, MDC execution_id/workflow_name, liveness/readiness probes (readiness includes DB).
  - CI: .github/workflows/ci.yml — PR + main build with Testcontainers; main merges publish `ghcr.io/antonovyuriy/potok:latest` + `:sha`.
  - docs/deploy.md: Koyeb + Neon free-tier guide (env list, readiness path, Hikari pool ≤ 5, honest caveats).
- **M2 audit** (2026-06-10): **18/19 PASS** — full live re-verification (tests 59/59, name reuse, jitter delays 2.4s→4.4s, DLQ flow + real Telegram alert, SIGTERM resume in 16s vs 10-min lease, metrics, JSON logs, probe flip, GHCR pull, 512MB boot at ~218MiB). Single FAIL was this file not being updated — fixed by this commit.
- **M0 done**: repo + skills bootstrapped (ECC, agent-scripts), project conventions in `conventions.md`, remote `git@github.com:AntonovYuriy/Potok.git` configured, `.env.example` and `.gitignore` in place.
- **Config pass done** (2026-06-10): `.local-tooling/settings.json` (project-level: `bypassPermissions` mode, empty attribution), conventions.md git flow rewritten to feature-branch + automated PR-then-squash-merge (no human approval gate), and `main` history rewritten to strip prior co-author footers (force-pushed).
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

## Known issues

- DLQ `lastError` shows "failed: null" for exceptions without a message (e.g. ConnectException) — cosmetic, fix in M3.
- Cron triggers fire on every instance when scaled out — single instance until leader election (M3).

## Verified 2026-06-10 (M1)

- `./gradlew test` — 45/45 green (unit + Testcontainers integration).
- `DOCKER_BUILDKIT=0 docker build -t potok-app . && POSTGRES_PORT=5433 docker compose up -d` — health UP (5433 because another local postgres held 5432; default mapping stays 5432).
- Live e2e: examples/healthcheck.yaml created via curl, run via `/run` — httpbin happened to return 503, alert branch fired and a REAL telegram message was delivered (token came from `.env` via compose). Full chain proven: probe(fail_on_status)→condition→telegram.
- Webhook fire (202 + payload in trigger_info), GET list/executions, problem+json on bad YAML (400), DELETE → 204 → webhook 404. YAML must be POSTed with `Content-Type: text/plain` or `application/yaml` (form-urlencoded is rejected with 415 by design).

## Machine notes (this dev box)

- colima + brew docker CLI 29. Testcontainers: `~/.testcontainers.properties` points `docker.host` at the colima socket; build.gradle.kts sets `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` (portable).
- buildx FIXED (2026-06-10): brew docker-buildx 0.34.1 symlinked into `~/.docker/cli-plugins/docker-buildx` (old v0.10.0 kept as `.bak`); `docker compose build` works natively now.
- Local port 5432 is often taken (`acc_postgres`) — use `POSTGRES_PORT=5433 docker compose up -d`.
- Integration tests give each Spring context its own database (cached contexts keep workers polling — they'd steal jobs). Keep this pattern when adding test configs.

## Next action

**First deploy to Koyeb + Neon per docs/deploy.md — manual, by owner** (accounts, secrets, clicking). Code side is ready: GHCR image published, probes configured, 512MB verified.

## M3 candidates (see docs/roadmap.md)

1. DAG execution: `needs:` between steps, parallel branches, richer conditions (`&&`/`||`, `<`/`>`, `contains`); replace `JobProcessor.advance` + `WorkflowDefinition.nextStep` — linearity is isolated there.
2. Poller triggers: RSS / HTTP polling + diff as a trigger source.
3. Executions TTL/archival: retention for workflow_execution / step_execution / dead_letter.
4. Read-only web UI: workflow list, execution timeline, step inspector.
5. Prettier DLQ error messages ("failed: null" cosmetics); cron leader election for multi-instance.

## How to use this file

- Read first thing each session.
- Update at the end of each session: what changed, what's next, blockers.
- Source of truth for project state — code + commits are authoritative for *what*, this file is for *where we are*.
