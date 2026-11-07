#!/usr/bin/env bash
set -euo pipefail

BACKUP_OK_FILE=${BACKUP_OK_FILE:-/var/backups/relay/last_backup.ok}
BACKUP_MAX_AGE_SECONDS=${BACKUP_MAX_AGE_SECONDS:-172800}
ALERT_WEBHOOK_URL=${ALERT_WEBHOOK_URL:-}
ALERT_LOG_PATH=${ALERT_LOG_PATH:-}
ALERT_TITLE=${ALERT_TITLE:-relay_backup_stale}

log_alert() {
  local message="$1"
  if [ -n "$ALERT_LOG_PATH" ]; then
    mkdir -p "$(dirname "$ALERT_LOG_PATH")"
    printf "%s %s\n" "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" "$message" >> "$ALERT_LOG_PATH"
  else
    echo "$message" >&2
  fi
}

send_webhook() {
  local message="$1"
  if [ -z "$ALERT_WEBHOOK_URL" ]; then
    return 0
  fi
  curl -fsS -X POST \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"${ALERT_TITLE}\",\"message\":\"${message}\"}" \
    "$ALERT_WEBHOOK_URL" >/dev/null || true
}

if [ ! -f "$BACKUP_OK_FILE" ]; then
  msg="backup missing: ${BACKUP_OK_FILE}"
  log_alert "$msg"
  send_webhook "$msg"
  exit 2
fi

now=$(date +%s)
last=$(stat -c %Y "$BACKUP_OK_FILE")
age=$((now - last))

if [ "$age" -gt "$BACKUP_MAX_AGE_SECONDS" ]; then
  msg="backup stale: age ${age}s > ${BACKUP_MAX_AGE_SECONDS}s"
  log_alert "$msg"
  send_webhook "$msg"
  exit 2
fi

exit 0
