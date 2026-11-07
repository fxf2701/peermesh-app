#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 /path/to/backup.dump" >&2
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

DUMP_FILE=$1

"$SCRIPT_DIR/create-restore-db-docker.sh"
"$SCRIPT_DIR/restore-validate-postgres-docker.sh" "$DUMP_FILE"
