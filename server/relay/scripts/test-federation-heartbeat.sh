#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-heartbeat.sh \\
    --server http://localhost:8080 \\
    --peer-url http://relay_2:8080 \\
    --rotated-token <rotated_token> \\
    --self-id relay-2
EOF
}

SERVER=""
PEER_URL=""
ROTATED_TOKEN=""
SELF_ID=""

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --peer-url) PEER_URL="$2"; shift 2 ;;
    --rotated-token) ROTATED_TOKEN="$2"; shift 2 ;;
    --self-id) SELF_ID="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$SERVER" ] || [ -z "$PEER_URL" ] || [ -z "$ROTATED_TOKEN" ] || [ -z "$SELF_ID" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

python_out=$(python3 - "$PEER_URL" "$SELF_ID" "$ROTATED_TOKEN" <<'PY'
import json, hmac, hashlib, sys, time, uuid
peer_url = sys.argv[1]
self_id = sys.argv[2]
token = sys.argv[3]
timestamp = str(int(time.time()))
nonce = str(uuid.uuid4())
payload = {"peer_url": peer_url, "self_id": self_id}
raw = json.dumps(payload, separators=(",", ":"))
canonical = f"HEARTBEAT\n{timestamp}\n{nonce}\n{raw}"
sig = hmac.new(token.encode(), canonical.encode(), hashlib.sha256).hexdigest()
print(raw)
print(sig)
print(timestamp)
print(nonce)
PY
)

BODY=$(printf "%s" "$python_out" | head -n1)
SIGNATURE=$(printf "%s" "$python_out" | sed -n '2p')
TIMESTAMP=$(printf "%s" "$python_out" | sed -n '3p')
NONCE=$(printf "%s" "$python_out" | sed -n '4p')

resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER}/v1/peers:heartbeat" \
  -H "Content-Type: application/json" \
  -H "X-Relay-Join-Signature: ${SIGNATURE}" \
  -H "X-Relay-Join-Timestamp: ${TIMESTAMP}" \
  -H "X-Relay-Join-Nonce: ${NONCE}" \
  --data-binary "${BODY}")
resp_body=$(printf "%s" "$resp" | sed '$d')
resp_code=$(printf "%s" "$resp" | tail -n 1)
echo "Heartbeat status: ${resp_code}"
echo "Heartbeat body: ${resp_body}"

if [ "$resp_code" != "200" ]; then
  echo "Heartbeat failed." >&2
  exit 1
fi
