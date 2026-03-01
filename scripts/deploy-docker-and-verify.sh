#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env.deploy}"

"$ROOT_DIR/scripts/deploy-docker.sh" "$ENV_FILE"
"$ROOT_DIR/scripts/verify-docker-deploy.sh" "$ENV_FILE"
