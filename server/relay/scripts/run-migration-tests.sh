#!/usr/bin/env bash
set -euo pipefail

RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"

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
  docker exec -e MIGRATIONS_TEST_DATABASE_URL \
    "${RELAY_CONTAINER}" node /app/scripts/test-migrations.js
else
  (cd "${repo_root}/server" && npm run test:migrations)
fi
