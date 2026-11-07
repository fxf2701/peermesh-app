#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"

RELAY_BASE_URL="${RELAY_BASE_URL:-http://localhost:8080}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin1234}"

(cd "${repo_root}" && \
  RELAY_BASE_URL="${RELAY_BASE_URL}" \
  ADMIN_PASSWORD="${ADMIN_PASSWORD}" \
  node scripts/test-reputation-crud.js)
