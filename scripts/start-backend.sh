#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DEFAULT_DB_URL="jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export DB_URL="${DB_URL:-$DEFAULT_DB_URL}"
export DB_USER="${DB_USER:-root}"
export DB_PASSWORD="${DB_PASSWORD:-guruselvaselvam1085sql&&&}"
export DB_DRIVER_CLASS_NAME="${DB_DRIVER_CLASS_NAME:-com.mysql.cj.jdbc.Driver}"
export DB_DIALECT="${DB_DIALECT:-org.hibernate.dialect.MySQLDialect}"
export APP_RUNTIME_DB_MODE="${APP_RUNTIME_DB_MODE:-mysql}"

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
  local run_dir="$ROOT_DIR/.run"
  mkdir -p "$run_dir"
  local demo_db_path="$run_dir/demo-calorie-db"
  export DB_URL="jdbc:h2:file:${demo_db_path};MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
  export DB_USER="sa"
  export DB_PASSWORD=""
  export DB_DRIVER_CLASS_NAME="org.h2.Driver"
  export DB_DIALECT="org.hibernate.dialect.H2Dialect"
  export APP_RUNTIME_DB_MODE="h2-fallback"
  echo "Switching to local fallback DB (H2) because MySQL is unavailable."
  echo "Fallback DB file: ${demo_db_path}"
}

if ! ensure_mysql_ready; then
  configure_h2_fallback
fi

cd "$ROOT_DIR/backend"
exec mvn spring-boot:run
