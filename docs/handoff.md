# Handoff

_Last updated: 2026-06-17 (M6.1 audit follow-ups done)._

## Current state

- **M6.1 audit follow-ups done — wording + test-coverage + flake guard** (2026-06-17, 236 tests):
  - **`to_recipient` wording aligned with the fail-loud behavior.** Behavior is unchanged — a `telegram` step targeting a non-APPROVED recipient by id/name fails the step with a clear error (`no approved recipient matches 'to_recipient': X (PENDING or REVOKED recipients never receive)`). README "Telegram recipients", `help/reference.json`, and the new integration test `toRecipientTargetingPendingFailsTheStep` make this explicit. Rationale: a single named target is almost certainly a typo or stale config, not something to silently swallow; broadcasts (`to: approved`) still skip non-APPROVED quietly.
  - **Integration coverage gaps closed (no behavior change, locks the rules in):**
    - `TelegramRecipientIngestIntegrationTest` drives the REAL `TelegramUpdatesPoller` via WireMock `/bot.*/getUpdates`: a `message` update from an unknown chat lands the recipient as PENDING, the bot replies, and the `offset` in subsequent getUpdates calls advances past the handled `update_id` so Telegram never re-delivers it. A second test ships two messages from the same chat in one batch and asserts the upsert keeps exactly one row + offset past both updates.
    - `TelegramPollLockIntegrationTest` contends two `TelegramPollLock` instances against the same Postgres: only one acquires while held, the second succeeds after the first releases, release-without-acquire is a no-op.
    - `RecipientsIntegrationTest` gains: explicit `2 APPROVED + 1 REVOKED + 1 PENDING → exactly 2 sends` shape (chat-by-chat counter to ignore unrelated approved leftovers from cached contexts), `DELETE /api/recipients/{id}` → 204 then 404 on repeat, and `to_recipient` targeting a still-PENDING chat → step FAILED with zero outbound sends.
  - **Flaky `DagIntegrationTest` neutralised with the Gradle `org.gradle.test-retry` plugin (`maxRetries=1`, `maxFailures=10`, `failOnPassedAfterRetry=false`).** Diagnosis: the failure was `parsing HTTP/1.1 status line, receiving [??????…]` on a random Tomcat port — `@SpringBootTest(webEnvironment=RANDOM_PORT)` plus cached contexts occasionally rebinds a port whose previous occupant still has unconsumed bytes in the kernel buffer; the request reads garbage and `RestTemplate` reports it as a status-line parse error. Why retry over a structural fix: the alternatives are forcing `cacheMaxSize=1` (≈3× longer suite) or hand-rolling a port-allocator (high blast radius for a once-in-N runs nuisance). `maxFailures=10` caps the safety net so a real regression still fails the build fast on attempt 1.
  - Suite: **236 tests green** (was 229), full `./gradlew clean test` + `--rerun-tasks` both BUILD SUCCESSFUL back to back.

