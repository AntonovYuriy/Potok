# Contributing

Issues and pull requests are welcome.

## Development

```bash
docker compose up -d postgres   # local database on :5432
./gradlew test                  # unit + integration (needs Docker for Testcontainers)
./gradlew bootRun               # app on :8080, dashboard at /
```

## Ground rules

- Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`).
- One concern per commit; tests next to the feature they cover.
- Integration tests run against real Postgres via Testcontainers — no in-memory stand-ins.
- The dashboard stays vanilla ES modules: no build step, no runtime CDN dependencies.
- New actions implement the `ActionHandler` SPI (one Spring bean); see
  `WarsawWasteActionHandler` for a complete real-world example.

## Before opening a PR

1. `./gradlew test` green.
2. README/docs updated for user-visible changes.
3. Changes land as squash-merges into `main` on the canonical repo
   (self-hosted Gitea). GitHub is an archive mirror — its Actions are off
   and pull requests opened there are not merged; the local suite in step 1
   is the gate.
