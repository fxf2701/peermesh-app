#!/usr/bin/env bash
set -euo pipefail

if ! command -v gsutil >/dev/null 2>&1; then
  echo "gsutil is required for GCS sync" >&2
  exit 1
fi

BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}
GCS_BUCKET=${GCS_BUCKET:-}
GCS_PREFIX=${GCS_PREFIX:-}
GCS_ENCRYPTION_KEY_BASE64=${GCS_ENCRYPTION_KEY_BASE64:-}

if [ -z "$GCS_BUCKET" ]; then
  echo "GCS_BUCKET is required" >&2
  exit 1
fi

if [ ! -d "$BACKUP_DIR" ]; then
  echo "Backup dir not found: $BACKUP_DIR" >&2
  exit 1
fi

dest="gs://${GCS_BUCKET}"
if [ -n "$GCS_PREFIX" ]; then
  dest="${dest%/}/${GCS_PREFIX#/}"
fi

extra_args=()
if [ -n "$GCS_ENCRYPTION_KEY_BASE64" ]; then
  extra_args+=(-o "GSUtil:encryption_key=${GCS_ENCRYPTION_KEY_BASE64}")
fi

shopt -s nullglob
files=("$BACKUP_DIR"/relay_*.dump "$BACKUP_DIR"/relay_*.dump.sha256)

if [ ${#files[@]} -eq 0 ]; then
  echo "No backups found in $BACKUP_DIR"
  exit 0
fi

gsutil "${extra_args[@]}" -m cp "${files[@]}" "${dest%/}/"

echo "GCS sync complete: ${dest}"
