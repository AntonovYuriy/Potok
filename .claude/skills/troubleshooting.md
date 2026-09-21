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

## Gate red with no log at all

_2026-09-21_

**Symptom.** A run ends `failure` while the job produced no output: no log
file under `~/Library/Logs/gitea-runner/jobs/` on the mac, and
`/actions/jobs/<id>/logs` answers 404 on the server. Around the same time
`runner.err.log` repeats `fetching a task timed out after 5s`.

**What it is.** Not the change under test. The Gitea host was saturated —
typically a deploy building an image on a two-core server — and the runner
could not talk to it. The job was marked failed without ever running.

**How to tell it apart from a real failure.** A real failure always leaves a
log with the failing step in it. Zero log plus fetch timeouts is the
infrastructure, not the code.

**Fix.** Wait for the server to come back (`/api/v1/version` answering in
well under a second is a good signal), then re-run the job.

**Already done, 2026-09-21.** `runner.fetch_timeout` in
`~/.gitea-runner/config.yaml` was raised from the 5s default to 30s, which is
what produced these failures in the first place. If they recur anyway, the
server is not merely slow — look at what is eating it rather than raising the
timeout again.

---

## A pull request that never triggers a run

_2026-09-21_

**Symptom.** The PR is open, `.gitea/workflows/gate.yml` is present on the
branch, the workflow is listed as active, the queue is empty — and no run
exists for the head commit. The required status therefore never arrives and
the PR cannot merge.

**What it is.** The `opened` event was dropped, again while the server was
under load. Gitea does not retry workflow dispatch for a lost event.

**Fix.** Re-fire the event: close and reopen the PR, or push to the branch.
Both produce a fresh `pull_request` event and a run.

---

## Gate red at `make test` with Docker errors

**Symptom.** Testcontainers cannot start a container; the gate is red while
the same suite is green on the developer machine.

**What it is.** The runner is a launchd agent on the mac; it sees the Docker
socket only while colima is up.

**Fix.** `colima status`, and `colima start` if it is down. The runner needs
no restart afterwards.
