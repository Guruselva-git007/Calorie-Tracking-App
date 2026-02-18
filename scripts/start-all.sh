#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"

BACKEND_LOG="$RUN_DIR/backend.log"
FRONTEND_LOG="$RUN_DIR/frontend.log"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

DEFAULT_DB_URL="jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
DB_URL="${DB_URL:-$DEFAULT_DB_URL}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-guruselvaselvam1085sql&&&}"
DB_DRIVER_CLASS_NAME="${DB_DRIVER_CLASS_NAME:-com.mysql.cj.jdbc.Driver}"
DB_DIALECT="${DB_DIALECT:-org.hibernate.dialect.MySQLDialect}"
APP_RUNTIME_DB_MODE="${APP_RUNTIME_DB_MODE:-mysql}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"

extract_db_host() {
  printf "%s" "$1" | sed -n "s|^jdbc:mysql://\([^:/?]*\).*$|\1|p"
}

extract_db_port() {
  printf "%s" "$1" | sed -n "s|^jdbc:mysql://[^:/?]*:\([0-9][0-9]*\).*$|\1|p"
}

DB_HOST="${MYSQL_HOST:-$(extract_db_host "$DB_URL")}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${MYSQL_PORT:-$(extract_db_port "$DB_URL")}"
DB_PORT="${DB_PORT:-3306}"

if [[ "$DB_HOST" = "localhost" ]]; then
  DB_HOST="127.0.0.1"
fi

mysql_ready() {
  if ! command -v mysql >/dev/null 2>&1; then
    return 0
  fi
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" -e "SELECT 1;" >/dev/null 2>&1
}

ensure_mysql_ready() {
  if mysql_ready; then
    return 0
  fi

  echo "MySQL is not reachable at $DB_HOST:$DB_PORT. Attempting to start MySQL service..."
  if [[ -x "/usr/local/mysql/support-files/mysql.server" ]]; then
    /usr/local/mysql/support-files/mysql.server start >/dev/null 2>&1 || true
  fi

  if ! mysql_ready && command -v brew >/dev/null 2>&1; then
    brew services start mysql >/dev/null 2>&1 || true
    brew services start mysql@8.0 >/dev/null 2>&1 || true
  fi

  for ((i = 0; i < 60; i++)); do
    if mysql_ready; then
      return 0
    fi
    sleep 1
  done

  echo "MySQL is still not reachable at $DB_HOST:$DB_PORT."
  echo "Check credentials/env vars and confirm MySQL is running, then retry."
  return 1
}

configure_h2_fallback() {
  local demo_db_path="$RUN_DIR/demo-calorie-db"
  DB_URL="jdbc:h2:file:${demo_db_path};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
  DB_USER="sa"
  DB_PASSWORD=""
  DB_DRIVER_CLASS_NAME="org.h2.Driver"
  DB_DIALECT="org.hibernate.dialect.H2Dialect"
  APP_RUNTIME_DB_MODE="h2-fallback"
  echo "Switching to local fallback DB (H2) because MySQL is unavailable."
  echo "Fallback DB file: ${demo_db_path}"
}

detect_ip() {
  if command -v ifconfig >/dev/null 2>&1; then
    ifconfig en0 2>/dev/null | awk '/inet / {print $2; exit}'
    return
  fi
  echo ""
}

port_pid() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    (lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true) | head -n 1
    return
  fi
  echo ""
}

existing_backend_pid="$(port_pid 8080)"
existing_frontend_pid="$(port_pid "$FRONTEND_PORT")"

if ! ensure_mysql_ready; then
  configure_h2_fallback
fi

if [[ -f "$BACKEND_PID_FILE" ]] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
  echo "Backend already running (PID $(cat "$BACKEND_PID_FILE"))."
elif [[ -n "$existing_backend_pid" ]]; then
  echo "Backend already listening on 8080 (PID $existing_backend_pid)."
  echo "$existing_backend_pid" >"$BACKEND_PID_FILE"
else
  (
    cd "$ROOT_DIR/backend"
    nohup env \
      DB_URL="$DB_URL" \
      DB_USER="$DB_USER" \
      DB_PASSWORD="$DB_PASSWORD" \
      DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
      DB_DIALECT="$DB_DIALECT" \
      APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
      mvn spring-boot:run >"$BACKEND_LOG" 2>&1 &
    echo $! >"$BACKEND_PID_FILE"
  )
