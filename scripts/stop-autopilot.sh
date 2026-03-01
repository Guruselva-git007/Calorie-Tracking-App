#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
AUTOPILOT_PID_FILE="$RUN_DIR/autopilot.pid"

if [[ ! -f "$AUTOPILOT_PID_FILE" ]]; then
  echo "Autopilot PID file not found."
  exit 0
fi

autopilot_pid="$(cat "$AUTOPILOT_PID_FILE" 2>/dev/null || true)"
if [[ -n "$autopilot_pid" ]] && kill -0 "$autopilot_pid" 2>/dev/null; then
  kill "$autopilot_pid" 2>/dev/null || true
  echo "Stopped autopilot (PID $autopilot_pid)."
else
  echo "Autopilot PID file found but process not running: $autopilot_pid"
fi

rm -f "$AUTOPILOT_PID_FILE"
