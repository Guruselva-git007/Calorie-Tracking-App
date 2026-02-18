#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"

stop_pid_file() {
  local label="$1"
  local file="$2"

  if [[ -f "$file" ]]; then
    local pid
    pid="$(cat "$file")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      echo "Stopped $label (PID $pid)."
    else
      echo "$label PID file found but process not running: $pid"
    fi
    rm -f "$file"
  else
    echo "$label PID file not found."
  fi
}

stop_pid_file "backend" "$BACKEND_PID_FILE"
stop_pid_file "frontend" "$FRONTEND_PID_FILE"

# Safety fallback by port, useful if started outside scripts.
if command -v lsof >/dev/null 2>&1; then
  backend_port_pids="$(lsof -tiTCP:8080 -sTCP:LISTEN || true)"
  frontend_port_pids="$(lsof -tiTCP:3000 -sTCP:LISTEN || true)"
  [[ -n "$backend_port_pids" ]] && kill $backend_port_pids 2>/dev/null || true
  [[ -n "$frontend_port_pids" ]] && kill $frontend_port_pids 2>/dev/null || true
fi
