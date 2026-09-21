# Deploying Potok

## The deployment that runs

Potok runs on the home server as a compose stack, deployed by a Gitea webhook
on merge to `main`. The mechanism — the webhook, `deploy.sh`, the shared
`deploy-template.sh`, its health check, rollback and log — belongs to the
infrastructure repository (`server-infra`), and lives there rather than being
restated here: a second copy stops matching the first the day either changes.

What this repository has to hold up its end:

- `docker-compose.yml` with a healthcheck on the app service;
- `/actuator/health` answering `{"status":"UP"}` once the app is ready;
- `.env` next to the compose file, mode 600, never in git.

Nothing else here triggers a deploy. Pushing to any branch other than `main`
is safe.

## The cloud instance

A second, independent instance runs on [Koyeb](https://www.koyeb.com) from the
GHCR image with a [Neon](https://neon.tech) Postgres behind it, kept awake by an
external `/actuator/health` ping. It is not a fallback for the home server and
is not deployed by the webhook — it is its own thing, wired up by hand.

Its setup guide used to live in this file. It is still in git
(`git log --follow -- docs/deploy.md`), and the inventory of the external
accounts it depends on is in `server-infra`.

## Configuration

Every knob is an environment variable; `.env.example` is the canonical list,
and the README explains what each one does.
