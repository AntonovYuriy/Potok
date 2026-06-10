# Potok

Self-hosted workflow engine. Triggers (webhook, cron) execute YAML-defined workflows of actions (Telegram, HTTP, etc.) durably via a Postgres-backed job queue.

## Stack

- Java 21
- Spring Boot 3.5
- Gradle (Kotlin DSL)
- PostgreSQL — durable job queue, polled with `FOR UPDATE SKIP LOCKED`
- Flyway migrations (`src/main/resources/db/migration`)
- JUnit 5 + Testcontainers for integration tests
- Docker Compose for local Postgres (`docker-compose.yml`)

## Layout (package-by-feature)

`io.potok.*`
- `api` — REST control plane, webhook ingress
- `definition` — YAML workflow parser, template resolver
- `trigger` — webhook + cron trigger sources
- `execution` — job queue, workers, action SPI dispatcher
- `action` — action handlers (Telegram, …)

Tests mirror this layout under `src/test/java/io/potok/`.

## Configuration

Env vars only. No secrets in repo.
- `TELEGRAM_BOT_TOKEN` — required for Telegram action
- `TELEGRAM_CHAT_ID` — default target chat
- DB / Postgres vars via Spring Boot defaults

`.env.example` is the canonical list. Copy to `.env` locally; `.env` is git-ignored.

## Conventions

- Git flow: every task/milestone gets its own branch off `main`, named `feat/<milestone>-<short-name>` (e.g. `feat/m1-core-engine`). No direct commits to `main` (except the post-merge `docs/handoff.md` update — see below).
- Finish flow is fully automated, no human approval gate:
  1. `git push -u origin <branch>`
  2. `gh pr create --base main --fill` (or with explicit title/body)
  3. verify CI / build / tests green
  4. `gh pr merge --squash --delete-branch`
  5. `git checkout main && git pull`
  6. update `docs/handoff.md` on `main` and push directly
- If `gh` is missing/unauthenticated: run `gh auth login` once. Fallback when PR path is unavailable: local squash-merge (`git checkout main && git merge --squash <branch> && git commit && git push && git branch -d <branch>`) — but prefer the PR path for history.
- Commits: Conventional Commits (`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`).
- One concern per commit. Imperative subject ≤ 72 chars.
- Commit messages and PR descriptions: no co-author or "generated with" footers.
- Style: terse, no dead code, no speculative abstractions.
- Tests next to features, integration tests against real Postgres via Testcontainers.

## Skills installed (project-local `.claude/`)

- **ECC** (`affaan-m/ECC`, `--target claude-project --profile full`) — 197 skills under `.claude/skills/ecc/` plus rules, agents, hooks, mcp-configs.
- **agent-scripts** (`steipete/agent-scripts`) — canonical clone at `~/Projects/agent-scripts`; per-skill symlinks under `.claude/skills/<name>` (host-local paths — re-create on a fresh machine by re-running the bootstrap).

## Source of truth between sessions

`docs/handoff.md` — milestone state, what's next. Read it first; update it before ending a session.
