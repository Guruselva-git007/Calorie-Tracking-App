#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"
PROCESS_LOCK_DIR="$RUN_DIR/process-control.lock"
PROCESS_LOCK_WAIT_SECONDS="${PROCESS_LOCK_WAIT_SECONDS:-120}"

BACKEND_LOG="$RUN_DIR/backend.log"
FRONTEND_LOG="$RUN_DIR/frontend.log"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
AUTOPILOT_ENABLED="${AUTOPILOT_ENABLED:-1}"

DEFAULT_DB_URL="jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
DB_URL="${DB_URL:-$DEFAULT_DB_URL}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-guruselvaselvam1085sql&&&}"
DB_DRIVER_CLASS_NAME="${DB_DRIVER_CLASS_NAME:-com.mysql.cj.jdbc.Driver}"
DB_DIALECT="${DB_DIALECT:-org.hibernate.dialect.MySQLDialect}"
APP_RUNTIME_DB_MODE="${APP_RUNTIME_DB_MODE:-mysql}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
FRONTEND_HOST="${FRONTEND_HOST:-0.0.0.0}"
MYSQL_START_WAIT_SECONDS="${MYSQL_START_WAIT_SECONDS:-60}"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
FRONTEND_HEALTH_URL="${FRONTEND_HEALTH_URL:-http://127.0.0.1:${FRONTEND_PORT}}"
BACKEND_RUN_MODE="${BACKEND_RUN_MODE:-jar}" # jar|maven
BACKEND_BUILD_ON_START="${BACKEND_BUILD_ON_START:-auto}" # auto|always|never
BACKEND_JAR_PATH="${BACKEND_JAR_PATH:-}"
SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"
BACKEND_START_RETRIES="${BACKEND_START_RETRIES:-5}"
PREFERRED_JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
PREFERRED_JAVA_HOME="${PREFERRED_JAVA_HOME:-$PREFERRED_JAVA_HOME_DEFAULT}"
JAVA_BIN="${JAVA_BIN:-}"

if [[ -z "$JAVA_BIN" ]]; then
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
  elif [[ -x "${PREFERRED_JAVA_HOME}/bin/java" ]]; then
    JAVA_BIN="${PREFERRED_JAVA_HOME}/bin/java"
    export JAVA_HOME="${PREFERRED_JAVA_HOME}"
  elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  else
    echo "Java runtime not found. Install Java and retry."
    exit 1
  fi
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
  if command -v mysql >/dev/null 2>&1; then
    mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" -e "SELECT 1;" >/dev/null 2>&1
    return $?
  fi

  if command -v nc >/dev/null 2>&1; then
    nc -z "$DB_HOST" "$DB_PORT" >/dev/null 2>&1
    return $?
  fi

  if (echo >/dev/tcp/"$DB_HOST"/"$DB_PORT") >/dev/null 2>&1; then
    return 0
  fi

  return 1
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

  for ((i = 0; i < MYSQL_START_WAIT_SECONDS; i++)); do
    if mysql_ready; then
      return 0
    fi
    sleep 1
  done

  echo "MySQL is still not reachable at $DB_HOST:$DB_PORT after ${MYSQL_START_WAIT_SECONDS}s."
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

remove_stale_pid_file() {
  local pid_file="$1"
  if [[ -f "$pid_file" ]]; then
    local pid
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$pid_file"
    fi
  fi
}

kill_port_listener() {
  local port="$1"
  local attempts="${2:-5}"
  local pid=""
  for ((i = 0; i < attempts; i++)); do
    pid="$(port_pid "$port")"
    if [[ -z "$pid" ]]; then
      return 0
    fi
    kill "$pid" 2>/dev/null || true
    sleep 1
  done

  pid="$(port_pid "$port")"
  [[ -z "$pid" ]]
}

probe_backend_health_once() {
  local body
  body="$(curl -sS --max-time 4 "$BACKEND_HEALTH_URL" 2>/dev/null || true)"
  [[ "$body" == *"\"status\":\"UP\""* ]]
}

probe_frontend_health_once() {
  curl -sS --max-time 4 "$FRONTEND_HEALTH_URL" >/dev/null 2>&1
}

