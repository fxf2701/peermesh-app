#!/usr/bin/env bash
set -euo pipefail

if ! command -v pg_dump >/dev/null 2>&1; then
  echo "pg_dump not found in PATH" >&2
  exit 1
fi

DATABASE_URL=${DATABASE_URL:-}
if [ -z "$DATABASE_URL" ]; then
  echo "DATABASE_URL is required" >&2
  exit 1
fi

BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}
RETENTION_DAYS=${RETENTION_DAYS:-14}

STAMP=$(date -u +"%Y%m%dT%H%M%SZ")
OUT="${BACKUP_DIR}/relay_${STAMP}.dump"
CHECKSUM="${OUT}.sha256"

mkdir -p "$BACKUP_DIR"

pg_dump -Fc "$DATABASE_URL" > "$OUT"
sha256sum "$OUT" > "$CHECKSUM"

if [ "$RETENTION_DAYS" -ge 0 ] 2>/dev/null; then
  find "$BACKUP_DIR" -type f -name "relay_*.dump" -mtime +"$RETENTION_DAYS" -delete
  find "$BACKUP_DIR" -type f -name "relay_*.dump.sha256" -mtime +"$RETENTION_DAYS" -delete
fi

echo "Backup written to $OUT"
