#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-rotated-enforcement.sh \\
    --server http://localhost:8080 \\
    --peer-id relay-2 \\
    --peer-url http://relay_2:8080 \\
    --rotated-token <rotated_token> \\
    --public-token dev-public-token \\
    [--gid demo-gid] \\
    [--request-signature-secret test-secret] \\
    [--expect-success 200] \\
    [--expect-fail 403]
EOF
}

SERVER=""
PEER_ID=""
PEER_URL=""
ROTATED_TOKEN=""
PUBLIC_TOKEN=""
GID="demo-gid"
REQ_SIG_SECRET="${REQUEST_SIGNATURE_SECRET:-test-secret}"
EXPECT_SUCCESS="200"
EXPECT_FAIL="403"

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --peer-id) PEER_ID="$2"; shift 2 ;;
    --peer-url) PEER_URL="$2"; shift 2 ;;
    --rotated-token) ROTATED_TOKEN="$2"; shift 2 ;;
    --public-token) PUBLIC_TOKEN="$2"; shift 2 ;;
    --gid) GID="$2"; shift 2 ;;
    --request-signature-secret) REQ_SIG_SECRET="$2"; shift 2 ;;
    --expect-success) EXPECT_SUCCESS="$2"; shift 2 ;;
    --expect-fail) EXPECT_FAIL="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$SERVER" ] || [ -z "$PEER_ID" ] || [ -z "$PEER_URL" ] || [ -z "$ROTATED_TOKEN" ] || [ -z "$PUBLIC_TOKEN" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

sign_request() {
  local method=$1
  local path=$2
  local body=$3
  if [ -z "$REQ_SIG_SECRET" ]; then
    echo ""
    return
  fi
  python3 - "$REQ_SIG_SECRET" "$method" "$path" "$body" <<'PY'
import hmac, hashlib, sys, time
secret = sys.argv[1]
method = sys.argv[2]
path = sys.argv[3]
body = sys.argv[4]
ts = int(time.time())
payload = f"{method}\n{path}\n{ts}\n{body}"
sig = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
print(ts)
print(sig)
PY
}

heartbeat_request() {
  local token=$1
  local expect=$2
  local ts
  local nonce
  local body
  local signature
  ts=$(date +%s)
  nonce=$(uuidgen)
  body=$(python3 - "$PEER_URL" "$PEER_ID" <<'PY'
import json, sys
peer_url = sys.argv[1]
self_id = sys.argv[2]
print(json.dumps({"peer_url": peer_url, "self_id": self_id}, separators=(",", ":")))
PY
)
  signature=$(python3 - "$token" "$ts" "$nonce" "$body" <<'PY'
import hmac, hashlib, sys
token = sys.argv[1]
ts = sys.argv[2]
nonce = sys.argv[3]
body = sys.argv[4]
canonical = f"HEARTBEAT\n{ts}\n{nonce}\n{body}"
sig = hmac.new(token.encode(), canonical.encode(), hashlib.sha256).hexdigest()
print(sig)
PY
)
  resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER}/v1/peers:heartbeat" \
    -H "Content-Type: application/json" \
    -H "X-Relay-Join-Signature: ${signature}" \
    -H "X-Relay-Join-Timestamp: ${ts}" \
    -H "X-Relay-Join-Nonce: ${nonce}" \
    --data-binary "${body}")
  body_out=$(printf "%s" "$resp" | sed '$d')
  code_out=$(printf "%s" "$resp" | tail -n 1)
  echo "Heartbeat status: ${code_out}"
  echo "Heartbeat body: ${body_out}"
  if [ "$expect" = "fail" ]; then
    if [ "$code_out" = "200" ]; then
      echo "Heartbeat check failed." >&2
      exit 1
    fi
    return
  fi
  if [ "$code_out" != "$expect" ]; then
    echo "Heartbeat check failed." >&2
    exit 1
  fi
}

fetch_request() {
  local token=$1
  local expect=$2
  local path="/v1/events:fetch?gid=${GID}&limit=1"
  local sig_out
  sig_out=$(sign_request "GET" "$path" "")
  if [ -n "$sig_out" ]; then
    REQ_TS=$(printf "%s" "$sig_out" | head -n1)
    REQ_SIG=$(printf "%s" "$sig_out" | tail -n1)
    REQ_HEADERS="-H X-Relay-Timestamp:${REQ_TS} -H X-Relay-Signature:${REQ_SIG}"
  else
    REQ_HEADERS=""
  fi
  resp=$(curl -s -w "\n%{http_code}" -X GET "${SERVER}${path}" \
    -H "x-relay-peer: 1" \
    -H "x-relay-from: ${PEER_ID}" \
    -H "x-relay-peer-token: ${token}" \
    ${REQ_HEADERS})
  body_out=$(printf "%s" "$resp" | sed '$d')
  code_out=$(printf "%s" "$resp" | tail -n 1)
  echo "Fetch status: ${code_out}"
  echo "Fetch body: ${body_out}"
  if [ "$code_out" != "$expect" ]; then
    echo "Fetch check failed." >&2
    exit 1
  fi
}

ingest_request() {
  local token=$1
  local expect=$2
  local now
  now=$(date +%s)
  body=$(python3 - "$now" "$GID" <<'PY'
import json, sys, uuid
now = int(sys.argv[1])
gid = sys.argv[2]
event = {
    "eid": str(uuid.uuid4()),
    "gid": gid,
    "ts": now,
    "exp": now + 300,
    "body": {"kind": "test"},
    "sig": "test-sig"
}
print(json.dumps({"events": [event]}, separators=(",", ":")))
PY
)
  local path="/v1/events:ingest"
  sig_out=$(sign_request "POST" "$path" "$body")
  if [ -n "$sig_out" ]; then
    REQ_TS=$(printf "%s" "$sig_out" | head -n1)
    REQ_SIG=$(printf "%s" "$sig_out" | tail -n1)
    REQ_HEADERS="-H X-Relay-Timestamp:${REQ_TS} -H X-Relay-Signature:${REQ_SIG}"
  else
    REQ_HEADERS=""
  fi
  resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER}${path}" \
    -H "Content-Type: application/json" \
    -H "x-relay-peer: 1" \
    -H "x-relay-from: ${PEER_ID}" \
    -H "x-relay-peer-token: ${token}" \
    ${REQ_HEADERS} \
    --data-binary "${body}")
  body_out=$(printf "%s" "$resp" | sed '$d')
  code_out=$(printf "%s" "$resp" | tail -n 1)
  echo "Ingest status: ${code_out}"
  echo "Ingest body: ${body_out}"
  if [ "$code_out" != "$expect" ]; then
    echo "Ingest check failed." >&2
    exit 1
  fi
}

echo "=== Rotated token tests ==="
heartbeat_request "$ROTATED_TOKEN" "$EXPECT_SUCCESS"
fetch_request "$ROTATED_TOKEN" "$EXPECT_SUCCESS"
ingest_request "$ROTATED_TOKEN" "$EXPECT_SUCCESS"

echo "=== Public token (should fail) ==="
heartbeat_request "$PUBLIC_TOKEN" "fail"
fetch_request "$PUBLIC_TOKEN" "$EXPECT_FAIL"
ingest_request "$PUBLIC_TOKEN" "$EXPECT_FAIL"