resolve_backend_jar() {
  if [[ -n "$BACKEND_JAR_PATH" && -f "$BACKEND_JAR_PATH" ]]; then
    printf "%s\n" "$BACKEND_JAR_PATH"
    return 0
  fi

  local jar
  jar="$(
    ls -1t "$ROOT_DIR/backend/target"/calorie-tracker-backend-*.jar 2>/dev/null \
      | grep -v '\.original$' \
      | head -n 1 \
      || true
  )"

  if [[ -n "$jar" ]]; then
    printf "%s\n" "$jar"
    return 0
  fi

  return 1
}

backend_sources_newer_than_jar() {
  local jar="$1"
  if [[ ! -f "$jar" ]]; then
    return 0
  fi

  local changed
  changed="$(find "$ROOT_DIR/backend/src" "$ROOT_DIR/backend/pom.xml" -type f -newer "$jar" -print -quit 2>/dev/null || true)"
  [[ -n "$changed" ]]
}

ensure_backend_artifact() {
  if [[ "$BACKEND_RUN_MODE" != "jar" ]]; then
    return 0
  fi

  local jar
  jar="$(resolve_backend_jar || true)"
  local should_build="0"

  case "$BACKEND_BUILD_ON_START" in
    always)
      should_build="1"
      ;;
    never)
      should_build="0"
      ;;
    auto|*)
      if [[ -z "$jar" ]] || backend_sources_newer_than_jar "$jar"; then
        should_build="1"
      fi
      ;;
  esac

  if [[ "$should_build" = "1" ]]; then
    echo "Building backend jar (skip tests)..."
    if ! (cd "$ROOT_DIR/backend" && mvn -DskipTests package >>"$BACKEND_LOG" 2>&1); then
      echo "Backend build failed. Check $BACKEND_LOG"
      return 1
    fi
    jar="$(resolve_backend_jar || true)"
  fi

  if [[ -z "$jar" ]]; then
    echo "Backend jar not found. Set BACKEND_RUN_MODE=maven or run: cd backend && mvn -DskipTests package"
    return 1
  fi

  BACKEND_ACTIVE_JAR="$jar"
  return 0
}

start_backend_process() {
  (
    cd "$ROOT_DIR/backend"
    if [[ "$BACKEND_RUN_MODE" = "jar" ]]; then
      if command -v perl >/dev/null 2>&1; then
        nohup perl -MPOSIX -e 'setsid() or die "setsid failed: $!"; exec @ARGV' -- \
          env \
          DB_URL="$DB_URL" \
          DB_USER="$DB_USER" \
          DB_PASSWORD="$DB_PASSWORD" \
          DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
          DB_DIALECT="$DB_DIALECT" \
          APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
          SERVER_ADDRESS="$SERVER_ADDRESS" \
          JAVA_HOME="${JAVA_HOME:-}" \
          "$JAVA_BIN" -jar "$BACKEND_ACTIVE_JAR" >"$BACKEND_LOG" 2>&1 < /dev/null &
      else
        nohup env \
          DB_URL="$DB_URL" \
          DB_USER="$DB_USER" \
          DB_PASSWORD="$DB_PASSWORD" \
          DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
          DB_DIALECT="$DB_DIALECT" \
          APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
          SERVER_ADDRESS="$SERVER_ADDRESS" \
          JAVA_HOME="${JAVA_HOME:-}" \
          "$JAVA_BIN" -jar "$BACKEND_ACTIVE_JAR" >"$BACKEND_LOG" 2>&1 < /dev/null &
      fi
    else
      if command -v perl >/dev/null 2>&1; then
        nohup perl -MPOSIX -e 'setsid() or die "setsid failed: $!"; exec @ARGV' -- \
          env \
          DB_URL="$DB_URL" \
          DB_USER="$DB_USER" \
          DB_PASSWORD="$DB_PASSWORD" \
          DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
          DB_DIALECT="$DB_DIALECT" \
          APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
          SERVER_ADDRESS="$SERVER_ADDRESS" \
          mvn spring-boot:run >"$BACKEND_LOG" 2>&1 < /dev/null &
      else
        nohup env \
          DB_URL="$DB_URL" \
          DB_USER="$DB_USER" \
          DB_PASSWORD="$DB_PASSWORD" \
          DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
          DB_DIALECT="$DB_DIALECT" \
          APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
          SERVER_ADDRESS="$SERVER_ADDRESS" \
          mvn spring-boot:run >"$BACKEND_LOG" 2>&1 < /dev/null &
      fi
    fi
    echo $! >"$BACKEND_PID_FILE"
  )
}

