# Potok roadmap

## M1 — MVP (done)

Linear workflow engine, complete and tested:

- YAML definitions (GitHub-Actions style), validated on create/update
- Triggers: cron (DB-driven, hot-reloaded) and webhook (`POST /hooks/{path}`)
- Linear step chains with `if:` conditions and minimal `{{ }}` templating
- Actions via Spring-bean SPI: `http`, `telegram`
- Postgres-only persistence: definitions, execution state, job queue
- Queue: `SELECT ... FOR UPDATE SKIP LOCKED`, virtual-thread workers,
  lease-based crash recovery, at-least-once with idempotent steps
- Retry: per-step `max_attempts` (default 3), fixed 30s backoff
- REST control plane with problem+json errors
- Docker multi-stage build + compose (app + postgres)

## M2 — DAG & richer workflows

- `needs:` between steps → DAG execution, parallel branches
  (queue already supports it: one row per runnable step; the scheduler in
  `JobProcessor.advance` is the only place that assumes linearity)
- Richer conditions: `&&`/`||`, `<`/`>`, `contains`
- More actions: slack, email (SMTP), shell (sandboxed), generic webhook-out
- Per-step `timeout:`; exponential backoff option
- Workflow versioning (currently PUT replaces in place)

## M3 — scale-out

- Queue backend interface; optional Kafka/Rabbit implementation
- Multiple app instances (already safe thanks to SKIP LOCKED — needs only
  cron-trigger leader election or dedup)
- Micrometer metrics (queue depth, step latency, failure rate), OTel tracing
- Dead-letter handling and manual re-run of FAILED executions from a step

## M4 — product surface

- Web UI: workflow list, execution timeline, step inspector
- AuthN/AuthZ: API tokens, per-workflow scopes (currently NO auth)
- Secrets management (currently env vars only, `${VAR}` substitution)
- Multi-tenancy
