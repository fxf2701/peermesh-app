#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME=${CONTAINER_NAME:-disaster-app-postgres}
DB_USER=${DB_USER:-postgres}
DB_NAME=${DB_NAME:-relay_restore}

docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -c "CREATE DATABASE ${DB_NAME};" >/dev/null \
  || docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -c "SELECT 1;" >/dev/null

echo "Restore database ready: $DB_NAME"