backend_log_has_pattern() {
  local pattern="$1"
  if [[ ! -f "$BACKEND_LOG" ]]; then
    return 1
  fi
  grep -qi "$pattern" "$BACKEND_LOG"
}

frontend_log_has_pattern() {
  local pattern="$1"
  if [[ ! -f "$FRONTEND_LOG" ]]; then
    return 1
  fi
  grep -qi "$pattern" "$FRONTEND_LOG"
}

start_frontend_process() {
  local host="$1"
  (
    cd "$ROOT_DIR/frontend"
    if [[ ! -d node_modules ]]; then
      npm install >/dev/null
    fi
    if command -v perl >/dev/null 2>&1; then
      nohup perl -MPOSIX -e 'setsid() or die "setsid failed: $!"; exec @ARGV' -- \
        env HOST="$host" PORT="$FRONTEND_PORT" SKIP_ENSURE_BACKEND=1 npm start >"$FRONTEND_LOG" 2>&1 < /dev/null &
    else
      nohup env HOST="$host" PORT="$FRONTEND_PORT" SKIP_ENSURE_BACKEND=1 npm start >"$FRONTEND_LOG" 2>&1 < /dev/null &
    fi
    echo $! >"$FRONTEND_PID_FILE"
  )
}

existing_backend_pid="$(port_pid 8080)"
existing_frontend_pid="$(port_pid "$FRONTEND_PORT")"

remove_stale_pid_file "$BACKEND_PID_FILE"
remove_stale_pid_file "$FRONTEND_PID_FILE"

if [[ -f "$BACKEND_PID_FILE" ]]; then
  backend_pid_from_file="$(cat "$BACKEND_PID_FILE" 2>/dev/null || true)"
  if [[ -n "$backend_pid_from_file" ]] && kill -0 "$backend_pid_from_file" 2>/dev/null; then
    if ! probe_backend_health_once; then
      echo "Backend PID file process ($backend_pid_from_file) is running but unhealthy. Restarting backend."
      kill "$backend_pid_from_file" 2>/dev/null || true
      sleep 1
      rm -f "$BACKEND_PID_FILE"
    fi
  fi
fi

if [[ -f "$FRONTEND_PID_FILE" ]]; then
  frontend_pid_from_file="$(cat "$FRONTEND_PID_FILE" 2>/dev/null || true)"
  if [[ -n "$frontend_pid_from_file" ]] && kill -0 "$frontend_pid_from_file" 2>/dev/null; then
    if ! probe_frontend_health_once; then
      echo "Frontend PID file process ($frontend_pid_from_file) is running but unhealthy. Restarting frontend."
      kill "$frontend_pid_from_file" 2>/dev/null || true
      sleep 1
      rm -f "$FRONTEND_PID_FILE"
    fi
  fi
fi

existing_backend_pid="$(port_pid 8080)"
existing_frontend_pid="$(port_pid "$FRONTEND_PORT")"

if ! ensure_mysql_ready; then
  configure_h2_fallback
fi

if [[ -n "$existing_backend_pid" ]] && ! probe_backend_health_once; then
  echo "Port 8080 is occupied by PID $existing_backend_pid but backend health check failed. Replacing process."
  if ! kill_port_listener 8080 6; then
    echo "Unable to free port 8080. Stop the conflicting process and retry."
    exit 1
  fi
  existing_backend_pid=""
fi

if [[ -n "$existing_frontend_pid" ]] && ! probe_frontend_health_once; then
  echo "Port $FRONTEND_PORT is occupied by PID $existing_frontend_pid but frontend health check failed. Replacing process."
  if ! kill_port_listener "$FRONTEND_PORT" 6; then
    echo "Unable to free port $FRONTEND_PORT. Stop the conflicting process and retry."
    exit 1
  fi
  existing_frontend_pid=""
fi

if [[ -f "$BACKEND_PID_FILE" ]] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
  echo "Backend already running (PID $(cat "$BACKEND_PID_FILE"))."
