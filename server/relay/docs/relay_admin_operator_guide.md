# Relay Admin Operator Guide

This guide helps operators use the internal Admin UI to manage relay governance.

> Note
>
> This guide applies to Relay Server administration only.
>
> Registry administration is intentionally handled by a separate Registry service and separate Registry Admin UI.
>
> See:
>
> - Registry production docs entrypoint: `server/registry/docs/README.md`
> - Registry production operator guide: `server/registry/docs/registry_admin_operator_guide.md`
> - Registry Admin UI guide: `server/registry/docs/registry_admin_ui_guide.md`

This guide is for production Relay operations only.

If this is the first file you opened in this folder, read `README.md` in the same folder first.

Production runtime/layout:

* compose: `server/relay/docker-compose.yml`
* env: `server/relay/.env`
* start: `server/relay/start.sh`
* stop: `server/relay/stop.sh`

Developer/local simulation is intentionally documented separately and should use:

* `server/docker-compose.dev.yml`
* `server/.env.dev`
* `server/start.sh`
* `server/stop.sh`

## 0) Service Lifecycle

Typical production lifecycle commands:

```bash
cd /path/to/repo
server/relay/start.sh --build
server/relay/stop.sh
```

Optional profiles:

* `server/relay/start.sh --profiles backup`
* `server/relay/start.sh --profiles metrics`
* `server/relay/start.sh --profiles backup,metrics`

Notes:

* keep `server/relay/.env` on the VPS and treat it as operator-managed state
* keep `server/relay/config/`, `server/relay/data/`, and `server/relay/backups/` persistent on the VPS
* do not use the root `server/start.sh` launcher for production relay service management

## 1) Access

- Admin UI is internal only (not public).
- Ensure IP allowlist permits your client (if enabled).
  - When `IP_ALLOWLIST` is set, the Admin UI shows a banner to confirm the allowlist is active.
- Login with the configured admin account.

### Initialize `ADMIN_AUTH_FILE`

If this is the first production start and `server/relay/config/admin_auth.json` does not exist yet, create it on the VPS before opening the Relay Admin UI.

Default path:

* `ADMIN_AUTH_FILE=./config/admin_auth.json`

Recommended initialization:

```bash
cd /path/to/repo/server/relay
cp config/admin_auth.example.json config/admin_auth.json
./scripts/reset-admin-password.sh 'change-this-admin-password'
```

What this does:

* keeps the username as `admin` unless you override it
* replaces the placeholder hash with a real password hash
* writes the final file to `server/relay/config/admin_auth.json`
* uses local `node` if available, otherwise falls back to `docker compose run` on the relay host

Expected file shape:

```json
{
  "username": "admin",
  "password_hash": "pbkdf2_sha256$..."
}
```

Operational notes:

* treat `server/relay/config/admin_auth.json` as operator-managed state on the VPS
* do not commit it to git
* do not rely on deployment sync to create it automatically
* current `deploy.sh` intentionally avoids overwriting `config/admin_auth.json`

## 2) Login / Logout

- Open `/admin`.
- Login with username/password.
- Use logout after finishing tasks.

## 3) Banlist Management

- Add peer IDs or CIDR blocks to banlist.
- Provide a reason for auditability.
- Disable or remove entries when resolved.
- CSV imports require a signature header (`X-Banlist-Signature`) generated with `BANLIST_IMPORT_HMAC_SECRET`.
  - Test note: `docs/tests/notes/banlist_signed_import.md`.

## 4) Reputation Notes

- Add a manual score or note for peers.
- Use notes to track abuse history or trust status.

## 5) Audit and Review

- Review audit logs for admin actions.
- If a change caused issues, rollback by removing the entry.

## Federation vs Registry

Registry and Federation are separate systems.

Registry is used for:

- relay discovery
- trust distribution
- relay verification
- revocation and policy feeds

Federation is used for:

- relay-to-relay event synchronization

A relay may:

- use registry only
- use federation only
- use both
- use neither

Registry participation does not automatically imply federation participation.

---

## 6) Federation Join (Peer)

- Open the Federation → Peers page.
- Add the peer URL and the public entry token (shared by the remote operator).

Federation may occur in several modes:

- Official PeerMesh Federation
- Trusted Federation Relay
- Independent Federation Network

The Relay Admin UI only manages federation connectivity and synchronization.

Trust assignment and relay verification belong to Registry administration.
- Add sync GIDs that should be replicated, then enable auto-sync after handshake.
- Suggested connectivity bounds: each relay should connect to **at least 2** peers and **no more than 10** peers.
- Join requests are HMAC-signed using the public token; they place the peer in a pending state.
- A follow-up handshake issues a rotated token bound to the peer identity.
- Only peers with a valid rotated token may push/pull federation data.
- Join/handshake endpoints are rate limited (`FEDERATION_JOIN_RATE_LIMIT`, `FEDERATION_HANDSHAKE_RATE_LIMIT`); if you hit the limit, wait before retrying.
- Enforce TLS (`FEDERATION_REQUIRE_TLS=1`) so only encrypted peers can join/handshake; mTLS credentials live under `FEDERATION_MTLS_*`.
- If the relay exceeds `FEDERATION_CONNECTION_CAPACITY`, new join requests receive `capacity_full`; heartbeat TTL (`FEDERATION_HEARTBEAT_TTL_SECONDS`) controls how long a peer is counted as connected.
- Tokens are write-only in the UI and will not be displayed after saving.
  - GID sources: use the shared data catalog/runbook, dataset registry, or the data owner's documented GID list. GIDs must match event `gid` values to sync.
