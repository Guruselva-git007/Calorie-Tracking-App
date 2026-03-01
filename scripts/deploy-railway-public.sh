#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RAILWAY_CLI="npx -y @railway/cli"
PROJECT_NAME="${RAILWAY_PROJECT_NAME:-calorie-tracking-app}"
WORKSPACE_ID="${RAILWAY_WORKSPACE:-}"
PROJECT_ID="${RAILWAY_PROJECT_ID:-}"

BACKEND_ENV_FILE="${BACKEND_ENV_FILE:-$ROOT_DIR/.env.railway-backend}"
FRONTEND_ENV_FILE="${FRONTEND_ENV_FILE:-$ROOT_DIR/.env.railway-frontend}"

if [[ ! -f "$BACKEND_ENV_FILE" ]]; then
  echo "Missing backend env template: $BACKEND_ENV_FILE"
  exit 1
fi

if [[ ! -f "$FRONTEND_ENV_FILE" ]]; then
  echo "Missing frontend env template: $FRONTEND_ENV_FILE"
  exit 1
fi

echo "Checking Railway auth..."
if ! (cd "$ROOT_DIR" && $RAILWAY_CLI whoami >/dev/null 2>&1); then
  echo "Railway auth missing."
  echo "Run once: npx -y @railway/cli login"
  echo "Then rerun: $ROOT_DIR/scripts/deploy-railway-public.sh"
  exit 1
fi

ensure_project_linked() {
  if (cd "$ROOT_DIR" && $RAILWAY_CLI status >/dev/null 2>&1); then
    return 0
  fi

  if [[ -n "$PROJECT_ID" ]]; then
    echo "Linking existing Railway project: $PROJECT_ID"
    (cd "$ROOT_DIR" && $RAILWAY_CLI link "$PROJECT_ID" >/dev/null)
    return 0
  fi

  echo "No linked Railway project found. Creating: $PROJECT_NAME"
  if [[ -n "$WORKSPACE_ID" ]]; then
    (cd "$ROOT_DIR" && $RAILWAY_CLI init -n "$PROJECT_NAME" -w "$WORKSPACE_ID" >/dev/null)
  else
    (cd "$ROOT_DIR" && $RAILWAY_CLI init -n "$PROJECT_NAME" >/dev/null)
  fi
}

ensure_service() {
  local service_name="$1"
  local service_kind="${2:-app}"

  if (cd "$ROOT_DIR" && $RAILWAY_CLI service status --json | rg -q "\"name\"\\s*:\\s*\"${service_name}\""); then
    return 0
  fi

  echo "Creating Railway service: $service_name"
  if [[ "$service_kind" == "mysql" ]]; then
    (cd "$ROOT_DIR" && $RAILWAY_CLI add -d mysql --service "$service_name" >/dev/null)
  else
    (cd "$ROOT_DIR" && $RAILWAY_CLI add --service "$service_name" >/dev/null)
  fi
}

apply_env_file() {
  local service_name="$1"
  local env_file="$2"

  echo "Applying variables to service: $service_name"
  while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    local line="${raw_line%$'\r'}"
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" != *=* ]] && continue

    local key="${line%%=*}"
    local value="${line#*=}"
    key="$(echo "$key" | xargs)"
    [[ -z "$key" ]] && continue
    (cd "$ROOT_DIR" && $RAILWAY_CLI variable set --service "$service_name" --skip-deploys "${key}=${value}" >/dev/null)
  done < "$env_file"
}

ensure_project_linked
ensure_service "mysql" "mysql"
ensure_service "backend" "app"
ensure_service "frontend" "app"

apply_env_file "backend" "$BACKEND_ENV_FILE"
apply_env_file "frontend" "$FRONTEND_ENV_FILE"

echo "Deploying backend..."
(cd "$ROOT_DIR" && $RAILWAY_CLI up backend --service backend --path-as-root --detach --ci >/dev/null)

echo "Deploying frontend..."
(cd "$ROOT_DIR" && $RAILWAY_CLI up frontend --service frontend --path-as-root --detach --ci >/dev/null)

echo "Generating frontend public domain..."
frontend_domain="$(
  cd "$ROOT_DIR" && $RAILWAY_CLI domain --service frontend 2>/dev/null | tail -n 1 | tr -d '\r'
)"

backend_domain="$(
  cd "$ROOT_DIR" && $RAILWAY_CLI domain --service backend 2>/dev/null | tail -n 1 | tr -d '\r' || true
)"

echo
echo "Railway deployment finished."
echo "Frontend domain: $frontend_domain"
if [[ -n "$backend_domain" ]]; then
  echo "Backend domain:  $backend_domain"
fi
echo "Verify health (if backend domain is public):"
echo "  ${backend_domain%/}/api/health"
