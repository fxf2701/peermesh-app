#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
BUILD_FLAG=""
PROFILES_LIST=""

print_usage() {
  cat >&2 <<'USAGE'
Usage: server/relay/start.sh [--build] [--profiles profile1,profile2]

Starts the standalone Relay production stack using:
  - server/relay/docker-compose.yml
  - server/relay/.env

Examples:
  server/relay/start.sh
  server/relay/start.sh --build
  server/relay/start.sh --profiles backup,metrics
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --build)
      BUILD_FLAG="--build"
      ;;
    --profiles)
      shift
      if [ -z "${1:-}" ]; then
        echo "--profiles requires a comma-separated list" >&2
        exit 1
      fi
      PROFILES_LIST="$1"
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

ENV_FILE_ARG=""
if [ -f "$ENV_FILE" ]; then
  ENV_FILE_ARG="--env-file $ENV_FILE"
else
  echo "Warning: env file not found: $ENV_FILE" >&2
fi

# shellcheck disable=SC2086
exec docker compose -f "$SCRIPT_DIR/docker-compose.yml" $ENV_FILE_ARG $PROFILE_ARGS up $BUILD_FLAG -d
