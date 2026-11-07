#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

IMAGE=${IMAGE:-postgres:16}
BACKUP_DIR_HOST=${BACKUP_DIR_HOST:-/var/backups/relay}
CONTAINER_BACKUP_DIR=/var/backups/relay
DELETE_INVALID=${DELETE_INVALID:-1}

docker run --rm \
  -e BACKUP_DIR="$CONTAINER_BACKUP_DIR" \
  -e DELETE_INVALID="$DELETE_INVALID" \
  -v "$BACKUP_DIR_HOST:$CONTAINER_BACKUP_DIR" \
  -v "$SCRIPT_DIR/verify-backups.sh:/scripts/verify-backups.sh:ro" \
  "$IMAGE" bash /scripts/verify-backups.sh
