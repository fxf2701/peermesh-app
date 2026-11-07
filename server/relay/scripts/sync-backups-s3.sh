#!/usr/bin/env bash
set -euo pipefail

if ! command -v aws >/dev/null 2>&1; then
  echo "aws CLI is required for S3 sync" >&2
  exit 1
fi

BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}
S3_BUCKET=${S3_BUCKET:-}
S3_PREFIX=${S3_PREFIX:-}
S3_SSE_C_KEY=${S3_SSE_C_KEY:-}

if [ -z "$S3_BUCKET" ]; then
  echo "S3_BUCKET is required" >&2
  exit 1
fi

if [ ! -d "$BACKUP_DIR" ]; then
  echo "Backup dir not found: $BACKUP_DIR" >&2
  exit 1
fi

dest="s3://${S3_BUCKET}"
if [ -n "$S3_PREFIX" ]; then
  dest="${dest%/}/${S3_PREFIX#/}"
fi

extra_args=()
if [ -n "$S3_SSE_C_KEY" ]; then
  extra_args+=(--sse-c AES256 --sse-c-key "$S3_SSE_C_KEY")
fi

shopt -s nullglob
files=("$BACKUP_DIR"/relay_*.dump "$BACKUP_DIR"/relay_*.dump.sha256)

if [ ${#files[@]} -eq 0 ]; then
  echo "No backups found in $BACKUP_DIR"
  exit 0
fi

for file in "${files[@]}"; do
  base=$(basename "$file")
  aws s3 cp "$file" "${dest%/}/${base}" "${extra_args[@]}"
done

echo "S3 sync complete: ${dest}"
