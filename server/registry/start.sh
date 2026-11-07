#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
BUILD_FLAG=""

print_usage() {
  cat >&2 <<'USAGE'
Usage: server/registry/start.sh [--build]

Starts the standalone Registry production stack using:
  - server/registry/docker-compose.yml
  - server/registry/.env
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --build)
      BUILD_FLAG="--build"
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

ENV_FILE_ARG=""
if [ -f "$ENV_FILE" ]; then
  ENV_FILE_ARG="--env-file $ENV_FILE"
else
  echo "Warning: env file not found: $ENV_FILE" >&2
fi

# shellcheck disable=SC2086
exec docker compose -f "$SCRIPT_DIR/docker-compose.yml" $ENV_FILE_ARG up $BUILD_FLAG -d
