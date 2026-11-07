#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}
DELETE_INVALID=${DELETE_INVALID:-1}

if [ ! -d "$BACKUP_DIR" ]; then
  echo "Backup dir not found: $BACKUP_DIR" >&2
  exit 1
fi

shopt -s nullglob
dump_files=("$BACKUP_DIR"/relay_*.dump)

if [ ${#dump_files[@]} -eq 0 ]; then
  echo "No backups found in $BACKUP_DIR"
  exit 0
fi

invalid=0

for dump in "${dump_files[@]}"; do
  checksum_file="${dump}.sha256"
  if [ ! -f "$checksum_file" ]; then
    echo "[backup_verify] missing checksum for $dump" >&2
    invalid=$((invalid + 1))
    if [ "$DELETE_INVALID" -eq 1 ]; then
      rm -f "$dump"
    fi
    continue
  fi

  if ! sha256sum -c "$checksum_file" >/dev/null 2>&1; then
    echo "[backup_verify] checksum mismatch for $dump" >&2
    invalid=$((invalid + 1))
    if [ "$DELETE_INVALID" -eq 1 ]; then
      rm -f "$dump" "$checksum_file"
    fi
  fi
done

if [ "$invalid" -gt 0 ]; then
  echo "[backup_verify] invalid backups: $invalid" >&2
  exit 2
fi

echo "[backup_verify] all backups verified"
