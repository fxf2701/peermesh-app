# Production Backup Automation Test Notes

Status: not tested yet.

## Why not tested

- Requires a VM with systemd or a Kubernetes cluster to validate timers/CronJobs.
- Not available in the current dev environment.

## Preconditions

- A Postgres instance reachable via `DATABASE_URL`.
- Backup scripts present under `server/relay/scripts/`.
- Sufficient storage at `/var/backups/relay` (or configured path).

## Systemd test (VM)

1) Install units:

```bash
sudo cp server/relay/config/relay-backup.service /etc/systemd/system/disaster-app-relay-backup.service
sudo cp server/relay/config/relay-backup.timer /etc/systemd/system/disaster-app-relay-backup.timer
sudo systemctl daemon-reload
sudo systemctl enable --now disaster-app-relay-backup.timer
```

2) Smoke test:

```bash
sudo systemctl start disaster-app-relay-backup.service
systemctl status disaster-app-relay-backup.service --no-pager
ls -l /var/backups/relay
```

## Kubernetes test

1) Apply CronJob:

```bash
kubectl apply -f server/relay/config/relay-backup-cronjob.yaml
```

2) Smoke test:

```bash
kubectl create job --from=cronjob/relay-db-backup relay-db-backup-smoke
kubectl logs job/relay-db-backup-smoke
```
