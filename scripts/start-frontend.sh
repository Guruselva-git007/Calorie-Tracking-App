#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HOST="${HOST:-127.0.0.1}"
export PORT="${FRONTEND_PORT:-3000}"

cd "$ROOT_DIR/frontend"

if [[ ! -d node_modules ]]; then
  npm install
fi

exec npm start
