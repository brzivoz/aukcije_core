#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$PROJECT_ROOT/.run"
PID_FILE="$RUNTIME_DIR/aukcije-core.pid"
LOG_FILE="$RUNTIME_DIR/aukcije-core.log"
APP_PORT="${SERVER_PORT:-8081}"
APP_URL="http://127.0.0.1:${APP_PORT}"

app_pid=""
database_started=false
startup_complete=false
database_port_explicit=false

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
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

port_is_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1 \
      && lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then
    return 0
  fi
  (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
}

cleanup_failed_start() {
  local status=$?
  trap - EXIT INT TERM

  if [[ "$startup_complete" != true ]]; then
    if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
      kill "$app_pid" 2>/dev/null || true
      wait "$app_pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    if [[ "$database_started" == true ]]; then
      docker compose down >/dev/null 2>&1 || true
    fi
  fi

  exit "$status"
}

trap cleanup_failed_start EXIT INT TERM

cd "$PROJECT_ROOT"
require_command docker
require_command java
require_command curl
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose is unavailable; install or start Docker Desktop."

if [[ -f "$PROJECT_ROOT/.env" ]]; then
  set -a
  # The repository's .env contract contains shell-compatible KEY=value lines.
  # shellcheck disable=SC1091
  source "$PROJECT_ROOT/.env"
  set +a
  APP_PORT="${SERVER_PORT:-8081}"
  APP_URL="http://127.0.0.1:${APP_PORT}"
fi

if [[ -n "${AUKCIJE_DB_PORT+x}" ]]; then
  database_port_explicit=true
fi
database_port="${AUKCIJE_DB_PORT:-5432}"
[[ "$database_port" =~ ^[0-9]+$ ]] \
  && ((database_port >= 1 && database_port <= 65535)) \
  || fail "AUKCIJE_DB_PORT must be an integer from 1 through 65535."

running_database_binding="$(docker compose port db 5432 2>/dev/null | tail -n 1 || true)"
if [[ -n "$running_database_binding" ]]; then
  running_database_port="${running_database_binding##*:}"
  if [[ "$database_port_explicit" == true \
      && "$database_port" != "$running_database_port" ]]; then
    fail "This project's database already uses port $running_database_port; stop it before changing AUKCIJE_DB_PORT to $database_port."
  fi
  database_port="$running_database_port"
elif port_is_in_use "$database_port"; then
  if [[ "$database_port_explicit" == true ]]; then
    fail "Database port $database_port is already in use. Choose a free AUKCIJE_DB_PORT in .env or the application environment."
  fi

  candidate_port=5433
  while ((candidate_port <= 5499)); do
    if ! port_is_in_use "$candidate_port"; then
      database_port="$candidate_port"
      printf 'Port 5432 is in use; using PostgreSQL port %s for this project.\n' \
        "$database_port"
      break
    fi
    ((candidate_port += 1))
  done
  ((candidate_port <= 5499)) \
    || fail "No free PostgreSQL host port was found from 5433 through 5499."
fi
export AUKCIJE_DB_PORT="$database_port"

password_file="${AUKCIJE_DB_PASSWORD_FILE:-.secrets/postgres-password}"
if [[ "$password_file" != /* ]]; then
  password_file="$PROJECT_ROOT/$password_file"
fi
[[ -r "$password_file" ]] || fail \
  "Missing database secret: $password_file. Follow the one-time setup in README.md."

database_password="$(tr -d '\r\n' < "$password_file")"
[[ -n "$database_password" ]] || fail "Database secret is empty: $password_file"
export AUKCIJE_DB_PASSWORD="$database_password"
unset database_password

mkdir -p "$RUNTIME_DIR"
if [[ -f "$PID_FILE" ]]; then
  existing_pid="$(sed -n '1p' "$PID_FILE")"
  existing_jar="$(sed -n '2p' "$PID_FILE")"
  if [[ "$existing_pid" =~ ^[0-9]+$ ]] && kill -0 "$existing_pid" 2>/dev/null; then
    if process_matches "$existing_pid" "$existing_jar"; then
      printf 'aukcije-core is already running (PID %s) at %s.\n' \
        "$existing_pid" "$(sed -n '3p' "$PID_FILE")"
      startup_complete=true
      trap - EXIT INT TERM
      exit 0
    fi
    fail "PID file points to an unrelated live process; inspect $PID_FILE before removing it."
  fi
  rm -f "$PID_FILE"
fi

if curl --silent --output /dev/null --max-time 2 "$APP_URL"; then
  fail "$APP_URL already responds, but no managed PID file exists. Stop that process first."
fi

printf 'Building the application jar...\n'
./gradlew --no-daemon bootJar

app_jar=""
for candidate in "$PROJECT_ROOT"/build/libs/aukcije-core-*.jar; do
  [[ -f "$candidate" ]] || continue
  [[ "$candidate" == *-plain.jar ]] && continue
  [[ -z "$app_jar" ]] || fail "More than one executable application jar was produced."
  app_jar="$candidate"
done
[[ -n "$app_jar" ]] || fail "Gradle did not produce an executable aukcije-core jar."

printf 'Starting PostgreSQL/PostGIS...\n'
database_started=true
docker compose up -d --wait db

printf '\n=== aukcije-core start %s ===\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$LOG_FILE"
nohup java -jar "$app_jar" --spring.profiles.active=dev \
  >> "$LOG_FILE" 2>&1 < /dev/null &
app_pid=$!
printf '%s\n%s\n%s\n' "$app_pid" "$app_jar" "$APP_URL" > "$PID_FILE"

printf 'Waiting for %s...\n' "$APP_URL"
for _attempt in {1..60}; do
  if ! kill -0 "$app_pid" 2>/dev/null; then
    wait "$app_pid" 2>/dev/null || true
    printf 'Application exited before becoming ready. Last log lines:\n' >&2
    tail -n 80 "$LOG_FILE" >&2 || true
    fail "Application startup failed."
  fi
  if curl --silent --fail --output /dev/null --max-time 2 "$APP_URL"; then
    startup_complete=true
    trap - EXIT INT TERM
    printf 'aukcije-core started (PID %s).\n' "$app_pid"
    printf 'Open: %s\n' "$APP_URL"
    printf 'Logs: tail -f %s\n' "$LOG_FILE"
    printf 'Stop: ./stop.sh\n'
    exit 0
  fi
  sleep 1
done

printf 'Application did not become ready within 60 seconds. Last log lines:\n' >&2
tail -n 80 "$LOG_FILE" >&2 || true
fail "Application readiness timed out."
