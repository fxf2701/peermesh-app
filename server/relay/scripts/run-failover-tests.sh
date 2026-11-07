#!/usr/bin/env bash
set -euo pipefail

RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-disaster-app-postgres}"
RELAY_BASE_URL="${RELAY_BASE_URL:-http://localhost:8080}"
USE_ENV_DEV="${USE_ENV_DEV:-0}"
ALLOW_DB_DISRUPTION="${ALLOW_DB_DISRUPTION:-0}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"

if [ "${USE_ENV_DEV}" = "1" ] && [ -f "${repo_root}/server/.env.dev" ]; then
  env_file="${repo_root}/server/.env.dev"
fi

echo "${env_file}"

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

if [ "${ALLOW_DB_DISRUPTION}" != "1" ]; then
  echo "SKIP failover_db_disconnect (set ALLOW_DB_DISRUPTION=1)"
  exit 0
fi

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "$1" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "$1" >/dev/null 2>&1
  fi
}

http_check() {
  local path="$1"
  local expected="$2"
  local base_url="${RELAY_BASE_URL:-http://localhost:8080}"
  RELAY_BASE_URL="${base_url}" CHECK_PATH="${path}" EXPECTED_STATUS="${expected}" \
    node -e 'const http=require("http");const url=new URL(process.env.RELAY_BASE_URL);const path=process.env.CHECK_PATH;const expected=Number(process.env.EXPECTED_STATUS);const req=http.request({hostname:url.hostname,port:url.port,path,method:"GET"},res=>{if(res.statusCode!==expected){console.error("FAIL",path,res.statusCode);process.exit(1);}res.resume();res.on("end",()=>{console.log("PASS",path,res.statusCode);});});req.on("error",err=>{console.error("FAIL",path,err.message||String(err));process.exit(1);});req.end();'
}

if ! command -v docker >/dev/null 2>&1; then
  echo "SKIP failover_db_disconnect (docker not available)"
  exit 0
fi

if ! container_running "${POSTGRES_CONTAINER}"; then
  echo "SKIP failover_db_disconnect (postgres container not running)"
  exit 0
fi

http_check "/healthz" 200
http_check "/readyz" 200

docker stop "${POSTGRES_CONTAINER}" >/dev/null
sleep 1

http_check "/healthz" 200
http_check "/readyz" 503

docker start "${POSTGRES_CONTAINER}" >/dev/null

for i in $(seq 1 20); do
  if docker exec "${POSTGRES_CONTAINER}" pg_isready -U postgres -d relay >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

http_check "/readyz" 200
echo "PASS failover_db_disconnect"
