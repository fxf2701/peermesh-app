#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
PROFILES_LIST=""
VOLUMES_FLAG=""

print_usage() {
  cat >&2 <<'USAGE'
Usage: server/relay/stop.sh [--profiles profile1,profile2] [--volumes]

Stops the standalone Relay production stack defined in:
  - server/relay/docker-compose.yml
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --profiles)
      shift
      if [ -z "${1:-}" ]; then
        echo "--profiles requires a comma-separated list" >&2
        exit 1
      fi
      PROFILES_LIST="$1"
      ;;
    --volumes|-v)
      VOLUMES_FLAG="--volumes"
      ;;
    --help|-h)
      print_usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      print_usage >&2
      exit 1
      ;;
  esac
  shift
done

PROFILE_ARGS=""
if [ -n "$PROFILES_LIST" ]; then
  OLD_IFS="$IFS"
  IFS=","
  for profile in $PROFILES_LIST; do
    profile_trimmed="$(echo "$profile" | xargs)"
    if [ -n "$profile_trimmed" ]; then
      PROFILE_ARGS="$PROFILE_ARGS --profile $profile_trimmed"
    fi
  done
  IFS="$OLD_IFS"
fi

docker compose -f "$SCRIPT_DIR/docker-compose.yml" $PROFILE_ARGS down $VOLUMES_FLAG
