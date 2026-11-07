#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-heartbeat-job.sh \\
    --receiver http://localhost:8081 \\
    --username admin \\
    --password admin1234 \\
    --peer-id relay-2 \\
    [--wait-seconds 15]

Notes:
  - The sender relay must already have a joined+enabled mapping to the receiver.
  - The sender must be running the heartbeat job.
EOF
}

RECEIVER=""
USERNAME=""
PASSWORD=""
PEER_ID=""
WAIT_SECONDS="15"

while [ $# -gt 0 ]; do
  case "$1" in
    --receiver) RECEIVER="$2"; shift 2 ;;
    --username) USERNAME="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --peer-id) PEER_ID="$2"; shift 2 ;;
    --wait-seconds) WAIT_SECONDS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$RECEIVER" ] || [ -z "$USERNAME" ] || [ -z "$PASSWORD" ] || [ -z "$PEER_ID" ]; then
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
login_resp=$(curl -s -w "\n%{http_code}" -X POST "${RECEIVER}/admin/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "${login_payload}" \
  -c "${COOKIE_JAR}")
login_body=$(printf "%s" "$login_resp" | sed '$d')
login_code=$(printf "%s" "$login_resp" | tail -n 1)
echo "Login status: ${login_code}"
echo "Login body: ${login_body}"
if [ "$login_code" != "200" ]; then
  echo "Login failed." >&2
  exit 1
fi

fetch_inbound() {
  curl -s -X GET "${RECEIVER}/admin/api/peers/inbound" -b "${COOKIE_JAR}"
}

extract_seen() {
  python3 - "$1" "$PEER_ID" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
peer_id = sys.argv[2]
for item in data.get("items", []):
    if item.get("self_id") == peer_id:
        print(item.get("last_seen_at") or "")
        sys.exit(0)
print("")
PY
}

inbound_before="$(fetch_inbound)"
seen_before="$(extract_seen "$inbound_before")"
echo "Inbound before: ${inbound_before}"
echo "Last seen before: ${seen_before:-<missing>}"

echo "Waiting ${WAIT_SECONDS}s for heartbeat job..."
sleep "${WAIT_SECONDS}"

inbound_after="$(fetch_inbound)"
seen_after="$(extract_seen "$inbound_after")"
echo "Inbound after: ${inbound_after}"
echo "Last seen after: ${seen_after:-<missing>}"

if [ -z "$seen_after" ]; then
  echo "Heartbeat job failed: peer not found." >&2
  exit 1
fi
if [ "$seen_before" = "$seen_after" ]; then
  echo "Heartbeat job failed: last_seen_at did not change." >&2
  exit 1
fi

echo "Heartbeat job ok: last_seen_at updated."
