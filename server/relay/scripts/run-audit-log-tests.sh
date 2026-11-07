#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"

TEST_TIMEOUT_SECONDS="${AUDIT_LOG_TEST_TIMEOUT_SECONDS:-10}"

(cd "${repo_root}/server" && \
  AUDIT_LOG_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
  npm run test:audit-log)
