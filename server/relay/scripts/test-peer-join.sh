#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-peer-join.sh \\
    --server http://localhost:8080 \\
    --peer-url http://localhost:8081 \\
    --self-id relay-2 \\
    --token dev-peer-token

Optional:
  --nonce <value>   Override nonce (default: timestamp-random)
  --timestamp <sec> Override unix timestamp (default: now)
EOF
}

SERVER=""
PEER_URL=""
SELF_ID=""
TOKEN=""
NONCE=""
TIMESTAMP=""

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
    --self-id)
      SELF_ID="$2"
      shift 2
      ;;
    --token)
      TOKEN="$2"
      shift 2
      ;;
    --nonce)
      NONCE="$2"
      shift 2
      ;;
    --timestamp)
      TIMESTAMP="$2"
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

if [ -z "$SERVER" ] || [ -z "$PEER_URL" ] || [ -z "$SELF_ID" ] || [ -z "$TOKEN" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

if [ -z "$TIMESTAMP" ]; then
  TIMESTAMP="$(date +%s)"
fi

if [ -z "$NONCE" ]; then
  NONCE="${TIMESTAMP}-${RANDOM:-0}"
fi

BODY="{\"peer_url\":\"${PEER_URL}\",\"self_id\":\"${SELF_ID}\"}"
CANON="$(printf "JOIN\n%s\n%s\n%s" "$TIMESTAMP" "$NONCE" "$BODY")"
SIGNATURE="$(printf "%s" "$CANON" | openssl dgst -sha256 -hmac "$TOKEN" -hex | awk '{print $2}')"

curl -s -X POST "${SERVER}/v1/peers:join" \
  -H "Content-Type: application/json" \
  -H "X-Relay-Join-Signature: ${SIGNATURE}" \
  -H "X-Relay-Join-Timestamp: ${TIMESTAMP}" \
  -H "X-Relay-Join-Nonce: ${NONCE}" \
  -d "${BODY}"
echo
