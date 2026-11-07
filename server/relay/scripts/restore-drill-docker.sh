#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"

if [ -n "${ENV_FILE:-}" ]; then
  env_path="${ENV_FILE}"
elif [ -f "${repo_root}/.env" ]; then
  env_path="${repo_root}/.env"
elif [ -f "${repo_root}/.env.dev" ]; then
  env_path="${repo_root}/.env.dev"
else
  env_path=""
fi

if [ -n "$env_path" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$env_path"
  set +a
fi

CONTAINER_NAME=${CONTAINER_NAME:-disaster-app-postgres}
DB_USER=${DB_USER:-postgres}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}
RESTORE_DB_PREFIX=${RESTORE_DB_PREFIX:-relay_restore_drill}
KEEP_DAYS=${KEEP_DAYS:-7}

latest_backup=$(ls -1t "${BACKUP_DIR}"/relay_*.dump 2>/dev/null | head -n 1 || true)
if [ -z "$latest_backup" ]; then
  echo "No backup files found in $BACKUP_DIR" >&2
  exit 1
fi

stamp=$(date -u +"%Y%m%dT%H%M%SZ")
restore_db="${RESTORE_DB_PREFIX}_${stamp}"
restore_db=$(echo "$restore_db" | tr 'A-Z' 'a-z')

if [ -f "${latest_backup}.sha256" ]; then
  expected_hash=$(awk '{print $1}' "${latest_backup}.sha256")
  actual_hash=$(sha256sum "${latest_backup}" | awk '{print $1}')
  if [ "$expected_hash" != "$actual_hash" ]; then
    echo "Checksum mismatch for ${latest_backup}" >&2
    exit 1
  fi
fi

docker exec -i "$CONTAINER_NAME" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres \
  -c "SELECT 1 FROM pg_database WHERE datname='${restore_db}'" >/dev/null || true

echo "Creating restore database ${restore_db}..."
if ! docker exec -i "$CONTAINER_NAME" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres \
  -c "CREATE DATABASE ${restore_db};"; then
  echo "Create database failed for ${restore_db}" >&2
  exit 1
fi

for i in 1 2 3 4 5; do
  exists=$(docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -Atq \
    -c "SELECT 1 FROM pg_database WHERE datname='${restore_db}'" | tr -d '\r\n ')
  if [ "$exists" = "1" ]; then
    break
  fi
  sleep 1
done

exists=$(docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -Atq \
  -c "SELECT 1 FROM pg_database WHERE datname='${restore_db}'" | tr -d '\r\n ')
if [ "$exists" != "1" ]; then
  echo "Failed to detect restore database: ${restore_db}" >&2
  exit 1
fi

cat "$latest_backup" | docker exec -i "$CONTAINER_NAME" pg_restore -Fc -U "$DB_USER" -d "$restore_db"

docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$restore_db" -c "SELECT count(*) FROM schema_migrations;"
docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$restore_db" -c "SELECT count(*) FROM events;"
docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d "$restore_db" -c "SELECT count(*) FROM federation_cursors;"

if [ "$KEEP_DAYS" -ge 0 ] 2>/dev/null; then
  cutoff=$(date -u -d "-${KEEP_DAYS} days" +"%Y%m%dT%H%M%SZ")
  dbs=$(docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres -At \
    -c "SELECT datname FROM pg_database WHERE datname LIKE '${RESTORE_DB_PREFIX}_%'")
  echo "$dbs" | while read -r db; do
    [ -z "$db" ] && continue
    suffix=${db#${RESTORE_DB_PREFIX}_}
    if [[ "$suffix" < "$cutoff" ]]; then
      docker exec -i "$CONTAINER_NAME" psql -U "$DB_USER" -d postgres \
        -c "DROP DATABASE IF EXISTS ${db};" >/dev/null
    fi
  done
fi

echo "Restore drill completed: ${restore_db}"
