# Admin UI Pages & Functions (Operator View)

This document lists the required Relay Admin UI pages and their detailed purpose,
data, actions, and related APIs/metrics. The Relay Admin UI is internal-only and
targets a single local admin account.

Scope:

- This document covers Relay Admin UI only.
- Registry Admin UI is documented separately.
- Relay Admin UI manages federation, peers, banlist, reputation, metrics, backup, retention, alerts, settings and audit logs.
- Registry Admin UI manages relay discovery, trust levels, certificates, revocations and policy feeds.

## 1) Authentication

### Login
- Purpose: secure access to the operator console.
- Shows: username, password input, login status message.
- Actions: login.
- API: `POST /admin/api/auth/login`

### Session
- Purpose: active session controls.
- Shows: current session state, TTL (if available).
- Actions: logout, change password.
- API: `GET /admin/api/auth/me`, `POST /admin/api/auth/logout`,
  `POST /admin/api/auth/change-password`

## 2) Dashboard (Federation-first snapshot)

- Purpose: single-screen operational snapshot.
- Shows:
  - Health: `/healthz`, `/readyz`, DB check latency.
  - Traffic: request rate, error rate, status mix.
  - DB pool: in-use/total, wait avg, waiting.
  - Federation: cursor lag, failures, backpressure queue.
  - Rate limit: spike active flag + recent totals.
  - Background: cleanup last duration, in-flight.
  - Backups: last backup age + status.
- Actions: quick links to Banlist/Reputation/Peers, open runbooks.
- Metrics:
  - `requests_total`, `errors_total`, `http_responses_total`,
    `http_request_duration_ms`
  - `db_pool_*`, `health_db_check_ms`
  - `federation_*`
  - `rate_limited_*`
  - `cleanup_*`
  - `ingest_retry_*` (if enabled)

## 3) Federation

### Federation vs Registry

Federation and Registry are separate systems.

Relay Admin UI manages federation:

- relay-to-relay connectivity
- peer mappings
- sync state
- heartbeat state
- federation troubleshooting

Registry Admin UI manages registry governance:

- relay discovery
- trust levels
- relay verification
- relay identity certificates
- revocation feeds
- policy feeds

Registry participation does not automatically imply federation participation.

A relay may:

- use registry only
- use federation only
- use both
- use neither

### Peers Overview
- Purpose: status of each peer and quick governance.
- Shows: peer URL, remote connected/capacity (if available), last seen, online/offline, link direction.
  Detailed sync metrics (lag/failures/backpressure/last error) are shown in Peer Detail.
  - Lag helper: when cursor lag is missing but last_sync exists, display "Lag unavailable".
  - Definitions:
    - Online: peer has a valid heartbeat within TTL (`last_seen_at` within `heartbeat_ttl_seconds`).
    - Connected: mapping + handshake is valid and there is recent sync activity with the peer.
  - This relay (local):
    - Shows Peer ID, Peer URL, and current public token (masked).
    - Actions: Create token (when missing), Copy/Replace/Revoke (when present).
      Generate is available in the inline edit to fill a valid token.
    - Public token is persisted to `public_token.txt` with UTC creation timestamp.
    - Public federation tokens are unrelated to Registry trust levels, relay verification, relay identity certificates or registry governance.
    - Validation: public token must be at least 12 characters and contain only letters, numbers, and hyphen.
    - Peer ID guidance:
      - Set via `FEDERATION_SELF_ID` (fallbacks to `FEDERATION_SELF_URL`) and keep read-only in UI.
      - Choose a globally unique, stable identifier (recommended: the public relay URL/host).
      - Avoid `localhost` or ephemeral IPs; use a stable domain or host+port.
      - Network-wide relay identity discovery belongs to Registry administration, not Relay Admin UI.
