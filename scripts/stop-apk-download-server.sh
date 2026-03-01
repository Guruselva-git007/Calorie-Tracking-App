#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/.run/apk-server.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "APK server PID file not found."
  exit 0
fi

apk_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
if [[ -n "$apk_pid" ]] && kill -0 "$apk_pid" 2>/dev/null; then
  kill "$apk_pid" 2>/dev/null || true
  echo "Stopped APK download server (PID $apk_pid)."
else
  echo "APK server PID file found but process not running: $apk_pid"
fi

rm -f "$PID_FILE"
