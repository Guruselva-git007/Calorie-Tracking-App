#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
AUTOPILOT_PID_FILE="$RUN_DIR/autopilot.pid"
PROCESS_LOCK_DIR="$RUN_DIR/process-control.lock"
PROCESS_LOCK_WAIT_SECONDS="${PROCESS_LOCK_WAIT_SECONDS:-120}"

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

mkdir -p "$RUN_DIR"
acquire_process_lock

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
stop_pid_file "autopilot" "$AUTOPILOT_PID_FILE"

# Safety fallback by port, useful if started outside scripts.
if command -v lsof >/dev/null 2>&1; then
  backend_port_pids="$(lsof -tiTCP:8080 -sTCP:LISTEN || true)"
  frontend_port_pids="$(lsof -tiTCP:3000 -sTCP:LISTEN || true)"
  [[ -n "$backend_port_pids" ]] && kill $backend_port_pids 2>/dev/null || true
  [[ -n "$frontend_port_pids" ]] && kill $frontend_port_pids 2>/dev/null || true
fi

wait_for_port_release() {
  local label="$1"
  local port="$2"
  local max_attempts="${3:-12}"
  local pid=""

  if ! command -v lsof >/dev/null 2>&1; then
    return 0
  fi

  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
    if [[ -z "$pid" ]]; then
      return 0
    fi
    sleep 0.5
  done

  pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pid" ]]; then
    kill -9 $pid 2>/dev/null || true
    sleep 0.5
  fi

  pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
  if [[ -n "$pid" ]]; then
    echo "Warning: $label still holding port $port (PID $pid)."
    return 1
  fi
  return 0
}

wait_for_port_release "backend" 8080 || true
wait_for_port_release "frontend" 3000 || true
