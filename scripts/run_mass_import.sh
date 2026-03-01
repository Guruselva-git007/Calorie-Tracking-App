#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
BACKEND_LOG="/tmp/calorie_import_retry.log"

cd "$BACKEND_DIR"

old_pid="$(lsof -tiTCP:8080 -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
if [[ -n "$old_pid" ]]; then
  kill -9 "$old_pid" 2>/dev/null || true
  sleep 1
fi

DB_URL="jdbc:mysql://localhost:3306/calorie_tracking_app?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
DB_USER="root" \
DB_PASSWORD="guruselvaselvam1085sql&&&" \
  mvn spring-boot:run >"$BACKEND_LOG" 2>&1 &
backend_pid=$!

cleanup() {
  kill "$backend_pid" 2>/dev/null || true
  wait "$backend_pid" 2>/dev/null || true
}
trap cleanup EXIT

health_code="000"
for _ in $(seq 1 120); do
  health_code="$(curl -s -o /tmp/calorie_health_retry.json -w '%{http_code}' 'http://127.0.0.1:8080/api/health' || true)"
  if [[ "$health_code" == "200" ]]; then
    break
  fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "backend_exited_before_ready"
    tail -n 120 "$BACKEND_LOG" || true
    exit 1
  fi
  sleep 1
done

if [[ "$health_code" != "200" ]]; then
  echo "backend_not_ready code=$health_code"
  tail -n 120 "$BACKEND_LOG" || true
  exit 1
fi

echo "---health---"
cat /tmp/calorie_health_retry.json
echo
echo "---stats-before---"
curl -sS "http://127.0.0.1:8080/api/stats"
echo

trigger_import() {
  local name="$1"
  shift
  local endpoint="$1"
  shift
  local trigger_file="/tmp/import_${name}_trigger.json"

  local code
  code="$(curl -sS -o "$trigger_file" -w '%{http_code}' -X POST --get "$endpoint" "$@")"
  echo "${name}_trigger_code=$code"
  cat "$trigger_file"
  echo

  if [[ "$code" != "200" ]]; then
    return 1
  fi

  local job
  job="$(sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p' "$trigger_file")"
  if [[ -z "$job" ]]; then
    echo "${name}_job_missing"
    return 1
  fi

  local body
  local state
  for i in $(seq 1 420); do
    body="$(curl -sS --max-time 20 "http://127.0.0.1:8080/api/import/jobs/$job")"
    state="$(echo "$body" | sed -n 's/.*"state":"\([A-Z]*\)".*/\1/p')"
    if [[ "$state" == "COMPLETED" || "$state" == "FAILED" ]]; then
      echo "---${name}-final---"
      echo "$body"
      return 0
    fi
    if (( i % 25 == 0 )); then
      echo "${name}_progress attempt=$i state=$state"
    fi
    sleep 2
  done

  echo "${name}_poll_timeout"
  return 1
}

trigger_import \
  "global" \
  "http://127.0.0.1:8080/api/import/global-datasets/async" \
  --data-urlencode "cuisines=indian,chinese,indo chinese,european,mediterranean,african,western,eastern,northern,southern" \
  --data-urlencode "maxPerCuisine=80" \
  --data-urlencode "includeMealDbAreas=true" \
  --data-urlencode "maxPerArea=20" \
  --data-urlencode "includeOpenFoodFacts=true" \
  --data-urlencode "countries=india,china,japan,thailand,vietnam,indonesia,philippines,saudi-arabia,turkey,egypt,morocco,nigeria,south-africa,united-states,canada,mexico,brazil,argentina,chile,peru,italy,spain,france,germany,united-kingdom,portugal,poland,russia,ukraine,greece,australia" \
  --data-urlencode "pages=3" \
  --data-urlencode "pageSize=180" \
  --data-urlencode "includeDummyJson=true" \
  --data-urlencode "dummyJsonPageSize=80" \
  --data-urlencode "dummyJsonMaxRecipes=800" || true

trigger_import \
  "openff" \
  "http://127.0.0.1:8080/api/import/open-food-facts/async" \
  --data-urlencode "countries=india,china,japan,south-korea,thailand,vietnam,indonesia,philippines,malaysia,singapore,turkey,egypt,morocco,nigeria,south-africa,united-states,canada,mexico,brazil,argentina,chile,peru,italy,spain,france,germany,united-kingdom,portugal,poland,russia,ukraine,greece,australia" \
  --data-urlencode "pages=4" \
  --data-urlencode "pageSize=220" || true

trigger_import \
  "world" \
  "http://127.0.0.1:8080/api/import/world-cuisines/async" \
  --data-urlencode "cuisines=indian,chinese,indo chinese,european,mediterranean,african,western,eastern,northern,southern" \
  --data-urlencode "maxPerCuisine=60" \
  --data-urlencode "includeOpenFoodFacts=true" \
  --data-urlencode "countries=india,china,japan,italy,greece,morocco,united-states,brazil,nigeria,south-africa" \
  --data-urlencode "pages=2" \
  --data-urlencode "pageSize=160" || true

trigger_import \
  "sweets" \
  "http://127.0.0.1:8080/api/import/sweets-desserts/async" \
  --data-urlencode "countries=india,china,japan,south-korea,thailand,vietnam,indonesia,philippines,italy,france,germany,spain,greece,turkey,united-kingdom,united-states,mexico,brazil,argentina,south-africa,nigeria,australia" \
  --data-urlencode "pages=1" \
  --data-urlencode "pageSize=120" \
  --data-urlencode "maxPerQuery=14" \
  --data-urlencode "maxMealDbDesserts=140" \
  --data-urlencode "includeCuratedFallback=true" || true

trigger_import \
  "correction" \
  "http://127.0.0.1:8080/api/import/correct-datasets/async" \
  --data-urlencode "promoteFactChecked=true" || true

echo "---stats-after---"
curl -sS "http://127.0.0.1:8080/api/stats"
echo

echo "---sample-searches---"
echo "chicken noodles:"
curl -sS "http://127.0.0.1:8080/api/dishes/suggest?search=chicken%20noodles&limit=8" | jq -r '.[].name'
echo "chilli chicken:"
curl -sS "http://127.0.0.1:8080/api/dishes/suggest?search=chilli%20chicken&limit=8" | jq -r '.[].name'
echo "dosa:"
curl -sS "http://127.0.0.1:8080/api/dishes/suggest?search=dosa&limit=8" | jq -r '.[].name'
