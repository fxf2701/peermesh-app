# Relay Backup + Restore Runbook (Postgres)

This runbook describes how to automate Postgres backups for the relay and how
operators can validate restores. It targets Docker, Kubernetes, and VM setups.
See `relay_deploy_rollback_runbook.md` for deployment/rollback procedures.

## 1) Objectives

- Automated, periodic backups.
- Encrypted backups at rest.
- Restore validation workflow with clear pass/fail checks.
- Retention and cleanup policies.

## 2) Assumptions

- Database is Postgres.
- Relay uses `DATABASE_URL` (see `server/relay/.env`).
- Backups are stored outside the relay container.

### Registry Note

This runbook applies to Relay databases only.

If Registry is deployed as a separate service, Registry databases must be backed up independently using a dedicated Registry backup runbook.

Relay backups do not include Registry data.

## 3) Backup Strategy

### 3.1 Recommended format

- Use `pg_dump` with custom format (`-Fc`) for efficient storage and restore.
- Compress and optionally encrypt the dump.

### 3.2 Retention policy (example)

- Daily backups for 7 days.
- Weekly backups for 4 weeks.
- Monthly backups for 6 months.

Adjust retention based on storage and compliance requirements.

## 4) Provided Scripts (VM or host)

Scripts are provided in `server/relay/scripts/`:

- `server/relay/scripts/backup-postgres.sh`
- `server/relay/scripts/backup-postgres-docker.sh`
- `server/relay/scripts/create-restore-db-docker.sh`
- `server/relay/scripts/restore-validate-postgres.sh`
- `server/relay/scripts/restore-validate-postgres-docker.sh`
- `server/relay/scripts/restore-validate-postgres-docker-workflow.sh`
- `server/relay/scripts/backup-healthcheck.sh`
- `server/relay/scripts/backup-alert.sh`
- `server/relay/scripts/verify-backups.sh`
- `server/relay/scripts/verify-backups-docker.sh`
- `server/relay/scripts/test-backup-restore.js`
- `server/relay/scripts/run-backup-restore-tests.sh`
- `server/relay/scripts/sync-backups-s3.sh`
- `server/relay/scripts/sync-backups-gcs.sh`
- `server/relay/scripts/test-offsite-backup.sh`
- `server/relay/scripts/restore-drill-docker.sh`

Backup usage:

```bash
BACKUP_DIR=/var/backups/relay \
RETENTION_DAYS=14 \
DATABASE_URL=postgres://... \
server/relay/scripts/backup-postgres.sh
```

Docker usage (requires `docker` on host and mounted backup path):

```bash
CONTAINER_NAME=peermesh-postgres \
DB_USER=postgres \
DB_NAME=relay \
BACKUP_DIR=/var/backups/relay \
server/relay/scripts/backup-postgres-docker.sh
```

The Docker backup script also writes a `.sha256` checksum alongside the dump.

Docker restore validation (restore into an existing DB, e.g. `relay_restore`):

```bash
CONTAINER_NAME=peermesh-postgres \
DB_USER=postgres \
DB_NAME=relay_restore \
server/relay/scripts/create-restore-db-docker.sh

CONTAINER_NAME=peermesh-postgres \
DB_USER=postgres \
DB_NAME=relay_restore \
server/relay/scripts/restore-validate-postgres-docker.sh /path/to/relay_<STAMP>.dump
```

Docker restore workflow (auto-create + restore + validate):

```bash
CONTAINER_NAME=peermesh-postgres \
DB_USER=postgres \
DB_NAME=relay_restore \
server/relay/scripts/restore-validate-postgres-docker-workflow.sh /path/to/relay_<STAMP>.dump
```

Run a full backup + restore test (Docker):

```bash
CONTAINER_NAME=peermesh-postgres \
DB_USER=postgres \
DB_NAME=relay \
BACKUP_DIR_HOST=server/backups \
server/relay/scripts/run-backup-restore-tests.sh
```

Schedule via cron (daily at 02:15 UTC):

