#!/usr/bin/env bash
set -euo pipefail

USE_ENV_DEV="${USE_ENV_DEV:-0}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-disaster-app-postgres}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"

if [ "${USE_ENV_DEV}" = "1" ] && [ -f "${repo_root}/server/.env.dev" ]; then
  env_file="${repo_root}/server/.env.dev"
fi

if [ -f "${env_file}" ]; then
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      "" | \#*) continue ;;
    esac
    if [[ "${line}" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then
      key="${line%%=*}"
      value="${line#*=}"
      if [[ "${value}" =~ ^\".*\"$ || "${value}" =~ ^\'.*\'$ ]]; then
        value="${value:1:${#value}-2}"
      fi
      if [ -z "${!key+x}" ]; then
        export "${key}=${value}"
      fi
    fi
  done < "${env_file}"
fi

if [ -z "${DATABASE_URL:-}" ]; then
  echo "DATABASE_URL is required (set in .env/.env.dev or export it)." >&2
  exit 1
fi

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${POSTGRES_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${POSTGRES_CONTAINER}" >/dev/null 2>&1
  fi
}

sql=$(cat <<'SQL'
DO $$
DECLARE
  is_partitioned boolean;
  start_ts timestamp;
  end_ts timestamp;
  suffix text;
BEGIN
  SELECT EXISTS (
    SELECT 1
    FROM pg_partitioned_table pt
    JOIN pg_class c ON c.oid = pt.partrelid
    WHERE c.relname = 'events'
  ) INTO is_partitioned;

  IF NOT is_partitioned THEN
    RAISE NOTICE 'events is not partitioned; apply schema_partitioned.sql first';
    RETURN;
  END IF;

  FOR i IN 0..1 LOOP
    start_ts := date_trunc('month', now() AT TIME ZONE 'UTC') + (i || ' month')::interval;
    end_ts := start_ts + interval '1 month';
    suffix := to_char(start_ts, 'YYYY_MM');
    EXECUTE format(
      'CREATE TABLE IF NOT EXISTS events_exp_%s PARTITION OF events FOR VALUES FROM (%s) TO (%s);',
      suffix,
      EXTRACT(EPOCH FROM start_ts)::bigint,
      EXTRACT(EPOCH FROM end_ts)::bigint
    );
  END LOOP;
END $$;
SQL
)

if command -v docker >/dev/null 2>&1 && container_running; then
  docker exec -i "${POSTGRES_CONTAINER}" psql "${DATABASE_URL}" -v ON_ERROR_STOP=1 <<SQL
${sql}
SQL
  exit 0
fi

if command -v psql >/dev/null 2>&1; then
  psql "${DATABASE_URL}" -v ON_ERROR_STOP=1 <<SQL
${sql}
SQL
  exit 0
fi

echo "psql not found and postgres container '${POSTGRES_CONTAINER}' is not running." >&2
exit 1
