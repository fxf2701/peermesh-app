#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME=${CONTAINER_NAME:-disaster-app-postgres}
DB_USER=${DB_USER:-postgres}
DB_NAME=${DB_NAME:-relay}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}

STAMP=$(date -u +"%Y%m%dT%H%M%SZ")
OUT="${BACKUP_DIR}/relay_${STAMP}.dump"
CHECKSUM="${OUT}.sha256"

docker exec -i "$CONTAINER_NAME" pg_dump -Fc -U "$DB_USER" "$DB_NAME" > "$OUT"
sha256sum "$OUT" > "$CHECKSUM"

echo "Backup written to $OUT"
