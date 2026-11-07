#!/bin/sh
set -e

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT_DIR/server/.env.dev"
COMPOSE_FILE="-f $ROOT_DIR/server/docker-compose.dev.yml"
BUILD_FLAG=""
PROFILES_LIST=""

cd "$ROOT_DIR"

print_usage() {
  cat >&2 <<'USAGE'
Usage: server/start.sh [--build] [--profiles profile1,profile2]

This launcher is for the local dev stack only.
It uses:
  - server/docker-compose.dev.yml
  - server/.env.dev

Examples:
  server/start.sh
  server/start.sh --build
  server/start.sh --profiles multi,metrics
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
      print_usage
      exit 1
      ;;
  esac
  shift
done

# Dev mode should boot the full local simulation by default:
# registry + relay + relay_2 + postgres/postgres_2 + redis/redis_2.
# Metrics remain opt-in via: --profiles multi,metrics
if [ -z "$PROFILES_LIST" ]; then
  PROFILES_LIST="multi"
fi

PROFILE_ARGS=""
if [ -n "$PROFILES_LIST" ]; then
  OLD_IFS="$IFS"
  IFS=","
  for profile in $PROFILES_LIST; do
    # xargs trims whitespace and is available on common Linux/macOS dev machines.
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
exec docker compose $COMPOSE_FILE $ENV_FILE_ARG $PROFILE_ARGS up $BUILD_FLAG -d
