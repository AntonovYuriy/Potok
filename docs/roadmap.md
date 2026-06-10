# Potok roadmap

## M1 — MVP (done)

- YAML definitions (GitHub-Actions style), validated on create/update
- Triggers: cron (DB-driven, hot-reloaded) and webhook (`POST /hooks/{path}`)
- Linear step chains with `if:` conditions and minimal `{{ }}` templating
- Actions via Spring-bean SPI: `http`, `telegram`
- Postgres-only persistence; `FOR UPDATE SKIP LOCKED` queue, virtual-thread
  workers, lease-based crash recovery, at-least-once with idempotent steps
- REST control plane with problem+json errors; Docker + compose

## M2 — reliability, observability, deploy-readiness (done)

- Workflow names unique among active workflows only (partial unique index) —
  deleted names are reusable
- Exponential backoff with full jitter (base 10s, ×2, cap 10min); per-step
  `retry: {max_attempts, base_delay, max_delay}`
- Dead letter queue: exhausted jobs with full context; list/requeue/delete
  API; optional rate-limited Telegram alert (`POTOK_DLQ_TELEGRAM`)
- Graceful shutdown: grace for in-flight steps, then immediate lease release
- Micrometer + Prometheus (`/actuator/prometheus`, potok_* meters),
  JSON logging switch, MDC, liveness/readiness probes
- CI: GitHub Actions build + tests; GHCR image `:latest` + `:sha` on main
- docs/deploy.md: Koyeb + Neon free-tier guide

## M3 — candidates

- **DAG execution**: `needs:` between steps, parallel branches (queue model
  already supports it; replace `JobProcessor.advance`); richer conditions
  (`&&`/`||`, `<`/`>`, `contains`)
- **Poller triggers**: periodic HTTP poll + diff as a trigger source
- **Executions TTL / archival**: retention policy for workflow_execution /
  step_execution / dead_letter growth
- **Read-only web UI**: workflow list, execution timeline, step inspector
- Cron leader election (multi-instance dedup), more actions (slack, email,
  shell), per-step timeouts, workflow versioning

## M4 — product surface

- AuthN/AuthZ: API tokens, per-workflow scopes (currently NO auth)
- Secrets management beyond env vars
- Multi-tenancy
- Editable UI
