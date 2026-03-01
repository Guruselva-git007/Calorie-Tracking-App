#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$ROOT_DIR/.run"
mkdir -p "$RUN_DIR"

AUTOPILOT_LOG="${AUTOPILOT_LOG:-$RUN_DIR/autopilot.log}"
CHECK_INTERVAL_SECONDS="${AUTOPILOT_INTERVAL_SECONDS:-45}"
BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://127.0.0.1:8080/api/health}"
FRONTEND_HEALTH_URL="${FRONTEND_HEALTH_URL:-http://127.0.0.1:3000}"

probe_backend() {
  local body
  body="$(curl -sS --max-time 4 "$BACKEND_HEALTH_URL" 2>/dev/null || true)"
  [[ "$body" == *"\"status\":\"UP\""* ]]
}

probe_frontend() {
  curl -fsS --max-time 4 "$FRONTEND_HEALTH_URL" >/dev/null 2>&1
}

log_line() {
  local message="$1"
  printf "[%s] %s\n" "$(date '+%Y-%m-%d %H:%M:%S')" "$message" >>"$AUTOPILOT_LOG"
}

log_line "Autopilot loop started. interval=${CHECK_INTERVAL_SECONDS}s"

while true; do
  if probe_backend && probe_frontend; then
    sleep "$CHECK_INTERVAL_SECONDS"
    continue
  fi

  log_line "Detected unhealthy app state. Running self-heal via scripts/start-all.sh"
  if AUTOPILOT_ENABLED=0 "$ROOT_DIR/scripts/start-all.sh" >>"$AUTOPILOT_LOG" 2>&1; then
    log_line "Self-heal completed."
  else
    log_line "Self-heal attempt failed. Will retry on next cycle."
  fi

  sleep "$CHECK_INTERVAL_SECONDS"
done