- Actions: open peer detail, quick ban/unban, sort by lag/failures.
- Distinguish configured mappings vs live peers:
  - Known Peers section lists configured mappings (operator intent/contact list).
  - Live Peers table lists peers with sync activity/telemetry and join-ready peers that publish a public token.
  - If Live Peers is empty, show helper: "No live peers yet. Configured mappings appear in Known Peers."
  - Sorting + emphasis:
    - Sort by lag or failures (asc/desc).
    - Highlight degraded peers in the list.
  - Live Peers inclusion rule:
    - Only show peers that are connected (sync activity/telemetry) or peers with a known public token
      (`peer_mappings.token_present = true`).
  - Live Peers layout:
    - Order: Live Peers section appears before Known Peers.
    - Columns: Peer URL, Status (online/offline), Link (⬅️ inbound, ➡️ outbound, ↔️ connected),
      Last Seen, Remote Conn/Cap, Actions (Detail + Connect/Disconnect), Trust placeholder.
    - Connect = create mapping; prompt for public token when not already configured.
    - Disconnect = disable mapping (keeps Known Peers entry).
    - Top peers/Trust: show placeholder "N/A" until reputation data is available.
    - Sorting: default order by most recent `last_seen_at`.
  - Inbound peers: keep a collapsed/summary view under Live Peers (expandable).
  - Connectivity bounds (ops guideline): keep each relay connected to at least 2 peers
    and no more than 10 peers (`FEDERATION_MIN_PEERS`, `FEDERATION_MAX_PEERS`).
  - Discovered peers (read-only):
    - When connected to a peer, the relay may fetch a read-only list of that peer's
      stable connections and show them in Live Peers as "discovered".
    - Discovered peers do not create mappings unless operator connects them manually.
  - Phase 3 note: auto topology (peer selection)
    - Nodes with auto-sync enabled may participate in a distributed peer-selection
      mechanism to maintain a stable, bounded degree network (min/max peers).
    - Selection should use health/telemetry signals, apply hysteresis, and avoid churn.
- API: peer status endpoint (to be defined), banlist endpoints.
  - `GET /admin/api/peers`
    - Response: `{ "items": [{ "id": "...", "peer_url": "...", "last_sync_at": "...", "cursor_lag_seconds": 12, "failures_10m": 0, "backpressure_queue": 0, "last_error": "", "remote_connected": 0, "remote_capacity": 32, "remote_heartbeat_ttl_seconds": 120, "last_seen_at": "...", "online_status": "online|offline|unknown" }], "meta": { "stats": { "connected": 0, "capacity_limit": 32, "heartbeat_ttl_seconds": 90, "capacity_warning": false, "auto_sync_enabled": false } } }`
    - Example: `{ "items": [{ "id": "relay_2:8080", "peer_url": "http://relay_2:8080", "status": "ok", "remote_connected": 3, "remote_capacity": 32, "remote_heartbeat_ttl_seconds": 120, "last_seen_at": "2026-01-18T09:43:46.000Z", "online_status": "online" }], "meta": { "stats": { "connected": 1, "capacity_limit": 1, "heartbeat_ttl_seconds": 90, "capacity_warning": true, "auto_sync_enabled": true } } }`
    - No remote stats: `{ "items": [{ "id": "relay_2:8080", "peer_url": "http://relay_2:8080", "status": "ok", "remote_connected": null, "remote_capacity": null, "remote_heartbeat_ttl_seconds": null, "last_seen_at": null, "online_status": "unknown" }], "meta": { "stats": { "connected": 0, "capacity_limit": 0, "heartbeat_ttl_seconds": 90, "capacity_warning": false, "auto_sync_enabled": false } } }`
    - Notes: remote stats are best-effort from the peer's latest reported state.
  - `GET /admin/api/peers/inbound`
    - Response: `{ "items": [{ "self_id": "relay_2:8080", "peer_url": "http://relay_2:8080", "last_seen_at": "...", "status": "online|offline" }] }`
    - Notes: inbound peers are derived from `peer_mappings.last_seen_at` and TTL.
- Metrics: `federation_sync_*`, `federation_cursor_lag_seconds`,
  `federation_backpressure_*`

### Peer Detail
- Purpose: troubleshoot a specific peer.
- Shows: recent sync attempts, failures, backoff, lag trend, last payload time,
  remote connected/capacity (if available), mapping auto_sync + sync_gids, last seen, online/offline.
