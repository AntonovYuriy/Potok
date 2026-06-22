# Deploying Potok (free tier): Koyeb + Neon

Goal: Potok running 24/7 for $0 — app on a [Koyeb](https://www.koyeb.com) free
instance from the GHCR image, database on [Neon](https://neon.tech) free Postgres.

## 1. Database — Neon

1. Sign up at neon.tech → **New project** (pick a region close to your Koyeb region).
2. In the project dashboard open **Connection details**, choose **Java** —
   you get a JDBC URL like:
   `jdbc:postgresql://ep-xxx-pooler.eu-central-1.aws.neon.tech/neondb?sslmode=require`
3. Note the username and password (or create a dedicated role `potok`).

Use the **pooled** connection string (`-pooler` host). Neon's free tier allows
a limited number of direct connections; the pooler multiplexes them.

## 2. Image — GHCR

CI publishes on every merge to main:

```
ghcr.io/antonovyuriy/potok:latest
ghcr.io/antonovyuriy/potok:<commit-sha>
```

If the package is private, create a GitHub PAT with `read:packages` for Koyeb,
or make the package public (GitHub → Packages → potok → settings → visibility).

## 3. App — Koyeb

1. Sign up at koyeb.com → **Create Service** → **Docker image**.
2. Image: `ghcr.io/antonovyuriy/potok:latest` (add registry credentials if private).
3. Instance type: **Free** (0.1 vCPU / 512 MB — fits, see notes below).
4. Port: `8000` is Koyeb's default — either set Koyeb's port to `8080`,
   or set env `PORT=8000` and keep Koyeb's default. Pick one, keep them equal.
5. **Health check**: HTTP, path `/actuator/health/readiness`, port as above.
6. Environment variables:

   | Name | Value |
   |---|---|
   | `DB_URL` | the Neon JDBC URL (with `?sslmode=require`) |
   | `DB_USER` | Neon role |
   | `DB_PASSWORD` | Neon password (use Koyeb **Secret** type) |
   | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `5` |
   | `POTOK_API_KEY` | long random string (Secret) — REQUIRED on a public URL, guards /api/** and the dashboard ops |
   | `TELEGRAM_BOT_TOKEN` | bot token (Secret) — optional |
   | `TELEGRAM_CHAT_ID` | default chat — optional |
   | `POTOK_DLQ_TELEGRAM` | `true` for DLQ alerts — optional |
   | `SMTP_HOST` | SMTP server for the email action — optional (unset = email steps fail gracefully) |
   | `SMTP_PORT` | submission port — optional, default `587` |
   | `SMTP_USERNAME` | SMTP login — optional |
   | `SMTP_PASSWORD` | SMTP password / app password (Secret) — optional |
   | `SMTP_FROM` | sender address — optional, defaults to `SMTP_USERNAME` |
   | `SMTP_STARTTLS` | optional, default `true` |
   | `SMTP_AUTH` | optional, default `true` |
   | `POTOK_LOG_JSON` | `true` (Koyeb log search works better with JSON) |

7. Deploy. First boot runs Flyway migrations automatically.

Smoke test:

```bash
curl https://<your-app>.koyeb.app/actuator/health
curl -H 'Content-Type: text/plain' --data-binary @examples/healthcheck.yaml \
     https://<your-app>.koyeb.app/api/workflows
```

## Tokens & webhook signatures

- After first boot, create per-client API tokens instead of sharing the root
  key: dashboard → Tokens (or `POST /api/tokens {"name": "ci"}` with the root
  key). The plaintext is shown once; revoke leaked tokens immediately —
  `POTOK_API_KEY` stays your bootstrap/admin credential (only it can call
  `POST /api/admin/purge`).
- For public webhooks add `hmac_secret_env: "MY_HOOK_SECRET"` to the trigger
  and set `MY_HOOK_SECRET` as a Koyeb Secret env var. GitHub webhooks: same
  secret in the webhook settings, GitHub signs with X-Hub-Signature-256
  automatically. Unsigned/invalid deliveries get 401.

## Email channel (SMTP)

Potok's `email` action is provider-agnostic — point the `SMTP_*` vars at any
submission server. If `SMTP_HOST` is unset the action fails the step with a
clear message (just like the telegram action without a token), so leaving it
blank is safe.

**Gmail (what the owner uses):**

1. Enable 2-Step Verification on the Google account (required for app passwords).
2. Create an **App password**: Google Account → Security → 2-Step Verification →
   App passwords. You get a 16-character password — use it as `SMTP_PASSWORD`,
   not your normal account password.
3. Set:

   | Var | Value |
   |---|---|
   | `SMTP_HOST` | `smtp.gmail.com` |
   | `SMTP_PORT` | `587` |
   | `SMTP_USERNAME` | your full Gmail address |
   | `SMTP_PASSWORD` | the 16-char app password (Secret) |
   | `SMTP_FROM` | same Gmail address (Gmail rewrites mismatched From to the account) |
   | `SMTP_STARTTLS` | `true` |

   Caveats: Gmail caps free sending at **~500 recipients/day**, and the `From`
   must match the authenticated account.

**Free alternatives** (better deliverability and higher limits for
notifications): **Brevo** (~300 emails/day free) and **SendGrid** (~100/day
free). Both give you an SMTP host, a username, and an API-key-as-password —
drop them into the same `SMTP_*` vars; the engine stays provider-agnostic.

## Honest notes (free-tier caveats)

- **RAM**: 512 MB works (`-XX:MaxRAMPercentage=75` caps the heap at ~384 MB;
  observed RSS is ~300 MB). Don't crank `POTOK_QUEUE_WORKERS` high — each
  in-flight http step holds buffers; 2–4 workers is the sweet spot here.
- **Koyeb free instances sleep** after inactivity ("scale to zero" on the
  free plan): incoming HTTP wakes the app (cold start ~15-30s, JVM + Flyway),
  but **cron triggers do not fire while the instance sleeps** — a sleeping
  instance runs no scheduler. If you rely on cron workflows, you need a plan
  without scale-to-zero or an external pinger (e.g. a free uptime monitor
  hitting /actuator/health every few minutes — that also keeps Neon warm).
- **Neon free tier**: the database itself also suspends after ~5 min idle;
  first query after suspend takes a few seconds — readiness may flap right
  after a cold start. The pooled (`-pooler`) host plus
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5` keeps you inside the
  connection limits; do NOT point multiple environments at one free project.
- **At-least-once reminder**: if you run more than one instance against one
  database, that's supported (SKIP LOCKED), but cron triggers will fire on
  EVERY instance — duplicate executions. Single instance until M3 adds
  leader election for cron.
- **Disk**: none needed; the app is stateless, state lives in Postgres.
