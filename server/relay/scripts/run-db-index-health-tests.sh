#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"

TEST_TIMEOUT_SECONDS="${DB_INDEX_HEALTH_TEST_TIMEOUT_SECONDS:-12}"

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

if command -v docker >/dev/null 2>&1 && container_running; then
  timeout "${TEST_TIMEOUT_SECONDS}" docker exec \
    -e DB_INDEX_HEALTH_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
    "${RELAY_CONTAINER}" node /app/scripts/test-db-index-health.js
else
  (cd "${repo_root}/server" && \
    DB_INDEX_HEALTH_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
    npm run test:db-index-health)
fi