- Actions: manual resync, clear backoff, refresh now (auto-refresh every 30s by default).
- API:
  - `GET /admin/api/peers/:id`
    - Response: `{ "item": { "id": "...", "peer_url": "...", "status": "ok|degraded", "last_sync_at": "...", "last_success_at": "...", "last_error": "", "remote_connected": 0, "remote_capacity": 32, "remote_heartbeat_ttl_seconds": 120 } }`
    - Example: `{ "item": { "id": "relay_2:8080", "peer_url": "http://relay_2:8080", "status": "ok", "remote_connected": 3, "remote_capacity": 32, "remote_heartbeat_ttl_seconds": 120 } }`
    - No remote stats: `{ "item": { "id": "relay_2:8080", "peer_url": "http://relay_2:8080", "status": "ok", "remote_connected": null, "remote_capacity": null, "remote_heartbeat_ttl_seconds": null } }`
  - `GET /admin/api/peers/:id/history`
    - Response: `{ "items": [{ "ts": "...", "result": "ok|fail", "lag_seconds": 12, "error": "" }] }`
  - Actions:
    - `POST /admin/api/peers/:id/resync` → `{ "ok": true }`
    - `POST /admin/api/peers/:id/clear-backoff` → `{ "ok": true }`

### Known Peers (formerly Join Network)
- Purpose: store known/configured peers (contact list) and manage mappings.
- Shows: peer URL, sync GIDs, token status (masked), join status, last join time,
  connected/max connections.
- Actions: add peer, revoke/remove peer, enable/disable auto-sync, copy join request template.
- Admin API (operator-managed):
  - `GET /admin/api/peer-mappings`
  - `POST /admin/api/peer-mappings` (create/update mapping after approval)
    - Request: `{ "peer_url": "...", "sync_gids": ["..."], "peer_token": "..." }`
    - Response: token not returned; use `token_present` boolean.
  - `PATCH /admin/api/peer-mappings/:id`
  - `DELETE /admin/api/peer-mappings/:id`
  - Notes: include `auto_sync` (boolean) to control pull behavior.
- Join API (automated):
  - Federation join authentication and handshake details are defined by `FEDERATION_PROTOCOL.md`.
  - Relay Admin UI should expose federation management state and actions, but should not duplicate low-level signing rules.
  - Expected UI-level behavior:
    - Show join status: `pending|joined|failed`.
    - Show token status as masked / present only.
    - Show last join / handshake time.
    - Show capacity errors such as `capacity_full`.
    - Show signature/auth failures as operator-readable errors.
  - Security note:
    - Public federation tokens are for federation join only.
    - Registry trust assignment and relay verification are separate Registry concerns.
  - `POST /v1/peers:handshake` (issue rotated token; protocol details defined by `FEDERATION_PROTOCOL.md`)
    - Request: `{ "peer_url": "...", "self_id": "...", "peer_join_token": "optional" }`
    - Signature: same headers; canonical prefix `HANDSHAKE`.
    - Validation:
      - Mapping exists and not banned.
      - Signature must match either a valid `join_token` (if present + not expired) or the public token.
      - Rotated token is unique per peer; rotate by replacing previous.
    - Response: `200 { "ok": true, "rotated_token": "...", "expires_in": 300, "join_token": "...", "join_token_expires_in": 600 }`
    - Notes:
      - `join_token` is short-lived and intended for reciprocal join mapping only.
      - Each successful handshake refreshes `join_token` (new value + new TTL), even if rotated_token is still valid.
      - Handshake job should refresh when `join_token` is near expiry (<= handshake interval) to avoid gaps.
      - If `peer_join_token` is provided and the mapping has no peer_token, store it so the receiver can join back automatically.
    - Sequence (ASCII):
      - Scenario: A initiates join to B with B's public token; handshake refreshes join_token (no remind endpoint).
      - Diagram:
        ```
        A (relay)                                  B (relay_2)
        ---------                                  ------------
        add mapping (B URL + B public_token)
        |                                         |
        |  POST /v1/peers:join (HMAC=B public_token)
        |---------------------------------------->|
        |                           upsert mapping (A) + accept
        |<----------------------------------------|
        |               200 { ok: true, status: pending/joined }
        |
        |  POST /v1/peers:handshake (HMAC=B public_token)
        |---------------------------------------->|
        |          issue rotated_token + join_token (refresh)
        |<----------------------------------------|
        |  200 { rotated_token, expires_in, join_token, join_token_expires_in }
        |
        |  (optional) POST /v1/peers:join with peer_join_token=A.join_token
        |---------------------------------------->|
        |  store peer_token if empty (for B->A reciprocal)
        |<----------------------------------------|
        |
        |  Heartbeat (HMAC=rotated_token of B)
        |---------------------------------------->|
        |<----------------------------------------|
        |  200 { ok: true, status: seen }
        ```
  - `POST /v1/peers:heartbeat` (authenticated federation heartbeat; protocol details defined by `FEDERATION_PROTOCOL.md`)
    - Request: `{ "peer_url": "...", "self_id": "..." }`
    - Uses same signature headers; canonical prefix `HEARTBEAT`.
    - Response: `200 { "ok": true, "status": "seen" }`
    - Heartbeat TTL: peers with no heartbeat within TTL are considered disconnected.
      - Recommended TTL >= 90s to avoid flapping (example: 90s TTL with 30s heartbeat interval tolerates 2 missed beats).
    - Behavior: reciprocal heartbeat — each peer sends heartbeat to every joined peer it has handshaked with.
      Receiver stores `last_seen_at` for the caller.
      - Heartbeat "ping/pong": a successful 200 response counts as "pong" from the remote.
      - After N consecutive heartbeat failures (default 5-10), invalidate rotated_token and back off before retry.
      - Handshake job will re-issue rotated_token after reset; mapping stays intact.
    - Auth: requires rotated_token (no public token) and HMAC signature using the rotated_token.
  - Federation data APIs (pull/push):
    - `GET /v1/events:fetch`, `POST /v1/events:ingest`
    - Auth: requires rotated_token; legacy public/peer token fallback is disabled.
  - Security:
    - Require TLS in production; consider mTLS for peer-to-peer traffic.
    - Audit log join/handshake/ban actions.
    - Auto-sync behavior:
      - When handshake succeeds, receiver stores rotated token for the peer.
      - If auto-sync is enabled, receiver pulls using the rotated token.
      - Full two-way sync requires both peers to accept join and enable auto-sync.

