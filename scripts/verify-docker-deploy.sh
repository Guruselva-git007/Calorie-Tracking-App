#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.deploy}"
COMPOSE_FILE="$ROOT_DIR/docker-compose.deploy.yml"
MAX_ATTEMPTS="${VERIFY_MAX_ATTEMPTS:-40}"
SLEEP_SECONDS="${VERIFY_SLEEP_SECONDS:-2}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Env file not found: $ENV_FILE"
  exit 1
fi

FRONTEND_PORT="$(grep -E '^FRONTEND_PORT=' "$ENV_FILE" | cut -d'=' -f2- || true)"
BACKEND_PORT="$(grep -E '^BACKEND_PORT=' "$ENV_FILE" | cut -d'=' -f2- || true)"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
BACKEND_PORT="${BACKEND_PORT:-8080}"

FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}"
BACKEND_HEALTH_URL="http://127.0.0.1:${BACKEND_PORT}/api/health"
INGREDIENTS_URL="http://127.0.0.1:${BACKEND_PORT}/api/ingredients?search=rice&limit=5"
DISH_SUGGEST_URL="http://127.0.0.1:${BACKEND_PORT}/api/dishes/suggest?search=chicken&limit=5"

if command -v docker >/dev/null 2>&1; then
  echo "Compose services:"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
  echo

  running_services="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --services --filter status=running | tr '\n' ' ')"
  for required in mysql backend frontend; do
    if [[ " $running_services " != *" $required "* ]]; then
      echo "Required Docker service is not running: $required"
      echo "Running services: ${running_services:-none}"
      exit 1
    fi
  done
  echo
fi

wait_for_http() {
  local label="$1"
  local url="$2"
  for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
    if curl -fsS --max-time 4 "$url" >/dev/null 2>&1; then
      echo "$label reachable: $url"
      return 0
    fi
    sleep "$SLEEP_SECONDS"
  done
  echo "$label unreachable: $url"
  return 1
}

wait_for_http "Frontend" "$FRONTEND_URL"
wait_for_http "Backend" "$BACKEND_HEALTH_URL"

backend_json="$(curl -sS --max-time 6 "$BACKEND_HEALTH_URL")"
if [[ "$backend_json" != *'"status":"UP"'* ]]; then
  echo "Backend health status is not UP"
  echo "$backend_json"
  exit 1
fi
if [[ "$backend_json" != *'"database":"UP"'* ]]; then
  echo "Database health is not UP"
  echo "$backend_json"
  exit 1
fi

ingredients_json="$(curl -sS --max-time 6 "$INGREDIENTS_URL")"
dishes_json="$(curl -sS --max-time 6 "$DISH_SUGGEST_URL")"

if [[ "$ingredients_json" != \[*\] && "$ingredients_json" != \{*\} ]]; then
  echo "Ingredients API response format invalid"
  exit 1
fi
if [[ "$dishes_json" != \[*\] && "$dishes_json" != \{*\} ]]; then
  echo "Dish suggest API response format invalid"
  exit 1
fi

echo
echo "Verification passed"
echo "Frontend: $FRONTEND_URL"
echo "Backend:  $BACKEND_HEALTH_URL"
echo "Backend health payload: $backend_json"
