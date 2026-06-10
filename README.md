# потóк

[![ci](https://github.com/AntonovYuriy/Potok/actions/workflows/ci.yml/badge.svg)](https://github.com/AntonovYuriy/Potok/actions/workflows/ci.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)

**Potok** is a self-hosted workflow engine: triggers (cron, webhook, HTTP/RSS
pollers) start YAML-defined DAGs of steps that call HTTP APIs, send Telegram
messages, or run your own actions. One Java service plus one PostgreSQL
database — the queue, the state, the history and the dedupe all live in
Postgres, so there is no broker to operate. A built-in dashboard (served from
the same jar, zero build step) covers editing, version history, executions,
the dead letter queue and API tokens.

![dashboard demo](docs/demo.gif)

## Quickstart

```bash
git clone https://github.com/AntonovYuriy/Potok.git && cd Potok
docker compose up -d
open http://localhost:8080            # dashboard; create a workflow in the editor
```

Or via API:

```bash
curl -s -H 'Content-Type: text/plain' --data-binary @examples/healthcheck.yaml \
     localhost:8080/api/workflows
```

## Architecture

```mermaid
flowchart LR
    subgraph triggers
        CRON[cron]
        HOOK[webhook<br/>HMAC optional]
        POLL[poll / rss<br/>edge-triggered]
    end
    subgraph postgres [PostgreSQL]
        Q[(job_queue<br/>SKIP LOCKED)]
        ST[(executions,<br/>versions, DLQ,<br/>poll state)]
    end
    subgraph engine [Potok jar]
        W1[worker]
        W2[worker]
        UI[dashboard + REST API]
    end
    A1[http action]
    A2[telegram action]
    A3[your ActionHandler bean]

    CRON --> Q
    HOOK --> Q
    POLL --> Q
    Q -->|FOR UPDATE SKIP LOCKED| W1 & W2
    W1 & W2 --> A1 & A2 & A3
    W1 & W2 --> ST
    UI --- ST
```

Workers are virtual threads claiming jobs with `SELECT … FOR UPDATE SKIP
LOCKED`; a `locked_until` lease doubles as crash recovery. Every step of every
execution is one queue row — parallel DAG branches are just multiple claimable
rows.

## YAML reference

```yaml
name: price-watch                  # unique among active workflows

trigger:                           # exactly one of cron | webhook | poll | rss
  cron: "0 19 * * *"               # 5-field crontab or 6-field Spring cron
  # webhook:
  #   path: "gh-events"            # → POST /hooks/gh-events
  #   hmac_secret_env: "GH_SECRET" # optional: require X-Hub-Signature-256 (see Security)
  # poll:                           # first poll runs immediately, then every interval
  #   interval: 5m
  #   http: { method: GET, url: "https://shop.example/api/item/42" }
  #   extract: { jsonpath: "$.price" }    # or { css: "span.price" } for HTML
  #   fire_when: "{{ poll.value < 100 }}" # expression (edge-triggered) or "changed"
  # rss: { interval: 15m, url: "https://hnrss.org/frontpage" }

steps:                             # a DAG; without `needs` steps run in file order
  - name: fetch
    action: http
    retry: { max_attempts: 5, base_delay: 10s, max_delay: 10m }   # optional
    with:
      method: GET
      url: "https://example.com/api"
      # headers: { Accept: application/json }
      # body: { any: json }
      # fail_on_status: false      # record non-2xx as success (healthcheck pattern)

  - name: notify
    needs: [fetch]                 # explicit dependencies; [] = root
    if: "{{ steps.fetch.status == 200 && exists(steps.fetch.body.message) }}"
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"          # ${VAR} = environment variable
      text: "Result: {{ steps.fetch.body.message }}"
```

### DAG semantics

- `needs: [a, b]` — runs once ALL listed steps are satisfied. No `needs` =
  the previous step in the file (linear YAMLs just work); independent ready
  steps run **in parallel**.
- Validation at create/update (400 with a clear message): dependency cycles,
  unknown `needs`, template references outside the step's dependency closure.
- A step that exhausts retries fails the execution and poisons its downstream
  as `SKIPPED (dependency failed: X)`; **independent branches keep running**.
  A DLQ requeue revives the downstream too.
- A step SKIPPED by its own `if:` **counts as satisfied** for dependents.

### Conditions

Used in step `if:` and `poll.fire_when`. Grammar (parentheses group, `&&`
binds tighter than `||`):

```
expr   := or
or     := and ('||' and)*
and    := unit ('&&' unit)*
unit   := '(' expr ')' | a OP b | contains(x, y) | exists(path) | path
OP     := == | != | > | < | >= | <=
```

Operands: dot-paths (`steps.fetch.body.price`, `trigger.user`, `poll.value`),
numbers, `'strings'`, `true/false/null`. Comparison is numeric when both sides
are numbers, lexicographic otherwise. `contains` = substring or list
membership; `exists` = path resolves to non-null.

### Templating

`{{ path }}` interpolates into strings; a `with:` value that is exactly one
expression keeps its type. Context: `trigger.*` (webhook payload / poll
response / rss item), `steps.<name>.*` (outputs of dependencies only).
`${ENV_VAR}` substitutes environment variables at execution time.

### Retry

Exponential backoff with full jitter:
`delay = random(0, min(max_delay, base_delay × 2^(attempt−1)))` — defaults
3 attempts, base 10s, cap 10min. Exhausted ⇒ dead letter queue.

## Versioning

Every create/update appends to an immutable history (`workflow_version`);
executions pin the version they started with — editing a workflow never
changes what an in-flight execution does. Rollback creates a *new* version
with the old content. Versions are plain text and kept forever.

```
GET  /api/workflows/{id}/versions?page=&size=
POST /api/workflows/{id}/versions/{n}/rollback
```

## Security

**API tokens** — `POTOK_API_KEY` (env) is the bootstrap root key; further
tokens are managed at `/api/tokens` (or the Tokens page in the dashboard).
Plaintext is shown once; only SHA-256 hashes are stored; revocation is
immediate. All of `/api/**` accepts root key or any active token via the
`X-API-Key` header — except `POST /api/admin/purge`, which is root-only.

**Webhook signatures** — set `trigger.webhook.hmac_secret_env: "MY_SECRET"`
and exports `MY_SECRET` on the server. Deliveries must then carry
`X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body>` — exactly what
GitHub sends. Wiring a GitHub webhook: repo → Settings → Webhooks → payload
URL `https://your-host/hooks/<path>`, content type JSON, secret = the same
value as `MY_SECRET`. Invalid/missing signature → 401; comparison is
constant-time; unset env var fails closed.

## Dead letter queue

Exhausted jobs land in `dead_letter` with input + trigger snapshot.
`GET /api/dlq`, `POST /api/dlq/{id}/requeue` (reopens the execution, revives
dependency-skipped downstream), `DELETE /api/dlq/{id}`. Optional Telegram
alert: `POTOK_DLQ_TELEGRAM=true`, rate-limited to 1/min.

## Dashboard

Served from the jar at `/` — vanilla ES modules and hand-written CSS, no
build step, no CDN. Workflow list and detail, **YAML editor** with inline
validation errors, **version history with rollback**, execution step timeline
(durations, attempts, errors, outputs), DLQ ops, **API tokens** page.
Auth-aware: `/api/meta` (public) tells the UI whether to prompt for a key.
Open views poll every 7s.

## REST API

| Method & path | Description |
|---|---|
| `POST /api/workflows` | create; body = raw YAML (`Content-Type: text/plain`) |
| `GET /api/workflows` · `GET /{id}` | list / detail (definition + YAML + current version) |
| `PUT /api/workflows/{id}` | update = new version; re-enables |
| `DELETE /api/workflows/{id}` · `POST /{id}/enable` | soft disable / enable |
| `POST /api/workflows/{id}/run` | manual run, 202 |
| `GET /api/workflows/{id}/versions` · `POST .../versions/{n}/rollback` | history / rollback |
| `POST /hooks/{path}` | webhook trigger (signature-checked when configured) |
| `GET /api/executions?workflowId=&page=&size=` · `GET /{id}` | history / step detail |
| `GET /api/dlq` · `POST /api/dlq/{id}/requeue` · `DELETE /api/dlq/{id}` | dead letters |
| `POST /api/tokens` · `GET /api/tokens` · `DELETE /api/tokens/{id}` | token management |
| `POST /api/admin/purge` | run retention now (root key only) |
| `GET /api/meta` | public: app name, authRequired |

Errors are RFC 7807 `application/problem+json`.

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/potok` | Postgres JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `potok` / `potok` | DB credentials |
| `PORT` | `8080` | HTTP port |
| `POTOK_API_KEY` | – | root API key; unset = auth off (local dev) |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | – | telegram action / examples |
| `POTOK_QUEUE_WORKERS` | `2` | concurrent workers (virtual threads) |
| `POTOK_QUEUE_LOCK_TIMEOUT` | `PT60S` | job lease; crash recovery horizon |
| `POTOK_QUEUE_RETRY_BASE_DELAY` / `_MAX_DELAY` | `PT10S` / `PT10M` | backoff shape |
| `POTOK_QUEUE_DEFAULT_MAX_ATTEMPTS` | `3` | default per-step attempts |
| `POTOK_SHUTDOWN_GRACE` | `PT20S` | in-flight budget on SIGTERM, then lease release |
| `POTOK_CRON_REFRESH_INTERVAL` | `PT30S` | trigger schedules re-read |
| `POTOK_RETENTION_DAYS` | `30` | nightly purge of finished executions |
| `POTOK_DLQ_TELEGRAM` | `false` | DLQ Telegram alerts |
| `POTOK_LOG_JSON` | `false` | structured JSON logs |
| `POTOK_TELEGRAM_API_BASE` | `https://api.telegram.org` | Bot API base (tests/self-hosted) |

## Observability

`/actuator/prometheus`: `potok_queue_depth`, `potok_dlq_size`, execution
started/succeeded/failed counters, `potok_step_duration_seconds{action,outcome}`,
retry / action-failure / purge counters. Liveness and readiness probes
(readiness includes the DB). MDC `execution_id` + `workflow_name` on step logs.

## Design decisions

**Postgres as the queue, not a broker.** A workflow engine needs durable
state in a database anyway; putting the queue in the same Postgres means
exactly-one moving part, transactional handoff between "execution created"
and "job visible", and free backpressure. `FOR UPDATE SKIP LOCKED` gives
contention-free claims; the `locked_until` lease makes crash recovery a
predicate instead of a startup sweep. The trade-off — polling latency and
queue throughput bounded by Postgres — is the right one below thousands of
jobs/minute, which is this tool's territory.

**At-least-once, with idempotency where it's cheap.** Actions do external
I/O, so holding a transaction across them is off the table. Instead: the
lease guards the attempt, re-delivery after a crash is possible, and a step
that already SUCCEEDED is never re-run. Duplicate side effects are confined
to the rare lease-expiry window — documented, not hidden.

**Edge-triggered pollers.** A condition that *stays* true fires once, not
every 5 minutes — alerting people repeatedly about the same price drop trains
them to ignore alerts. State (`poll_state`, `rss_seen`) lives in Postgres and
commits in the same transaction as the execution start, so restarts neither
lose nor double fires. `extract` narrows change detection to the one value
that matters, immune to timestamp noise.

**DAG join dedupe via unique index.** When two parallel branches finish
simultaneously, both compute the join step as ready. Rather than a
coordinator, a unique index on `(execution_id, step_name)` plus
`ON CONFLICT DO NOTHING` makes the second enqueue a no-op — correctness from
the database, not from locks in application code.

**Embedded no-build UI.** The dashboard is vanilla ES modules served from the
jar: no node toolchain in CI, no CDN dependency at runtime, one artifact to
deploy. The cost (no framework conveniences) is acceptable at this UI size;
the benefit is that the UI can never be down, stale, or blocked by a build.

**Executions pin their definition.** Editing a workflow mid-run must not
change what running executions do, so each execution snapshots the parsed
definition and version at start. History is append-only; rollback appends.

## Deploying

Free-tier guide (Koyeb + Neon, from the GHCR image CI publishes):
[docs/deploy.md](docs/deploy.md). Multi-instance: job execution is safe at any
replica count (SKIP LOCKED); trigger schedulers dedupe via advisory locks and
cron claims — but the free tier is one instance anyway, and one instance is
the well-trodden path.

## Development

```bash
./gradlew test          # unit + integration (needs Docker for Testcontainers)
./gradlew bootRun       # against local postgres
```

Package-by-feature: `api`, `definition`, `trigger`, `execution`, `action`.
Adding an action = one Spring bean implementing `ActionHandler`; see
`WarsawWasteActionHandler` for a real-world example. More in
[CONTRIBUTING.md](CONTRIBUTING.md).

## Roadmap

Done: M1 linear engine → M2 reliability/observability → M3 auth, DAG,
pollers, dashboard → M4 editor, versioning, HMAC, tokens, extract.
Next (M5 candidates): multi-user/RBAC, workflow templates, SSE live updates
in the dashboard, richer actions. See [docs/roadmap.md](docs/roadmap.md).
