#!/bin/sh
set -eu

SERVICE_NAME="registry"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
SERVER_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$SERVER_ROOT/.env.dev"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") [--dry-run] [--delete] [--apply] [--health-check] [--port PORT] [--remote PATH] user@host
  $(basename "$0") [--dry-run] [--delete] [--apply] [--health-check] [--port PORT] user@host:/absolute/remote/path
  $(basename "$0") --apply-only [--health-check] [--port PORT] [--remote PATH] user@host

Syncs code-only changes for server/$SERVICE_NAME to a VPS.
If no CLI target is passed, the script falls back to server/.env.dev:
  - REGISTRY_DEPLOY_USER
  - REGISTRY_DEPLOY_HOST
  - REGISTRY_DEPLOY_PORT
  - REGISTRY_DEPLOY_PATH
  - REGISTRY_DEPLOY_POST_SYNC_CMD
  - REGISTRY_DEPLOY_HEALTHCHECK_CMD

Excluded by default:
  - .env files
  - config/
  - data/, backups/, export/
  - logs, caches, node_modules

Examples:
  $(basename "$0") root@registry-vps:/opt/peermesh/server/registry
  $(basename "$0") --dry-run --delete --port 2222 --remote /srv/peermesh/server/registry ubuntu@1.2.3.4
  $(basename "$0") --apply --health-check

Recommended remote commands:
  REGISTRY_DEPLOY_POST_SYNC_CMD="docker compose --env-file .env -f docker-compose.yml up -d --build registry"
  REGISTRY_DEPLOY_HEALTHCHECK_CMD="curl -fsS http://127.0.0.1:8090/healthz"
EOF
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

read_dotenv_value() {
  key="$1"
  file="$2"
  [ -f "$file" ] || return 0
  awk -v key="$key" '
    index($0, key "=") == 1 {
      value = substr($0, length(key) + 2)
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      if (value ~ /^".*"$/ || value ~ /^'\''.*'\''$/) {
        value = substr(value, 2, length(value) - 2)
      }
      print value
      exit
    }
  ' "$file"
}

DRY_RUN=0
DELETE=0
SSH_PORT=""
REMOTE_SPEC=""
REMOTE_PATH=""
REMOTE_USER=""
APPLY=0
APPLY_ONLY=0
HEALTH_CHECK=0
POST_SYNC_CMD=""
HEALTHCHECK_CMD=""

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      ;;
    --delete)
      DELETE=1
      ;;
    --apply)
      APPLY=1
      ;;
    --apply-only)
      APPLY=1
      APPLY_ONLY=1
      ;;
    --health-check)
      HEALTH_CHECK=1
      ;;
    --port)
      shift
      [ $# -gt 0 ] || { echo "Missing value after --port" >&2; exit 1; }
      SSH_PORT="$1"
      ;;
    --post-sync-cmd)
      shift
      [ $# -gt 0 ] || { echo "Missing value after --post-sync-cmd" >&2; exit 1; }
      POST_SYNC_CMD="$1"
      ;;
    --healthcheck-cmd)
      shift
      [ $# -gt 0 ] || { echo "Missing value after --healthcheck-cmd" >&2; exit 1; }
      HEALTHCHECK_CMD="$1"
      ;;
    --remote)
      shift
      [ $# -gt 0 ] || { echo "Missing value after --remote" >&2; exit 1; }
      REMOTE_PATH="$1"
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [ -n "$REMOTE_SPEC" ]; then
        echo "Unexpected extra argument: $1" >&2
        usage >&2
        exit 1
      fi
      REMOTE_SPEC="$1"
      ;;
  esac
  shift
done

ENV_REMOTE_USER="$(read_dotenv_value REGISTRY_DEPLOY_USER "$ENV_FILE")"
ENV_REMOTE_HOST="$(read_dotenv_value REGISTRY_DEPLOY_HOST "$ENV_FILE")"
ENV_REMOTE_PORT="$(read_dotenv_value REGISTRY_DEPLOY_PORT "$ENV_FILE")"
ENV_REMOTE_PATH="$(read_dotenv_value REGISTRY_DEPLOY_PATH "$ENV_FILE")"
ENV_POST_SYNC_CMD="$(read_dotenv_value REGISTRY_DEPLOY_POST_SYNC_CMD "$ENV_FILE")"
ENV_HEALTHCHECK_CMD="$(read_dotenv_value REGISTRY_DEPLOY_HEALTHCHECK_CMD "$ENV_FILE")"

if [ -z "$REMOTE_SPEC" ]; then
  REMOTE_USER="${ENV_REMOTE_USER:-}"
  REMOTE_HOST="${ENV_REMOTE_HOST:-}"
  if [ -z "$SSH_PORT" ]; then
    SSH_PORT="${ENV_REMOTE_PORT:-}"
  fi
  if [ -z "$REMOTE_PATH" ]; then
    REMOTE_PATH="${ENV_REMOTE_PATH:-}"
  fi
  if [ -n "$REMOTE_HOST" ]; then
    if [ -n "$REMOTE_USER" ]; then
      REMOTE_SPEC="$REMOTE_USER@$REMOTE_HOST"
    else
      REMOTE_SPEC="$REMOTE_HOST"
    fi
  fi
fi

[ -n "$REMOTE_SPEC" ] || { usage >&2; exit 1; }

if [ -z "$POST_SYNC_CMD" ]; then
  POST_SYNC_CMD="${ENV_POST_SYNC_CMD:-}"
fi

if [ -z "$HEALTHCHECK_CMD" ]; then
  HEALTHCHECK_CMD="${ENV_HEALTHCHECK_CMD:-}"
fi

case "$REMOTE_SPEC" in
  *:*)
    if [ -n "$REMOTE_PATH" ]; then
      echo "Do not use --remote together with user@host:/path form" >&2
      exit 1
    fi
    REMOTE_HOST="${REMOTE_SPEC%%:*}"
    REMOTE_PATH="${REMOTE_SPEC#*:}"
    ;;
  *)
    REMOTE_HOST="$REMOTE_SPEC"
    ;;
