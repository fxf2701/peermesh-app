#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 /path/to/backup.dump" >&2
  exit 1
fi

CONTAINER_NAME=${CONTAINER_NAME:-disaster-app-postgres}
DB_USER=${DB_USER:-postgres}
DB_NAME=${DB_NAME:-relay_restore}

DUMP_FILE=$1

if [ ! -f "$DUMP_FILE" ]; then
  echo "Backup file not found: $DUMP_FILE" >&2
  exit 1
fi

if [ -f "${DUMP_FILE}.sha256" ]; then
  sha256sum -c "${DUMP_FILE}.sha256"
fi

cat "$DUMP_FILE" | docker exec -i "$CONTAINER_NAME" pg_restore -Fc -U "$DB_USER" -d "$DB_NAME"

docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT count(*) FROM schema_migrations;"
docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT count(*) FROM events;"
docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$DB_NAME" -c "SELECT count(*) FROM federation_cursors;"

echo "Restore validation completed"
