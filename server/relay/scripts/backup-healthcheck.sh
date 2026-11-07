#!/usr/bin/env sh
set -eu

LAST_FILE=${BACKUP_OK_FILE:-/backup/last_backup.ok}
MAX_AGE=${BACKUP_MAX_AGE_SECONDS:-}

if [ -z "$MAX_AGE" ]; then
  interval=${BACKUP_INTERVAL_SECONDS:-0}
  if [ "$interval" -gt 0 ] 2>/dev/null; then
    MAX_AGE=$((interval * 2))
  else
    MAX_AGE=172800
  fi
fi

if [ ! -f "$LAST_FILE" ]; then
  echo "[relay_backup][healthcheck] missing $LAST_FILE" >&2
  exit 1
fi

now=$(date +%s)
last=$(stat -c %Y "$LAST_FILE")
age=$((now - last))

if [ "$age" -gt "$MAX_AGE" ]; then
  echo "[relay_backup][healthcheck] last backup too old: ${age}s > ${MAX_AGE}s" >&2
  exit 1
fi

exit 0
