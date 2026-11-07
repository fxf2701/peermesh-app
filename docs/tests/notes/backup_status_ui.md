# Backup Status UI Test Notes

Status: not tested yet.

## Preconditions

- Relay running with admin auth enabled.
- `BACKUP_OK_FILE` points to an existing file.
- (Optional) `RESTORE_DRILL_OK_FILE` points to an existing file for restore drill status.
- Operator can access `/admin/dashboard`.

## Test Steps

1) Prepare a fake backup OK file:

```bash
mkdir -p /tmp/backup
touch /tmp/backup/last_backup.ok
```

2) Configure env (dev example):

```bash
BACKUP_OK_FILE=/tmp/backup/last_backup.ok
BACKUP_MAX_AGE_SECONDS=60
RESTORE_DRILL_OK_FILE=/tmp/backup/last_restore_drill.ok
```

3) Reload `/admin/dashboard` and verify:

- **Last Backup** shows a recent timestamp.
- **Age** increases over time.
- **Status** shows `ok` (pill color ok).
- **Last Restore Drill** shows `—` if file missing.

4) Create a restore drill marker:

```bash
touch /tmp/backup/last_restore_drill.ok
```

- **Last Restore Drill** shows a timestamp.

5) Simulate stale backup:

```bash
BACKUP_MAX_AGE_SECONDS=1
```

- Wait >1s and reload.
- **Status** shows `stale` (pill color offline).

## Expected Errors

- If `BACKUP_OK_FILE` missing, UI shows "Backup status unavailable."