```bash
15 2 * * * BACKUP_DIR=/var/backups/relay DATABASE_URL=postgres://... /opt/scripts/backup_postgres.sh
```

## 5) Docker Compose Backup

If Postgres runs in Compose, use `pg_dump` inside the db container:

```bash
docker exec -i peermesh-postgres pg_dump -Fc -U relay relay > /var/backups/relay/relay_$(date -u +"%Y%m%dT%H%M%SZ").dump
```

Notes:
- Ensure `/var/backups/relay` is a host-mounted path.
- Do not store backups in the same container volume as the database.

Optional: use the `backup` profile in `docker-compose.dev.yml` to run a
sidecar-like backup loop:

```bash
docker compose -f docker-compose.dev.yml --profile backup up -d relay_backup
```

The backup service reads `BACKUP_RETENTION_DAYS` and `BACKUP_INTERVAL_SECONDS`
from `server/.env.dev`. Override them there or by setting container env vars.

For production-like Compose, use:

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml --profile backup up -d relay_backup
```

The backup service reads `BACKUP_RETENTION_DAYS`, `BACKUP_INTERVAL_SECONDS`,
and `BACKUP_MAX_AGE_SECONDS` from `server/relay/.env`.

Smoke test (Docker):

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml --profile backup up -d relay_backup
docker logs --tail=50 peermesh-relay-backup
```

The Docker backup service writes progress logs to stdout and touches
`/backup/last_backup.ok` on success; the relay service should read the same
host directory through the `./backups -> /var/backups/relay` bind mount using
`BACKUP_OK_FILE=/var/backups/relay/last_backup.ok` and
`RESTORE_DRILL_OK_FILE=/var/backups/relay/last_restore_drill.ok`.
The backup healthcheck verifies the file exists
and is newer than `BACKUP_MAX_AGE_SECONDS` (defaults to 2x
`BACKUP_INTERVAL_SECONDS`). Healthcheck failures emit a log line from
`server/relay/scripts/backup-healthcheck.sh`.

## 6) Kubernetes Backup

Options:
- Use a CronJob that runs `pg_dump` against the database service.
- Store output in an object store (S3, GCS) or a persistent volume.

Example CronJob:

```yaml
# See server/relay/config/relay-backup-cronjob.yaml for a full example.
```

### 6.1 Kubernetes manifest

Full manifest example:

- `server/relay/config/relay-backup-cronjob.yaml`

Apply with:

```bash
kubectl apply -f server/relay/config/relay-backup-cronjob.yaml
```

Smoke test (K8s):

```bash
kubectl create job --from=cronjob/relay-db-backup relay-db-backup-smoke
kubectl logs job/relay-db-backup-smoke
```

## 6.2 VM automation (systemd)

Systemd unit + timer examples:

- `server/relay/config/relay-backup.service`
- `server/relay/config/relay-backup.timer`

Install (adjust paths/user first):

```bash
sudo cp server/relay/config/relay-backup.service /etc/systemd/system/disaster-app-relay-backup.service
sudo cp server/relay/config/relay-backup.timer /etc/systemd/system/disaster-app-relay-backup.timer
sudo systemctl daemon-reload
sudo systemctl enable --now disaster-app-relay-backup.timer
```

Smoke test (systemd):

```bash
sudo systemctl start disaster-app-relay-backup.service
systemctl status disaster-app-relay-backup.service --no-pager
```

## 7) Restore Procedure

### 7.1 Prepare a restore target

- Use a fresh Postgres instance (new DB) for validation.
- Ensure the relay is stopped or uses a different database.

### 7.2 Restore steps

Use a dedicated restore database URL (already created):

```bash
RESTORE_DATABASE_URL=postgres://.../relay_restore \
server/relay/scripts/restore-validate-postgres.sh /path/to/relay_<STAMP>.dump
```

### 7.3 Validate restore

- Check row counts for core tables:

```bash
psql -d relay_restore -c "SELECT count(*) FROM events;"
psql -d relay_restore -c "SELECT count(*) FROM federation_cursors;"
```

