#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

PUBLIC_HOST="${ATLAS_PUBLIC_HOST:-10.210.0.62}"
SERVICE_PORT="${ATLAS_SERVICE_PORT:-8301}"
CONSOLE_PORT="${ATLAS_CONSOLE_PORT:-8300}"

mkdir -p data/company

if [[ ! -f .env ]]; then
  DB_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
  umask 077
  printf '%s\n' \
    'ATLAS_DB_USERNAME=atlas' \
    "ATLAS_DB_PASSWORD=${DB_PASSWORD}" \
    "ATLAS_SERVICE_PORT=${SERVICE_PORT}" \
    "ATLAS_CONSOLE_PORT=${CONSOLE_PORT}" \
    "ATLAS_PUBLIC_API_BASE=http://${PUBLIC_HOST}:${SERVICE_PORT}" \
    "ATLAS_ALLOWED_ORIGINS=http://${PUBLIC_HOST}:${CONSOLE_PORT}" \
    'ATLAS_SEARCH_PRIMARY_ENABLED=false' \
    'ATLAS_SEARCH_LLM_ENABLED=false' \
    > .env
  chmod 600 .env
fi

docker compose config --quiet
docker compose up -d --build --remove-orphans
docker compose ps
