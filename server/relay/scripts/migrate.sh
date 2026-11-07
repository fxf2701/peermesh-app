#!/bin/sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$ROOT_DIR"

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 [--dev] --up [N] | --down [N]" >&2
  exit 1
fi

dev_mode=""
if [ "$1" = "--dev" ]; then
  dev_mode="1"
  shift
fi

case "$1" in
  --up|--down)
    if [ -n "$dev_mode" ]; then
      primary_url="${DATABASE_URL:-}"
      secondary_url="${DATABASE_URL_SECONDARY:-}"
      if [ -n "${primary_url}" ]; then
        echo "Running migrations for ${primary_url}"
        DATABASE_URL="${primary_url}" node scripts/migrate.js "$@"
      else
        echo "Running migrations for default DATABASE_URL"
        node scripts/migrate.js "$@"
      fi
      if [ -n "${secondary_url}" ]; then
        echo "Running migrations for ${secondary_url}"
        DATABASE_URL="${secondary_url}" node scripts/migrate.js "$@"
      fi
      exit 0
    fi
    if [ -n "${DATABASE_URLS:-}" ]; then
      IFS=","; for url in ${DATABASE_URLS}; do
        if [ -z "${url}" ]; then
          continue
        fi
        echo "Running migrations for ${url}"
        DATABASE_URL="${url}" node scripts/migrate.js "$@"
      done
      unset IFS
    elif [ -n "${DATABASE_URL_SECONDARY:-}" ]; then
      primary_url="${DATABASE_URL:-}"
      if [ -n "${primary_url}" ]; then
        echo "Running migrations for ${primary_url}"
        DATABASE_URL="${primary_url}" node scripts/migrate.js "$@"
      else
        echo "Running migrations for default DATABASE_URL"
        node scripts/migrate.js "$@"
      fi
      echo "Running migrations for ${DATABASE_URL_SECONDARY}"
      DATABASE_URL="${DATABASE_URL_SECONDARY}" node scripts/migrate.js "$@"
    else
      node scripts/migrate.js "$@"
    fi
    ;;
  *)
    echo "Unknown option: $1" >&2
    echo "Usage: $0 [--dev] --up [N] | --down [N]" >&2
    exit 1
    ;;
esac