## 4) Banlist

- Purpose: block abusive peers/IP ranges quickly.
- Shows: type (peer_id/IP/CIDR), value, reason, status, updated time.
- Actions: add, edit, toggle, delete, bulk import/export.
- API:
  - `GET /admin/api/banlist`
  - `POST /admin/api/banlist`
  - `PATCH /admin/api/banlist/:id`
  - `DELETE /admin/api/banlist/:id`
  - Bulk:
    - `POST /admin/api/banlist/import`
    - `GET /admin/api/banlist/export`

## 5) Reputation

- Purpose: manual scoring/annotations for peers.
- Shows: peer ID, score, labels, notes, updated time.
- Actions: add, edit, delete, bulk import/export.
- API:
  - `GET /admin/api/reputation`
  - `POST /admin/api/reputation`
  - `PATCH /admin/api/reputation/:id`
  - `DELETE /admin/api/reputation/:id`
  - Bulk:
    - `POST /admin/api/reputation/import`
    - `GET /admin/api/reputation/export`

## 6) Metrics (Integrated)

### Traffic
- Purpose: detect spikes and errors fast.
- Shows: requests/s, error rate, top status codes.
- Metrics: `requests_total`, `errors_total`, `http_responses_total`,
  `http_request_duration_ms`

### Database
- Purpose: watch DB saturation.
- Shows: pool usage, wait avg, waiting, cooldown active.
- Metrics: `db_pool_*`, `db_cooldown_*`, `health_db_check_ms`

### Federation
- Purpose: monitor sync health.
- Shows: lag, failures, backpressure queue, regressions.
- Metrics: `federation_*`

### Background Jobs
- Purpose: cleanup/maintenance health.
- Shows: last cleanup duration, in-flight, stuck count.
- Metrics: `cleanup_*`

### Metrics adapter behavior
- Source: `GET /metrics` (Prometheus text format).
- Adapter output: JSON with sampled values + 5m rate deltas for counters.
- Suggested endpoints:
  - `GET /admin/api/metrics/summary`
  - `GET /admin/api/metrics/detail?domain=traffic|db|federation|rate_limit`

## 7) Backup & Restore

- Purpose: ensure recoverability.
- Shows: last backup time/age, last status, last restore drill time.
- Actions: open runbook, copy restore command.
- Sources:
  - Backup OK file path (e.g. `BACKUP_OK_FILE`).
  - Optional alert log path (`ALERT_LOG_PATH`).
  - Restore drill log (if available).
- Suggested endpoint: `GET /admin/api/backup/status`.

## 8) Retention & Cleanup

- Purpose: confirm data lifecycle configuration.
- Shows: retention profile, cleanup interval, last run duration.
- Actions: open retention guide, trigger manual cleanup (if supported).
- Metrics: `cleanup_last_duration_ms`, `cleanup_in_flight`

## 9) Alerts & Incidents

