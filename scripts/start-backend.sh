#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"
BACKEND_LOG="$RUN_DIR/backend.log"

DEFAULT_DB_URL="jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DB_URL="${DB_URL:-$DEFAULT_DB_URL}"
export DB_USER="${DB_USER:-root}"
export DB_PASSWORD="${DB_PASSWORD:-guruselvaselvam1085sql&&&}"
export DB_DRIVER_CLASS_NAME="${DB_DRIVER_CLASS_NAME:-com.mysql.cj.jdbc.Driver}"
export DB_DIALECT="${DB_DIALECT:-org.hibernate.dialect.MySQLDialect}"
export APP_RUNTIME_DB_MODE="${APP_RUNTIME_DB_MODE:-mysql}"

MYSQL_START_WAIT_SECONDS="${MYSQL_START_WAIT_SECONDS:-60}"
BACKEND_RUN_MODE="${BACKEND_RUN_MODE:-jar}" # jar|maven
BACKEND_BUILD_ON_START="${BACKEND_BUILD_ON_START:-auto}" # auto|always|never
BACKEND_JAR_PATH="${BACKEND_JAR_PATH:-}"
SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"
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
  echo "Switching to H2 fallback for backend launch."
  return 1
}

configure_h2_fallback() {
  local demo_db_path="$RUN_DIR/demo-calorie-db"
  export DB_URL="jdbc:h2:file:${demo_db_path};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
  export DB_USER="sa"
  export DB_PASSWORD=""
  export DB_DRIVER_CLASS_NAME="org.h2.Driver"
  export DB_DIALECT="org.hibernate.dialect.H2Dialect"
  export APP_RUNTIME_DB_MODE="h2-fallback"
  echo "Fallback DB file: ${demo_db_path}"
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
      exit 1
    fi
    jar="$(resolve_backend_jar || true)"
  fi

  if [[ -z "$jar" ]]; then
    echo "Backend jar not found. Run: cd backend && mvn -DskipTests package"
    exit 1
  fi

  BACKEND_ACTIVE_JAR="$jar"
}

if ! ensure_mysql_ready; then
  configure_h2_fallback
fi

cd "$ROOT_DIR/backend"
if [[ "$BACKEND_RUN_MODE" = "jar" ]]; then
  ensure_backend_artifact
  echo "Starting backend from jar: $(basename "$BACKEND_ACTIVE_JAR")"
  echo "Using Java runtime: $JAVA_BIN"
  exec env \
    DB_URL="$DB_URL" \
    DB_USER="$DB_USER" \
    DB_PASSWORD="$DB_PASSWORD" \
    DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
    DB_DIALECT="$DB_DIALECT" \
    APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
    SERVER_ADDRESS="$SERVER_ADDRESS" \
    JAVA_HOME="${JAVA_HOME:-}" \
    "$JAVA_BIN" -jar "$BACKEND_ACTIVE_JAR"
fi

echo "Starting backend in maven mode."
exec env \
  DB_URL="$DB_URL" \
  DB_USER="$DB_USER" \
  DB_PASSWORD="$DB_PASSWORD" \
  DB_DRIVER_CLASS_NAME="$DB_DRIVER_CLASS_NAME" \
  DB_DIALECT="$DB_DIALECT" \
  APP_RUNTIME_DB_MODE="$APP_RUNTIME_DB_MODE" \
  SERVER_ADDRESS="$SERVER_ADDRESS" \
  mvn spring-boot:run
