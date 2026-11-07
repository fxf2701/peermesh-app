#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-reciprocal-flow.sh \\
    --server-a http://localhost:8080 \\
    --server-b http://localhost:8081 \\
    --peer-url-a http://relay:8080 \\
    --peer-url-b http://relay_2:8080 \\
    --public-token-b dev-public-token \\
    --self-id-a relay \\
    --self-id-b relay-2 \\
    [--public-token-a dev-public-token] \\
    [--username admin --password admin1234]
EOF
}

SERVER_A=""
SERVER_B=""
PEER_URL_A=""
PEER_URL_B=""
PUBLIC_TOKEN_A=""
PUBLIC_TOKEN_B=""
SELF_ID_A=""
SELF_ID_B=""
USERNAME=""
PASSWORD=""

while [ $# -gt 0 ]; do
  case "$1" in
    --server-a) SERVER_A="$2"; shift 2 ;;
    --server-b) SERVER_B="$2"; shift 2 ;;
    --peer-url-a) PEER_URL_A="$2"; shift 2 ;;
    --peer-url-b) PEER_URL_B="$2"; shift 2 ;;
    --public-token-a) PUBLIC_TOKEN_A="$2"; shift 2 ;;
    --public-token-b) PUBLIC_TOKEN_B="$2"; shift 2 ;;
    --self-id-a) SELF_ID_A="$2"; shift 2 ;;
    --self-id-b) SELF_ID_B="$2"; shift 2 ;;
    --username) USERNAME="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$SERVER_A" ] || [ -z "$SERVER_B" ] || [ -z "$PEER_URL_A" ] || [ -z "$PEER_URL_B" ] \
  || [ -z "$PUBLIC_TOKEN_B" ] || [ -z "$SELF_ID_A" ] || [ -z "$SELF_ID_B" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

create_payload() {
  local prefix=$1
  local token=$2
  local peer_url=$3
  local self_id=$4
  local timestamp=$5
  local nonce=$6
  local python_output
  python_output=$(python3 - "$peer_url" "$self_id" "$token" "$prefix" "$timestamp" "$nonce" <<'PY'
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
  local server=$1
  local endpoint=$2
  local signature=$3
  local timestamp=$4
  local nonce=$5
  local body=$6
  curl -s -w "\n%{http_code}" -X POST "${server}${endpoint}" \
    -H "Content-Type: application/json" \
    -H "X-Relay-Join-Signature: ${signature}" \
    -H "X-Relay-Join-Timestamp: ${timestamp}" \
    -H "X-Relay-Join-Nonce: ${nonce}" \
    --data-binary "${body}"
}

json_get() {
  python3 - "$1" "$2" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
key = sys.argv[2]
value = data.get(key)
print("" if value is None else value)
PY
}

echo "=== Step 1: A -> B join + handshake ==="
TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "JOIN" "$PUBLIC_TOKEN_B" "$PEER_URL_A" "$SELF_ID_A" "$TIMESTAMP" "$NONCE"
join_resp=$(send_request "$SERVER_B" "/v1/peers:join" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
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
create_payload "HANDSHAKE" "$PUBLIC_TOKEN_B" "$PEER_URL_A" "$SELF_ID_A" "$TIMESTAMP" "$NONCE"
handshake_resp=$(send_request "$SERVER_B" "/v1/peers:handshake" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
handshake_body=$(printf "%s" "$handshake_resp" | sed '$d')
handshake_code=$(printf "%s" "$handshake_resp" | tail -n 1)
echo "Handshake status: ${handshake_code}"
echo "Handshake body: ${handshake_body}"
if [ "$handshake_code" != "200" ]; then
  echo "Handshake failed." >&2
  exit 1
fi

ROTATED_TOKEN_AB=$(json_get "$handshake_body" "rotated_token")
JOIN_TOKEN_AB=$(json_get "$handshake_body" "join_token")

if [ -z "$ROTATED_TOKEN_AB" ]; then
  echo "Missing rotated_token from B." >&2
  exit 1
fi
if [ -z "$JOIN_TOKEN_AB" ]; then
  echo "Missing join_token from B." >&2
  exit 1
fi

echo "=== Step 2: B -> A join + handshake ==="
TOKEN_FOR_A="$PUBLIC_TOKEN_A"
if [ -z "$TOKEN_FOR_A" ]; then
  TOKEN_FOR_A="$JOIN_TOKEN_AB"
  echo "Using join_token for A (no public-token-a provided)."
fi

TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "JOIN" "$TOKEN_FOR_A" "$PEER_URL_B" "$SELF_ID_B" "$TIMESTAMP" "$NONCE"
join_back_resp=$(send_request "$SERVER_A" "/v1/peers:join" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
join_back_body=$(printf "%s" "$join_back_resp" | sed '$d')
join_back_code=$(printf "%s" "$join_back_resp" | tail -n 1)
echo "Join back status: ${join_back_code}"
echo "Join back body: ${join_back_body}"
if [ "$join_back_code" != "200" ]; then
  echo "Join back failed." >&2
  exit 1
fi

TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "HANDSHAKE" "$TOKEN_FOR_A" "$PEER_URL_B" "$SELF_ID_B" "$TIMESTAMP" "$NONCE"
handshake_back_resp=$(send_request "$SERVER_A" "/v1/peers:handshake" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
handshake_back_body=$(printf "%s" "$handshake_back_resp" | sed '$d')
handshake_back_code=$(printf "%s" "$handshake_back_resp" | tail -n 1)
echo "Handshake back status: ${handshake_back_code}"
echo "Handshake back body: ${handshake_back_body}"
if [ "$handshake_back_code" != "200" ]; then
  echo "Handshake back failed." >&2
  exit 1
fi

ROTATED_TOKEN_BA=$(json_get "$handshake_back_body" "rotated_token")
if [ -z "$ROTATED_TOKEN_BA" ]; then
  echo "Missing rotated_token from A." >&2
  exit 1
fi

echo "=== Step 3: Heartbeat A -> B ==="
TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "HEARTBEAT" "$ROTATED_TOKEN_AB" "$PEER_URL_A" "$SELF_ID_A" "$TIMESTAMP" "$NONCE"
heartbeat_resp=$(send_request "$SERVER_B" "/v1/peers:heartbeat" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
heartbeat_body=$(printf "%s" "$heartbeat_resp" | sed '$d')
heartbeat_code=$(printf "%s" "$heartbeat_resp" | tail -n 1)
echo "Heartbeat A->B status: ${heartbeat_code}"
echo "Heartbeat A->B body: ${heartbeat_body}"
if [ "$heartbeat_code" != "200" ]; then
  echo "Heartbeat A->B failed." >&2
  exit 1
fi

echo "=== Step 4: Heartbeat B -> A ==="
TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "HEARTBEAT" "$ROTATED_TOKEN_BA" "$PEER_URL_B" "$SELF_ID_B" "$TIMESTAMP" "$NONCE"
heartbeat_back_resp=$(send_request "$SERVER_A" "/v1/peers:heartbeat" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
heartbeat_back_body=$(printf "%s" "$heartbeat_back_resp" | sed '$d')
heartbeat_back_code=$(printf "%s" "$heartbeat_back_resp" | tail -n 1)
echo "Heartbeat B->A status: ${heartbeat_back_code}"
echo "Heartbeat B->A body: ${heartbeat_back_body}"
if [ "$heartbeat_back_code" != "200" ]; then
  echo "Heartbeat B->A failed." >&2
  exit 1
fi

if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
  echo "=== Inbound peers on A ==="
  ./server/relay/scripts/test-federation-inbound-peers.sh --server "$SERVER_A" --username "$USERNAME" --password "$PASSWORD"
  echo "=== Inbound peers on B ==="
  ./server/relay/scripts/test-federation-inbound-peers.sh --server "$SERVER_B" --username "$USERNAME" --password "$PASSWORD"
fi
