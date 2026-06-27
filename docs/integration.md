# Connect & API

Developer-facing reference for integrating *other* programs with a Potok instance.
Two surfaces:

- **A. Inbound webhooks** — your program fires a workflow by `POST`ing to a webhook trigger.
- **B. REST API** — your program manages workflows, runs them, reads executions.

Throughout this page `{base}` is your instance URL — for example `http://localhost:8080`
when running `docker compose up`, or your Render / Koyeb URL in production. The
public discovery endpoint `GET {base}/api/meta` returns `{ "app": "potok", "authRequired": true|false }`
so an integration can detect whether `X-API-Key` is needed without trial requests.

There is **no SDK** — plain HTTP, JSON in and out, raw YAML for workflow bodies.

---

## A. Trigger Potok from your app

A workflow with a `webhook` trigger gets a public path of its own:

```yaml
name: gh-events
trigger:
  webhook:
    path: "gh-events"          # → POST /hooks/gh-events
steps:
  - name: notify
    action: telegram
    with:
      chat_id: "${TELEGRAM_CHAT_ID}"        # or: to: approved | to: subscribers (M7) | to_recipient: "Name"
      text: "Got event from {{ trigger.body.repository.full_name }}"
```

After `POST /api/workflows` accepts this YAML, the workflow listens at
`POST {base}/hooks/gh-events`. The request body becomes the trigger payload and
your steps read it through templating:

| Path | Resolves to |
|---|---|
| `{{ trigger.body }}` | the parsed JSON body (or `{ raw: "..." }` when the body isn't JSON) |
| `{{ trigger.body.some.field }}` | dot-path into the JSON body |
| `{{ trigger.path }}` | the URL path segment that matched the workflow |
| `{{ trigger.type }}` | the literal string `"webhook"` |

> Webhooks are **not** behind `X-API-Key`. The only open paths on a Potok instance are
> `/hooks/**`, `/api/meta`, and `/actuator/**`; everything else under `/api/**` requires
> a key when `POTOK_API_KEY` is set.

### Unsigned example

```bash
curl -sS -X POST {base}/hooks/gh-events \
  -H 'Content-Type: application/json' \
  -d '{"repository":{"full_name":"acme/widgets"},"pusher":"alice"}'
```

`202 Accepted`:

```json
{
  "executionId": "8c2b1c2a-1f4d-4f08-8e3a-2a1d8a4d2f51",
  "workflowId":  "0e1d2c3b-4a5b-4c6d-8e7f-90a1b2c3d4e5",
  "status":      "PENDING"
}
```

Unknown path → `404` (not `401`), since the auth filter never runs for `/hooks/**`.

### Signed example (HMAC) — GitHub-compatible

Set `hmac_secret_env` on the trigger; the value is read from a server environment
variable at delivery time. The plaintext secret **never** touches the database.

```yaml
trigger:
  webhook:
    path: "gh-events"
    hmac_secret_env: "GH_WEBHOOK_SECRET"    # set on the server
```

Each delivery must carry `X-Hub-Signature-256: sha256=<hex>` where `<hex>` is the
**lowercase HMAC-SHA256 of the raw request body**, keyed by the secret. Comparison
is constant-time. An unset env var fails closed (`401`) — a workflow that asks for
signatures will not silently accept unsigned calls.

Pick the language you're calling from:

**bash (openssl)**

```bash
BODY='{"hello":"world"}'
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$GH_WEBHOOK_SECRET" -hex | awk '{print $2}')"
curl -sS -X POST {base}/hooks/gh-events \
  -H 'Content-Type: application/json' \
  -H "X-Hub-Signature-256: $SIG" \
  -d "$BODY"
```

**Python (`hmac` + `hashlib`)**

```python
import hmac, hashlib, os, urllib.request, json

secret = os.environ["GH_WEBHOOK_SECRET"].encode()
body   = json.dumps({"hello": "world"}).encode()
sig    = "sha256=" + hmac.new(secret, body, hashlib.sha256).hexdigest()

req = urllib.request.Request(
    "{base}/hooks/gh-events",
    data=body,
    headers={"Content-Type": "application/json", "X-Hub-Signature-256": sig},
    method="POST",
)
print(urllib.request.urlopen(req).read().decode())
```

**Node (`crypto`)**

```js
import crypto from "node:crypto";

const secret = process.env.GH_WEBHOOK_SECRET;
const body   = JSON.stringify({ hello: "world" });
const sig    = "sha256=" + crypto.createHmac("sha256", secret).update(body).digest("hex");

const r = await fetch("{base}/hooks/gh-events", {
  method:  "POST",
  headers: { "Content-Type": "application/json", "X-Hub-Signature-256": sig },
  body,
});
console.log(r.status, await r.json());
```

A valid signature returns the same `202 Accepted` envelope as the unsigned example.
A missing or wrong signature returns `401` with a problem+json body:

```json
{
  "type":   "about:blank",
  "title":  "Unauthorized",
  "status": 401,
  "detail": "missing or invalid X-Hub-Signature-256 signature"
}
```

If `hmac_secret_env` names a variable that the server hasn't exported, the same `401`
fires with `detail: "webhook signature secret is not configured on the server"`.

### Wiring a GitHub webhook

Repository → **Settings → Webhooks → Add webhook**:

- **Payload URL** — `{base}/hooks/<path>` (the `path:` from your workflow).
- **Content type** — `application/json`.
- **Secret** — the same value you put behind `hmac_secret_env`.
- Event type — your choice (push, pull_request, …).

GitHub sends `X-Hub-Signature-256` natively; Potok verifies it the same way it
verifies any other signed call. Use the `signed webhook (HMAC)` example in the
gallery (`examples/github-notify.yaml`) as a starting point.

---

## B. Control Potok via REST API

### Authentication

When `POTOK_API_KEY` is unset, the auth filter is **off** — useful for local
development. With it set, every `/api/**` route requires the
`X-API-Key` header. Accepted values:

- the root key from `POTOK_API_KEY` itself, or
- any active token created via `POST /api/tokens`.

Use the root key only to mint per-integration tokens; ship the tokens to your
integrations. Tokens are stored as SHA-256 hashes; the plaintext is shown
exactly once on creation. Revocation is immediate.

Comparison is constant-time on both code paths (root key and token hash lookup).
A missing or wrong header returns `401` with RFC 7807 problem+json:

```json
{
  "type":   "about:blank",
  "title":  "Unauthorized",
  "status": 401,
  "detail": "missing or invalid X-API-Key header"
}
```

Mint a token:

```bash
curl -sS -X POST {base}/api/tokens \
  -H "X-API-Key: $POTOK_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ci-deploy-bot"}'
```

`201 Created`:

```json
{
  "id":    "1c33b5f4-7ad9-4e92-8a91-2c2e6b8f54a3",
  "name":  "ci-deploy-bot",
  "token": "ptk_3a9c1e…",
  "note":  "store this token now — it is shown only once"
}
```

Revoke (immediate; the token is rejected from the next call onward):

```bash
curl -sS -X DELETE {base}/api/tokens/1c33b5f4-7ad9-4e92-8a91-2c2e6b8f54a3 \
  -H "X-API-Key: $POTOK_API_KEY"
```

`POST /api/admin/purge` additionally requires the root key — an api_token alone
returns `403`.

### Endpoint reference

`auth` column: `public` = no key needed, `token` = any active token or the root key,
`root` = only the bootstrap `POTOK_API_KEY`. With auth off, every route is open.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET`    | `/api/meta` | public | App name and `authRequired` flag |
| `POST`   | `/hooks/{path}` | public¹ | Fire a webhook-triggered workflow |
| `POST`   | `/api/workflows` | token | Create from raw YAML body (see Content-Type) |
| `GET`    | `/api/workflows` | token | List workflows (enabled and disabled) |
| `GET`    | `/api/workflows/{id}` | token | Workflow detail (definition + YAML + current version) |
| `PUT`    | `/api/workflows/{id}` | token | Update; appends a new version, re-enables |
| `DELETE` | `/api/workflows/{id}` | token | Soft-disable (history kept); `?permanent=true` hard-deletes it + all history (must be disabled first, else `409`) |
| `POST`   | `/api/workflows/{id}/enable` | token | Re-enable a disabled workflow |
| `POST`   | `/api/workflows/{id}/run` | token | Manual run (returns `202` with `executionId`) |
| `GET`    | `/api/workflows/{id}/versions?page=&size=` | token | Paged version history |
| `POST`   | `/api/workflows/{id}/versions/{n}/rollback` | token | Append a new version with version `n`'s content |
| `POST`   | `/api/preview` | token | Dry-run a YAML body, persisting nothing |
| `GET`    | `/api/executions?workflowId=&page=&size=` | token | Recent executions (paged) |
| `GET`    | `/api/executions/{id}` | token | Execution detail with step timeline |
| `POST`   | `/api/executions/{id}/steps/{stepName}/decide` | token | Approve/deny a waiting `approval` step |
| `GET`    | `/api/dlq?page=&size=` | token | Dead-letter queue |
| `POST`   | `/api/dlq/{id}/requeue` | token | Re-queue a dead letter; revives skipped downstream |
| `DELETE` | `/api/dlq/{id}` | token | Drop a dead letter |
| `POST`   | `/api/tokens` | token² | Mint a token; plaintext shown once |
| `GET`    | `/api/tokens` | token | List tokens (id, name, created/last-used/revoked) |
| `DELETE` | `/api/tokens/{id}` | token | Revoke a token |
| `GET`    | `/api/settings/smtp` | token | SMTP config (no password); `configured`, `password_set`, `source` |
| `PUT`    | `/api/settings/smtp` | token | Store SMTP config; `password` optional (omit to keep stored) |
| `DELETE` | `/api/settings/smtp` | token | Clear DB SMTP config (falls back to env) |
| `POST`   | `/api/settings/smtp/test` | token | Connect + auth only (sends nothing); `{ok, error}` |
| `POST`   | `/api/admin/purge` | root | Run retention purge now |

¹ Webhook deliveries are open by design. When `hmac_secret_env` is set on the
trigger, the request must additionally carry a valid `X-Hub-Signature-256`.

² Any active token may mint another token. If you want only the root key to mint
tokens, restrict it at your network or reverse-proxy layer.

#### Content types

- `POST/PUT /api/workflows` and `POST /api/preview` accept raw YAML.
  Use `Content-Type: text/plain`, `application/yaml`, `application/x-yaml`,
  or `text/yaml`. A form-encoded post yields `415 Unsupported Media Type`.
- Other write endpoints use `application/json` bodies.
- Webhook bodies are forwarded as-is; the signature, when required, covers the
  raw bytes — sign before any pretty-printing or transformation.

#### Examples

Create a workflow from YAML:

```bash
curl -sS -X POST {base}/api/workflows \
  -H "X-API-Key: $POTOK_TOKEN" \
  -H 'Content-Type: text/plain' \
  --data-binary @my-workflow.yaml
```

Run a workflow manually:

```bash
curl -sS -X POST {base}/api/workflows/$WF_ID/run \
  -H "X-API-Key: $POTOK_TOKEN"
```

List recent executions for a workflow:

```bash
curl -sS "{base}/api/executions?workflowId=$WF_ID&size=20" \
  -H "X-API-Key: $POTOK_TOKEN"
```

Approve a waiting step from your CI:

```bash
curl -sS -X POST "{base}/api/executions/$EXEC_ID/steps/ask/decide" \
  -H "X-API-Key: $POTOK_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"approved": true}'
```

### Typical integration walkthrough

A program that wants to "create a workflow and run it" needs four calls:

```bash
# 1. Mint a per-integration token (run once, store the result).
TOKEN=$(curl -sS -X POST {base}/api/tokens \
  -H "X-API-Key: $POTOK_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ci-deploy-bot"}' | jq -r .token)

# 2. Create a workflow from YAML.
WF_ID=$(curl -sS -X POST {base}/api/workflows \
  -H "X-API-Key: $TOKEN" \
  -H 'Content-Type: text/plain' \
  --data-binary @deploy.yaml | jq -r .id)

# 3. Run it now (returns 202 with executionId).
EXEC_ID=$(curl -sS -X POST {base}/api/workflows/$WF_ID/run \
  -H "X-API-Key: $TOKEN" | jq -r .executionId)

# 4. Poll for completion (terminal states: SUCCEEDED, FAILED). WAITING means a
#    durable wait or approval step is parked — keep polling, or drive it via
#    /api/executions/{id}/steps/{stepName}/decide.
while :; do
  STATUS=$(curl -sS {base}/api/executions/$EXEC_ID \
    -H "X-API-Key: $TOKEN" | jq -r .status)
  echo "status=$STATUS"
  case "$STATUS" in SUCCEEDED|FAILED) break ;; esac
  sleep 2
