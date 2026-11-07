#!/usr/bin/env bash
set -euo pipefail

RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"
RELAY_BASE_URL="${RELAY_BASE_URL:-http://localhost:8080}"
USE_ENV_DEV="${USE_ENV_DEV:-0}"
SMOKE_TEST_TIMEOUT_SECONDS="${SMOKE_TEST_TIMEOUT_SECONDS:-20}"
SMOKE_TEST_TIMEOUT_MS=$((SMOKE_TEST_TIMEOUT_SECONDS * 1000 - 1000))
if [ "${SMOKE_TEST_TIMEOUT_MS}" -lt 1000 ]; then
  SMOKE_TEST_TIMEOUT_MS=1000
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"
env_dev_file="${repo_root}/server/.env.dev"

if [ "${USE_ENV_DEV}" = "1" ] && [ -f "${env_dev_file}" ]; then
  env_file="${env_dev_file}"
fi

if [ -f "${env_file}" ]; then
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      "" | \#*) continue ;;
    esac
    if [[ "${line}" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      key="${line%%=*}"
      value="${line#*=}"
      if [[ "${value}" =~ ^\".*\"$ || "${value}" =~ ^\'.*\'$ ]]; then
        value="${value:1:${#value}-2}"
      fi
      if [ -z "${!key+x}" ]; then
        export "${key}=${value}"
      fi
    fi
  done < "${env_file}"
fi

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

if command -v docker >/dev/null 2>&1 && container_running; then
  if docker exec "${RELAY_CONTAINER}" test -f /app/scripts/test-smoke.js >/dev/null 2>&1; then
    exec_env=()
    if [ -n "${RELAY_BASE_URL:-}" ]; then
      exec_env+=(-e RELAY_BASE_URL="${RELAY_BASE_URL}")
    fi
    if [ -n "${REQUEST_SIGNATURE_ENABLED:-}" ]; then
      exec_env+=(-e REQUEST_SIGNATURE_ENABLED)
    fi
    if [ -n "${REQUEST_SIGNATURE_SECRET:-}" ]; then
      exec_env+=(-e REQUEST_SIGNATURE_SECRET)
    fi
    if [ -n "${SMOKE_TEST_TIMEOUT_MS:-}" ]; then
      exec_env+=(-e SMOKE_TEST_TIMEOUT_MS="${SMOKE_TEST_TIMEOUT_MS}")
    fi
    docker exec "${exec_env[@]}" "${RELAY_CONTAINER}" node /app/scripts/test-smoke.js
  else
    (cd "${repo_root}/server" && \
      RELAY_BASE_URL="${RELAY_BASE_URL}" \
      SMOKE_TEST_TIMEOUT_MS="${SMOKE_TEST_TIMEOUT_MS}" \
      node scripts/test-smoke.js)
  fi
else
  (cd "${repo_root}/server" && \
    RELAY_BASE_URL="${RELAY_BASE_URL}" \
    SMOKE_TEST_TIMEOUT_MS="${SMOKE_TEST_TIMEOUT_MS}" \
    node scripts/test-smoke.js)
fi
