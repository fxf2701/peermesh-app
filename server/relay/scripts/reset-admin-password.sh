#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
env_file="${repo_root}/.env"

new_password="${1:-${ADMIN_DEFAULT_PASSWORD:-admin}}"

if command -v node >/dev/null 2>&1; then
  (cd "${repo_root}" && node "${repo_root}/scripts/set-admin-password.js" --password "${new_password}")
  exit 0
fi

if command -v docker >/dev/null 2>&1; then
  env_args=()
  if [ -f "${env_file}" ]; then
    env_args+=(--env-file "${env_file}")
  fi
  (
    cd "${repo_root}" && \
      docker compose -f "${repo_root}/docker-compose.yml" "${env_args[@]}" \
        run --rm --no-deps --entrypoint node relay \
        /app/scripts/set-admin-password.js --password "${new_password}"
  )
  exit 0
fi

echo "Neither 'node' nor 'docker' is available on this host." >&2
echo "Install Node.js or run this script on a Docker-enabled relay host." >&2
exit 1
