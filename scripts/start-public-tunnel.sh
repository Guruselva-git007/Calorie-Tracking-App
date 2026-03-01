#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
PID_FILE="$RUN_DIR/public-tunnel.pid"
URL_FILE="$RUN_DIR/public-tunnel.url"
LOG_FILE="$RUN_DIR/public-tunnel.log"
LOCAL_PORT="${PUBLIC_TUNNEL_PORT:-3000}"

mkdir -p "$RUN_DIR"

if [[ -f "$PID_FILE" ]]; then
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    if [[ -f "$URL_FILE" ]]; then
      echo "Tunnel already running: $(cat "$URL_FILE")"
    else
      echo "Tunnel already running (PID $pid). URL not found yet."
    fi
    exit 0
  fi
  rm -f "$PID_FILE"
fi

rm -f "$URL_FILE" "$LOG_FILE"

nohup script -q "$LOG_FILE" \
  ssh -tt \
  -o StrictHostKeyChecking=no \
  -o ServerAliveInterval=30 \
  -o ExitOnForwardFailure=yes \
  -R 80:localhost:"$LOCAL_PORT" \
  nokey@localhost.run >/dev/null 2>&1 &
wrapper_pid="$!"

tunnel_pid=""
for _ in $(seq 1 20); do
  candidate_pid="$(pgrep -f "ssh -tt .* -R 80:localhost:${LOCAL_PORT} .*nokey@localhost.run" | head -n 1 || true)"
  if [[ -n "$candidate_pid" ]]; then
    tunnel_pid="$candidate_pid"
    break
  fi
  sleep 0.5
done

if [[ -z "$tunnel_pid" ]]; then
  tunnel_pid="$wrapper_pid"
fi

echo "$tunnel_pid" > "$PID_FILE"

for _ in $(seq 1 90); do
  if ! kill -0 "$tunnel_pid" 2>/dev/null; then
    echo "Tunnel process exited. See log: $LOG_FILE"
    exit 1
  fi

  url="$(rg -o 'https://[A-Za-z0-9.-]+\.lhr\.life' "$LOG_FILE" | tail -n 1 || true)"
  if [[ -n "$url" ]]; then
    echo "$url" > "$URL_FILE"
    echo "Public URL: $url"
    echo "PID: $tunnel_pid"
    exit 0
  fi
  sleep 1
done

echo "Tunnel started but URL was not detected yet."
echo "Check log: $LOG_FILE"
