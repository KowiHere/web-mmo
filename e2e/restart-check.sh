#!/usr/bin/env bash
#
# Proves the one thing this milestone is about: a character outlives the
# process. Walks somewhere, kills the server, starts it again, and checks the
# character is still standing where it was left.
#
# Run from the repository root:  e2e/restart-check.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/web-mmo-0.0.1-SNAPSHOT.jar"
WHERE="$(mktemp)"
SERVER_PID=""

cleanup() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        # Shutting down flushes the last saves and releases the database lock.
        # Returning before that finishes makes a second run fail to start.
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -f "$WHERE"
}
trap cleanup EXIT

start_server() {
    # `exec` matters: without it $! is the subshell's pid, and the java process
    # survives every kill below - holding both the port and the database file,
    # so the "restarted" server is quietly the old one.
    (cd "$ROOT" && exec java -jar "$JAR" > /tmp/web-mmo-restart-check.log 2>&1) &
    SERVER_PID=$!
    for _ in $(seq 1 60); do
        curl -sf -o /dev/null --noproxy localhost http://localhost:8080/ && return 0
        sleep 1
    done
    echo "server did not come up; see /tmp/web-mmo-restart-check.log" >&2
    return 1
}

stop_server() {
    kill "$SERVER_PID"
    wait "$SERVER_PID" 2>/dev/null || true
    # H2 locks the database file, so the next start fails outright if the old
    # process has not finished letting go of it.
    for _ in $(seq 1 30); do
        curl -sf -o /dev/null --noproxy localhost http://localhost:8080/ || break
        sleep 1
    done
    SERVER_PID=""
}

[ -f "$JAR" ] || { echo "build it first: ./mvnw package -DskipTests" >&2; exit 1; }

echo "--- first run ---"
start_server
node "$ROOT/e2e/persistence.mjs" record "$WHERE"
sleep 2   # let the write-behind thread drain before the process goes away
stop_server

echo "--- restarting ---"
start_server
node "$ROOT/e2e/persistence.mjs" verify "$WHERE"
