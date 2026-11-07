#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 /path/to/backup.dump" >&2
  exit 1
fi

if ! command -v pg_restore >/dev/null 2>&1; then
  echo "pg_restore not found in PATH" >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "psql not found in PATH" >&2
  exit 1
fi

DUMP_FILE=$1
RESTORE_DATABASE_URL=${RESTORE_DATABASE_URL:-}

if [ -z "$RESTORE_DATABASE_URL" ]; then
  echo "RESTORE_DATABASE_URL is required" >&2
  exit 1
fi

if [ ! -f "$DUMP_FILE" ]; then
  echo "Backup file not found: $DUMP_FILE" >&2
  exit 1
fi

if [ -f "${DUMP_FILE}.sha256" ]; then
  sha256sum -c "${DUMP_FILE}.sha256"
fi

pg_restore -Fc -d "$RESTORE_DATABASE_URL" "$DUMP_FILE"

psql "$RESTORE_DATABASE_URL" -c "SELECT count(*) FROM schema_migrations;"
psql "$RESTORE_DATABASE_URL" -c "SELECT count(*) FROM events;"
psql "$RESTORE_DATABASE_URL" -c "SELECT count(*) FROM federation_cursors;"

echo "Restore validation completed"
