#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"

PID_FILE="$RUN_DIR/apk-server.pid"
LOG_FILE="$RUN_DIR/apk-server.log"
PORT="${APK_SERVER_PORT:-9090}"

if [[ -f "$PID_FILE" ]]; then
  existing_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "$existing_pid" ]] && kill -0 "$existing_pid" 2>/dev/null; then
    echo "APK server already running (PID $existing_pid) on port $PORT."
    exit 0
  fi
  rm -f "$PID_FILE"
fi

cd "$ROOT_DIR"
nohup python3 -m http.server "$PORT" --bind 0.0.0.0 >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"

echo "APK download server started (PID $(cat "$PID_FILE")) on port $PORT."
echo "Log: $LOG_FILE"
