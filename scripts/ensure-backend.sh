#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"
PROCESS_LOCK_DIR="$RUN_DIR/process-control.lock"
PROCESS_LOCK_WAIT_SECONDS="${PROCESS_LOCK_WAIT_SECONDS:-120}"

BACKEND_LOG="$RUN_DIR/backend.log"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}"

if [[ "${SKIP_ENSURE_BACKEND:-0}" = "1" ]]; then
  exit 0
fi

acquire_process_lock() {
  local started_at=$SECONDS
  while true; do
    if mkdir "$PROCESS_LOCK_DIR" 2>/dev/null; then
      printf "%s\n" "$$" >"$PROCESS_LOCK_DIR/pid"
      trap 'rm -rf "$PROCESS_LOCK_DIR"' EXIT
      return 0
    fi

    local lock_pid=""
    if [[ -f "$PROCESS_LOCK_DIR/pid" ]]; then
      lock_pid="$(cat "$PROCESS_LOCK_DIR/pid" 2>/dev/null || true)"
    fi

    if [[ -n "$lock_pid" ]] && ! kill -0 "$lock_pid" 2>/dev/null; then
      rm -rf "$PROCESS_LOCK_DIR"
      continue
    fi

    if (( SECONDS - started_at >= PROCESS_LOCK_WAIT_SECONDS )); then
      echo "Timed out waiting for process lock: $PROCESS_LOCK_DIR"
      return 1
    fi
    sleep 0.35
  done
}

acquire_process_lock

DEFAULT_DB_URL="jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
DB_URL="${DB_URL:-$DEFAULT_DB_URL}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-guruselvaselvam1085sql&&&}"
DB_DRIVER_CLASS_NAME="${DB_DRIVER_CLASS_NAME:-com.mysql.cj.jdbc.Driver}"
DB_DIALECT="${DB_DIALECT:-org.hibernate.dialect.MySQLDialect}"
APP_RUNTIME_DB_MODE="${APP_RUNTIME_DB_MODE:-mysql}"
BACKEND_RUN_MODE="${BACKEND_RUN_MODE:-jar}"
BACKEND_BUILD_ON_START="${BACKEND_BUILD_ON_START:-auto}"
SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"
MYSQL_START_WAIT_SECONDS="${MYSQL_START_WAIT_SECONDS:-12}"

probe_backend_health_once() {
  local body
  body="$(curl -sS --max-time 4 "$BACKEND_HEALTH_URL" 2>/dev/null || true)"
  [[ "$body" == *"\"status\":\"UP\""* ]]
}

wait_for_backend_up() {
  local attempts="${1:-40}"
  local delay_seconds="${2:-1}"
  for ((i = 0; i < attempts; i++)); do
    if probe_backend_health_once; then
      return 0
    fi
    sleep "$delay_seconds"
  done
  return 1
}

port_pid() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    (lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true) | head -n 1
    return
  fi
  echo ""
}

remove_stale_pid_file() {
  if [[ -f "$BACKEND_PID_FILE" ]]; then
    local pid
    pid="$(cat "$BACKEND_PID_FILE" 2>/dev/null || true)"
    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$BACKEND_PID_FILE"
    fi
  fi
}

kill_existing_backend() {
  if [[ -f "$BACKEND_PID_FILE" ]]; then
    local pid
    pid="$(cat "$BACKEND_PID_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$BACKEND_PID_FILE"
  fi

  local active_port_pid
  active_port_pid="$(port_pid 8080)"
  if [[ -n "$active_port_pid" ]]; then
    kill "$active_port_pid" 2>/dev/null || true
  fi
}

start_backend_detached() {
  (
    cd "$ROOT_DIR/backend"
    if command -v perl >/dev/null 2>&1; then
      nohup perl -MPOSIX -e 'setsid() or die "setsid failed: $!"; exec @ARGV' -- \
        env \
        DB_URL="$DB_URL" \
        DB_USER="$DB_USER" \
        DB_PASSWORD="$DB_PASSWORD" \
        DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
        DB_DIALECT="$DB_DIALECT" \
        APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
        BACKEND_RUN_MODE="$BACKEND_RUN_MODE" \
        BACKEND_BUILD_ON_START="$BACKEND_BUILD_ON_START" \
        SERVER_ADDRESS="$SERVER_ADDRESS" \
        MYSQL_START_WAIT_SECONDS="$MYSQL_START_WAIT_SECONDS" \
        "$ROOT_DIR/scripts/start-backend.sh" >"$BACKEND_LOG" 2>&1 < /dev/null &
    else
      nohup env \
        DB_URL="$DB_URL" \
        DB_USER="$DB_USER" \
        DB_PASSWORD="$DB_PASSWORD" \
        DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
        DB_DIALECT="$DB_DIALECT" \
        APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
        BACKEND_RUN_MODE="$BACKEND_RUN_MODE" \
        BACKEND_BUILD_ON_START="$BACKEND_BUILD_ON_START" \
        SERVER_ADDRESS="$SERVER_ADDRESS" \
        MYSQL_START_WAIT_SECONDS="$MYSQL_START_WAIT_SECONDS" \
        "$ROOT_DIR/scripts/start-backend.sh" >"$BACKEND_LOG" 2>&1 < /dev/null &
    fi
    echo $! >"$BACKEND_PID_FILE"
  )
}

if probe_backend_health_once; then
  echo "Backend already healthy."
  exit 0
fi

remove_stale_pid_file

if [[ -f "$BACKEND_PID_FILE" ]] || [[ -n "$(port_pid 8080)" ]]; then
  if wait_for_backend_up 30 1; then
    echo "Backend became healthy."
    exit 0
  fi
  echo "Existing backend is not healthy. Restarting backend..."
  kill_existing_backend
  sleep 1
fi

echo "Backend is down. Starting backend..."
start_backend_detached
if wait_for_backend_up 70 1; then
  echo "Backend started successfully."
  exit 0
fi

if grep -qi "Operation not permitted" "$BACKEND_LOG" 2>/dev/null && [[ "$SERVER_ADDRESS" != "127.0.0.1" ]]; then
  echo "Detected bind permission issue. Retrying backend on localhost only (127.0.0.1)."
  kill_existing_backend
  SERVER_ADDRESS="127.0.0.1"
  start_backend_detached
  if wait_for_backend_up 70 1; then
    echo "Backend started successfully on localhost."
    exit 0
  fi
fi

echo "Backend still unavailable after auto-start attempts."
echo "Last backend log lines:"
tail -n 60 "$BACKEND_LOG" || true
exit 1
