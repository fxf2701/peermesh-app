#!/bin/sh
set -e

if [ -n "$DATABASE_URL" ]; then
  echo "waiting for database to be ready..."
  if command -v pg_isready >/dev/null 2>&1; then
    db_fields="$(node -e 'const raw=process.env.DATABASE_URL||"";if(!raw){console.log("||||");process.exit(0);}const u=new URL(raw);const db=u.pathname&&u.pathname.length>1?u.pathname.slice(1):"postgres";const port=u.port||"5432";const user=u.username||"postgres";const pass=u.password||"";console.log([u.hostname,port,user,pass,db].join("|"));')"
    IFS="|" read -r DB_HOST DB_PORT DB_USER DB_PASSWORD DB_NAME <<EOF
${db_fields}
EOF
    echo "database target: host=${DB_HOST} port=${DB_PORT} db=${DB_NAME} user=${DB_USER}"
    if [ -n "${DB_PASSWORD:-}" ]; then
      export PGPASSWORD="${DB_PASSWORD}"
    fi
    max_retries="${DB_WAIT_MAX_RETRIES:-20}"
    retry=1
    until pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1; do
      if [ "$retry" -ge "$max_retries" ]; then
        echo "database not ready after ${max_retries} attempts, exiting."
        exit 1
      fi
      echo "database not ready (pg_isready), retrying in 3s... (${retry}/${max_retries})"
      retry=$((retry + 1))
      sleep 3
    done

    retry=1
    until psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -c "SELECT 1" >/dev/null 2>&1; do
      if [ "$retry" -ge "$max_retries" ]; then
        echo "database not reachable after ${max_retries} attempts, exiting."
        exit 1
      fi
      echo "database not ready (psql), retrying in 3s... (${retry}/${max_retries})"
      retry=$((retry + 1))
      sleep 3
    done
  fi
  max_retries="${DB_WAIT_MAX_RETRIES:-20}"
  retry=1
  until npm run migrate:up; do
    if [ "$retry" -ge "$max_retries" ]; then
      echo "database not ready after ${max_retries} attempts, exiting."
      exit 1
    fi
    echo "database not ready, retrying in 3s... (${retry}/${max_retries})"
    retry=$((retry + 1))
    sleep 3
  done
fi

exec npm start