- Purpose: view active issues and resolution context.
- Shows: active alerts, recent incidents, links to runbooks.
- Actions: acknowledge/notes (if supported).
  - Ack writes an audit-style line to the alert log; it does not remove the alert.
- Runbooks: provide PDF download links for operator-friendly offline use.
- Suggested endpoints:
  - `GET /admin/api/alerts`
  - `POST /admin/api/alerts/:id/ack`

## 10) Settings (Read-only + Safe Ops)

- Purpose: confirm effective config and reduce misconfig risk.
- Shows: effective config summary (masked secrets), env/profile info.
  - Include: `REQUEST_SIGNATURE_ENABLED`, `FEDERATION_*`, `RATE_LIMIT_*`,
    `CLEANUP_*`, `BACKUP_*`, `ADMIN_SESSION_TTL_SECONDS`.
- Actions: show log locations; reset password via runbook.
- Notes: no raw secret values displayed.
  - Toggle: auto-connect discovered peers (off by default).
  - Registry settings are out of scope for Relay Admin UI and should be managed by Registry Admin UI.

## 11) Audit Log

- Purpose: traceability of admin actions.
- Shows: timestamp, actor, action, target, status, request id.
- Actions: filter/search by action or target.
- Source: audit log file or DB table (current: file-based JSON).
- Suggested endpoint: `GET /admin/api/audit?since=...&limit=...`.

## 12) API Contract Examples

All requests use `Content-Type: application/json` unless noted. All error
responses follow: `{ "error": { "code": "...", "message": "..." } }`.

### Error Code Taxonomy

General:
- `auth_required` — missing/expired session.
- `forbidden` — authenticated but not allowed.
- `not_found` — requested resource not found.
- `invalid_input` — request body/query invalid.
- `conflict` — state conflict (duplicate/invalid transition).
- `unsupported` — operation not supported by current deployment.
- `internal_error` — unexpected server error.

Auth:
- `invalid_credentials` — username/password rejected.
- `password_mismatch` — current password invalid.
- `session_expired` — session no longer valid.

Federation:
- `peer_not_found` — peer id unknown.
- `peer_action_failed` — resync/clear-backoff failed.

Banlist/Reputation:
- `import_failed` — bulk import failed.
- `export_failed` — bulk export failed.

Metrics/Ops:
- `metrics_unavailable` — unable to read `/metrics`.
- `backup_status_unavailable` — backup status not available.
- `retention_status_unavailable` — retention status not available.
- `alerts_unavailable` — alert source not configured.
- `settings_unavailable` — settings summary not available.
- `audit_unavailable` — audit log not available.

List responses can include pagination:
```json
{
  "items": [],
  "page": 1,
  "per_page": 50,
  "total": 0
}
```

### Auth

`POST /admin/api/auth/login`

Request:
```json
{ "username": "admin", "password": "admin1234" }
```

Response:
```json
{ "ok": true, "user": { "username": "admin" } }
```

Error:
```json
{ "error": { "code": "invalid_credentials", "message": "Invalid username or password." } }
```

`GET /admin/api/auth/me`

Response:
```json
{ "ok": true, "user": { "username": "admin" }, "expires_at": "2026-01-15T08:00:00Z" }
```

`POST /admin/api/auth/logout`

Response:
```json
{ "ok": true }
```

`POST /admin/api/auth/change-password`

Request:
```json
{ "current_password": "admin1234", "new_password": "new-secret" }
```

Response:
```json
{ "ok": true }
```

### Peers

`GET /admin/api/peers`

Response:
```json
{
  "items": [
    {
      "id": "peer-1",
      "peer_url": "https://peer.example.org",
      "last_sync_at": "2026-01-15T07:50:00Z",
      "cursor_lag_seconds": 120,
      "failures_10m": 0,
      "backpressure_queue": 0,
      "last_error": ""
    }
  ],
  "page": 1,
  "per_page": 50,
  "total": 1
}
```

Error:
```json
{ "error": { "code": "auth_required", "message": "Authentication required." } }
```

`GET /admin/api/peers/:id`

Response:
```json
{
  "item": {
    "id": "peer-1",
    "peer_url": "https://peer.example.org",
    "status": "ok",
    "last_sync_at": "2026-01-15T07:50:00Z",
    "last_success_at": "2026-01-15T07:50:00Z",
    "last_error": ""
  }
}
```

