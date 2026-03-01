#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
AUTOPILOT_PID_FILE="$RUN_DIR/autopilot.pid"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
FRONTEND_HEALTH_URL="${FRONTEND_HEALTH_URL:-http://127.0.0.1:3000}"

report_pid() {
  local label="$1"
  local file="$2"
  local port="$3"

  local port_pid=""
  local pid_file_pid=""
  local pid_alive="0"
  if command -v lsof >/dev/null 2>&1; then
    port_pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
  fi

  if [[ -f "$file" ]]; then
    pid_file_pid="$(cat "$file")"
    if kill -0 "$pid_file_pid" 2>/dev/null; then
      pid_alive="1"
    fi

    if [[ -n "$port_pid" && "$port_pid" = "$pid_file_pid" ]]; then
      echo "$label: running (PID $pid_file_pid)"
    elif [[ -n "$port_pid" ]]; then
      if [[ "$pid_alive" = "1" ]]; then
        echo "$label: running (PID $port_pid, PID file has live PID $pid_file_pid)"
      else
        echo "$label: running (PID $port_pid, PID file has stale PID $pid_file_pid)"
      fi
    elif [[ "$pid_alive" = "1" ]]; then
      echo "$label: running (PID $pid_file_pid, port check unavailable)"
    else
      echo "$label: stopped (stale PID file $pid_file_pid)"
    fi
  elif [[ -n "$port_pid" ]]; then
    echo "$label: running (PID $port_pid, no PID file)"
  else
    echo "$label: stopped (no PID file)"
  fi
}

report_port() {
  local label="$1"
  local port="$2"
  if command -v lsof >/dev/null 2>&1; then
    local out
    out="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | tail -n +2 || true)"
    if [[ -n "$out" ]]; then
      echo "$label port $port: LISTEN"
      echo "$out"
    else
      echo "$label port $port: not listening"
    fi
  else
    echo "lsof unavailable; cannot inspect port $port"
  fi
}

report_background_pid() {
  local label="$1"
  local file="$2"
  if [[ -f "$file" ]]; then
    local pid
    pid="$(cat "$file" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "$label: running (PID $pid)"
    else
      echo "$label: stopped (stale PID file ${pid:-unknown})"
    fi
  else
    echo "$label: stopped (no PID file)"
  fi
}

report_http() {
  local label="$1"
  local url="$2"
  local body
  body="$(curl -sS --max-time 3 "$url" 2>/dev/null || true)"
  if [[ -z "$body" ]]; then
    echo "$label health: DOWN ($url)"
    return
  fi

  if [[ "$url" == *"/api/health" ]]; then
    local status
    local database
    local db_mode
    status="$(printf "%s" "$body" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
    database="$(printf "%s" "$body" | sed -n 's/.*"database":"\([^"]*\)".*/\1/p')"
    db_mode="$(printf "%s" "$body" | sed -n 's/.*"dbMode":"\([^"]*\)".*/\1/p')"
    status="${status:-UNKNOWN}"
    database="${database:-UNKNOWN}"
    db_mode="${db_mode:-unknown}"
    if [[ "$status" = "UP" ]]; then
      echo "$label health: UP (database=$database, dbMode=$db_mode, $url)"
    else
      echo "$label health: $status (database=$database, dbMode=$db_mode, $url)"
    fi
    return
  fi

  echo "$label health: UP ($url)"
}

echo "Service status:"
report_pid "Backend" "$BACKEND_PID_FILE" 8080
report_pid "Frontend" "$FRONTEND_PID_FILE" 3000
report_background_pid "Autopilot" "$AUTOPILOT_PID_FILE"
report_http "Backend" "$BACKEND_HEALTH_URL"
report_http "Frontend" "$FRONTEND_HEALTH_URL"
echo
report_port "Backend" 8080
echo
report_port "Frontend" 3000
