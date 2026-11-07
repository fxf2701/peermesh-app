# Phase 1.3 Task List — Relay Community Ops

This checklist focuses on long-term operations, security hygiene, and federation governance.

## 1) Operations & Reliability
- [x] Add automated backup + restore runbook (Postgres).
  - Done: backup + restore validation scripts in `server/relay/scripts/` and runbook in `server/relay/docs/relay_backup_restore_runbook.md`.
- [x] Offsite/offline backup storage + encryption/rotation policy.
  - Done: policy + examples documented in `server/relay/docs/relay_backup_restore_runbook.md`.
- [-] Production backup automation (systemd timer / K8s CronJob).
  - Done: systemd unit/timer + K8s CronJob manifest documented (see `server/relay/docs/relay_backup_restore_runbook.md`).
  - Test note: `docs/tests/notes/production_backup_automation.md`.
- [x] Backup alerting (failures or stale backups).
  - Done: alert script + runbook guidance in `server/relay/docs/relay_backup_restore_runbook.md`.
- [x] Periodic restore drills (validate backup usability).
  - Done: Docker restore drill script + runbook guidance in `server/relay/docs/relay_backup_restore_runbook.md`.
- [-] Offsite backup automation (sync to S3/GCS on schedule).
  - Done: sync scripts in `server/relay/scripts/` and cron examples in `server/relay/docs/relay_backup_restore_runbook.md`.
  - Test note: `docs/tests/notes/offsite_backup_automation.md`.
- [x] Docker backup automation (compose backup profile).
  - Done: backup service in `docker-compose.yml` (see `server/relay/docs/relay_backup_restore_runbook.md`).
- [x] Add alert rules for core metrics (DB saturation, error budget, federation failures).
  - Done: alert rules template in `server/relay/config/relay_alert_rules.yml`.
- [x] Add runbook for incident response (DB outage, peer abuse, traffic spikes).
  - Done: `server/relay/docs/relay_incident_response_runbook.md`.
- [x] Define SLO/SLA targets and alert thresholds for ops.
  - Done: `server/relay/docs/relay_production_guide.md`.
- [x] Deployment/rollback runbook (including DB migrations).
  - Done: `server/relay/docs/relay_deploy_rollback_runbook.md`.
- [x] Disaster recovery plan with RPO/RTO targets (see `server/relay/docs/relay_disaster_recovery_plan.md`).

## 2) Security & Secrets
- [-] Integrate secret management guidance (env files vs vaults).
  - Done: documented secret storage and rotation recommendations.
  - Test note: `docs/tests/notes/secret_management_guidance.md`.
- [-] Token rotation playbook (peer tokens + request signatures).
  - Done: rolling update steps to rotate secrets safely.
  - Test note: `docs/tests/notes/token_rotation_playbook.md`.
- [-] Harden metrics exposure (auth or strict IP allowlist).
  - Done: production guidance + reverse proxy snippets.
  - Test note: `docs/tests/notes/harden_metrics_exposure.md`.
- [x] Security hardening checklist (WAF rules, headers, log redaction) (see `server/relay/docs/relay_security_hardening_checklist.md`).

## 3) Federation Governance
- [x] Dynamic peer banlist (DB-backed).
  - Done: CRUD endpoints + DB table for banlist entries.
  - Test script: `server/relay/scripts/test-banlist-dynamic.js`.
  - Test runner: `server/relay/scripts/run-banlist-dynamic-tests.sh`.
- [x] Banlist UI for operators.
  - Done: Settings UI can add/remove/toggle bans without restart (see `server/relay/docs/relay_admin_ui_guide.md`).
- [-] Peer reputation signals (manual scoring or annotations).
  - Done: optional reputation fields stored and displayed in UI (see `server/relay/docs/relay_admin_ui_guide.md`).
  - Test note: `docs/tests/notes/peer_reputation_signals.md`.
  - Test script: `server/relay/scripts/test-reputation-crud.js`.
  - Test runner: `server/relay/scripts/run-reputation-crud-tests.sh`.
- [x] Basic admin UI architecture (routing + static assets) (see `server/relay/docs/relay_admin_ui_guide.md`).
  - Done: static assets under `server/relay/src/public/admin/` and `/admin` routes.
- [x] Admin UI operator guide (usage + audit log practices) (see `server/relay/docs/relay_admin_operator_guide.md`).
- [-] Admin UI auth (login/logout/password change + terminal reset) (see `server/relay/docs/relay_admin_ui_guide.md`).
  - Done: file-based auth + login/logout/change-password endpoints + reset script.
  - Test note: `docs/tests/notes/admin_ui_auth.md`.
- [x] Admin UI development guideline (see `docs/server/admin_ui_development_guideline.md`).
  - Note: Phase 1.4 will focus on Admin UI expansion (additional pages + polish).
- [x] Dedupe enhancement note: consider content/nonce-based dedupe beyond `eid` (see `docs/server/notes/relay_dedupe_note.md`).

## 4) Deployment & Platform
- [x] Helm chart expansion (DB/Redis/Ingress/Secrets).
  - Done: chart installs relay + dependencies with sane defaults.
  - Note: run `helm template relay server/helm/relay` before first deploy (see `server/relay/docs/relay_production_guide.md`).
  - Test script: `server/relay/scripts/run-helm-chart-tests.sh`.
- [x] Grafana dashboard bundle (relay ops) (see `server/relay/docs/relay_grafana_dashboards.md`).
  - Done: JSON dashboards shipped and documented.
- [x] Dev migration sync for postgres_2 (apply migrations to secondary DB in multi-profile dev).
  - Done: `server/relay/scripts/migrate.sh` supports `DATABASE_URL_SECONDARY` / `DATABASE_URLS`; see `server/.env.dev`.
- [x] Relay TLS edge configuration guide (see `server/relay/docs/relay_tls_edge_guide.md`).
  - Done: nginx/traefik examples for Docker/K8s/VM documented.

## 5) Data Management
- [x] Retention policy configuration (exp/cleanup tuning by profile) (see `server/relay/docs/relay_retention_policy.md`).
  - Done: documented presets and safe bounds.
- [x] Optional data export for community transparency (see `server/relay/docs/relay_data_export_guide.md`).
  - Done: export script + checksum guidance in `server/relay/scripts/export-events.js`.
  - Test script: `server/relay/scripts/test-export-events.sh`.

Status: Phase 1.3 closed with accepted pending tests.
Next: Phase 1.4 Admin UI plan in `docs/milestones/Phase_1_4_Task_List.md`.
