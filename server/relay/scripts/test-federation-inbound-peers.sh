#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-federation-inbound-peers.sh \\
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
    --server) SERVER="$2"; shift 2 ;;
    --username) USERNAME="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
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
login_resp=$(curl -s -w "\n%{http_code}" -X POST "${SERVER}/admin/api/auth/login" \
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

list_resp=$(curl -s -w "\n%{http_code}" -X GET "${SERVER}/admin/api/peers/inbound" \
  -b "${COOKIE_JAR}")
list_body=$(printf "%s" "$list_resp" | sed '$d')
list_code=$(printf "%s" "$list_resp" | tail -n 1)
echo "Inbound status: ${list_code}"
echo "Inbound body: ${list_body}"
if [ "$list_code" != "200" ]; then
  echo "Inbound peers failed." >&2
  exit 1
fi
