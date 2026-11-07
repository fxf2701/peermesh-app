#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-peers-stats.sh \\
    --server http://localhost:8080 \\
    --username admin \\
    --password admin1234
EOF
}

SERVER=""
USERNAME=""
PASSWORD=""

while [ $# -gt 0 ]; do
  case "$1" in
    --server)
      SERVER="$2"
      shift 2
      ;;
    --username)
      USERNAME="$2"
      shift 2
      ;;
    --password)
      PASSWORD="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ -z "$SERVER" ] || [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

COOKIE_JAR="$(mktemp)"
cleanup() {
  rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

login_payload="{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}"
curl -s -X POST "${SERVER}/admin/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "${login_payload}" \
  -c "${COOKIE_JAR}" >/dev/null || true

resp="$(curl -s -w "\n%{http_code}" "${SERVER}/admin/api/peers" -b "${COOKIE_JAR}" || true)"
body="$(printf "%s" "$resp" | sed '$d')"
code="$(printf "%s" "$resp" | tail -n 1)"
echo "Peers API status: ${code}"
echo "Peers API body: ${body}"

if [ "$code" != "200" ]; then
  echo "Peers API failed." >&2
  exit 1
fi

connected=0
capacity="--"
ttl="--"
if [ -n "$body" ]; then
  stats_line=$(PEERS_BODY="$body" python3 - <<'PY'
import json, os
payload = json.loads(os.environ["PEERS_BODY"])
stats = payload.get("meta", {}).get("stats", {})
print(
    stats.get("connected", 0),
    stats.get("capacity_limit", "--"),
    stats.get("heartbeat_ttl_seconds", "--"),
    stats.get("capacity_warning", False),
    stats.get("auto_sync_enabled", False),
)
PY
  )
  set -- $stats_line
  connected="${1:-0}"
  capacity="${2:---}"
  ttl="${3:---}"
  capacity_warning="${4:-false}"
  auto_sync_enabled="${5:-false}"
fi
echo "Connected: ${connected}, Capacity: ${capacity}, Heartbeat TTL: ${ttl}s"
echo "Capacity warning: ${capacity_warning}, Auto-sync enabled: ${auto_sync_enabled}"
