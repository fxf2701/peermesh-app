#!/bin/sh
set -e

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"

cd "$ROOT_DIR"

service=""
profiles_list=""

while [ $# -gt 0 ]; do
  case "$1" in
    --service)
      shift
      if [ $# -eq 0 ]; then
        echo "Missing service name after --service" >&2
        exit 1
      fi
      service="$1"
      ;;
    --dev)
      compose_args="-f docker-compose.dev.yml"
      ;;
    --profiles)
      shift
      if [ $# -eq 0 ]; then
        echo "--profiles requires a comma-separated list" >&2
        exit 1
      fi
      profiles_list="$1"
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

profile_args=""
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

if [ -n "$service" ]; then
  docker compose ${compose_args:-} $profile_args build "$service"
else
  docker compose ${compose_args:-} $profile_args build
fi
