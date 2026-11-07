#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-remote-stats.sh \\
    --server http://localhost:8080 \\
    --peer-id relay-2 \\
    --peer-token <rotated_token> \\
    [--expect-status 200]
EOF
}

SERVER=""
PEER_ID=""
PEER_TOKEN=""
EXPECT_STATUS="200"

while [ $# -gt 0 ]; do
  case "$1" in
    --server) SERVER="$2"; shift 2 ;;
    --peer-id) PEER_ID="$2"; shift 2 ;;
    --peer-token) PEER_TOKEN="$2"; shift 2 ;;
    --expect-status) EXPECT_STATUS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [ -z "$SERVER" ] || [ -z "$PEER_ID" ] || [ -z "$PEER_TOKEN" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

resp=$(curl -s -w "\n%{http_code}" -X GET "${SERVER}/v1/peers:stats" \
  -H "x-relay-peer: 1" \
  -H "x-relay-from: ${PEER_ID}" \
  -H "x-relay-peer-token: ${PEER_TOKEN}")
body=$(printf "%s" "$resp" | sed '$d')
code=$(printf "%s" "$resp" | tail -n 1)
echo "Remote stats status: ${code}"
echo "Remote stats body: ${body}"

if [ "$code" != "$EXPECT_STATUS" ]; then
  echo "Remote stats failed." >&2
  exit 1
fi
