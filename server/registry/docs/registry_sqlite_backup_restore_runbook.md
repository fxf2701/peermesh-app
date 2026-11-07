# Registry SQLite Backup and Restore Runbook

This runbook covers backup and restore procedures for the Registry SQLite
database configured by `REGISTRY_DB_PATH`.

Typical default path:

- local/container default: `/app/data/registry.sqlite`

Use this runbook together with:

- `registry_admin_operator_guide.md`
- `registry_policy_rollback_runbook.md`
- `registry_compromised_relay_session_incident_runbook.md`

## 1. Backup Scope

The registry SQLite database contains:

- relay directory state
- trust/status/federation flags
- certificates
- audit history
- persisted policy feed
- persisted revocation feed
- federation session state
- client access grant state
- client abuse report state

Registry backup is service-level. It is not a row-level export mechanism.

## 2. When to Take a Backup

Recommended timing:

- before every registry deployment
- before policy or revocation maintenance windows
- before schema-moving releases
- before credential or signing-secret rotation windows
- on a regular production schedule

Minimum expectation:

- keep at least one fresh pre-deploy backup
- keep at least one recent known-good restore candidate

## 3. Recommended Backup Method

Preferred baseline:

1. Stop the registry service for a short maintenance window.
2. Copy the SQLite DB file.
3. If SQLite WAL mode has produced adjacent files, copy:
   - `registry.sqlite-wal`
   - `registry.sqlite-shm`
4. Store the backup outside the active registry working directory.
5. Record:
   - backup timestamp
   - application commit/tag
   - source DB path
   - operator who took the backup

Example:

```bash
cp /path/to/registry.sqlite \
  /safe-backups/registry_$(date -u +"%Y%m%dT%H%M%SZ").sqlite
```

If WAL files are present:

```bash
stamp=$(date -u +"%Y%m%dT%H%M%SZ")
cp /path/to/registry.sqlite "/safe-backups/registry_${stamp}.sqlite"
cp /path/to/registry.sqlite-wal "/safe-backups/registry_${stamp}.sqlite-wal"
cp /path/to/registry.sqlite-shm "/safe-backups/registry_${stamp}.sqlite-shm"
```

## 3.1 Maintenance Window Checklist

Use this checklist before any registry deployment or rollback that may touch the
SQLite schema, policy state, revocation state, or signing/admin credentials.

Before the window:

1. Confirm a fresh backup destination exists and has free space.
2. Confirm the current `REGISTRY_DB_PATH` on the target host/container mount.
3. Record:
   - current registry image/tag or commit
   - active env/config source
   - operator starting the window
   - planned rollback point
4. Confirm a known-good restore candidate already exists.
5. Announce a short operator maintenance window if other admins depend on the service.

During the window:

1. Stop the registry service cleanly.
2. Copy the SQLite DB file and any `-wal` / `-shm` companions.
3. Label the backup as the pre-change snapshot for that deploy/rollback.
4. Apply the deployment, rollback, or other change.
5. Start the registry service and complete the post-change verification checklist.

After the window:

1. Verify `/healthz` and `/readyz`.
2. Verify admin login.
3. Verify relay directory, policy feed, revocation feed, and federation sessions.
4. Record the result of the maintenance window and the exact backup path kept for rollback.
5. If verification fails, stop and restore from the pre-change snapshot instead of continuing with partial service.

## 4. Container Example

If the registry runs in Docker and the DB file is mounted on the host:

1. stop the registry container
2. copy the mounted SQLite files from the host path
3. restart the registry container after the copy is complete

If the DB file is only inside the container filesystem, move to a host-mounted
data path before relying on backups.

## 5. Restore Procedure

1. Stop the registry service.
2. Preserve the current DB files in a temporary rollback location.
3. Restore the selected backup files to `REGISTRY_DB_PATH`.
4. Ensure file ownership and permissions match the runtime user.
5. Start the registry service.
6. Verify:
   - `/healthz`
   - `/readyz`
   - admin login
   - relay directory
   - policy feed
   - revocation feed
   - federation sessions view

## 6. Minimal Restore Verification

Confirm at least:

- a known relay record is present
- expected trust/status fields are intact
- policy values match the intended restored snapshot
- revocation feed version matches the intended restored snapshot
- recent audit rows are readable
- admin login works with expected credentials

## 7. Safety Notes

- Prefer a short maintenance window over blind hot-copy assumptions unless you
  have separately validated a live SQLite backup method for this deployment.
- Use the maintenance-window checklist above for deployments, rollbacks, policy
  repair work, and signing/admin credential rotations that may require DB
  recovery.
- Keep backups outside the registry container working directory.
- Encrypt backups at rest if they leave the host or contain sensitive operator
  metadata.
- Do not treat this backup as a substitute for credential/signing-secret
  rotation procedures.
- Before any rollback to an older build, verify backup freshness and then use
  `registry_policy_rollback_runbook.md` and the schema rollback guidance in
  `registry_admin_operator_guide.md`.
