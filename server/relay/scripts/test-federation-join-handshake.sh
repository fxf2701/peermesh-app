#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-join-handshake.sh \\
    --server http://localhost:8080 \\
    --peer-url http://relay_2:8080 \\
    --public-token dev-public-token \\
    --self-id relay-2
EOF
}

SERVER=""
PEER_URL=""
PUBLIC_TOKEN=""
SELF_ID=""

while [ $# -gt 0 ]; do
  case "$1" in
    --server)
      SERVER="$2"
      shift 2
      ;;
    --peer-url)
      PEER_URL="$2"
      shift 2
      ;;
    --public-token)
      PUBLIC_TOKEN="$2"
      shift 2
      ;;
    --self-id)
      SELF_ID="$2"
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

if [ -z "$SERVER" ] || [ -z "$PEER_URL" ] || [ -z "$PUBLIC_TOKEN" ] || [ -z "$SELF_ID" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

create_payload() {
  local prefix=$1
  local timestamp=$2
  local nonce=$3
  local python_output
  python_output=$(python3 - "$PEER_URL" "$SELF_ID" "$PUBLIC_TOKEN" "$prefix" "$timestamp" "$nonce" <<'PY'
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
  BODY=$(printf "%s" "$python_output" | head -n1)
  SIGNATURE=$(printf "%s" "$python_output" | tail -n1)
}

send_request() {
  local endpoint=$1
  local signature=$2
  local timestamp=$3
  local nonce=$4
  local body=$5
  curl -s -w "\n%{http_code}" -X POST "${SERVER}${endpoint}" \
    -H "Content-Type: application/json" \
    -H "X-Relay-Join-Signature: ${signature}" \
    -H "X-Relay-Join-Timestamp: ${timestamp}" \
    -H "X-Relay-Join-Nonce: ${nonce}" \
    --data-binary "${body}"
}

BODY=$(printf '{"peer_url":"%s","self_id":"%s"}' "$PEER_URL" "$SELF_ID")
TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "JOIN" "$TIMESTAMP" "$NONCE"
SIG="$SIGNATURE"
join_resp=$(send_request "/v1/peers:join" "$SIG" "$TIMESTAMP" "$NONCE" "$BODY")
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
SIG="$SIGNATURE"
handshake_resp=$(send_request "/v1/peers:handshake" "$SIG" "$TIMESTAMP" "$NONCE" "$BODY")
handshake_body=$(printf "%s" "$handshake_resp" | sed '$d')
handshake_code=$(printf "%s" "$handshake_resp" | tail -n 1)
echo "Handshake status: ${handshake_code}"
echo "Handshake body: ${handshake_body}"

if [ "$handshake_code" != "200" ]; then
  echo "Handshake failed." >&2
  exit 1
fi