- **M6 done — Telegram recipient directory + per-chat approvals** (2026-06-17, PR #17, squash `6e7a008`, 229 tests):
  - **Scope clarified in code AND docs**: the recipient directory governs WHO RECEIVES bot messages. It grants NO Potok control-plane access — `/api/**` stays behind `X-API-Key`/`api_token` always. README "Telegram recipients" section + the Recipients dashboard page both say this explicitly.
  - New `telegram_recipient` + `setting` tables (Flyway V11). `RecipientService` runs the PENDING/APPROVED/REVOKED state machine and the bot reply logic (centralised + unit-tested); first contact lands PENDING by default, APPROVED when `telegram_auto_approve` is on. **Default OFF** — leaving it off avoids the "bot is discoverable → anyone is subscribed" failure mode.
  - `TelegramUpdatesPoller` now consumes `message` updates as well as `callback_query`. Single-consumer guarantee via a session-scoped Postgres advisory lock on a dedicated JDBC connection (`TelegramPollLock`) so replicas don't race the in-memory offset; existing 409-from-getUpdates fallback unchanged. Bot replies to `/start`, `/stop`, `/status` and stays silent for plain text from approved chats. `POTOK_TELEGRAM_POLL_UPDATES=false` continues to switch approval buttons to URL mode AND also stops new-recipient auto-registration (documented).
  - REST: `GET /api/recipients` (paged, `status=` filter, returns masked chat ids), `POST /api/recipients/{id}/approve|revoke`, `DELETE /api/recipients/{id}`, `GET/PATCH /api/settings { telegram_auto_approve }`. All token-authed like the rest of `/api/**`.
  - Dashboard: new "Recipients" nav item with a pending-count badge; page has status filters (All/Pending/Approved/Revoked), per-row Approve / Re-approve / Revoke / Delete, and the auto-approve toggle at the top with the trust trade-off spelled out. Auto-refresh consistent with Workflows/DLQ (7s).
  - `telegram` action: backward-compatible new addressing. `chat_id: ...` unchanged (M1 examples keep working — covered by `chatIdPathStillWorksUnchanged` test). `to_recipient: <uuid|display-name>` sends to one APPROVED recipient (PENDING/REVOKED never receive even if referenced). `to: approved` fans out to every APPROVED recipient — per-recipient send, output `{sent_count, total_recipients, failed_count, failures}`, step fails only when EVERY send fails. Empty broadcast is not a failure (just `sent_count=0`).
  - Tests: 28 new (229 total). `RecipientServiceTest` pins the state machine, auto-approve routing, and `/start`/`/stop`/`/status`/silent-returning-chat reply rules. `TelegramActionHandlerTest` covers all three addressing keys, ambiguous-combo rejection, fan-out, partial-failure, all-fail. `RecipientsIntegrationTest` is the end-to-end wiring on a real Postgres: PENDING vs APPROVED on first contact based on the toggle, approve/revoke flow via REST, broadcast that hits ONLY approved (PENDING + REVOKED chats verified absent in the WireMock sendMessage bodies), backward-compat `chat_id` path. The integration test sets `potok.telegram.poll-updates=false` and drives bot messages through `RecipientService.handleBotMessage` directly — the poller's getUpdates wiring stays covered by the existing `TelegramButtonsIntegrationTest`.

- **Integration docs done — "Connect & API" tab** (2026-06-16, PR #16, squash `1199cd3`, 201 tests):
  - New third Help tab (`#/help/connect`) plus mirror at `docs/integration.md`; both render the same source asset `src/main/resources/static/help/connect.md` — a `HelpIntegrationDocsTest` byte-compares the two, so the GitHub-rendered doc cannot silently drift from the dashboard view. Tiny vanilla-JS markdown renderer (`static/js/markdown.js`) supports headings/lists/GFM tables/fenced code/links — no build step added.
  - Two clearly separated sections, both verified against the running code: **A. Trigger Potok from your app** — `POST /hooks/{path}` unsigned + signed (GitHub-compatible `X-Hub-Signature-256`, bash/Python/Node snippets, repo-webhook wiring) with the explicit "hooks are not behind X-API-Key" callout; **B. Control Potok via REST API** — `X-API-Key` model (root vs `api_token`, constant-time), full endpoint reference table with auth levels, Content-Type rules for raw-YAML routes, end-to-end `mint token → create workflow → run → poll → revoke` walkthrough, RFC 7807 error shapes, real limits (`POTOK_PREVIEW_TIMEOUT`, MAX_STEPS=10, page caps). README gains an "Integrate / API" section pointing at `docs/integration.md`.
  - Same test asserts auth reality matches the doc: `/api/meta`, `/hooks/**`, `/actuator/health`, the asset itself stay key-free under `POTOK_API_KEY`; documented `/api/**` paths return 401 problem+json without the header. 4/4 new tests; 201 total green.
  - Live-verified (compose, real key + HMAC secret): unsigned hook → 401 "missing or invalid X-Hub-Signature-256 signature"; wrong sig → 401; `openssl dgst -sha256 -hmac` over the raw body → 202 with `PENDING`. Walkthrough curl block executed end-to-end: token mint (plaintext shown once) → workflow create → manual run → poll PENDING→SUCCEEDED → revoke 204 → next call 401.

- **M6.1 done — durable waits + human-in-the-loop approvals** (2026-06-12, PR #14, squash `431745b`, 195 tests):
  - `wait: <duration>` step field: durable sleep via job_queue.run_at reschedule (no action/with, never retried); durations gain `d` units everywhere (parser + form validator). `action: approval`: ONE telegram message with two one-time links (`GET /hooks/approval/<token>`, public — token is the credential; SHA-256 hashes only in new `approval` table, Flyway V9), step parks WAITING, worker released. Decision (click / dashboard Approve-Deny buttons / `POST /api/executions/{id}/steps/{step}/decide` / timeout) → step SUCCEEDED with `{approved, timed_out, decided_at}`; downstream branches via ordinary `if:`. **Timeout = result, never failure** (no retry/DLQ); telegram send failure = normal retry semantics. `POTOK_PUBLIC_URL` (default localhost:8080) controls link host.
  - WAITING first-class for steps AND executions (V9 widens both check constraints); UI: yellow badge, "sleeping until"/"waiting for approval" notes, Approve/Deny on the step card. DAG advance extracted to `ExecutionAdvancer` (JobProcessor + approval clicks share it). Preview simulates both (downstream shown for approved path).
  - **Backward-compat standing rule starts here**: approval needs only `text` (timeout→24h, channel→telegram, chat_id→TELEGRAM_CHAT_ID); pre-M6.1 YAML identical behavior (compat Step ctor keeps old jsonb snapshots valid; drift harness untouched).
  - Catalog 15 cards: follow-up-reminder (2nd; message → wait Nd → follow-up), confirm-before-act (4th; webhook → approval → act/cancelled). README "Durable waits & approvals", use-cases 15 sections, reference rows, demo.gif re-recorded with approval frame.
  - Tests incl. **RestartSurvivalIntegrationTest**: app #1 parks wait+approval and closes; app #2 on the same DB wakes the sleeper on time and honors the dead process's link. Test infra: per-context Hikari pool capped at 3 (src/test/resources/application.properties) — context count outgrew Postgres max_connections.
  - Live-verified (compose, real telegram): follow-up 60s — both real messages, slept PT1M; confirm-before-act — curl webhook → real question with links on the phone → approve → stub got exactly 1 POST → "done" message; WAITING screenshot.

- **M5.4 done — audit nits** (2026-06-11, PR #13, squash `8d4ffcd`, 184 tests):
  - Quote-safe template params: render filters `{{param.x|cond}}` (double-quoted condition literal inside single-quoted YAML: `\`/`"` backslash-escaped, `'` doubled) and `{{param.x|dq}}` (double-quoted YAML scalar) — Java TemplateRenderer + JS twin in help.js, identical semantics. TemplateResolver understands backslash escapes inside condition string literals (quote tracking skips them, operands unescape). keyword-on-page uses both; O'Brien / say "hi" / mixed-quote keywords create 2xx (integration test).
  - garbage-reminder `when` → select (today|tomorrow). Preview poll-fetch wording: jsonpath says "value found"/"check the path" (css keeps element/selector). CI docker job: `concurrency: ghcr-publish, cancel-in-progress: false` — GHCR unknown-blob race closed. templates.json now pretty-printed.

- **M5.3 done — 13-card human-first catalog** (2026-06-11, PR #12, squash `cfea4a5`, 178 tests):
  - 5 new templates: json-threshold (universal "watch a number from any API", 2nd in gallery, select param for comparison), keyword-on-page (contains() over body text, edge-triggered), monthly-payment-reminder (renders `0 H D * *`), release-watcher (rss on github releases.atom), price-drop (css + `number: true` + numeric fire_when).
  - Engine affordance: `extract.number: true` — PollExtractor.parseNumber coerces "249,99 zł"/"$1,299.00"/"1 234,50 PLN" → BigDecimal (comma/dot locale logic, first number in text, no digits → null). Parser validates the flag; unit-tested matrix.
  - All 13 cards rewritten human-first: goal-titles, jargon-free problems, plain trigger chips ("on a schedule"/"watches a page"/…), "What you'll need:" line in card detail auto-built from required params; gallery ordered by simplicity, garbage-reminder last (custom-action example); template messages now English (action output unchanged). Forms: new `select` param type (dropdown, validated client-side + manifest schema test asserts options + default∈options). Gallery order pinned by test.
  - docs/use-cases.md rewritten (13 walkthroughs), README table 13 rows, demo.gif re-recorded (gallery layout changed; frames now end on the inline preview panel).
  - Live-verified e2e: json-threshold form → select dropdown → Preview honestly "NOT met (4.2)" → Create → baseline quiet → flip local API 4.20→4.42 → exactly 1 execution, REAL telegram "📈 The value is now 4.42 (> 4.30)".

- **M5.2 done — live preview + SSRF guard** (2026-06-11, PR #11, squash `0a29bfb`, 173 tests):
  - `POST /api/preview` (yaml body, normal tokens): validates like create, runs the DAG ONCE synchronously in-process — nothing persisted (no workflow/execution rows, no poll state). Read-only actions execute for real (http GET, poll fetch → live `trigger.*` for step templates, ssl_check, warsaw_waste; rss uses the latest feed item); side effects simulated (telegram text rendered NOT sent, non-GET http → "would send POST to …"); custom/unknown actions never executed. Engine-true semantics: condition-skip satisfies dependents, downstream of failed step → "would be skipped". Limits: 10s wall clock (`POTOK_PREVIEW_TIMEOUT`), 10 steps, 1 attempt, capped strings; failures are result entries, not 5xx.
  - UI: "Preview ▶" on the template form AND the YAML editor; inline panel — friendly card per step (`✓/✉/○/✗/→`), trigger card with plain-language fire-semantics line, technical detail collapsed. Shared module static/js/preview.js.
  - `UrlGuard` (io.potok.common): blocks loopback/RFC1918/link-local (incl. 169.254.169.254)/IPv6 unique-local in http + pollers + ssl_check + warsaw_waste. `POTOK_ALLOW_PRIVATE_URLS=true` disables (README Security documents trade-off + limitations: initial URL only, no redirect re-check, no DNS-rebinding defense). Tests run with allow=true via src/test/resources/application.properties (WireMock on localhost); guard covered by UrlGuardTest + PreviewSsrfIntegrationTest (overrides to false).
  - Live-verified: real-browser clickthrough (Playwright via npx, browsers in ~/Library/Caches/ms-playwright) — form preview of availability-watcher vs local page ("element found: In stock!", message rendered not sent); editor preview of garbage-reminder vs REAL Warsaw API ("No collection today", condition honestly false, no telegram sent); SSRF default blocks metadata endpoint with friendly text.

- **M5.1 done — parameterized templates, form-based import** (2026-06-11, PR #10, squash `e9b06d4`, 147 tests):
  - `templates/*.yaml.tpl` (8, incl. new `simple-reminder` — cron+message, first in the gallery) with `{{param.key}}` placeholders are the single source of truth. `examples/` is generated: `./gradlew renderExamples` renders each template with its `templates.json` defaults. Drift impossible — `TemplatesIntegrationTest` re-renders every template, asserts byte-equality with the committed example, then creates it via the API (2xx).
  - `templates.json`: per-template `default_name` + typed `params` (`url|string|duration|cron|number|text|env`). `env` params never ask for input — rendered as locked notes in the form.
  - Help UI: card → Use template → typed form (client-side validation: url/duration/cron/number; API errors 400/409 surfaced inline) → YAML rendered in JS (`renderTemplate` twin of `TemplateRenderer`) → existing create API → navigate to workflow. "Advanced: edit YAML" escape hatch drops rendered YAML into the editor (M5 flow).
  - Dockerfile: `COPY templates templates` — `.tpl` assets 404'd in the container while tests passed from classpath (same bug class as M5's missing examples COPY; caught in live verify again).
  - Fixed M5 desync: garbage card text/sample now match `when: today` morning semantics ("Сегодня вывоз: Bio").
  - Live-verified (compose, real Telegram token): availability-watcher with form values against a local page — baseline quiet, badge flip fired exactly 1 execution, real message delivered; simple-reminder created from the form, manual run SUCCEEDED, real message delivered. demo.gif re-recorded with the form flow.
- **M5 done — showcase** (2026-06-11, PR #8, squash `ba05bf5`, 132 tests):
  - Use-case library: 7 importable automations (examples/ + docs/use-cases.md, problem → YAML → sample message). Flagship garbage-reminder live-verified against the real Warsaw API incl. a real delivered Telegram message.
  - New `ssl_check` action (~100 lines, trust-all by design — inspects expired/self-signed certs, outputs days_left/not_after/subject/issuer) — the minimal "write your own action" template.
  - Dashboard Help: Examples tab (cards → deep-linked detail #/help/<id> → one-click Import into the editor with a placeholder checklist) + YAML reference cheat-sheet. Single source: examples/ is copied into jar resources at build (templates.json/reference.json drive the UI); Dockerfile now copies examples/ into the build stage (was a container-only 404 — caught in live verify).
  - README: use-case table; demo.gif re-recorded with the Help flow.
- **M4.1 done — repository cleanup + audit gap fixes** (2026-06-10):
  - History rewritten (git filter-repo): local tooling configuration removed from every commit; full-history sweep clean in a fresh clone (0 matches, 133 tracked files); new root commit `2169a18`, force-pushed; CI green on the rewritten main, image republished. Local-only ignores live in `.git/info/exclude` (re-add on a fresh machine if you keep tooling files in the working copy).
  - Condition validation at create/update (PR #7, squash `cdbe96e`): malformed `if:` / `fire_when:` (dangling `&&`, unbalanced parens) → 400 with step name; pure syntax walk, no evaluation, so runtime short-circuiting can't hide errors.
  - Immediate first poll (same PR): pollers schedule at `Instant.now()` + fixedDelay — baseline lands right after create/enable (live-measured 0.3s; previously one full interval). Documented in README.
  - Live-verified: malformed fire_when → 400; full poll run (extract `$.product.price`, edge fire on threshold, `{{ trigger.value }}` in step input) SUCCEEDED.
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

- Recipients have no per-workflow subscription topics yet — `to: approved` is one undifferentiated audience. The next milestone candidate is `/subscribe <workflow>` (a recipient picks which workflows they want) and a matching `to: subscribers` addressing.
- The bot updates poller offset is in-memory; after a restart Telegram re-delivers a small batch — recipient upsert is idempotent and approval decide() is one-time, so the worst case is one extra "you're subscribed" reply per re-delivered `/start`. Persisting the offset would remove even that.
- No CLI/REST path to add a recipient WITHOUT the chat first messaging the bot (operator can't pre-seed a chat id from outside). Adds easily but not in M6.
- Approval channel is telegram-only (channel param validated, but no other senders yet — B3 action pack would unlock email/slack).
- No "cancel execution" API: a WAITING approval can only be decided or left to expire; an unwanted parked wait runs to completion.
- `wait` duration is unbounded — a typo like `wait: 300d` parks a job for a year with no admin override (cancel API would solve both).

- Dashboard list still does N+1 last-run fetches (fine for small N); no SSE — views poll every 7s.
- Tokens have no scopes — any active token = full API (except root-only admin); no users/RBAC.
- Conditions: no `!` negation, no arithmetic; `contains` is case-sensitive.
- UI editor is a plain textarea — no YAML syntax highlighting (deliberate: no build step).
- cron_fire claims assume minute granularity — a 6-field cron with seconds would dedupe to 1 fire/min across replicas (single instance unaffected).
- `renderTemplate` exists twice (Java `TemplateRenderer` + JS twin in help.js) — kept in sync by the drift test only at the Java end; JS regex must match `PARAM` pattern if it ever changes.
- Preview executes the poll fetch but evaluates `fire_when` informationally only — it cannot show "would fire" for changed-mode (needs two observations by definition).
- UrlGuard checks the initial URL only: redirect chains are followed unguarded, DNS rebinding not addressed (documented in README).

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

**Live verify M6 with a real second Telegram account** (owner step, needs 2 phones / accounts):
1. `docker compose up -d` with `TELEGRAM_BOT_TOKEN` set; auto-approve OFF (the default).
2. Message the bot `/start` from the second account → check the Recipients page shows it as PENDING with a `…last 4 of chat id` mask and the display name.
3. Click Approve → status flips to APPROVED.
4. Create a workflow:
   ```yaml
   name: m6-live
   trigger: { webhook: { path: "m6-live" } }
   steps:
     - name: notify
       action: telegram
       with: { to: approved, text: "M6 broadcast — hi" }
   ```
5. Run it → both your own chat (if approved) and the second account get the message; PENDING/REVOKED chats stay silent.
6. Revoke the second account → run again → second account no longer receives.

Then: **First deploy to Koyeb + Neon per docs/deploy.md — manual, by owner** (accounts, secrets, clicking). Set `POTOK_API_KEY`; create per-client tokens after boot; use `hmac_secret_env` for public webhooks.

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
