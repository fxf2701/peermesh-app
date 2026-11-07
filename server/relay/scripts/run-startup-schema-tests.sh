#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"
TEST_TIMEOUT_SECONDS="${STARTUP_SCHEMA_TEST_TIMEOUT_SECONDS:-20}"

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

if command -v docker >/dev/null 2>&1 && container_running; then
  timeout "${TEST_TIMEOUT_SECONDS}" docker exec "${RELAY_CONTAINER}" \
    node /app/scripts/test-startup-schema.js
else
  (cd "${repo_root}/server" && \
    STARTUP_SCHEMA_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
    npm run test:startup-schema)
fi
