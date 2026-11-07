#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"

TEST_TIMEOUT_SECONDS="${REQUEST_SIGNATURE_TEST_TIMEOUT_SECONDS:-12}"

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

if command -v docker >/dev/null 2>&1 && container_running; then
  timeout "${TEST_TIMEOUT_SECONDS}" docker exec \
    -e REQUEST_SIGNATURE_SECRET="${REQUEST_SIGNATURE_SECRET:-test-secret}" \
    -e REQUEST_SIGNATURE_ENABLED="${REQUEST_SIGNATURE_ENABLED:-1}" \
    -e REQUEST_SIGNATURE_SKEW_SECONDS="${REQUEST_SIGNATURE_SKEW_SECONDS:-300}" \
    -e RELAY_BASE_URL="${RELAY_BASE_URL:-http://localhost:8080}" \
    -e REQUEST_SIGNATURE_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
    "${RELAY_CONTAINER}" node /app/scripts/test-request-signature.js
else
  (cd "${repo_root}/server" && \
    REQUEST_SIGNATURE_ENABLED="${REQUEST_SIGNATURE_ENABLED:-1}" \
    REQUEST_SIGNATURE_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
    npm run test:request-signature)
fi