- Run a basic app-level smoke test (optional) using the restored DB.

## 8) Restore Validation Workflow

Recommended workflow:

1) Backup is created.
2) Restore into a temporary DB (`relay_restore`).
3) Run quick SQL checks (row counts, schema).
4) If checks pass, mark backup as valid.
5) If checks fail, alert and keep the last known-good backup.

## 9) Security Notes

- Store backups encrypted at rest.
- Restrict access to backup storage.
- Rotate encryption keys and document key recovery steps.

## 10) Offsite/Offline Storage + Encryption Policy

### 10.1 Offsite storage options

- Object storage (S3/GCS/compatible): recommended for durability and access control.
- NAS (separate host/zone): acceptable but less resilient to site-wide outages.
- Offline/air-gapped copies: for high-risk environments or ransomware protection.

Guidelines:
- Keep at least one copy off the primary host/cluster.
- Avoid storing backups only on the database volume.
- Use immutable/locked buckets where possible.

### 10.2 Encryption policy

Minimum baseline:
- Encrypt backups at rest with a strong key (KMS/Vault or manual key).
- Keep keys outside the backup storage location.
- Restrict access to keys to a small operator set.

Rotation policy:
- Rotate keys on a fixed cadence (e.g., 90 days) or after incidents.
- New backups use the latest key; old backups remain decryptable by retained keys.
- Document recovery steps and validate decrypt + restore after rotation.

### 10.3 Example storage layout (S3)

- `s3://relay-backups/daily/relay_YYYYMMDD.dump`
- `s3://relay-backups/weekly/relay_YYYYWww.dump`
- `s3://relay-backups/monthly/relay_YYYYMM.dump`

Retention can be enforced with object lifecycle policies.

### 10.4 Upload examples (S3/GCS)

S3 (AWS CLI):

```bash
aws s3 cp /var/backups/relay/relay_20250101T021500Z.dump \
  s3://relay-backups/daily/relay_20250101T021500Z.dump
```

S3 with SSE-KMS:

```bash
aws s3 cp /var/backups/relay/relay_20250101T021500Z.dump \
  s3://relay-backups/daily/relay_20250101T021500Z.dump \
  --sse aws:kms --sse-kms-key-id <KMS_KEY_ID>
```

GCS (gsutil):

```bash
gsutil cp /var/backups/relay/relay_20250101T021500Z.dump \
  gs://relay-backups/daily/relay_20250101T021500Z.dump
```

GCS with CMEK:

```bash
gsutil -o \"GSUtil:encryption_key\"=<BASE64_KEY> \
  cp /var/backups/relay/relay_20250101T021500Z.dump \
  gs://relay-backups/daily/relay_20250101T021500Z.dump
```

### 10.5 Local encryption examples (age/gpg)

Encrypt with `age` (public key):

```bash
age -r <AGE_PUBLIC_KEY> \
  -o /var/backups/relay/relay_20250101T021500Z.dump.age \
  /var/backups/relay/relay_20250101T021500Z.dump
```

Encrypt with `gpg` (recipient key):

```bash
gpg --encrypt --recipient <KEY_ID> \
  --output /var/backups/relay/relay_20250101T021500Z.dump.gpg \
  /var/backups/relay/relay_20250101T021500Z.dump
```

### 10.6 Key rotation checklist (base64 key)

- Store the base64 key outside the backup host (password manager or vault).
- Version the key (e.g., `backup_key_v1`, `backup_key_v2`) and record activation dates.
- When rotating, keep old keys accessible for the full retention window.
- After rotation, verify decryption + restore using both new and old backups.
- Restrict key access to a small operator group; audit access changes.

### 10.7 Offsite automation scripts (S3/GCS)

Scripts:

- `server/relay/scripts/sync-backups-s3.sh`
- `server/relay/scripts/sync-backups-gcs.sh`

S3 example:

```bash
BACKUP_DIR=/var/backups/relay \
S3_BUCKET=relay-backups \
S3_PREFIX=daily \
S3_SSE_C_KEY=<BASE64_KEY> \
server/relay/scripts/sync-backups-s3.sh
```

