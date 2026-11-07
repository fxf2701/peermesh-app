#!/usr/bin/env bash
set -euo pipefail

RELAY_CONTAINER="${RELAY_CONTAINER:-disaster-app-relay}"
RELAY_BASE_URL="${RELAY_BASE_URL:-http://localhost:8080}"
USE_ENV_DEV="${USE_ENV_DEV:-0}"
HEALTH_CHECK_TIMEOUT_SECONDS="${HEALTH_CHECK_TIMEOUT_SECONDS:-15}"
METRICS_TEST_TIMEOUT_SECONDS="${METRICS_TEST_TIMEOUT_SECONDS:-15}"
METRICS_TEST_USE_HOST="${METRICS_TEST_USE_HOST:-}"
METRICS_TEST_TIMEOUT_MS=$((METRICS_TEST_TIMEOUT_SECONDS * 1000 - 1000))
if [ "${METRICS_TEST_TIMEOUT_MS}" -lt 1000 ]; then
  METRICS_TEST_TIMEOUT_MS=1000
fi

cleanup() {
  exit 130
}

trap cleanup INT

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/../.." && pwd)"
env_file="${repo_root}/server/.env"

if [ "$USE_ENV_DEV" = "1" ] && [ -f "${repo_root}/server/.env.dev" ]; then
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

container_running() {
  if command -v rg >/dev/null 2>&1; then
    docker ps --format '{{.Names}}' | rg -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  else
    docker ps --format '{{.Names}}' | grep -x "${RELAY_CONTAINER}" >/dev/null 2>&1
  fi
}

if command -v docker >/dev/null 2>&1 && container_running; then
  while IFS= read -r line; do
    case "${line}" in
      REQUEST_SIGNATURE_ENABLED=*)
        REQUEST_SIGNATURE_ENABLED="${line#*=}"
        ;;
      REQUEST_SIGNATURE_SECRET=*)
        REQUEST_SIGNATURE_SECRET="${line#*=}"
        ;;
    esac
  done < <(docker exec "${RELAY_CONTAINER}" env 2>/dev/null || true)
  export REQUEST_SIGNATURE_ENABLED REQUEST_SIGNATURE_SECRET
fi

echo "starting health check (${RELAY_BASE_URL}/healthz)"
health_check_cmd='const http=require("http");const crypto=require("crypto");const url=new URL(process.env.RELAY_BASE_URL||"http://localhost:8080");const maxRetries=Number(process.env.HEALTH_CHECK_RETRIES||"5");const baseDelayMs=Number(process.env.HEALTH_CHECK_BASE_DELAY_MS||"500");const secret=process.env.REQUEST_SIGNATURE_SECRET||"";const enabled=process.env.REQUEST_SIGNATURE_ENABLED==="1"&&secret;const debug=process.env.SIGNATURE_DEBUG==="1";function headersFor(path){const ts=String(Math.floor(Date.now()/1000));const sig=enabled?crypto.createHmac("sha256",secret).update(["GET",path,ts,""].join("\n")).digest("hex"):"";if(debug){console.log("signature_debug",JSON.stringify({enabled:!!enabled,secret_len:secret.length,timestamp:ts,signature:enabled?sig.slice(0,8)+"...":""}));}if(!enabled)return{};return{"x-relay-signature":sig,"x-relay-timestamp":ts};}let attempt=0;function check(){attempt+=1;const path="/healthz";const req=http.request({hostname:url.hostname,port:url.port,path,method:"GET",headers:headersFor(path)},res=>{let body="";res.on("data",c=>body+=c);res.on("end",()=>{if(res.statusCode===200){console.log("Health check ok");return;}if(attempt>=maxRetries){console.error("Health check failed:",res.statusCode,body);process.exit(1);}const delay=baseDelayMs*Math.pow(2,attempt-1);console.error("Health check failed:",res.statusCode,body,"retrying in",delay+"ms");setTimeout(check,delay);});});req.on("error",err=>{if(attempt>=maxRetries){console.error("Health check error:",err.message||String(err));process.exit(1);}const delay=baseDelayMs*Math.pow(2,attempt-1);console.error("Health check error:",err.message||String(err),"retrying in",delay+"ms");setTimeout(check,delay);});req.end();}check();'

if [ -z "${METRICS_TEST_USE_HOST}" ]; then
  case "${RELAY_BASE_URL}" in
    http://localhost:*|http://127.0.0.1:*|https://localhost:*|https://127.0.0.1:*)
      METRICS_TEST_USE_HOST=1
      ;;
    *)
      METRICS_TEST_USE_HOST=0
      ;;
  esac
fi

