#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

if ! "$ROOT_DIR/run"; then
  echo "Failed to start app. Check:"
  echo "  $ROOT_DIR/.run/backend.log"
  echo "  $ROOT_DIR/.run/frontend.log"
  exit 1
fi

if [[ "${SKIP_OPEN_BROWSER:-0}" = "1" ]]; then
  echo "App is healthy. Browser open skipped (SKIP_OPEN_BROWSER=1)."
  exit 0
fi

if [[ -d "/Applications/Google Chrome.app" ]]; then
  open -na "Google Chrome" --args --app="http://localhost:3000"
else
  open "http://localhost:3000"
fi
