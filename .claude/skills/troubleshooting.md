# Troubleshooting — Potok

## Contract

On **any** failure, match the symptom here first.

- Matched — apply the recorded fix, no investigation needed.
- Not matched — investigate, fix, then **add an entry**. A file that does not
  grow is useless within a year.

One entry per symptom: what is seen, what it actually is, what to do. Keep the
original date of the incident. Entries come from real failures; nothing here
is written in advance "just in case".

---

## `:test UP-TO-DATE` — a green suite that never ran

_2026-08-04_

**Symptom.** `./gradlew build` or `./gradlew test` finishes in a second and
reports success; `build/test-results/test/*.xml` still holds the previous run.

**What it is.** Gradle's up-to-date check. Nothing was executed, so the green
means nothing. The runner reuses its work directory, which makes this reachable
on the gate and not just locally.

**Fix.** Run the tests through `make test` — it calls `cleanTest test`. When
invoking Gradle by hand, use `./gradlew cleanTest test` and check the XML
timestamps.

---

## Drift tests red after editing `templates/`

_2026-07-07_

**Symptom.** `TemplatesIntegrationTest` or `HelpIntegrationDocsTest` fails
after a change under `templates/`.

**What it is.** `examples/` is generated from `templates/` and committed; the
tests compare the two.

**Fix.** `./gradlew renderExamples`, commit the regenerated `examples/`.

---

## Database published to the world after a compose edit on the server

_source: `server-infra/README-SERVER.md`, "Potok" section_

**Symptom.** Postgres reachable from outside the host after editing
`docker-compose.override.yml` on the server.

**What it is.** Compose merges list-valued keys. Without the `!override` tag
the server's `ports` list is appended to the base one instead of replacing it,
so the base publication survives and bypasses ufw.

**Fix.** `ports` in `docker-compose.override.yml` always carries `!override`.

---

## Gate red at `make test` with Docker errors

**Symptom.** Testcontainers cannot start a container; the gate is red while
the same suite is green on the developer machine.

**What it is.** The runner is a launchd agent on the mac; it sees the Docker
socket only while colima is up.

**Fix.** `colima status`, and `colima start` if it is down. The runner needs
no restart afterwards.