elif [[ -n "$existing_backend_pid" ]]; then
  echo "Backend already listening on 8080 (PID $existing_backend_pid)."
  echo "$existing_backend_pid" >"$BACKEND_PID_FILE"
else
  if ! ensure_backend_artifact; then
    exit 1
  fi
  start_backend_process
fi

if [[ -f "$FRONTEND_PID_FILE" ]] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
  echo "Frontend already running (PID $(cat "$FRONTEND_PID_FILE"))."
elif [[ -n "$existing_frontend_pid" ]]; then
  echo "Frontend already listening on $FRONTEND_PORT (PID $existing_frontend_pid)."
  echo "$existing_frontend_pid" >"$FRONTEND_PID_FILE"
else
  start_frontend_process "$FRONTEND_HOST"
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

wait_for_backend_up() {
  local attempts="${1:-30}"
  local delay_seconds="${2:-1}"
  local body=""
  for ((i = 0; i < attempts; i++)); do
    body="$(curl -sS --max-time 4 "$BACKEND_HEALTH_URL" 2>/dev/null || true)"
    if [[ "$body" == *"\"status\":\"UP\""* ]]; then
      echo "1"
      return 0
    fi
    sleep "$delay_seconds"
  done
  echo "0"
}

wait_for_http_success() {
  local url="$1"
  local attempts="${2:-12}"
  local delay_seconds="${3:-1}"
  for ((i = 0; i < attempts; i++)); do
    if curl -fsS --max-time 4 "$url" >/dev/null 2>&1; then
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
backend_http_up="$(wait_for_backend_up 30 1)"
frontend_http_up="$(wait_for_http_success "$FRONTEND_HEALTH_URL" 30 1)"
if [[ -n "$active_backend_pid" ]]; then
  echo "$active_backend_pid" >"$BACKEND_PID_FILE"
fi
if [[ -n "$active_frontend_pid" ]]; then
  echo "$active_frontend_pid" >"$FRONTEND_PID_FILE"
fi

if [[ "$backend_http_up" != "1" ]]; then
  if [[ "$APP_RUNTIME_DB_MODE" != "h2-fallback" ]]; then
    echo "Backend did not become healthy in MySQL mode. Retrying with H2 fallback."
    if [[ -n "$active_backend_pid" ]]; then
      kill "$active_backend_pid" 2>/dev/null || true
    fi
    kill_port_listener 8080 6 || true
    configure_h2_fallback
    if ! ensure_backend_artifact; then
      echo "Backend artifact preparation failed during fallback retry."
      rm -f "$BACKEND_PID_FILE"
      exit 1
    fi
    start_backend_process
    active_backend_pid="$(wait_for_port_pid 8080 25 1)"
    backend_http_up="$(wait_for_backend_up 40 1)"
    if [[ -n "$active_backend_pid" ]]; then
      echo "$active_backend_pid" >"$BACKEND_PID_FILE"
    fi
  fi
fi

if [[ "$backend_http_up" != "1" ]]; then
  for ((attempt = 2; attempt <= BACKEND_START_RETRIES; attempt++)); do
    echo "Backend is still not healthy. Retry $attempt/$BACKEND_START_RETRIES..."
    if [[ -n "$active_backend_pid" ]]; then
      kill "$active_backend_pid" 2>/dev/null || true
    fi
    kill_port_listener 8080 6 || true

    if backend_log_has_pattern "Operation not permitted" && [[ "$SERVER_ADDRESS" != "127.0.0.1" ]]; then
      SERVER_ADDRESS="127.0.0.1"
      echo "Detected bind permission issue. Retrying backend on localhost only (127.0.0.1)."
    fi

    start_backend_process
    active_backend_pid="$(wait_for_port_pid 8080 25 1)"
    backend_http_up="$(wait_for_backend_up 35 1)"
    if [[ -n "$active_backend_pid" ]]; then
      echo "$active_backend_pid" >"$BACKEND_PID_FILE"
    fi
    if [[ "$backend_http_up" = "1" ]]; then
      break
    fi
    sleep 1
  done
fi

if [[ "$backend_http_up" != "1" ]]; then
  echo "Backend failed to start on port 8080."
  if [[ -n "$active_backend_pid" ]]; then
    echo "Process found on 8080 (PID $active_backend_pid), but /api/health is not responding."
  fi
  echo "Last backend log lines:"
  tail -n 80 "$BACKEND_LOG" || true
  if [[ -n "$active_frontend_pid" ]]; then
    kill "$active_frontend_pid" 2>/dev/null || true
  fi
  rm -f "$BACKEND_PID_FILE" "$FRONTEND_PID_FILE"
  exit 1
fi

if [[ "$frontend_http_up" != "1" ]]; then
  if [[ "$FRONTEND_HOST" != "127.0.0.1" ]] && \
    (frontend_log_has_pattern "Could not find an open port at 0.0.0.0" || \
      frontend_log_has_pattern "listen EPERM" || \
      frontend_log_has_pattern "operation not permitted")
  then
    echo "Detected frontend bind issue on $FRONTEND_HOST. Retrying frontend on localhost only (127.0.0.1)."
    if [[ -n "$active_frontend_pid" ]]; then
      kill "$active_frontend_pid" 2>/dev/null || true
    fi
    kill_port_listener "$FRONTEND_PORT" 6 || true
    FRONTEND_HOST="127.0.0.1"
    start_frontend_process "$FRONTEND_HOST"
    active_frontend_pid="$(wait_for_port_pid "$FRONTEND_PORT" 20 1)"
    frontend_http_up="$(wait_for_http_success "$FRONTEND_HEALTH_URL" 35 1)"
    if [[ -n "$active_frontend_pid" ]]; then
      echo "$active_frontend_pid" >"$FRONTEND_PID_FILE"
    fi
  fi
fi

if [[ "$frontend_http_up" != "1" ]]; then
  echo "Frontend failed to start on port $FRONTEND_PORT."
  if [[ -n "$active_frontend_pid" ]]; then
    echo "Process found on $FRONTEND_PORT (PID $active_frontend_pid), but HTTP check failed."
  fi
  echo "Last frontend log lines:"
  tail -n 80 "$FRONTEND_LOG" || true
  if [[ -n "$active_backend_pid" ]]; then
    kill "$active_backend_pid" 2>/dev/null || true
  fi
  rm -f "$BACKEND_PID_FILE" "$FRONTEND_PID_FILE"
  exit 1
fi

LAN_IP="$(detect_ip)"

echo "Started services:"
echo "  Backend PID: $(cat "$BACKEND_PID_FILE" 2>/dev/null || echo N/A)"
echo "  Frontend PID: $(cat "$FRONTEND_PID_FILE" 2>/dev/null || echo N/A)"
echo "  Frontend host: $FRONTEND_HOST"
echo "  Java runtime: $JAVA_BIN"
if [[ "$BACKEND_RUN_MODE" = "jar" && -n "${BACKEND_ACTIVE_JAR:-}" ]]; then
  echo "  Backend mode: jar ($(basename "$BACKEND_ACTIVE_JAR"))"
else
  echo "  Backend mode: $BACKEND_RUN_MODE"
fi
echo
echo "Local URLs:"
echo "  Backend:  http://localhost:8080"
echo "  Frontend: http://localhost:$FRONTEND_PORT"
if [[ "$APP_RUNTIME_DB_MODE" = "h2-fallback" ]]; then
  echo
  echo "Database mode:"
  echo "  H2 fallback (MySQL unavailable at startup)"
fi
if [[ -n "$LAN_IP" && "$SERVER_ADDRESS" != "127.0.0.1" && "$FRONTEND_HOST" != "127.0.0.1" ]]; then
  echo
  echo "Network URLs:"
  echo "  Backend:  http://$LAN_IP:8080"
  echo "  Frontend: http://$LAN_IP:$FRONTEND_PORT"
fi
echo
echo "Logs:"
echo "  $BACKEND_LOG"
echo "  $FRONTEND_LOG"
if [[ "$AUTOPILOT_ENABLED" = "1" ]]; then
  "$ROOT_DIR/scripts/start-autopilot.sh" >/dev/null 2>&1 || true
  if [[ -f "$RUN_DIR/autopilot.pid" ]]; then
    echo "  $RUN_DIR/autopilot.log"
    echo "  Autopilot PID: $(cat "$RUN_DIR/autopilot.pid" 2>/dev/null || echo N/A)"
  fi
fi
