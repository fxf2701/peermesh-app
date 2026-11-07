#!/bin/sh
set -e

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$ROOT_DIR"

profiles_list=""
volumes_flag=""

print_usage() {
  cat >&2 <<'USAGE'
Usage: server/stop.sh [--profiles profile1,profile2] [--volumes]

This launcher stops the local dev stack only.
It uses:
  - server/docker-compose.dev.yml
  - default profile: multi
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
      profiles_list="$1"
      ;;
    --volumes|-v)
      volumes_flag="--volumes"
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

profile_args=""
if [ -z "$profiles_list" ]; then
  profiles_list="multi"
fi

if [ -n "$profiles_list" ]; then
  old_ifs="$IFS"
  IFS=","
  for profile in $profiles_list; do
    profile_trimmed="$(echo "$profile" | xargs)"
    if [ -n "$profile_trimmed" ]; then
      profile_args="$profile_args --profile $profile_trimmed"
    fi
  done
  IFS="$old_ifs"
fi

docker compose -f docker-compose.dev.yml $profile_args down $volumes_flag
