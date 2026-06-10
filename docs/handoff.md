# Handoff

_Last updated: 2026-06-10 (M4 done)._

## Current state

- **M4 done — UI editing, versioning, channel security, pollers v2, polish** (2026-06-10, PR #5, squash `d6cdb67`, 117 tests):
  - Dashboard YAML editor (#/new, edit): line-number gutter, tab handling, inline 400s; no libs/build step.
  - Versioning: append-only `workflow_version` (V6, backfilled), `current_version` pointer, GET versions + rollback (= new version); executions pin version_no AND snapshot the parsed definition — mid-run edits are inert. UI version list + rollback.
  - Security: webhook `hmac_secret_env` → X-Hub-Signature-256 over raw body (constant-time, fail-closed, secret env-only); api_token table (SHA-256 only, show-once, revoke), filter takes root key or active token; `/api/admin/purge` root-only. UI tokens page.
  - Pollers v2: `extract {jsonpath|css}` (jayway/jsoup) — changed-mode hashes the extracted value only, expressions get `poll.value`; conditions support `&&`/`||`/parens (recursive evaluator, grammar in README).
  - Ops: on-demand purge endpoint; replica-safe firing (advisory xact lock for pollers, (workflow, minute) claim for cron, claims purged nightly); UI flash banners for op errors.
  - Portfolio: README rebuilt (badges, mermaid, demo.gif, Design decisions), MIT LICENSE, CONTRIBUTING, issue templates, repo description/topics set.
  - Live-verified: editor create → cycle 400 inline → rollback chain v1→v3; tokens create/use/revoke; signed webhook via curl+openssl (401/401/202); extract immune to per-request noise (0 fires) then exactly 1 on threshold cross; UI captured headless → docs/demo.gif.
- **M3 done — auth, DAG, pollers, dashboard, hygiene** (2026-06-10, PR #4, squash `78803a7`, 94 tests):
  - Auth: `POTOK_API_KEY` → `X-API-Key` on `/api/**` (401 problem+json); unset = off; `/api/meta` (public) reports `authRequired`.
  - DAG: `needs:` per step (default = previous step, linear YAMLs unchanged); parallel ready steps; create-time validation (cycles, unknown needs, template refs limited to needs-closure); failure → downstream SKIPPED(`dependency failed: X`), independent branches finish, execution FAILED when graph terminal; condition-SKIPPED satisfies dependents; DLQ requeue un-skips downstream. Join dedupe: unique job_queue(execution_id, step_name) + ON CONFLICT.
  - Conditions: `== != > < >= <= contains() exists()`.
  - Pollers: `trigger.poll` (changed-hash | edge-triggered expression) and `trigger.rss` (per-item, guid/link dedupe, Rome). State in postgres (poll_state, rss_seen), fire+state one tx. Examples: coin-watcher, rss-digest.
  - Dashboard at `/` from the jar (vanilla ES modules, no build step): workflows, YAML view, paged executions, step timeline, DLQ requeue/delete, run/enable/disable, API-key prompt, 7s polling. Screenshot: docs/dashboard.png.
  - Hygiene: nightly retention purge (`POTOK_RETENTION_DAYS`=30, DLQ-referenced exempt, `potok_purged_total`); `Errors.describe()` — no more "failed: null"; logback janino WARN gone (two static configs via logging.config placeholder).
  - Live-verified: auth 401/200, diamond DAG on postman-echo (branches overlapped, join after both), poller edge-trigger on mutable endpoint (baseline→fire once→no refire), UI screenshots via headless Chrome.
- **Examples fix** (2026-06-10, PR #3, squash `6fc7129`): `garbage-reminder.yaml` now uses the real Warsaw waste API (warszawa19115.pl, addressPointId-keyed; no session cookie needed — verified live). Tomorrow-filter + fraction mapping live in the new `warsaw_waste` action — reference implementation of a custom ActionHandler. Telegram message only when something is collected tomorrow.
- **M2 done — reliability, observability, CI/GHCR, deploy-ready** (2026-06-10, PR #2, squash `4896db4`):
  - Name reuse: partial unique index `workflow(name) WHERE enabled`; deleted names reusable, history intact.
  - Retry: exponential backoff + full jitter (base 10s, ×2, cap 10min); per-step `retry: {max_attempts, base_delay, max_delay}`; legacy `max_attempts` works.
  - DLQ: `dead_letter` table with input+trigger snapshot; GET /api/dlq (paged), requeue, delete; optional Telegram alert `POTOK_DLQ_TELEGRAM=true`, rate-limited 1/min.
  - Graceful shutdown: SIGTERM → grace (`POTOK_SHUTDOWN_GRACE`, 20s) → lease release → another instance continues immediately. JVM exits 143 (128+SIGTERM) — standard, accepted by k8s/Koyeb.
  - Observability: /actuator/prometheus (potok_* meters; counters in-memory — reset on restart, gauges DB-backed), JSON logs via `POTOK_LOG_JSON`, MDC execution_id/workflow_name, liveness/readiness probes (readiness includes DB).
  - CI: .github/workflows/ci.yml — PR + main build with Testcontainers; main merges publish `ghcr.io/antonovyuriy/potok:latest` + `:sha`.
  - docs/deploy.md: Koyeb + Neon free-tier guide (env list, readiness path, Hikari pool ≤ 5, honest caveats).
- **M2 audit** (2026-06-10): **18/19 PASS** — full live re-verification (tests 59/59, name reuse, jitter delays 2.4s→4.4s, DLQ flow + real Telegram alert, SIGTERM resume in 16s vs 10-min lease, metrics, JSON logs, probe flip, GHCR pull, 512MB boot at ~218MiB). Single FAIL was this file not being updated — fixed by this commit.
- **M0 done**: repo bootstrapped, project conventions agreed, remote `git@github.com:AntonovYuriy/Potok.git` configured, `.env.example` and `.gitignore` in place.
- **Conventions pass done** (2026-06-10): git flow settled on feature-branch + automated PR-then-squash-merge; `main` history normalized (force-pushed).
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

## Known issues / gaps

- Dashboard list still does N+1 last-run fetches (fine for small N); no SSE — views poll every 7s.
- Tokens have no scopes — any active token = full API (except root-only admin); no users/RBAC.
- Conditions: no `!` negation, no arithmetic; `contains` is case-sensitive.
- UI editor is a plain textarea — no YAML syntax highlighting (deliberate: no build step).
- cron_fire claims assume minute granularity — a 6-field cron with seconds would dedupe to 1 fire/min across replicas (single instance unaffected).

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

**First deploy to Koyeb + Neon per docs/deploy.md — manual, by owner** (accounts, secrets, clicking). Set `POTOK_API_KEY`; create per-client tokens after boot; use `hmac_secret_env` for public webhooks.

## M5 ideas

1. Multi-user / RBAC: token scopes (read-only, per-workflow), audit log.
2. Workflow templates / marketplace: shareable YAML snippets, import from URL.
3. SSE live updates in the dashboard (replace 7s polling), execution tail view.
4. Oracle Cloud / own-VM migration guide (always-free ARM box fits this stack; systemd unit + caddy).
5. Per-step timeouts, `!` negation in conditions, case-insensitive contains.
6. Autodeploy: Koyeb redeploy hook from CI on GHCR push (zero-downtime via readiness probe).

## How to use this file

- Read first thing each session.
- Update at the end of each session: what changed, what's next, blockers.
- Source of truth for project state — code + commits are authoritative for *what*, this file is for *where we are*.