esac

[ -n "$REMOTE_PATH" ] || {
  echo "Remote path is required. Pass user@host:/path or --remote /path." >&2
  exit 1
}

require_cmd rsync
require_cmd ssh

if [ "$APPLY" -eq 1 ] && [ -z "$POST_SYNC_CMD" ]; then
  echo "Apply requested but no post-sync command configured. Set REGISTRY_DEPLOY_POST_SYNC_CMD or pass --post-sync-cmd." >&2
  exit 1
fi

if [ "$HEALTH_CHECK" -eq 1 ] && [ -z "$HEALTHCHECK_CMD" ]; then
  echo "Health check requested but no healthcheck command configured. Set REGISTRY_DEPLOY_HEALTHCHECK_CMD or pass --healthcheck-cmd." >&2
  exit 1
fi

RSYNC_RSH="ssh"
if [ -n "$SSH_PORT" ]; then
  RSYNC_RSH="ssh -p $SSH_PORT"
fi

set -- rsync -az --human-readable --itemize-changes \
  --exclude '.env' \
  --exclude '.env.*' \
  --exclude 'config/' \
  --exclude 'data/' \
  --exclude 'backups/' \
  --exclude 'backups_2/' \
  --exclude 'export/' \
  --exclude 'logs/' \
  --exclude 'log/' \
  --exclude '*.log' \
  --exclude '.cache/' \
  --exclude 'cache/' \
  --exclude 'tmp/' \
  --exclude 'coverage/' \
  --exclude 'node_modules/' \
  --exclude '*.sqlite' \
  --exclude '*.sqlite-shm' \
  --exclude '*.sqlite-wal' \
  --exclude 'deploy.sh' \
  -e "$RSYNC_RSH"

if [ "$DRY_RUN" -eq 1 ]; then
  set -- "$@" --dry-run
fi

if [ "$DELETE" -eq 1 ]; then
  set -- "$@" --delete
fi

if [ "$APPLY_ONLY" -eq 0 ]; then
  set -- "$@" "$SCRIPT_DIR/" "$REMOTE_HOST:$REMOTE_PATH/"

  echo "Deploying server/$SERVICE_NAME code to $REMOTE_HOST:$REMOTE_PATH"
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "Simulation only, no files will be uploaded."
  fi
  "$@"
fi

SSH_TARGET="$REMOTE_HOST"

run_remote() {
  remote_cmd="$1"
  echo "Running remote command on $SSH_TARGET"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] Simulation only, remote command will not run: %s\n' "$remote_cmd"
    return 0
  fi
  if [ -n "$SSH_PORT" ]; then
    ssh -p "$SSH_PORT" "$SSH_TARGET" "cd '$REMOTE_PATH' && $remote_cmd"
  else
    ssh "$SSH_TARGET" "cd '$REMOTE_PATH' && $remote_cmd"
  fi
}

if [ "$APPLY" -eq 1 ]; then
  run_remote "$POST_SYNC_CMD"
fi

if [ "$HEALTH_CHECK" -eq 1 ]; then
  run_remote "$HEALTHCHECK_CMD"
fi
