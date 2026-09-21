#!/usr/bin/env bash
# Один повтор, и только если падение выглядит как таймаут.
# Честное падение теста повторно не гоняется.
set -uo pipefail

LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT

run() { "$@" 2>&1 | tee "$LOG"; return "${PIPESTATUS[0]}"; }

if run "$@"; then exit 0; fi

if grep -qiE 'timed? ?out|timeout|ETIMEDOUT|exceeded .*(timeout|ms)' "$LOG"; then
  echo "--- падение похоже на таймаут, один повтор ---" >&2
  run "$@" && exit 0
  echo "--- повтор тоже упал: красный ---" >&2
  exit 1
fi

exit 1
