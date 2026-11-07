#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-join-token-flow.sh \\
    --server-a http://localhost:8080 \\
    --server-b http://localhost:8081 \\
    --peer-url-a http://relay:8080 \\
    --peer-url-b http://relay_2:8080 \\
    --public-token-b dev-public-token \\
    --self-id-a relay \\
    --self-id-b relay-2 \\
    [--join-back-token <token>] \\
    [--wait-seconds 90 --container-b disaster-app-relay_2] \\
    [--username admin --password admin1234]
EOF
}

SERVER_A=""
SERVER_B=""
PEER_URL_A=""
PEER_URL_B=""
PUBLIC_TOKEN_B=""
SELF_ID_A=""
SELF_ID_B=""
JOIN_BACK_TOKEN=""
WAIT_SECONDS=""
CONTAINER_B=""
USERNAME=""
PASSWORD=""

while [ $# -gt 0 ]; do
  case "$1" in
    --server-a) SERVER_A="$2"; shift 2 ;;
    --server-b) SERVER_B="$2"; shift 2 ;;
    --peer-url-a) PEER_URL_A="$2"; shift 2 ;;
    --peer-url-b) PEER_URL_B="$2"; shift 2 ;;
    --public-token-b) PUBLIC_TOKEN_B="$2"; shift 2 ;;
    --self-id-a) SELF_ID_A="$2"; shift 2 ;;
    --self-id-b) SELF_ID_B="$2"; shift 2 ;;
    --join-back-token) JOIN_BACK_TOKEN="$2"; shift 2 ;;
    --wait-seconds) WAIT_SECONDS="$2"; shift 2 ;;
    --container-b) CONTAINER_B="$2"; shift 2 ;;
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
  local peer_join_token=$5
  local timestamp=$6
  local nonce=$7
  local python_output
  python_output=$(python3 - "$peer_url" "$self_id" "$token" "$prefix" "$timestamp" "$nonce" "$peer_join_token" <<'PY'
import json, hmac, hashlib, sys
peer_url = sys.argv[1]
self_id = sys.argv[2]
token = sys.argv[3]
prefix = sys.argv[4]
timestamp = sys.argv[5]
nonce = sys.argv[6]
peer_join_token = sys.argv[7]
payload = {"peer_url": peer_url, "self_id": self_id}
if peer_join_token:
    payload["peer_join_token"] = peer_join_token
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
PEER_JOIN_TOKEN=$(uuidgen)
echo "Using peer_join_token from A: ${PEER_JOIN_TOKEN}"
TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "JOIN" "$PUBLIC_TOKEN_B" "$PEER_URL_A" "$SELF_ID_A" "$PEER_JOIN_TOKEN" "$TIMESTAMP" "$NONCE"
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
create_payload "HANDSHAKE" "$PUBLIC_TOKEN_B" "$PEER_URL_A" "$SELF_ID_A" "$PEER_JOIN_TOKEN" "$TIMESTAMP" "$NONCE"
handshake_resp=$(send_request "$SERVER_B" "/v1/peers:handshake" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
handshake_body=$(printf "%s" "$handshake_resp" | sed '$d')
handshake_code=$(printf "%s" "$handshake_resp" | tail -n 1)
echo "Handshake status: ${handshake_code}"
echo "Handshake body: ${handshake_body}"
if [ "$handshake_code" != "200" ]; then
  echo "Handshake failed." >&2
  exit 1
fi

echo "=== Step 2: A -> B join with peer_join_token (store outbound token on B) ==="
TIMESTAMP=$(date +%s)
NONCE=$(uuidgen)
create_payload "JOIN" "$PUBLIC_TOKEN_B" "$PEER_URL_A" "$SELF_ID_A" "$PEER_JOIN_TOKEN" "$TIMESTAMP" "$NONCE"
join2_resp=$(send_request "$SERVER_B" "/v1/peers:join" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
join2_body=$(printf "%s" "$join2_resp" | sed '$d')
join2_code=$(printf "%s" "$join2_resp" | tail -n 1)
echo "Join (peer_join_token) status: ${join2_code}"
echo "Join (peer_join_token) body: ${join2_body}"
if [ "$join2_code" != "200" ]; then
  echo "Join with peer_join_token failed." >&2
  exit 1
fi

if [ -n "$JOIN_BACK_TOKEN" ]; then
  echo "=== Step 3: B -> A join + handshake using provided join-back token ==="
  TIMESTAMP=$(date +%s)
  NONCE=$(uuidgen)
  create_payload "JOIN" "$JOIN_BACK_TOKEN" "$PEER_URL_B" "$SELF_ID_B" "" "$TIMESTAMP" "$NONCE"
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
  create_payload "HANDSHAKE" "$JOIN_BACK_TOKEN" "$PEER_URL_B" "$SELF_ID_B" "" "$TIMESTAMP" "$NONCE"
  handshake_back_resp=$(send_request "$SERVER_A" "/v1/peers:handshake" "$SIGNATURE" "$TIMESTAMP" "$NONCE" "$BODY")
  handshake_back_body=$(printf "%s" "$handshake_back_resp" | sed '$d')
  handshake_back_code=$(printf "%s" "$handshake_back_resp" | tail -n 1)
  echo "Handshake back status: ${handshake_back_code}"
  echo "Handshake back body: ${handshake_back_body}"
  if [ "$handshake_back_code" != "200" ]; then
    echo "Handshake back failed." >&2
    exit 1
  fi
else
  echo "=== Step 3: skip join-back (no --join-back-token provided) ==="
  echo "Note: relay_2 will use the stored peer_token in its handshake job to join back automatically."
fi

if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
  echo "=== Step 4: Verify token_present on B mapping ==="
  COOKIE_JAR="$(mktemp)"
  cleanup() { rm -f "$COOKIE_JAR"; }
  trap cleanup EXIT
  login_payload="{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}"
  login_resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER_B}/admin/api/auth/login" \
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

  list_resp=$(curl -s -w "\n%{http_code}" -X GET "${SERVER_B}/admin/api/peer-mappings" \
    -b "${COOKIE_JAR}")
  list_body=$(printf "%s" "$list_resp" | sed '$d')
  list_code=$(printf "%s" "$list_resp" | tail -n 1)
  echo "Mappings status: ${list_code}"
  echo "Mappings body: ${list_body}"
  if [ "$list_code" != "200" ]; then
    echo "List mappings failed." >&2
    exit 1
  fi

  python3 - "$list_body" "$PEER_URL_A" <<'PY'
import json, sys
data = json.loads(sys.argv[1])
peer_url = sys.argv[2]
items = data.get("items", [])
match = next((item for item in items if item.get("peer_url") == peer_url), None)
if not match:
    print("Mapping not found for peer_url:", peer_url)
    raise SystemExit(1)
print("token_present:", match.get("token_present"))
PY
fi

if [ -n "$WAIT_SECONDS" ] && [ -n "$CONTAINER_B" ]; then
  echo "=== Step 5: wait + verify handshake job logs on B ==="
  echo "Waiting ${WAIT_SECONDS}s..."
  sleep "$WAIT_SECONDS"
  join_log=$(docker logs --since "${WAIT_SECONDS}s" "$CONTAINER_B" 2>/dev/null | grep -m1 "federation_join_send" || true)
  handshake_log=$(docker logs --since "${WAIT_SECONDS}s" "$CONTAINER_B" 2>/dev/null | grep -m1 "federation_handshake_send" || true)
  if [ -n "$join_log" ]; then
    echo "Found join log: ${join_log}"
  else
    echo "No join log in ${CONTAINER_B} (handshake job may not be needed yet)."
  fi
  if [ -n "$handshake_log" ]; then
    echo "Found handshake log: ${handshake_log}"
  else
    echo "No handshake log in ${CONTAINER_B} (rotated/join token likely still valid)."
  fi
fi
