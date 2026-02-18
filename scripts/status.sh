#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

report_pid() {
  local label="$1"
  local file="$2"
  local port="$3"

  local port_pid=""
  if command -v lsof >/dev/null 2>&1; then
    port_pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
  fi

  if [[ -f "$file" ]]; then
    local pid
    pid="$(cat "$file")"
    if [[ -n "$port_pid" && "$port_pid" = "$pid" ]]; then
      echo "$label: running (PID $pid)"
    elif [[ -n "$port_pid" ]]; then
      echo "$label: running (PID $port_pid, PID file has $pid)"
    else
      echo "$label: stopped (stale PID file $pid)"
    fi
  elif [[ -n "$port_pid" ]]; then
    echo "$label: running (PID $port_pid, no PID file)"
  else
    echo "$label: no PID file"
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

echo "Service status:"
report_pid "Backend" "$BACKEND_PID_FILE" 8080
report_pid "Frontend" "$FRONTEND_PID_FILE" 3000
echo
report_port "Backend" 8080
echo
report_port "Frontend" 3000
