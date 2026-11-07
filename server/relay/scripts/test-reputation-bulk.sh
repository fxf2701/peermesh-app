#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-reputation-bulk.sh \\
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

tmp_csv="$(mktemp)"
cat > "${tmp_csv}" <<EOF
peer_id,score,note
peer-rep-1,5,good actor
peer-rep-2,-3,abuse report
EOF

import_resp="$(curl -s -w "\n%{http_code}" -X POST "${SERVER}/admin/api/reputation/import" \
  -H "Content-Type: text/csv" \
  --data-binary @"${tmp_csv}" \
  -b "${COOKIE_JAR}")"
import_body="$(printf "%s" "$import_resp" | sed '$d')"
import_code="$(printf "%s" "$import_resp" | tail -n 1)"
echo "Import status: ${import_code}"
echo "Import body: ${import_body}"

export_csv="$(curl -s -X GET "${SERVER}/admin/api/reputation/export" -b "${COOKIE_JAR}")"
echo "Export CSV:"
echo "${export_csv}"

rm -f "${tmp_csv}"
