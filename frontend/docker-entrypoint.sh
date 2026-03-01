#!/usr/bin/env sh
set -eu

: "${PORT:=80}"
: "${BACKEND_BASE_URL:=http://backend:8080}"

export PORT BACKEND_BASE_URL
envsubst '${PORT} ${BACKEND_BASE_URL}' \
  < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf

exec nginx -g 'daemon off;'
