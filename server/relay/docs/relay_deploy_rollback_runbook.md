# PeerMesh Relay Deployment + Rollback Runbook

This runbook defines safe deployment, upgrade, validation and rollback procedures for PeerMesh Relay nodes.

This document applies to:

* Official PeerMesh Relay
* Trusted Federation Relay
* Independent Relay Deployments

This document does not apply to Registry servers.

See:

* NODE_OPERATOR_GUIDE.md
* RELAY_PROTOCOL_SPEC.md
* FEDERATION_PROTOCOL.md
* REGISTRY_PROTOCOL.md

---

# 1) Pre-Deploy Checklist

Before every deployment:

## Infrastructure

* Confirm host resources are healthy.
* Confirm storage has sufficient free space.
* Confirm TLS certificates are valid.

## Backup

* Confirm latest backup exists.
* Confirm latest restore drill passed.
* Confirm backup verification passed.

See:

* relay_backup_restore_runbook.md

## Configuration

Verify:

* DATABASE_URL
* REQUEST_SIGNATURE_REQUIRED=1
* REQUEST_SIGNATURE_SECRET
* FEDERATION_ENABLED
* REGISTRY_URL (if used)

## Compatibility

Verify:

* Android clients remain protocol compatible.
* Federation peers remain protocol compatible.
* Relay Protocol changes are documented.

---

# 2) Deployment Types

Supported deployment targets:

* Docker Compose
* VM + systemd
* Kubernetes

---

# 3) Docker Compose Deployment

Pull latest code:

```bash
git pull
```

Pull images:

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml pull
```

Build and deploy:

```bash
cd server/relay
docker compose --env-file .env -f docker-compose.yml up -d --build relay
```

---

# 4) VM Deployment

```bash
cd /opt/peermesh-relay
git pull
./server/relay/scripts/build.sh
sudo systemctl restart peermesh-relay.service
```

---

# 5) Kubernetes Deployment

```bash
kubectl apply -f server/helm/relay
```

---

# 6) Database Migrations

Run migrations only after backups are verified.

Docker:

```bash
docker exec -i peermesh-relay npm run migrate:up
```

VM:

```bash
node server/relay/scripts/migrate.js up
```

Kubernetes:

```bash
kubectl exec -it deploy/relay -- node scripts/migrate.js up
```

---

# 7) Post-Deploy Verification

## Health Endpoints

```bash
curl http://localhost:8080/healthz
curl http://localhost:8080/readyz
```

Expected:

```json
{
  "ok": true
}
```

---

## Metrics

```bash
curl http://localhost:8080/metrics
```

---

## Request Signature Verification

Verify:

* signed ingest succeeds
* unsigned ingest is rejected (if enabled)

---

## Relay Protocol Smoke Test

Verify:

* POST /v1/events:ingest
* GET /v1/events:fetch

using valid signed requests.

---

## Federation Verification

If federation enabled:

Verify:

* peer connectivity
* federation heartbeat
* sync activity
* cursor advancement

---

## Registry Verification

If registry enabled:

Verify:

* heartbeat success
* relay visible in registry
* trust level unchanged
* status active

---

# 8) Rollback Strategy

Rollback order:

1. Config rollback
2. Application rollback
3. Migration rollback
4. Database restore

Use the least destructive option possible.

---

# 9) Config Rollback

Restore previous:

* .env
* secrets
* federation config
* registry config

Restart relay.

---

# 10) Application Rollback

Docker:

```bash
docker compose pull relay:<previous-tag>
docker compose up -d relay
```

VM:

```bash
git checkout <previous-tag>
sudo systemctl restart peermesh-relay.service
```

---

# 11) Migration Rollback

If supported:

```bash
node scripts/migrate.js down
```

Only perform rollback if migration documentation explicitly supports it.

---

# 12) Database Restore

If migration rollback is unsafe:

1. Stop relay.
2. Restore latest verified backup.
3. Start relay.
4. Re-run validation checks.

See:

* relay_backup_restore_runbook.md

---

# 13) Incident Response

For failed deployment:

* record timestamp
* record release version
* record migration version
* record rollback action

Store incident notes.

---

# 14) Release Checklist

Deployment complete when:

* health checks pass
* ready checks pass
* protocol tests pass
* federation tests pass (if enabled)
* registry heartbeat passes (if enabled)
* metrics are healthy
* logs show no critical errors
