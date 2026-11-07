#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"

if [ -n "${ENV_FILE:-}" ]; then
  env_path="${ENV_FILE}"
elif [ -f "${repo_root}/.env" ]; then
  env_path="${repo_root}/.env"
elif [ -f "${repo_root}/.env.dev" ]; then
  env_path="${repo_root}/.env.dev"
else
  env_path=""
fi

if [ -n "$env_path" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$env_path"
  set +a
fi

DATABASE_URL=${DATABASE_URL:-}
if [ -z "$DATABASE_URL" ]; then
  echo "DATABASE_URL is required (set in .env/.env.dev or export it)." >&2
  exit 1
fi

EXPORT_OUTPUT=${EXPORT_OUTPUT:-export/test_events.jsonl}

mkdir -p "$(dirname "$EXPORT_OUTPUT")"

DATABASE_URL="$DATABASE_URL" \
EXPORT_GID="${EXPORT_GID:-}" \
EXPORT_SINCE_TS="${EXPORT_SINCE_TS:-}" \
EXPORT_UNTIL_TS="${EXPORT_UNTIL_TS:-}" \
EXPORT_BATCH_SIZE="${EXPORT_BATCH_SIZE:-1000}" \
EXPORT_OUTPUT="$EXPORT_OUTPUT" \
node "${repo_root}/scripts/test-export-events.js"
