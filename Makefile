.PHONY: ci-setup lint typecheck test test-integration smoke verify assert-no-skips

SHELL    := /usr/bin/env bash
PROJECT  ?= $(notdir $(CURDIR))
PORT     ?= 8080
BASE_URL ?= http://localhost:$(PORT)

ci-setup:
	./gradlew --version

# Check mode only. spotlessApply is the mutating half and is never what the
# gate runs: a check that rewrites the files it inspects is not a check.
lint:
	./gradlew spotlessCheck

typecheck:
	./gradlew compileJava compileTestJava

# The suite is not split by tag, so this is all of it — Testcontainers cases
# included, which means a running Docker is required. Skips are counted after
# the run and turn the target red: a suite that quietly skips on a missing
# environment is a false green.
#
# cleanTest is not a nicety. The runner reuses its work directory, so Gradle
# can report `:test UP-TO-DATE` and hand the gate a green it never earned.
test:
	./scripts/gate-retry.sh ./gradlew cleanTest test
	@$(MAKE) --no-print-directory assert-no-skips

# Integration tests are not separable today: src/test carries no @Tag, and the
# Testcontainers cases sit in the same task as the unit ones. Splitting them
# edits test sources, which this task may not do.
test-integration:
	@echo "n/a"

smoke:
	curl -fsS $(BASE_URL)/actuator/health | grep -q '"status":"UP"'
	@echo "smoke ok"

# Brings the real compose stack up (app + postgres), waits for the app to go
# healthy, smokes it, tears it down.
#
# docker-compose.yml is never edited: the host ports are remapped through an
# override fed on stdin, because on a developer machine 8080 and 5432 usually
# belong to some other project already. Change VERIFY_PORT / POSTGRES_PORT if
# these collide too.
#
# ⚠️ ITS OWN COMPOSE PROJECT (-p). Without it the project name comes from the
# directory, so `down` reaches whatever else happens to share that name — and
# on a developer machine the directory name is not a promise. With -p, `down`
# can only touch containers, networks and volumes this target created, which is
# also what makes `-v` safe: the single named volume (potok-pgdata) belongs to
# the verify project and to nothing else.
#
# ⚠️ STOPPING IS PROVED, NOT ASSUMED (STANDARD.md §4). Teardown used to sit on
# the last line of the recipe, so a failing smoke aborted make and left the
# stack up holding both ports — reproduced, not theorised. Now up, smoke and
# down live in one block with an rc, `down` runs whatever happened, and
# scripts/stop-and-prove.sh checks that both published ports actually went
# quiet: `docker compose down` exiting 0 is not proof that nothing answers.
VERIFY_PORT   ?= 18080
POSTGRES_PORT ?= 55432
VERIFY_PROJECT ?= potok-verify
COMPOSE_OVERRIDE = printf 'services:\n  app:\n    ports: !override\n      - "$(VERIFY_PORT):$(PORT)"\n'
COMPOSE = POSTGRES_PORT=$(POSTGRES_PORT) docker compose -p $(VERIFY_PROJECT) -f docker-compose.yml -f -

verify:
	$(COMPOSE_OVERRIDE) | $(COMPOSE) up -d --build
	@rc=0; \
	for i in $$(seq 1 48); do \
	  s=$$($(COMPOSE_OVERRIDE) | $(COMPOSE) ps --format '{{.Health}}' app 2>/dev/null | head -1); \
	  [ "$$s" = healthy ] && break; \
	  if [ "$$s" = unhealthy ]; then \
	    $(COMPOSE_OVERRIDE) | $(COMPOSE) logs --tail 50 app; rc=1; break; \
	  fi; \
	  sleep 5; \
	done; \
	if [ $$rc = 0 ]; then \
	  $(MAKE) --no-print-directory smoke BASE_URL=http://localhost:$(VERIFY_PORT) || rc=1; \
	fi; \
	$(COMPOSE_OVERRIDE) | $(COMPOSE) down -v || rc=1; \
	./scripts/stop-and-prove.sh - $(VERIFY_PORT) || rc=1; \
	./scripts/stop-and-prove.sh - $(POSTGRES_PORT) || rc=1; \
	test $$rc = 0 && echo "verify ok"; \
	exit $$rc

# A green suite that skipped half of itself is not green. JUnit writes the
# count per class; anything above zero is a red target.
assert-no-skips:
	@skipped=$$(awk -F'skipped="' '/<testsuite /{split($$2,a,"\""); s+=a[1]} END{print s+0}' \
	  build/test-results/test/*.xml); \
	echo "skipped tests: $$skipped"; \
	if [ "$$skipped" != "0" ]; then \
	  echo "skipped tests are red per the standard"; exit 1; \
	fi
