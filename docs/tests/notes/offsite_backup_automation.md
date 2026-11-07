# Offsite Backup Automation Test Notes

Status: not tested yet.

## Why not tested

- Requires access to S3 or GCS buckets and valid credentials.
- Account verification for cloud providers is still in progress.

## Preconditions

- Latest backup dump + checksum in `BACKUP_DIR`.
- `aws` CLI (S3) or `gsutil` (GCS) installed and authenticated.
- Bucket created and write permissions granted.

## S3 test

```bash
BACKUP_DIR=/var/backups/relay \
S3_BUCKET=relay-backups \
S3_PREFIX=test \
S3_SSE_C_KEY=<BASE64_KEY> \
server/relay/scripts/sync-backups-s3.sh
```

## GCS test

```bash
BACKUP_DIR=/var/backups/relay \
GCS_BUCKET=relay-backups \
GCS_PREFIX=test \
GCS_ENCRYPTION_KEY_BASE64=<BASE64_KEY> \
server/relay/scripts/sync-backups-gcs.sh
```

## Validation

- Confirm uploaded files exist in the bucket.
- Download one file and verify checksum with the `.sha256` file.