done
```

If the workflow is driven by its own trigger (cron / webhook / poll), step 3
disappears — the program just polls `/api/executions?workflowId=…` for new rows.

### Errors

All `/api/**` errors are RFC 7807 `application/problem+json`. Beyond `401`, the
common ones are:

```json
{
  "type":   "about:blank",
  "title":  "Invalid workflow definition",
  "status": 400,
  "detail": "step 'notify' references unknown step 'fetcher' (did you mean 'fetch'?)"
}
```

- `400 Invalid workflow definition` — YAML parse error, unknown action, unknown
  `needs`, dependency cycle, template references outside the step's dependency
  closure, malformed `if:` / `fire_when:`.
- `409 Workflow conflict` — `name` collides with another *enabled* workflow.
- `404` — workflow / execution / dead letter / approval not found.
- `415` — wrong `Content-Type` on a YAML endpoint (use `text/plain` or
  `application/yaml`, not `application/json`).

### Limits worth knowing

- `POST /api/preview` runs synchronously and is capped at **10 steps** and
  **10 seconds** of wall clock by default (`POTOK_PREVIEW_TIMEOUT=PT10S`).
  Steps past the deadline come back as `skipped`, not as an error. String
  outputs are truncated past ~4 KB to keep the response well under 256 KB.
- `GET /api/executions` accepts `size` up to **200**; defaults to 100.
- `GET /api/workflows/{id}/versions` accepts `size` up to **100**; defaults to 20.
- `GET /api/dlq` accepts `size` up to **200**; defaults to 20.
- Pagination uses zero-based `page=` together with `size=`.

There is no fixed body cap for webhook deliveries beyond the servlet container's
default; very large payloads should be split or summarised before posting.

---

## Cross-cutting

### Delivery channels — Telegram and Email

Potok delivers through two channels; both are ordinary actions, so a workflow
can use either or both.

- **`telegram`** — `text` plus one of `chat_id` / `to_recipient` / `to: approved` /
  `to: subscribers`. Needs `TELEGRAM_BOT_TOKEN`.
- **`email`** — SMTP, provider-agnostic (`SMTP_*` env). Params:

  | Param | Required | Notes |
  |---|---|---|
  | `to` | yes | string or list of addresses; a single string may be comma/semicolon-separated |
  | `cc`, `bcc` | no | string or list |
  | `subject` | yes | templated |
  | `body` | yes | templated; plain text unless `html: true` |
  | `html` | no | default `false` → send `text/html` when `true` |

  Addresses are validated, de-duplicated (case-insensitive) and capped at **50**
  across To+Cc+Bcc. One send fans out to all recipients; the step fails only when
  the whole send fails — a server that rejects some addresses still succeeds and
  lists them under `failed`. Output: `{sent_count, failed_count, recipients}`
  (plus `failed`). If `SMTP_HOST` is unset the step fails gracefully with a clear
  message, exactly like `telegram` without a token. See [deploy.md](deploy.md)
  for Gmail (app password) and Brevo/SendGrid setup.

  SMTP can also be set from the dashboard (**Settings → Email (SMTP)**, or
  `/api/settings/smtp`). The password is stored **AES-256-GCM encrypted at rest**
  and is **write-only** — `GET` returns `password_set`/`source` but never the
  secret, and `PUT` keeps the stored password when you omit it. Storing a
  password needs `POTOK_SECRET_KEY` (base64 32 bytes); without it the API returns
  `400 set POTOK_SECRET_KEY to store secrets` and env `SMTP_*` still works.
  Resolution precedence at send time: **complete DB config → `SMTP_*` env → not
  configured**. `POST /api/settings/smtp/test` connects + authenticates only
  (sends nothing).

```yaml
steps:
  - name: notify
    action: email
    with:
      to: "alerts@example.com"
      subject: "📈 EUR crossed 4.30"
      body: "The value is now {{ trigger.value }}"
```

**Preview** (`POST /api/preview`) **simulates both channels** — it renders the
message (Telegram text, or email recipients + subject + body) and reports what
*would* be sent, opening **zero** Telegram or SMTP connections.

### Finding `{base}`

- `docker compose up` → `http://localhost:8080`.
- Production (Render / Koyeb / your reverse proxy) → whatever public URL serves
  the same `/api/meta` route. `GET {base}/api/meta` always returns 200 with
  `{ "app": "potok", "authRequired": … }` regardless of auth.

### Security

- Keep tokens secret. Prefer **one token per integration** so a leak can be
  revoked without disrupting others.
- Use **HMAC** (`hmac_secret_env`) on any webhook that's reachable from the public
  internet — otherwise anyone who learns the path can fire your workflow.
- Outbound `http` steps, pollers, and previews go through an **SSRF guard**:
  loopback, RFC1918, link-local (including the `169.254.169.254` cloud metadata
  endpoint) and IPv6 unique-local addresses are refused with a clear error. Set
  `POTOK_ALLOW_PRIVATE_URLS=true` to disable the guard when you intentionally
  want to call internal services. The check is on the initial URL only; a
  redirect chain is not re-checked.
- Webhook signature comparison and `X-API-Key` comparison are both constant-time.
