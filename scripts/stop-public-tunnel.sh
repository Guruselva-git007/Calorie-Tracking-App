#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
PID_FILE="$RUN_DIR/public-tunnel.pid"
URL_FILE="$RUN_DIR/public-tunnel.url"

if [[ ! -f "$PID_FILE" ]]; then
  echo "No tunnel PID file found."
  exit 0
fi

pid="$(cat "$PID_FILE" 2>/dev/null || true)"
if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
  kill "$pid" || true
  sleep 1
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" || true
  fi
  echo "Tunnel stopped (PID $pid)."
else
  pkill -f "ssh -tt .*nokey@localhost.run" 2>/dev/null || true
  echo "Tunnel process not running."
fi

rm -f "$PID_FILE" "$URL_FILE"