GCS example:

```bash
BACKUP_DIR=/var/backups/relay \
GCS_BUCKET=relay-backups \
GCS_PREFIX=daily \
GCS_ENCRYPTION_KEY_BASE64=<BASE64_KEY> \
server/relay/scripts/sync-backups-gcs.sh
```

Schedule via cron (daily at 02:30 UTC):

```bash
30 2 * * * BACKUP_DIR=/var/backups/relay S3_BUCKET=relay-backups S3_PREFIX=daily /opt/scripts/sync-backups-s3.sh
```

## 11) Operational Checklist

- Backups run daily and are monitored.
- Restore validation runs weekly or after any major upgrade.
- Retention is enforced.
- Backup logs are retained for audit.
- Federation state is included in backups when federation is enabled.
- Registry metadata is backed up separately if Registry is deployed independently.

## 12) Periodic Backup Verification

Verify backup integrity by checking checksums and optionally removing invalid
files:

```bash
BACKUP_DIR=/var/backups/relay \
DELETE_INVALID=1 \
server/relay/scripts/verify-backups.sh
```

Schedule it weekly via cron (Sunday 03:10 UTC):

```bash
10 3 * * 0 BACKUP_DIR=/var/backups/relay DELETE_INVALID=1 /opt/scripts/verify-backups.sh
```

Docker-friendly verification (mount a host folder into the container):

```bash
BACKUP_DIR_HOST=server/backups \
DELETE_INVALID=1 \
server/relay/scripts/verify-backups-docker.sh
```

## 13) Backup Alerting (stale or missing backups)

Use the alert script to emit a log line and optionally call a webhook when
backups are missing or too old.

Example (cron every 15 minutes):

```bash
*/15 * * * * BACKUP_OK_FILE=/var/backups/relay/last_backup.ok BACKUP_MAX_AGE_SECONDS=172800 /opt/scripts/backup-alert.sh
```

Webhook example:

```bash
BACKUP_OK_FILE=/var/backups/relay/last_backup.ok \
BACKUP_MAX_AGE_SECONDS=172800 \
ALERT_WEBHOOK_URL=https://hooks.example.org/backup \
server/relay/scripts/backup-alert.sh
```

## 14) Offsite Backup Test (S3/GCS)

This test uploads the latest backup + checksum to offsite storage, downloads
them back, and verifies the checksum.

S3 example:

```bash
PROVIDER=s3 \
BACKUP_DIR=/var/backups/relay \
S3_BUCKET=relay-backups \
S3_PREFIX=test \
S3_SSE_C_KEY=<BASE64_KEY> \
server/relay/scripts/test-offsite-backup.sh
```

## 15) Periodic Restore Drill (Docker)

This drill restores the latest backup into a new database and runs basic SQL
checks. It can optionally clean up old drill databases by age.

Example (keep drill DBs for 7 days):

```bash
CONTAINER_NAME=peermesh-postgres \
DB_USER=postgres \
BACKUP_DIR=server/backups \
RESTORE_DB_PREFIX=relay_restore_drill \
KEEP_DAYS=7 \
server/relay/scripts/restore-drill-docker.sh
```

Using .env/.env.dev:

```bash
ENV_FILE=server/.env.dev \
server/relay/scripts/restore-drill-docker.sh
```

Note: in dev, set `BACKUP_DIR=server/backups` to match the Compose volume mount.

GCS example:

```bash
PROVIDER=gcs \
BACKUP_DIR=/var/backups/relay \
GCS_BUCKET=relay-backups \
GCS_PREFIX=test \
GCS_ENCRYPTION_KEY_BASE64=<BASE64_KEY> \
server/relay/scripts/test-offsite-backup.sh
```

Local-only (skip offsite upload):

```bash
BACKUP_DIR=/var/backups/relay \
SKIP_OFFSITE=1 \
server/relay/scripts/test-offsite-backup.sh
```
