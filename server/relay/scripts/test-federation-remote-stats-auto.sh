#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-remote-stats-auto.sh \\
    --server http://localhost:8080 \\
    --peer-url http://relay_2:8080 \\
    --public-token dev-public-token \\
    --self-id relay-2 \\
    [--expect-status 200]
EOF
}

SERVER=""
PEER_URL=""
PUBLIC_TOKEN=""
SELF_ID=""
EXPECT_STATUS="200"

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --peer-url) PEER_URL="$2"; shift 2 ;;
    --public-token) PUBLIC_TOKEN="$2"; shift 2 ;;
    --self-id) SELF_ID="$2"; shift 2 ;;
    --expect-status) EXPECT_STATUS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$SERVER" ] || [ -z "$PEER_URL" ] || [ -z "$PUBLIC_TOKEN" ] || [ -z "$SELF_ID" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

create_payload() {
  local prefix=$1
  local timestamp=$2
  local nonce=$3
  local python_out
  python_out=$(python3 - "$PEER_URL" "$SELF_ID" "$PUBLIC_TOKEN" "$prefix" "$timestamp" "$nonce" <<'PY'
import json, hmac, hashlib, sys
peer_url = sys.argv[1]
self_id = sys.argv[2]
token = sys.argv[3]
prefix = sys.argv[4]
timestamp = sys.argv[5]
nonce = sys.argv[6]
payload = {"peer_url": peer_url, "self_id": self_id}
raw = json.dumps(payload, separators=(",", ":"))
canonical = f"{prefix}\n{timestamp}\n{nonce}\n{raw}"
sig = hmac.new(token.encode(), canonical.encode(), hashlib.sha256).hexdigest()
print(raw)
print(sig)
PY
  )
  BODY=$(printf "%s" "$python_out" | head -n1)
  SIGNATURE=$(printf "%s" "$python_out" | tail -n1)
}

TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "JOIN" "$TIMESTAMP" "$NONCE"
join_resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER}/v1/peers:join" \
  -H "Content-Type: application/json" \
  -H "X-Relay-Join-Signature: ${SIGNATURE}" \
  -H "X-Relay-Join-Timestamp: ${TIMESTAMP}" \
  -H "X-Relay-Join-Nonce: ${NONCE}" \
  --data-binary "${BODY}")
join_body=$(printf "%s" "$join_resp" | sed '$d')
join_code=$(printf "%s" "$join_resp" | tail -n 1)
echo "Join status: ${join_code}"
echo "Join body: ${join_body}"
if [ "$join_code" != "200" ]; then
  echo "Join failed." >&2
  exit 1
fi

TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "HANDSHAKE" "$TIMESTAMP" "$NONCE"
handshake_resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER}/v1/peers:handshake" \
  -H "Content-Type: application/json" \
  -H "X-Relay-Join-Signature: ${SIGNATURE}" \
  -H "X-Relay-Join-Timestamp: ${TIMESTAMP}" \
  -H "X-Relay-Join-Nonce: ${NONCE}" \
  --data-binary "${BODY}")
handshake_body=$(printf "%s" "$handshake_resp" | sed '$d')
handshake_code=$(printf "%s" "$handshake_resp" | tail -n 1)
echo "Handshake status: ${handshake_code}"
echo "Handshake body: ${handshake_body}"
if [ "$handshake_code" != "200" ]; then
  echo "Handshake failed." >&2
  exit 1
fi

rotated_token=$(python3 - "$handshake_body" <<'PY'
import json, sys
try:
  data = json.loads(sys.argv[1])
  token = data.get("rotated_token") or ""
  print(token)
except Exception:
  print("")
PY
)

if [ -z "$rotated_token" ]; then
  echo "Missing rotated_token." >&2
  exit 1
fi

stats_resp=$(curl -s -w "\n%{http_code}" -X GET "${SERVER}/v1/peers:stats" \
  -H "x-relay-peer: 1" \
  -H "x-relay-from: ${SELF_ID}" \
  -H "x-relay-peer-token: ${rotated_token}")
stats_body=$(printf "%s" "$stats_resp" | sed '$d')
stats_code=$(printf "%s" "$stats_resp" | tail -n 1)
echo "Remote stats status: ${stats_code}"
echo "Remote stats body: ${stats_body}"

if [ "$stats_code" != "$EXPECT_STATUS" ]; then
  echo "Remote stats failed." >&2
  exit 1
fi

python3 - "$stats_body" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
missing = []
for key in ("connected", "capacity_limit", "heartbeat_ttl_seconds"):
    if key not in data:
        missing.append(key)
if missing:
    print("Missing keys:", ", ".join(missing))
    sys.exit(1)
print("Remote stats keys present:", "connected, capacity_limit, heartbeat_ttl_seconds")
PY
