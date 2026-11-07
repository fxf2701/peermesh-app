#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/var/backups/relay}
PROVIDER=${PROVIDER:-}
SKIP_OFFSITE=${SKIP_OFFSITE:-0}

if [ ! -d "$BACKUP_DIR" ]; then
  echo "Backup dir not found: $BACKUP_DIR" >&2
  exit 1
fi

latest_backup=$(ls -1t "${BACKUP_DIR}"/relay_*.dump 2>/dev/null | head -n 1 || true)
if [ -z "$latest_backup" ]; then
  echo "No backup files found in $BACKUP_DIR" >&2
  exit 1
fi

checksum_file="${latest_backup}.sha256"
if [ ! -f "$checksum_file" ]; then
  echo "Checksum file missing: $checksum_file" >&2
  exit 1
fi

if [ "$SKIP_OFFSITE" -eq 1 ]; then
  sha256sum -c "$checksum_file"
  echo "Offsite test skipped (local checksum OK)"
  exit 0
fi

if [ -z "$PROVIDER" ]; then
  echo "PROVIDER is required (s3 or gcs)" >&2
  exit 1
fi

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

base_name=$(basename "$latest_backup")

case "$PROVIDER" in
  s3)
    if ! command -v aws >/dev/null 2>&1; then
      echo "aws CLI is required for S3 tests" >&2
      exit 1
    fi
    S3_BUCKET=${S3_BUCKET:-}
    S3_PREFIX=${S3_PREFIX:-test}
    S3_SSE_C_KEY=${S3_SSE_C_KEY:-}
    if [ -z "$S3_BUCKET" ]; then
      echo "S3_BUCKET is required" >&2
      exit 1
    fi

    dest="s3://${S3_BUCKET}/${S3_PREFIX}/${base_name}"
    checksum_dest="s3://${S3_BUCKET}/${S3_PREFIX}/${base_name}.sha256"

    upload_args=()
    if [ -n "$S3_SSE_C_KEY" ]; then
      upload_args+=(--sse-c AES256 --sse-c-key "$S3_SSE_C_KEY")
    fi

    aws s3 cp "$latest_backup" "$dest" "${upload_args[@]}"
    aws s3 cp "$checksum_file" "$checksum_dest" "${upload_args[@]}"

    aws s3 cp "$dest" "${tmp_dir}/${base_name}" "${upload_args[@]}"
    aws s3 cp "$checksum_dest" "${tmp_dir}/${base_name}.sha256" "${upload_args[@]}"
    ;;
  gcs)
    if ! command -v gsutil >/dev/null 2>&1; then
      echo "gsutil is required for GCS tests" >&2
      exit 1
    fi
    GCS_BUCKET=${GCS_BUCKET:-}
    GCS_PREFIX=${GCS_PREFIX:-test}
    GCS_ENCRYPTION_KEY_BASE64=${GCS_ENCRYPTION_KEY_BASE64:-}
    if [ -z "$GCS_BUCKET" ]; then
      echo "GCS_BUCKET is required" >&2
      exit 1
    fi

    dest="gs://${GCS_BUCKET}/${GCS_PREFIX}/${base_name}"
    checksum_dest="gs://${GCS_BUCKET}/${GCS_PREFIX}/${base_name}.sha256"

    gsutil_args=()
    if [ -n "$GCS_ENCRYPTION_KEY_BASE64" ]; then
      gsutil_args+=(-o "GSUtil:encryption_key=${GCS_ENCRYPTION_KEY_BASE64}")
    fi

    gsutil "${gsutil_args[@]}" cp "$latest_backup" "$dest"
    gsutil "${gsutil_args[@]}" cp "$checksum_file" "$checksum_dest"

    gsutil "${gsutil_args[@]}" cp "$dest" "${tmp_dir}/${base_name}"
    gsutil "${gsutil_args[@]}" cp "$checksum_dest" "${tmp_dir}/${base_name}.sha256"
    ;;
  *)
    echo "Unknown PROVIDER: $PROVIDER (expected s3 or gcs)" >&2
    exit 1
    ;;
esac

(
  cd "$tmp_dir"
  sha256sum -c "${base_name}.sha256"
)

echo "Offsite backup test passed (${PROVIDER})"