Error:
```json
{ "error": { "code": "peer_not_found", "message": "Peer not found." } }
```

`GET /admin/api/peers/:id/history`

Response:
```json
{
  "items": [
    { "ts": "2026-01-15T07:49:00Z", "result": "ok", "lag_seconds": 110, "error": "" },
    { "ts": "2026-01-15T07:45:00Z", "result": "fail", "lag_seconds": 180, "error": "timeout" }
  ],
  "page": 1,
  "per_page": 50,
  "total": 2
}
```

Error:
```json
{ "error": { "code": "peer_not_found", "message": "Peer not found." } }
```

`POST /admin/api/peers/:id/resync`

Response:
```json
{ "ok": true }
```

Error:
```json
{ "error": { "code": "unsupported", "message": "Resync not supported." } }
```

`POST /admin/api/peers/:id/clear-backoff`

Response:
```json
{ "ok": true }
```

### Banlist

`GET /admin/api/banlist`

Response:
```json
{
  "items": [
    {
      "id": "ban-1",
      "type": "peer_id",
      "value": "peer-1",
      "reason": "abuse",
      "enabled": true,
      "created_at": "2026-01-15T07:00:00Z",
      "updated_at": "2026-01-15T07:00:00Z"
    }
  ],
  "page": 1,
  "per_page": 50,
  "total": 1
}
```

Error:
```json
{ "error": { "code": "auth_required", "message": "Authentication required." } }
```

`POST /admin/api/banlist`

Request:
```json
{ "type": "ip", "value": "192.168.1.10", "reason": "spam", "enabled": true }
```

Response:
```json
{ "item": { "id": "ban-2", "type": "ip", "value": "192.168.1.10", "reason": "spam", "enabled": true } }
```

Error:
```json
{ "error": { "code": "invalid_input", "message": "type and value are required." } }
```

`PATCH /admin/api/banlist/:id`

Request:
```json
{ "reason": "resolved", "enabled": false }
```

Response:
```json
{ "item": { "id": "ban-2", "enabled": false, "reason": "resolved" } }
```

`DELETE /admin/api/banlist/:id`

Response:
```json
{ "ok": true }
```

`POST /admin/api/banlist/import`

Request:
- Content-Type: `text/csv`
- Header: `X-Banlist-Signature` (hex HMAC-SHA256 of raw CSV using `BANLIST_IMPORT_HMAC_SECRET`)
- Body: CSV (`type,value,reason,enabled`)

Response:
```json
{ "ok": true, "created": 2 }
```

Error:
```json
{ "error": { "code": "invalid_signature", "message": "Invalid banlist signature." } }
```

`GET /admin/api/banlist/export`

Response:
```json
{
  "items": [
    { "type": "peer_id", "value": "peer-3", "reason": "abuse", "enabled": true }
  ]
}
```

Error:
```json
{ "error": { "code": "export_failed", "message": "Unable to export banlist." } }
```

### Reputation

`GET /admin/api/reputation`

Response:
```json
{
  "items": [
    {
      "id": "rep-1",
      "peer_id": "peer-1",
      "score": 3,
      "labels": ["trusted"],
      "notes": "consistent uptime",
      "created_at": "2026-01-15T07:10:00Z",
      "updated_at": "2026-01-15T07:10:00Z"
    }
  ],
  "page": 1,
  "per_page": 50,
  "total": 1
}
```

Error:
```json
{ "error": { "code": "auth_required", "message": "Authentication required." } }
```

`POST /admin/api/reputation`

Request:
```json
{ "peer_id": "peer-2", "score": -2, "labels": ["abusive"], "notes": "spam bursts" }
```

Response:
```json
{ "item": { "id": "rep-2", "peer_id": "peer-2", "score": -2, "labels": ["abusive"], "notes": "spam bursts" } }
```

Error:
```json
{ "error": { "code": "invalid_input", "message": "peer_id and score are required." } }
```

`PATCH /admin/api/reputation/:id`

Request:
```json
{ "score": 1, "labels": ["watch"], "notes": "improved behavior" }
```

Response:
```json
{ "item": { "id": "rep-2", "score": 1, "labels": ["watch"], "notes": "improved behavior" } }
```

`DELETE /admin/api/reputation/:id`

Response:
```json
{ "ok": true }
```

`POST /admin/api/reputation/import`

Request:
```json
{
  "items": [
    { "peer_id": "peer-9", "score": 2, "labels": ["trusted"], "notes": "" }
  ]
}
```

