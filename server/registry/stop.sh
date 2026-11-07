#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
VOLUMES_FLAG=""

print_usage() {
  cat >&2 <<'USAGE'
Usage: server/registry/stop.sh [--volumes]

Stops the standalone Registry production stack defined in:
  - server/registry/docker-compose.yml
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
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

exec docker compose -f "$SCRIPT_DIR/docker-compose.yml" down $VOLUMES_FLAG
