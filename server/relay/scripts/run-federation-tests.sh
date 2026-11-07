#!/usr/bin/env bash
set -euo pipefail

RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"
USE_ENV_DEV="${USE_ENV_DEV:-0}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"
env_dev_file="${repo_root}/server/.env.dev"

if [ "${USE_ENV_DEV}" = "1" ] && [ -f "${env_dev_file}" ]; then
  env_file="${env_dev_file}"
elif [ -f "${env_dev_file}" ] && [ -f "${env_file}" ]; then
  if command -v rg >/dev/null 2>&1; then
    if rg -n "DATABASE_URL=.*db-host" "${env_file}" >/dev/null 2>&1; then
      env_file="${env_dev_file}"
    fi
  else
    if grep -q "DATABASE_URL=.*db-host" "${env_file}" 2>/dev/null; then
      env_file="${env_dev_file}"
    fi
  fi
fi

echo "${env_file}"

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
  if docker exec "${RELAY_CONTAINER}" test -f /app/src/federation.js >/dev/null 2>&1; then
    exec_env=()
    if [ -n "${DATABASE_URL:-}" ]; then
      exec_env+=(-e DATABASE_URL)
    fi
    if [ -n "${FEDERATION_SELF_ID:-}" ]; then
      exec_env+=(-e FEDERATION_SELF_ID)
    fi
    docker exec "${exec_env[@]}" "${RELAY_CONTAINER}" node /app/scripts/test-federation-hardening.js
    docker exec "${exec_env[@]}" "${RELAY_CONTAINER}" node /app/scripts/test-federation-cursor-durability.js
    docker exec "${exec_env[@]}" "${RELAY_CONTAINER}" node /app/scripts/test-federation-backpressure.js
    docker exec "${exec_env[@]}" "${RELAY_CONTAINER}" node /app/scripts/test-federation-hop-chain.js
    docker exec "${exec_env[@]}" "${RELAY_CONTAINER}" node /app/scripts/test-federation-banlist.js
  else
    (cd "${repo_root}/server" && node scripts/test-federation-hardening.js)
    (cd "${repo_root}/server" && node scripts/test-federation-cursor-durability.js)
    (cd "${repo_root}/server" && node scripts/test-federation-backpressure.js)
    (cd "${repo_root}/server" && node scripts/test-federation-hop-chain.js)
    (cd "${repo_root}/server" && node scripts/test-federation-banlist.js)
  fi
else
  (cd "${repo_root}/server" && node scripts/test-federation-hardening.js)
  (cd "${repo_root}/server" && node scripts/test-federation-cursor-durability.js)
  (cd "${repo_root}/server" && node scripts/test-federation-backpressure.js)
  (cd "${repo_root}/server" && node scripts/test-federation-hop-chain.js)
  (cd "${repo_root}/server" && node scripts/test-federation-banlist.js)
fi