- Security notes: join/handshake are rate-limited; rotated tokens are bound to peer identity and expire after inactivity; use TLS/mTLS for peer traffic in production.
  - Risks: shared token is single-factor; token leaks grant access; no allowlist increases exposure; tokens are not tied to IP by default.
  - Mitigations: rotate tokens regularly; bind tokens to peer identity; enforce `FEDERATION_PEER_ALLOWLIST`; enable TLS/mTLS; disable unused mappings.
- Recommended availability defaults:
  - `FEDERATION_HEARTBEAT_TTL_SECONDS=30` to keep status fresh.
  - `FEDERATION_HEARTBEAT_FAILURE_THRESHOLD=6000` (~1 day at 15s heartbeat interval).
  - `FEDERATION_ROTATED_TOKEN_TTL_SECONDS=2592000` (30 days) to keep connections stable.

## 7) Peer Identity (This Relay)

- Peer URL is configured via `FEDERATION_SELF_URL`.
- Peer ID is configured via `FEDERATION_SELF_ID` (fallbacks to `FEDERATION_SELF_URL`).
- Use a globally unique, stable identifier (recommended: public relay URL/host).
- Avoid `localhost` or ephemeral IPs; use a stable domain or host+port.
- There is no reliable network-wide "taken peer id" check without a central registry.

## 8) Metrics Detail (Dashboard)

- Open `/admin/dashboard` and use the Metrics Detail card.
- Domains: Traffic, Database, Federation, Rate Limit.
- Values refresh automatically; use Refresh for an immediate update.
- Rate fields are derived from deltas between successive UI requests.
- Traffic fields:
  - `requests_total`: total HTTP requests since boot.
  - `errors_total`: total error responses (status >= 400) since boot.
  - `requests_per_s`: requests per second over the last sampling window.
  - `errors_per_s`: errors per second over the last sampling window.
  - `error_rate`: `errors_per_s / requests_per_s` (0-1).
- Database fields:
  - `pool_in_use`: active DB connections in use.
  - `pool_total`: total DB connections in the pool.
  - `pool_idle`: idle DB connections.
  - `pool_waiting`: queued requests waiting for a connection.
  - `wait_ms_avg`: average DB wait time in milliseconds.
  - `wait_ms_last`: last observed DB wait time in milliseconds.
- Federation fields:
  - `cursor_lag_seconds`: max observed cursor lag across peers.
  - `sync_success_per_s`: sync successes per second.
  - `sync_fail_per_s`: sync failures per second.
  - `failures_10m`: estimated failures over 10 minutes (rate * 600).
  - `backpressure_queue_max`: max queued sync requests.
  - `backpressure_in_flight_max`: max in-flight sync requests.
  - `backpressure_drops_per_s`: backpressure drops per second.
- Rate Limit fields:
  - `rate_limited_total`: total rate-limited requests since boot.
  - `rate_limited_per_s`: rate-limited requests per second.
  - `spike_active`: whether spike detector is active.
  - `spike_total`: total spike alerts since boot.

## 9) Backup Status

- Open `/admin/dashboard` and check the Backup Status card.
- Fields:
  - Last Backup: last successful backup time.
  - Status: `ok` or `stale` based on `BACKUP_MAX_AGE_SECONDS`.
  - Last Restore Drill: timestamp from `RESTORE_DRILL_OK_FILE`.
- If restore drill is stale, run a restore drill and update the OK file.
- Troubleshooting checklist:
  - Ensure the backup job is running on schedule (cron/systemd/K8s job).
  - Verify `BACKUP_OK_FILE` path exists and is writable by the job.
  - Check `BACKUP_MAX_AGE_SECONDS` to confirm the expected freshness window.
  - If status is stale, run the backup job manually and verify the OK file timestamp.

## 10) Retention & Cleanup

- Open `/admin/dashboard` and check the Retention & Cleanup card.
- Fields:
  - Profile: `RETENTION_PROFILE` (policy label).
  - Cleanup Interval: how often cleanup runs.
  - Last Cleanup: last run time + duration.
  - Next Run: calculated from last run + interval.

## 11) Alerts & Incidents

- Open `/admin/dashboard` and check the Alerts & Incidents card.
- Active alerts show current issues; use Ack to record a note.
- Recent incidents list reflects ack entries.
- Runbook links download PDF runbooks for offline use.

## 12) Settings (Read-only)

- Open `/admin/settings` to review effective configuration.
- Settings are read-only; edits require `.env` changes and a relay restart.
- Discovery toggle stores intent only (auto-connect logic is Phase 3).

## 13) Password Reset

If the password is forgotten, reset in terminal:

```bash
cd /path/to/repo/server/relay
./scripts/reset-admin-password.sh <new_password>
```
