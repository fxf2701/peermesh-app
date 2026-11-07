#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"

TEST_TIMEOUT_SECONDS="${INGEST_RETRY_TEST_TIMEOUT_SECONDS:-10}"

(cd "${repo_root}/server" && \
  INGEST_RETRY_TEST_TIMEOUT_MS="$((TEST_TIMEOUT_SECONDS * 1000))" \
  npm run test:ingest-retry-queue)
