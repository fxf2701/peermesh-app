#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

if command -v docker >/dev/null 2>&1 && container_running; then
  docker exec "${RELAY_CONTAINER}" node /app/scripts/test-health-timeout.js
else
  (cd "${repo_root}/server" && npm run test:health-timeout)
fi
