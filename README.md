# Potok

Self-hosted workflow engine. Define workflows in YAML (GitHub-Actions style):
a **trigger** (cron or webhook) starts a **linear chain of steps**, each step runs
an **action** (HTTP call, Telegram message). PostgreSQL is the only dependency —
it stores definitions, execution state, *and* the job queue
(`SELECT ... FOR UPDATE SKIP LOCKED`). No Kafka, no Redis, no UI. One container + one database.

```
            ┌──────────────┐      ┌────────────────┐      ┌─────────────────┐
 trigger ──▶│  REST API /  │─────▶│  job_queue     │─────▶│  workers        │
 (cron,     │  webhook     │ row  │  (postgres,    │ poll │  (virtual       │
  webhook,  └──────────────┘      │   SKIP LOCKED) │      │   threads)      │
  manual)                         └────────────────┘      └────────┬────────┘
                                                                   │ executes
                                                          ┌────────▼────────┐
                                                          │  actions (SPI)  │
                                                          │  http, telegram │
                                                          └─────────────────┘
```

## Quickstart

```bash
git clone <this repo> && cd potok
docker compose up -d
curl -s -H 'Content-Type: text/plain' --data-binary @examples/healthcheck.yaml localhost:8080/api/workflows
```

App: `http://localhost:8080`, health: `/actuator/health`, Postgres: `:5432`.
Telegram is optional — set `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` in the
environment before `docker compose up` to make `telegram` steps work; without a
token the step fails with a clear error message, nothing crashes.

Trigger a run manually and inspect it:

```bash
ID=$(curl -s -H 'Content-Type: text/plain' --data-binary @examples/healthcheck.yaml localhost:8080/api/workflows | jq -r .id)
EXEC=$(curl -s -X POST localhost:8080/api/workflows/$ID/run | jq -r .executionId)
curl -s localhost:8080/api/executions/$EXEC | jq
```

## YAML reference

```yaml
name: garbage-reminder          # unique workflow name (required)

trigger:                        # exactly one of cron | webhook (required)
  cron: "0 19 * * *"            # 5-field crontab or 6-field Spring cron
  # webhook: { path: "gh-events" }   # → POST /hooks/gh-events starts a run

steps:                          # ordered, executed strictly in sequence
  - name: fetch                 # unique step name (required)
    action: http                # action type (required)
    max_attempts: 3             # optional, default 3; fixed 30s backoff between attempts
    with:                       # action inputs; values support templating
      method: GET
      url: "https://example.com/api"
      headers: { Accept: application/json }
      # body: { any: json }     # maps/lists are sent as JSON
      # fail_on_status: false   # record non-2xx as success so a later `if` can react

  - name: notify
    if: "{{ steps.fetch.status == 200 }}"   # optional condition; false → step SKIPPED
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"        # ${VAR} = environment variable
      text: "Завтра вывоз: {{ steps.fetch.body }}"
```

### Templating

Minimal by design — no full expression language:

| Syntax | Meaning |
|---|---|
| `{{ trigger.user.name }}` | dot-path into the trigger payload (webhook JSON body) |
| `{{ steps.fetch.status }}` | dot-path into a previous step's output |
| `{{ a == b }}`, `{{ a != b }}` | comparison in `if:` conditions (numbers, 'strings', true/false/null) |
| `${ENV_VAR}` | environment variable substitution (empty when unset) |

A `with:` value that is exactly one `{{ … }}` keeps its original type
(numbers stay numbers, objects stay objects).

### Step outputs

`http` → `{status, headers, body}` (body parsed as JSON when possible).
`telegram` → `{status, chat_id}`.

## Execution semantics

- Steps run strictly in order; each step is one row in `job_queue`.
- **At-least-once** delivery with idempotency: a step that already SUCCEEDED
  for an execution is never re-run.
- Retry: per-step `max_attempts` (default 3), fixed 30s backoff;
  exhausted → step FAILED → execution FAILED, later steps never run.
- Crash recovery: claimed jobs hold a `locked_until` lease (60s); if a worker
  dies, the lease expires and any worker picks the job up again. Actions that
  outlive the lease can be delivered twice — that's the at-least-once contract.
- Statuses: execution `PENDING → RUNNING → SUCCEEDED | FAILED`,
  step additionally `SKIPPED` (false `if:` condition).

## REST API

| Method & path | Description |
|---|---|
| `POST /api/workflows` | create workflow; body = raw YAML (`Content-Type: text/plain` or `application/yaml`); 201 + JSON |
| `GET /api/workflows` | list workflows |
| `GET /api/workflows/{id}` | one workflow with definition + YAML source |
| `PUT /api/workflows/{id}` | replace definition (raw YAML body), re-enables |
| `DELETE /api/workflows/{id}` | soft delete: `enabled=false`, history kept |
| `POST /api/workflows/{id}/run` | start an execution manually; 202 |
| `POST /hooks/{path}` | webhook trigger; JSON body becomes `trigger.*` payload; 202 |
| `GET /api/executions?workflowId=` | recent executions (latest 100) |
| `GET /api/executions/{id}` | execution with per-step status, input, output, error |

Errors are RFC 7807 `application/problem+json`. **No auth in M1** — put it
behind a reverse proxy or private network; auth is on the roadmap (M4).

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/potok` | Postgres JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `potok` / `potok` | DB credentials |
| `PORT` | `8080` | HTTP port |
| `TELEGRAM_BOT_TOKEN` | – | enables the `telegram` action |
| `TELEGRAM_CHAT_ID` | – | convention used by the examples via `${TELEGRAM_CHAT_ID}` |
| `POTOK_QUEUE_WORKERS` | `2` | concurrent queue workers (virtual threads) |
| `POTOK_QUEUE_POLL_INTERVAL` | `PT1S` | idle poll sleep |
| `POTOK_QUEUE_LOCK_TIMEOUT` | `PT60S` | job lease; crashed workers recover after this |
| `POTOK_QUEUE_RETRY_BACKOFF` | `PT30S` | fixed delay between attempts |
| `POTOK_QUEUE_DEFAULT_MAX_ATTEMPTS` | `3` | default per-step attempts |
| `POTOK_CRON_REFRESH_INTERVAL` | `PT30S` | how often cron schedules re-read the DB |
| `POTOK_TELEGRAM_API_BASE` | `https://api.telegram.org` | Bot API base (tests/self-hosted) |

## Development

```bash
./gradlew test          # unit + integration (needs Docker for Testcontainers)
./gradlew bootRun       # against a local postgres (see DB_URL default)
```

Package-by-feature layout, single module: `api` (REST), `definition`
(YAML/templating/storage), `trigger` (cron, webhook), `execution`
(queue, workers, retry), `action` (SPI + handlers).

### Adding an action

Implement one Spring bean — discovery is automatic:

```java
@Component
class SlackActionHandler implements ActionHandler {
    public String type() { return "slack"; }
    public StepResult execute(StepContext ctx) { ... }
}
```

## Roadmap

- **M1 (this)** — linear workflows, cron + webhook triggers, http + telegram
  actions, Postgres queue, REST API, Docker.
- **M2** — DAG execution (needs/parallel branches), richer conditions,
  more actions (Slack, email, shell), per-step timeouts.
- **M3** — pluggable queue backends (Kafka/Rabbit) behind the queue interface,
  horizontal worker scaling, metrics + tracing.
- **M4** — web UI, API auth (tokens), multi-tenancy, secrets management.

See [docs/roadmap.md](docs/roadmap.md) and [docs/handoff.md](docs/handoff.md)
for the current state and next steps.
