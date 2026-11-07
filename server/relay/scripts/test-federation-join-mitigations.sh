#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-join-mitigations.sh \\
    --server http://localhost:8080 \\
    --peer-url http://relay_2:8080 \\
    --public-token dev-public-token \\
    --self-id relay-2 \\
    [--second-self-id relay-3] \\
    [--join-attempts 3] \\
    [--handshake-attempts 3]
EOF
}

SERVER=""
PEER_URL=""
PUBLIC_TOKEN=""
SELF_ID=""
SECOND_SELF_ID=""
JOIN_ATTEMPTS=3
HANDSHAKE_ATTEMPTS=3

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --peer-url) PEER_URL="$2"; shift 2 ;;
    --public-token) PUBLIC_TOKEN="$2"; shift 2 ;;
    --self-id) SELF_ID="$2"; shift 2 ;;
    --second-self-id) SECOND_SELF_ID="$2"; shift 2 ;;
    --join-attempts) JOIN_ATTEMPTS="$2"; shift 2 ;;
    --handshake-attempts) HANDSHAKE_ATTEMPTS="$2"; shift 2 ;;
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
  local peer_id=$2
  local ts nonce output
  ts=$(date +%s)
  nonce=$(uuidgen)
  output=$(python3 - "$PEER_URL" "$peer_id" "$PUBLIC_TOKEN" "$prefix" "$ts" "$nonce" <<'PY'
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
  BODY=$(printf "%s" "$output" | head -n1)
  SIGNATURE=$(printf "%s" "$output" | tail -n1)
  TIMESTAMP="$ts"
  NONCE="$nonce"
}

post_signed() {
  local endpoint=$1
  local body=$2
  local sig=$3
  local ts=$4
  local nonce=$5
  curl -s -w "\n%{http_code}" -X POST "${SERVER}${endpoint}" \
    -H "Content-Type: application/json" \
    -H "X-Relay-Join-Signature: ${sig}" \
    -H "X-Relay-Join-Timestamp: ${ts}" \
    -H "X-Relay-Join-Nonce: ${nonce}" \
    --data-binary "${body}"
}

echo "=== Join rate-limit test (${JOIN_ATTEMPTS} attempts) ==="
for i in $(seq 1 "$JOIN_ATTEMPTS"); do
  create_payload "JOIN" "$SELF_ID"
  resp="$(post_signed "/v1/peers:join" "$BODY" "$SIGNATURE" "$TIMESTAMP" "$NONCE")"
  body="$(printf "%s" "$resp" | sed '$d')"
  code="$(printf "%s" "$resp" | tail -n 1)"
  echo "Join attempt ${i}: status=${code} body=${body}"
done

echo "=== Handshake rate-limit test (${HANDSHAKE_ATTEMPTS} attempts) ==="
for i in $(seq 1 "$HANDSHAKE_ATTEMPTS"); do
  create_payload "HANDSHAKE" "$SELF_ID"
  resp="$(post_signed "/v1/peers:handshake" "$BODY" "$SIGNATURE" "$TIMESTAMP" "$NONCE")"
  body="$(printf "%s" "$resp" | sed '$d')"
  code="$(printf "%s" "$resp" | tail -n 1)"
  echo "Handshake attempt ${i}: status=${code} body=${body}"
done

if [ -n "$SECOND_SELF_ID" ]; then
  echo "=== Capacity test with second peer (${SECOND_SELF_ID}) ==="
  create_payload "JOIN" "$SECOND_SELF_ID"
  resp="$(post_signed "/v1/peers:join" "$BODY" "$SIGNATURE" "$TIMESTAMP" "$NONCE")"
  body="$(printf "%s" "$resp" | sed '$d')"
  code="$(printf "%s" "$resp" | tail -n 1)"
  echo "Capacity test join (second peer): status=${code} body=${body}"
fi
