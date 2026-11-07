#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-rotated-expiry.sh \\
    --server http://localhost:8080 \\
    --peer-id relay-2 \\
    --peer-token <rotated_token> \\
    [--gid demo-gid] \\
    [--expect-status 200] \\
    [--signature-secret test-secret]
EOF
}

SERVER=""
PEER_ID=""
PEER_TOKEN=""
GID="demo-gid"
EXPECT_STATUS="200"
SIGNATURE_SECRET="${REQUEST_SIGNATURE_SECRET:-test-secret}"

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --peer-id) PEER_ID="$2"; shift 2 ;;
    --peer-token) PEER_TOKEN="$2"; shift 2 ;;
    --gid) GID="$2"; shift 2 ;;
    --expect-status) EXPECT_STATUS="$2"; shift 2 ;;
    --signature-secret) SIGNATURE_SECRET="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$SERVER" ] || [ -z "$PEER_ID" ] || [ -z "$PEER_TOKEN" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

PATH_QUERY="/v1/events:fetch?gid=${GID}&limit=1"

SIGNATURE_HEADERS=""
if [ -n "$SIGNATURE_SECRET" ]; then
  python_out=$(python3 - "$SIGNATURE_SECRET" "$PATH_QUERY" <<'PY'
import hmac, hashlib, sys, time
secret = sys.argv[1]
path = sys.argv[2]
ts = int(time.time())
payload = f"GET\n{path}\n{ts}\n"
sig = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
print(ts)
print(sig)
PY
  )
  TIMESTAMP=$(printf "%s" "$python_out" | head -n1)
  SIGNATURE=$(printf "%s" "$python_out" | tail -n1)
  SIGNATURE_HEADERS="-H X-Relay-Signature:${SIGNATURE} -H X-Relay-Timestamp:${TIMESTAMP}"
fi

fetch_resp=$(curl -s -w "\n%{http_code}" -X GET "${SERVER}${PATH_QUERY}" \
  -H "X-Relay-Peer: 1" \
  -H "X-Relay-From: ${PEER_ID}" \
  -H "X-Relay-Peer-Token: ${PEER_TOKEN}" \
  ${SIGNATURE_HEADERS})
fetch_body=$(printf "%s" "$fetch_resp" | sed '$d')
fetch_code=$(printf "%s" "$fetch_resp" | tail -n 1)
echo "Fetch status: ${fetch_code}"
echo "Fetch body: ${fetch_body}"

if [ "$fetch_code" != "$EXPECT_STATUS" ]; then
  echo "Fetch failed." >&2
  exit 1
fi
