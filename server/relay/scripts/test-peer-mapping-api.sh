#!/bin/sh
set -e

usage() {
  cat <<EOF
Usage:
  ./server/relay/scripts/test-peer-mapping-api.sh \\
    --server http://localhost:8080 \\
    --username admin \\
    --password admin \\
    --peer-url http://localhost:8081 \\
    --peer-token dev-peer-token \\
    --sync-gids demo-gid \\
    --auto-sync true

Notes:
  - Uses admin session cookie from /admin/api/auth/login.
  - sync-gids is a comma-separated string.
EOF
}

SERVER=""
USERNAME=""
PASSWORD=""
PEER_URL=""
PEER_TOKEN=""
SYNC_GIDS=""
AUTO_SYNC="false"

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
    --peer-url)
      PEER_URL="$2"
      shift 2
      ;;
    --peer-token)
      PEER_TOKEN="$2"
      shift 2
      ;;
    --sync-gids)
      SYNC_GIDS="$2"
      shift 2
      ;;
    --auto-sync)
      AUTO_SYNC="$2"
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

if [ -z "$SERVER" ] || [ -z "$USERNAME" ] || [ -z "$PASSWORD" ] || [ -z "$PEER_URL" ] || [ -z "$PEER_TOKEN" ]; then
  echo "Missing required args." >&2
  usage
  exit 1
fi

COOKIE_JAR="$(mktemp)"

login_payload="{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}"
curl -s -X POST "${SERVER}/admin/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "${login_payload}" \
  -c "${COOKIE_JAR}" >/dev/null

sync_gids_json="[]"
if [ -n "$SYNC_GIDS" ]; then
  gids=$(printf "%s" "$SYNC_GIDS" | awk -F',' '{for (i=1;i<=NF;i++){gsub(/^ +| +$/,"",$i);if($i!=""){printf "\"%s\"%s",$i,(i<NF?",":"")}}}')
  sync_gids_json="[$gids]"
fi

create_payload="{\"peer_url\":\"${PEER_URL}\",\"peer_token\":\"${PEER_TOKEN}\",\"sync_gids\":${sync_gids_json},\"auto_sync\":${AUTO_SYNC}}"
create_resp="$(curl -s -X POST "${SERVER}/admin/api/peer-mappings" \
  -H "Content-Type: application/json" \
  -d "${create_payload}" \
  -b "${COOKIE_JAR}")"
echo "Create response: ${create_resp}"

id=$(printf "%s" "$create_resp" | sed -n 's/.*"id":[ ]*\([0-9]*\).*/\1/p')
if [ -z "$id" ]; then
  echo "Failed to parse mapping id." >&2
  exit 1
fi

update_payload="{\"auto_sync\":false}"
update_resp="$(curl -s -X PATCH "${SERVER}/admin/api/peer-mappings/${id}" \
  -H "Content-Type: application/json" \
  -d "${update_payload}" \
  -b "${COOKIE_JAR}")"
echo "Update response: ${update_resp}"

list_resp="$(curl -s -X GET "${SERVER}/admin/api/peer-mappings" -b "${COOKIE_JAR}")"
echo "List response: ${list_resp}"

delete_resp="$(curl -s -X DELETE "${SERVER}/admin/api/peer-mappings/${id}" -b "${COOKIE_JAR}")"
echo "Delete response: ${delete_resp}"

rm -f "${COOKIE_JAR}"
