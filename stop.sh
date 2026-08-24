#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$PROJECT_ROOT/.run"
PID_FILE="$RUNTIME_DIR/aukcije-core.pid"
keep_database=false

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

process_matches() {
  local pid="$1"
  local jar="$2"
  local process_command
  [[ "$jar" == "$PROJECT_ROOT"/build/libs/aukcije-core-*.jar \
    && "$jar" != *-plain.jar ]] || return 1
  process_command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ "$process_command" == *"-jar $jar"* \
    && "$process_command" == *"--spring.profiles.active=dev"* ]]
}

case "${1:-}" in
  "") ;;
  --keep-db) keep_database=true ;;
  *) fail "Usage: ./stop.sh [--keep-db]" ;;
esac

cd "$PROJECT_ROOT"

if [[ -f "$PID_FILE" ]]; then
  app_pid="$(sed -n '1p' "$PID_FILE")"
  app_jar="$(sed -n '2p' "$PID_FILE")"
  app_url="$(sed -n '3p' "$PID_FILE")"

  if [[ "$app_pid" =~ ^[0-9]+$ ]] && kill -0 "$app_pid" 2>/dev/null; then
    process_matches "$app_pid" "$app_jar" \
      || fail "PID $app_pid is not the managed aukcije-core process; refusing to stop it."

    printf 'Stopping aukcije-core (PID %s)...\n' "$app_pid"
    kill "$app_pid"
    for _attempt in {1..30}; do
      kill -0 "$app_pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$app_pid" 2>/dev/null \
      && fail "Application did not stop within 30 seconds; PostgreSQL was left running."
    printf 'Application stopped.\n'
  elif command -v curl >/dev/null 2>&1 \
      && curl --silent --output /dev/null --max-time 2 "$app_url"; then
    fail "The recorded PID is stale but $app_url responds; refusing to stop PostgreSQL."
  else
    printf 'Removing stale application PID file.\n'
  fi
  rm -f "$PID_FILE"
else
  app_url="http://127.0.0.1:${SERVER_PORT:-8081}"
  if command -v curl >/dev/null 2>&1 \
      && curl --silent --output /dev/null --max-time 2 "$app_url"; then
    fail "$app_url responds without a managed PID file; stop that application manually first."
  fi
  printf 'No managed aukcije-core application is running.\n'
fi

if [[ "$keep_database" == true ]]; then
  printf 'PostgreSQL/PostGIS was left running (--keep-db).\n'
  exit 0
fi

command -v docker >/dev/null 2>&1 || fail "Required command is unavailable: docker"
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose is unavailable; install or start Docker Desktop."
printf 'Stopping PostgreSQL/PostGIS...\n'
docker compose down
printf 'Local stack stopped; the postgres-data volume was preserved.\n'
