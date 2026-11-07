#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-peers-api.sh \\
    --server http://localhost:8080 \\
    --username admin \\
    --password admin
EOF
}

SERVER=""
USERNAME=""
PASSWORD=""

while [ $# -gt 0 ]; do
  case "$1" in
    --server)
      SERVER="$2"
      shift 2
      ;;
    --username)
      USERNAME="$2"
      shift 2
      ;;
    --password)
      PASSWORD="$2"
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

if [ -z "$SERVER" ] || [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

COOKIE_JAR="$(mktemp)"
cleanup() {
  rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

login_payload="{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}"
curl -s -X POST "${SERVER}/admin/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "${login_payload}" \
  -c "${COOKIE_JAR}" >/dev/null

list_resp="$(curl -s -w "\n%{http_code}" -X GET "${SERVER}/admin/api/peers" -b "${COOKIE_JAR}")"
list_body="$(printf "%s" "$list_resp" | sed '$d')"
list_code="$(printf "%s" "$list_resp" | tail -n 1)"
echo "List status: ${list_code}"
echo "List body: ${list_body}"

if [ "$list_code" != "200" ]; then
  echo "List failed." >&2
  exit 1
fi

peer_id="$(printf "%s" "$list_body" | sed -n 's/.*"id"[ ]*:[ ]*"\([^"]*\)".*/\1/p' | head -n 1)"
if [ -z "$peer_id" ]; then
  echo "No peer id found in list; skipping detail/history checks."
  exit 0
fi

detail_resp="$(curl -s -w "\n%{http_code}" -X GET "${SERVER}/admin/api/peers/${peer_id}" -b "${COOKIE_JAR}")"
detail_body="$(printf "%s" "$detail_resp" | sed '$d')"
detail_code="$(printf "%s" "$detail_resp" | tail -n 1)"
echo "Detail status: ${detail_code}"
echo "Detail body: ${detail_body}"
if [ "$detail_code" != "200" ]; then
  echo "Detail failed." >&2
  exit 1
fi

history_resp="$(curl -s -w "\n%{http_code}" -X GET "${SERVER}/admin/api/peers/${peer_id}/history" -b "${COOKIE_JAR}")"
history_body="$(printf "%s" "$history_resp" | sed '$d')"
history_code="$(printf "%s" "$history_resp" | tail -n 1)"
echo "History status: ${history_code}"
echo "History body: ${history_body}"
if [ "$history_code" != "200" ]; then
  echo "History failed." >&2
  exit 1
fi