Response:
```json
{ "ok": true, "imported": 1 }
```

`GET /admin/api/reputation/export`

Response:
```json
{
  "items": [
    { "peer_id": "peer-9", "score": 2, "labels": ["trusted"], "notes": "" }
  ]
}
```

Error:
```json
{ "error": { "code": "export_failed", "message": "Unable to export reputation data." } }
```

### Metrics

`GET /admin/api/metrics/summary`

Response:
```json
{
  "timestamp": "2026-01-15T08:00:00Z",
  "traffic": { "requests_per_s": 0.5, "error_rate": 0.01 },
  "db": { "pool_in_use": 1, "pool_total": 5, "wait_ms_avg": 12 },
  "federation": { "cursor_lag_seconds": 120, "failures_10m": 0 },
  "rate_limit": { "spike_active": false }
}
```

Error:
```json
{ "error": { "code": "metrics_unavailable", "message": "Unable to read /metrics." } }
```

`GET /admin/api/metrics/detail?domain=federation`

Response:
```json
{
  "timestamp": "2026-01-15T08:00:00Z",
  "domain": "federation",
  "metrics": {
    "cursor_lag_seconds": 120,
    "sync_success_per_s": 0.2,
    "sync_fail_per_s": 0,
    "failures_10m": 0,
    "backpressure_queue_max": 1,
    "backpressure_in_flight_max": 1,
    "backpressure_drops_per_s": 0
  }
}
```

Error:
```json
{ "error": { "code": "invalid_request", "message": "domain is required." } }
```

### Backup & Restore

`GET /admin/api/backup/status`

Response:
```json
{
  "last_backup_at": "2026-01-15T07:00:00Z",
  "backup_age_seconds": 3600,
  "last_status": "ok",
  "last_restore_drill_at": "2026-01-10T07:00:00Z",
  "restore_drill_status": "stale"
}
```

Error:
```json
{ "error": { "code": "backup_status_unavailable", "message": "Backup status not available." } }
```

### Retention & Cleanup

`GET /admin/api/retention/status`

Response:
```json
{
  "profile": "community",
  "cleanup_interval_seconds": 2,
  "last_cleanup_duration_ms": 5,
  "last_cleanup_at": "2026-01-15T07:58:00Z"
}
```

Error:
```json
{ "error": { "code": "retention_status_unavailable", "message": "Retention status not available." } }
```

### Alerts & Incidents

`GET /admin/api/alerts`

Response:
```json
{
  "items": [
    { "id": "alert-1", "name": "DBPoolSaturation", "severity": "warning", "starts_at": "2026-01-15T07:55:00Z" }
  ],
  "incidents": [
    { "id": "incident-1", "name": "ack alert-1 Investigating", "acknowledged_at": "2026-01-15T08:10:00Z" }
  ],
  "page": 1,
  "per_page": 50,
  "total": 1,
  "incidents_total": 1
}
```

Error:
```json
{ "error": { "code": "alerts_unavailable", "message": "Alert source not configured." } }
```

`POST /admin/api/alerts/:id/ack`

Request:
```json
{ "note": "Investigating" }
```

Response:
```json
{ "ok": true }
```

Error:
```json
{ "error": { "code": "not_found", "message": "Alert not found." } }
```

### Settings

`GET /admin/api/settings/summary`

Response:
```json
{
  "request_signature_enabled": true,
  "federation_peer_allowlist": "10.30.0.0/24",
  "rate_limit_window_seconds": 60,
  "cleanup_interval_seconds": 2,
  "backup_retention_days": 14,
  "admin_session_ttl_seconds": 28800,
  "auto_connect_discovered_peers": false,
  "log_paths": { "audit": "./data/audit.log" }
}
```

Error:
```json
{ "error": { "code": "settings_unavailable", "message": "Settings summary not available." } }
```

### Audit Log

`GET /admin/api/audit?since=2026-01-15T07:00:00Z&limit=100`

Response:
```json
{
  "items": [
    {
      "ts": "2026-01-15T07:30:00Z",
      "actor": "admin",
      "action": "banlist.create",
      "target": "peer-1",
      "status": "ok",
      "request_id": "req-abc"
    }
  ],
  "page": 1,
  "per_page": 100,
  "total": 1
}
```

Error:
```json
{ "error": { "code": "audit_unavailable", "message": "Audit log not available." } }
```
