#!/usr/bin/env bash
set -euo pipefail

RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"
RELAY_BASE_URL="${RELAY_BASE_URL:-http://localhost:8080}"
USE_ENV_DEV="${USE_ENV_DEV:-0}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"

if [ "$USE_ENV_DEV" = "1" ] && [ -f "${repo_root}/server/.env.dev" ]; then
  env_file="${repo_root}/server/.env.dev"
fi

echo "$env_file"

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

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

health_check_cmd='const http=require("http");const url=new URL(process.env.RELAY_BASE_URL||"http://localhost:8080");const maxRetries=Number(process.env.HEALTH_CHECK_RETRIES||"5");const baseDelayMs=Number(process.env.HEALTH_CHECK_BASE_DELAY_MS||"500");let attempt=0;function check(){attempt+=1;const req=http.request({hostname:url.hostname,port:url.port,path:"/health",method:"GET"},res=>{let body="";res.on("data",c=>body+=c);res.on("end",()=>{if(res.statusCode===200){console.log("Health check ok");return;}if(attempt>=maxRetries){console.error("Health check failed:",res.statusCode,body);process.exit(1);}const delay=baseDelayMs*Math.pow(2,attempt-1);console.error("Health check failed:",res.statusCode,body,"retrying in",delay+"ms");setTimeout(check,delay);});});req.on("error",err=>{if(attempt>=maxRetries){console.error("Health check error:",err.message||String(err));process.exit(1);}const delay=baseDelayMs*Math.pow(2,attempt-1);console.error("Health check error:",err.message||String(err),"retrying in",delay+"ms");setTimeout(check,delay);});req.end();}check();'

if command -v docker >/dev/null 2>&1 && container_running; then
  docker exec -e RELAY_BASE_URL="${RELAY_BASE_URL}" \
    "${RELAY_CONTAINER}" node -e "${health_check_cmd}"
  docker exec -e RELAY_BASE_URL="${RELAY_BASE_URL}" \
    -e MAX_REQUEST_BYTES \
    -e INGEST_RATE_LIMIT_PER_IP \
    -e INGEST_RATE_LIMIT_PER_GID \
    -e FETCH_RATE_LIMIT_PER_IP \
    -e FETCH_RATE_LIMIT_PER_GID \
    -e RATE_LIMIT_BURST_WINDOW_SECONDS \
    -e INGEST_BURST_PER_IP \
    -e INGEST_BURST_PER_GID \
    -e FETCH_BURST_PER_IP \
    -e FETCH_BURST_PER_GID \
    -e TRUSTED_PROXY_IPS \
    -e IP_ALLOWLIST \
    -e IP_DENYLIST \
    -e FEDERATION_PEER_ALLOWLIST \
    -e REDIS_URL \
    "${RELAY_CONTAINER}" node /app/scripts/test-security.js
else
  (cd "${repo_root}/server" && RELAY_BASE_URL="${RELAY_BASE_URL}" node -e "${health_check_cmd}")
  (cd "${repo_root}/server" && RELAY_BASE_URL="${RELAY_BASE_URL}" npm run test:security)
fi
