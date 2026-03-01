#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.deploy}"
COMPOSE_FILE="$ROOT_DIR/docker-compose.deploy.yml"
MAX_ATTEMPTS="${DEPLOY_MAX_ATTEMPTS:-4}"
SKIP_BACKEND_PACKAGE="${SKIP_BACKEND_PACKAGE:-0}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed. Install Docker Desktop first."
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE"
  echo "Create it from template:"
  echo "  cp $ROOT_DIR/.env.deploy.example $ROOT_DIR/.env.deploy"
  exit 1
fi

if [[ "$SKIP_BACKEND_PACKAGE" != "1" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is required to package backend jar before Docker build."
    exit 1
  fi
  echo "Packaging backend jar (host build)..."
  (
    cd "$ROOT_DIR/backend"
    mvn -DskipTests package
  )
fi

echo "Deploying with compose file: $COMPOSE_FILE"
attempt=1
until docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build; do
  if [[ "$attempt" -ge "$MAX_ATTEMPTS" ]]; then
    echo "Docker deploy failed after ${MAX_ATTEMPTS} attempts."
    exit 1
  fi
  wait_seconds=$((attempt * 8))
  echo "Docker deploy attempt ${attempt}/${MAX_ATTEMPTS} failed. Retrying in ${wait_seconds}s..."
  sleep "$wait_seconds"
  attempt=$((attempt + 1))
done

echo
printf "Service status:\n"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps

echo
echo "Health check URLs:"
FRONTEND_PORT="$(grep -E '^FRONTEND_PORT=' "$ENV_FILE" | cut -d'=' -f2- || true)"
BACKEND_PORT="$(grep -E '^BACKEND_PORT=' "$ENV_FILE" | cut -d'=' -f2- || true)"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
BACKEND_PORT="${BACKEND_PORT:-8080}"
echo "  Frontend: http://localhost:${FRONTEND_PORT}"
echo "  Backend:  http://localhost:${BACKEND_PORT}/api/health"