if [ "${METRICS_TEST_USE_HOST}" = "1" ]; then
  if ! (cd "${repo_root}/server" && env RELAY_BASE_URL="${RELAY_BASE_URL}" REQUEST_SIGNATURE_ENABLED="${REQUEST_SIGNATURE_ENABLED:-}" REQUEST_SIGNATURE_SECRET="${REQUEST_SIGNATURE_SECRET:-}" timeout "${HEALTH_CHECK_TIMEOUT_SECONDS}" node -e "${health_check_cmd}"); then
    echo "health check timed out after ${HEALTH_CHECK_TIMEOUT_SECONDS}s (${RELAY_BASE_URL}/healthz)"
    exit 1
  fi
  echo "running metrics test (${RELAY_BASE_URL}/metrics)"
  if ! (cd "${repo_root}/server" && env RELAY_BASE_URL="${RELAY_BASE_URL}" REQUEST_SIGNATURE_ENABLED="${REQUEST_SIGNATURE_ENABLED:-}" REQUEST_SIGNATURE_SECRET="${REQUEST_SIGNATURE_SECRET:-}" SIGNATURE_DEBUG="${SIGNATURE_DEBUG:-}" METRICS_TEST_TIMEOUT_MS="${METRICS_TEST_TIMEOUT_MS}" timeout "${METRICS_TEST_TIMEOUT_SECONDS}" npm run test:metrics); then
    echo "metrics test timed out after ${METRICS_TEST_TIMEOUT_SECONDS}s (${RELAY_BASE_URL}/metrics)"
    exit 1
  fi
elif command -v docker >/dev/null 2>&1 && container_running; then
  exec_env=()
  if [ -n "${REQUEST_SIGNATURE_ENABLED:-}" ]; then
    exec_env+=(-e REQUEST_SIGNATURE_ENABLED)
  fi
  if [ -n "${REQUEST_SIGNATURE_SECRET:-}" ]; then
    exec_env+=(-e REQUEST_SIGNATURE_SECRET)
  fi
  if ! timeout "${HEALTH_CHECK_TIMEOUT_SECONDS}" \
    docker exec "${exec_env[@]}" -e RELAY_BASE_URL="${RELAY_BASE_URL}" -e SIGNATURE_DEBUG="${SIGNATURE_DEBUG:-}" \
    "${RELAY_CONTAINER}" node -e "${health_check_cmd}"; then
    echo "health check timed out after ${HEALTH_CHECK_TIMEOUT_SECONDS}s (${RELAY_BASE_URL}/healthz)"
    exit 1
  fi
  echo "running metrics test (${RELAY_BASE_URL}/metrics)"
  if ! timeout "${METRICS_TEST_TIMEOUT_SECONDS}" \
    docker exec "${exec_env[@]}" -e RELAY_BASE_URL="${RELAY_BASE_URL}" -e SIGNATURE_DEBUG="${SIGNATURE_DEBUG:-}" \
    -e METRICS_TEST_TIMEOUT_MS="${METRICS_TEST_TIMEOUT_MS}" \
    "${RELAY_CONTAINER}" node /app/scripts/test-metrics.js; then
    echo "metrics test timed out after ${METRICS_TEST_TIMEOUT_SECONDS}s (${RELAY_BASE_URL}/metrics)"
    exit 1
  fi
else
  if ! (cd "${repo_root}/server" && timeout "${HEALTH_CHECK_TIMEOUT_SECONDS}" RELAY_BASE_URL="${RELAY_BASE_URL}" REQUEST_SIGNATURE_ENABLED="${REQUEST_SIGNATURE_ENABLED:-}" REQUEST_SIGNATURE_SECRET="${REQUEST_SIGNATURE_SECRET:-}" SIGNATURE_DEBUG="${SIGNATURE_DEBUG:-}" node -e "${health_check_cmd}"); then
    echo "health check timed out after ${HEALTH_CHECK_TIMEOUT_SECONDS}s (${RELAY_BASE_URL}/healthz)"
    exit 1
  fi
  echo "running metrics test (${RELAY_BASE_URL}/metrics)"
  if ! (cd "${repo_root}/server" && timeout "${METRICS_TEST_TIMEOUT_SECONDS}" RELAY_BASE_URL="${RELAY_BASE_URL}" REQUEST_SIGNATURE_ENABLED="${REQUEST_SIGNATURE_ENABLED:-}" REQUEST_SIGNATURE_SECRET="${REQUEST_SIGNATURE_SECRET:-}" SIGNATURE_DEBUG="${SIGNATURE_DEBUG:-}" METRICS_TEST_TIMEOUT_MS="$((${METRICS_TEST_TIMEOUT_SECONDS} * 1000))" npm run test:metrics); then
    echo "metrics test timed out after ${METRICS_TEST_TIMEOUT_SECONDS}s (${RELAY_BASE_URL}/metrics)"
    exit 1
  fi
fi
