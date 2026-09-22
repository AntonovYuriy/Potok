#!/usr/bin/env bash
# Stop a process started by verify/smoke, and prove it stopped.
#
# Usage: stop-and-prove.sh <pid|-> <port>
#
# WHY THIS EXISTS. `$!` is the PID of whatever the shell backgrounded, which is not always the
# process that ends up holding the port:
#
#   ( cd "$box" && java -jar app.jar ) &   -> $! is the subshell; the JVM is its child
#   mvn spring-boot:run &                  -> $! is Maven; the app is a forked JVM
#   gradle bootRun & / npm run start & / uv run … &   -> same shape, different wrapper
#   foo | tee log &                        -> $! is the last element of the pipeline
#
# Killing the wrapper leaves the real process running. It keeps the port, keeps writing, and
# for anything that polls an upstream it keeps polling: a leaked Telegram bot steals getUpdates
# from production and production answers 409 Conflict (TelegramBelotCounter, 2026-09-21 — three
# leaked JVMs after three smoke runs).
#
# The cure has two halves and both are needed:
#   1. `exec` inside the subshell, so `$!` is the process itself:
#        ( cd "$box" && exec env FOO=bar java -jar app.jar > log 2>&1 ) & pid=$!
#      `env` and `exec` both replace the shell, so `env … prog &` is already correct.
#   2. this script, because a kill that was sent is not a kill that landed.
#
# Stopping counts as successful only when the process is gone AND the port no longer answers.
# Anything else exits non-zero, which makes the calling target red.
#
# Portable to the macOS runners: bash 3.2, no `setsid`, no `timeout`, no GNU-only flags. The
# port check is a plain TCP connect via bash's /dev/tcp — no lsof, no ss, and it does not
# mistake a socket in TIME_WAIT for a live listener the way `lsof` would.
set -uo pipefail

TERM_WAIT_SECONDS=${TERM_WAIT_SECONDS:-10}
PORT_WAIT_SECONDS=${PORT_WAIT_SECONDS:-10}

pid=${1:-}
port=${2:-}

if [ -z "$pid" ] || [ -z "$port" ]; then
    echo "usage: stop-and-prove.sh <pid|-> <port>" >&2
    exit 2
fi

alive() { [ "$1" != "-" ] && kill -0 "$1" 2>/dev/null; }

# "Free" means nothing accepts a connection there any more.
port_answers() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3<&- 3>&-
}

wait_gone() {
    local deadline=$(( $(date +%s) + $2 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        alive "$1" || return 0
        sleep 0.2
    done
    ! alive "$1"
}

wait_port_free() {
    local deadline=$(( $(date +%s) + $2 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        port_answers "$1" || return 0
        sleep 0.2
    done
    ! port_answers "$1"
}

if [ "$pid" != "-" ]; then
    # Children are collected BEFORE the parent dies: once it is gone they are reparented to
    # init and `pgrep -P` can no longer find them. This is a safety net for a caller that
    # forgot `exec`, not a licence to forget it.
    children=$(pgrep -P "$pid" 2>/dev/null | tr '\n' ' ')
    kill "$pid" 2>/dev/null
    for child in $children; do kill "$child" 2>/dev/null; done
    if [ -n "$children" ]; then
        # Not fatal — the stop still succeeds — but say it out loud: a server that is a child
        # of the captured PID means the caller backgrounded a wrapper instead of `exec`ing the
        # server, and the net below only reaches ONE generation. A grandchild, or a child of a
        # wrapper that has already exited, is out of reach and will fail the port check.
        echo "stop-and-prove: pid $pid had children ($children) — add \`exec\` so \$! is the" >&2
        echo "server itself; the child reaper here is a net, not a substitute" >&2
    fi

    if ! wait_gone "$pid" "$TERM_WAIT_SECONDS"; then
        echo "stop-and-prove: pid $pid ignored SIGTERM, sending SIGKILL" >&2
        kill -9 "$pid" 2>/dev/null
        for child in $children; do kill -9 "$child" 2>/dev/null; done
        wait_gone "$pid" 5
    fi

    if alive "$pid"; then
        echo "stop-and-prove: pid $pid is STILL RUNNING after SIGKILL" >&2
        exit 1
    fi
fi

if ! wait_port_free "$port" "$PORT_WAIT_SECONDS"; then
    echo "stop-and-prove: port $port still answers — something the kill did not reach is" >&2
    echo "holding it. Look for a child of the process that was killed, or a wrapper whose" >&2
    echo "PID was captured instead of the server's own." >&2
    exit 1
fi

echo "stopped: pid ${pid} gone, port ${port} free"
