#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"

AUTOPILOT_PID_FILE="$RUN_DIR/autopilot.pid"
AUTOPILOT_LOG="$RUN_DIR/autopilot.log"

if [[ -f "$AUTOPILOT_PID_FILE" ]]; then
  existing_pid="$(cat "$AUTOPILOT_PID_FILE" 2>/dev/null || true)"
  if [[ -n "${existing_pid}" ]] && kill -0 "$existing_pid" 2>/dev/null; then
    echo "Autopilot already running (PID $existing_pid)."
    exit 0
  fi
  rm -f "$AUTOPILOT_PID_FILE"
fi

if command -v perl >/dev/null 2>&1; then
  nohup perl -MPOSIX -e 'setsid() or die "setsid failed: $!"; exec @ARGV' -- \
    env AUTOPILOT_LOG="$AUTOPILOT_LOG" AUTOPILOT_ENABLED=0 "$ROOT_DIR/scripts/autopilot-loop.sh" \
    >>"$AUTOPILOT_LOG" 2>&1 < /dev/null &
else
  nohup env AUTOPILOT_LOG="$AUTOPILOT_LOG" AUTOPILOT_ENABLED=0 "$ROOT_DIR/scripts/autopilot-loop.sh" \
    >>"$AUTOPILOT_LOG" 2>&1 < /dev/null &
fi

echo $! >"$AUTOPILOT_PID_FILE"
echo "Autopilot started (PID $(cat "$AUTOPILOT_PID_FILE"))."
echo "Autopilot log: $AUTOPILOT_LOG"