fi

if [[ -f "$FRONTEND_PID_FILE" ]] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
  echo "Frontend already running (PID $(cat "$FRONTEND_PID_FILE"))."
elif [[ -n "$existing_frontend_pid" ]]; then
  echo "Frontend already listening on $FRONTEND_PORT (PID $existing_frontend_pid)."
  echo "$existing_frontend_pid" >"$FRONTEND_PID_FILE"
else
  (
    cd "$ROOT_DIR/frontend"
    if [[ ! -d node_modules ]]; then
      npm install >/dev/null
    fi
    nohup env HOST=0.0.0.0 PORT="$FRONTEND_PORT" npm start >"$FRONTEND_LOG" 2>&1 &
    echo $! >"$FRONTEND_PID_FILE"
  )
fi

sleep 3

wait_for_port_pid() {
  local port="$1"
  local attempts="${2:-8}"
  local delay_seconds="${3:-1}"
  local pid=""
  for ((i = 0; i < attempts; i++)); do
    pid="$(port_pid "$port")"
    if [[ -n "$pid" ]]; then
      echo "$pid"
      return 0
    fi
    sleep "$delay_seconds"
  done
  echo ""
}

wait_for_http() {
  local url="$1"
  local attempts="${2:-12}"
  local delay_seconds="${3:-1}"
  for ((i = 0; i < attempts; i++)); do
    if curl -sS --max-time 4 "$url" >/dev/null 2>&1; then
      echo "1"
      return 0
    fi
    sleep "$delay_seconds"
  done
  echo "0"
}

# Some launchers (for example Maven/Node wrappers) can hand off to a child PID.
# Refresh PID files from listening ports so status/stop commands stay accurate.
active_backend_pid="$(wait_for_port_pid 8080 12 1)"
active_frontend_pid="$(wait_for_port_pid "$FRONTEND_PORT" 12 1)"
backend_http_up="$(wait_for_http 'http://127.0.0.1:8080/api/health' 18 1)"
frontend_http_up="$(wait_for_http "http://127.0.0.1:$FRONTEND_PORT" 18 1)"
if [[ -n "$active_backend_pid" ]]; then
  echo "$active_backend_pid" >"$BACKEND_PID_FILE"
fi
if [[ -n "$active_frontend_pid" ]]; then
  echo "$active_frontend_pid" >"$FRONTEND_PID_FILE"
fi

if [[ -z "$active_backend_pid" && "$backend_http_up" != "1" ]]; then
  echo "Backend failed to start on port 8080."
  echo "Last backend log lines:"
  tail -n 80 "$BACKEND_LOG" || true
  if [[ -n "$active_frontend_pid" ]]; then
    kill "$active_frontend_pid" 2>/dev/null || true
  fi
  exit 1
fi

if [[ -z "$active_frontend_pid" && "$frontend_http_up" != "1" ]]; then
  echo "Frontend failed to start on port $FRONTEND_PORT."
  echo "Last frontend log lines:"
  tail -n 80 "$FRONTEND_LOG" || true
  if [[ -n "$active_backend_pid" ]]; then
    kill "$active_backend_pid" 2>/dev/null || true
  fi
  exit 1
fi

LAN_IP="$(detect_ip)"

echo "Started services:"
echo "  Backend PID: $(cat "$BACKEND_PID_FILE" 2>/dev/null || echo N/A)"
echo "  Frontend PID: $(cat "$FRONTEND_PID_FILE" 2>/dev/null || echo N/A)"
echo
echo "Local URLs:"
echo "  Backend:  http://localhost:8080"
echo "  Frontend: http://localhost:$FRONTEND_PORT"
if [[ "$APP_RUNTIME_DB_MODE" = "h2-fallback" ]]; then
  echo
  echo "Database mode:"
  echo "  H2 fallback (MySQL unavailable at startup)"
fi
if [[ -n "$LAN_IP" ]]; then
  echo
  echo "Network URLs:"
  echo "  Backend:  http://$LAN_IP:8080"
  echo "  Frontend: http://$LAN_IP:$FRONTEND_PORT"
fi
echo
echo "Logs:"
echo "  $BACKEND_LOG"
echo "  $FRONTEND_LOG"
