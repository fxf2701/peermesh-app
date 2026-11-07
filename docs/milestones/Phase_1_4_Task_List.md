# Phase 1.4 Task List — Admin UI (Operator Console)

Scope assumptions:
- Admin UI includes write operations.
- Single local admin account (file-based).
- Metrics are integrated into the Admin UI (not just Grafana links).
- Federation governance is the top priority.

## 1) Federation First (Priority)
- [x] Federation peers API (list + detail + status).
  - Endpoints: `GET /admin/api/peers`, `GET /admin/api/peers/:id`.
  - Data: last sync, lag, failures, backpressure, last error.
- [x] Federation overview UI (peer list + status).
  - Show: peer URL, remote conn/cap, trust placeholder, last seen, link, online status.
  - Actions: connect/disconnect, open peer detail.
  - Subtasks:
    - [x] Show connected/max (capacity + heartbeat).
    - [x] Per-peer connected/capacity from remote stats (API + UI).
      - [x] Remote stats API (per-peer connected/capacity/ttl snapshot).
        - Spec: `docs/server/admin_ui_pages_spec.md` (remote stats examples incl. null).
        - Test: configure peer mapping to a relay with stats enabled; call `GET /admin/api/peers` and/or `GET /admin/api/peers/:id` and verify fields `remote_connected`, `remote_capacity`, `remote_heartbeat_ttl_seconds`; UI shows remote connected/max in list and detail.
      - [x] Remote stats UI (peer list + detail).
        - Spec: `docs/server/admin_ui_pages_spec.md` (remote stats examples incl. null).
        - Test: peer list shows remote connected/max and TTL; peer detail shows remote connected/max/TTL or placeholders when null.
    - [x] Inbound peers list (API + UI).
      - [x] Inbound peers API (list peers connecting to this relay).
        - Spec: `docs/server/admin_ui_pages_spec.md` (GET `/admin/api/peers/inbound`).
        - Test: send heartbeat from a peer; run `./server/relay/scripts/test-federation-inbound-peers.sh --server http://localhost:8080 --username admin --password admin1234`; API returns item with `self_id`, `peer_url`, `last_seen_at`, `status`.
      - [x] Inbound peers UI (section on peers list).
        - Spec: `docs/server/admin_ui_pages_spec.md`.
        - Test: UI shows inbound peers with online/offline status and last seen.
    - [x] Distinguish configured mappings vs live peers.
      - Spec: `docs/server/admin_ui_pages_spec.md` (Join Network = configured mappings; Live Peers = sync activity).
      - Test: add a mapping only → Join Network shows it, Live Peers shows "No live peers yet"; trigger sync activity → Live Peers row appears.
    - [x] Show last_seen/online-offline in list.
      - Spec: `docs/server/admin_ui_pages_spec.md` (Peers Overview shows last_seen + online/offline).
      - Test: send heartbeat from a peer; `/admin/api/peers` returns `last_seen_at` and `online_status`; UI shows Last Seen and Online pill (online/offline).
    - [x] Add sorting (lag/failures) + degraded emphasis.
      - Test: sort by lag/failures; verify order; degraded rows are highlighted.
    - [x] Auto-refresh (10s fast, 30s slow; pause when tab hidden).
    - [x] Lag unavailable helper when cursor missing.
      - Test: peer has `last_sync_at` but `cursor_lag_seconds` null → show "Lag unavailable" in list.
- [x] Federation peer detail API (history + controls).
  - Endpoints: `GET /admin/api/peers/:id/history`, optional action endpoints.
- [x] Federation peer detail UI (history + sync controls).
  - Show: recent sync attempts, failures, backoff, lag trend.
  - Actions: manual resync trigger (if supported), clear backoff (if supported).
- [x] Banlist API (CRUD + bulk import/export).
- [x] Banlist UI (full CRUD + bulk ops).
  - UI: list + search/filter, add/edit/toggle, bulk import/export CSV/JSON.
- [x] Reputation API (CRUD + bulk import/export).
- [x] Reputation UI (CRUD + annotations).
  - UI: list, edit score/labels, notes, history.
- [x] Federation audit trail API (ban/reputation changes).
- [x] Federation audit trail UI (viewer + filters).
- [x] Federation join network API (public token + rotated token handshake + auto-sync).
  - Include: join request signature (HMAC), mapping auto-create (pending), rotated token issuance,
    peer metadata storage, auto-sync toggle.
  -  Add UI tooltip/note that auto-sync only enables after handshake and stays off until re-enabled manually.
- [x] Federation connection capacity limit + heartbeat TTL.
  - API now returns `capacity_warning` and `auto_sync_enabled` to drive the KPIs.
  - Show connected/max in UI, reject join when full.
  - Test scenarios (mark [x] when pass):
    - [x] Heartbeat refresh keeps slot.
      - Setup: `FEDERATION_CONNECTION_CAPACITY=1`, `FEDERATION_HEARTBEAT_TTL_SECONDS=30`.
      - Steps: join+handshake `relay-2` → send heartbeat via `./server/relay/scripts/test-federation-heartbeat.sh --server http://localhost:8080 --peer-url http://relay_2:8080 --rotated-token <ROTATED_TOKEN> --self-id relay-2` → join `relay-3`.
      - Expected: second join returns `429 capacity_full`.
    - [x] Peers stats show connected/capacity/ttl + capacity_warning.
      - Steps: run `./server/relay/scripts/test-federation-peers-stats.sh --server http://localhost:8080 --username admin --password admin1234`.
      - Expected: `stats.connected`, `capacity_limit`, `heartbeat_ttl_seconds` populated; `capacity_warning` true when near/full.
    - [x] Capacity=0 means unlimited.
      - Setup: `FEDERATION_CONNECTION_CAPACITY=0`.
      - Steps: join+handshake `relay-2` then `relay-3`.
      - Expected: both joins return 200 (no `capacity_full`).
    - [x] Disabled mapping reduces connected count.
      - Steps: disable mapping in UI (`/admin/peers`) then rerun peers stats script.
      - Expected: `stats.connected` decreases after disable.
- [x] Federation join mitigations (rate limit + token binding + TLS/mTLS guidance).
  - Rate limit join/handshake endpoints and expose config vars for tuning.
  - Bind rotated_token to peer_url/self_id and reject self_id mismatches.
  - Document TLS/mTLS requirements (`FEDERATION_REQUIRE_TLS`, `FEDERATION_MTLS_*`).
  - Test scenarios (mark [x] when pass):
    - [x] Happy path: join + handshake with correct public token.
    - [x] Wrong token/HMAC: expect 401 invalid_signature.
    - [x] TLS required: `FEDERATION_REQUIRE_TLS=1`, HTTP request → 426 tls_required.
    - [x] Join rate limit: low `FEDERATION_JOIN_RATE_LIMIT`, repeat joins → 429 rate_limited.
    - [x] Handshake rate limit: low `FEDERATION_HANDSHAKE_RATE_LIMIT`, repeat handshakes → 429 rate_limited.
    - [x] Self ID mismatch on handshake: expect 403 invalid_self_id.
    - [x] Banlist/allowlist blocks join/handshake: expect 403 forbidden.
    - [x] Capacity full: low `FEDERATION_CONNECTION_CAPACITY`, next join → 429 capacity_full.
    - [x] TTL expiration: wait beyond `FEDERATION_HEARTBEAT_TTL_SECONDS`, connected drops/joins allowed.
      - Note: capacity=1; join relay-2 then immediately join relay-3 -> 429 capacity_full; wait > TTL then join relay-3 -> 200.
    - [x] Rotated token expiry: low `FEDERATION_ROTATED_TOKEN_TTL_SECONDS`, old token rejected; handshake re-issues token.

## Federation Enhancements
- [x] Auto-sync + capacity/heartbeat integration for peers API.
  - Spec: `docs/server/admin_ui_pages_spec.md` (`/admin/api/peers` includes `stats` + `last_seen_at` + `online_status`).
  - Test: run `./server/relay/scripts/test-federation-peers-stats.sh --server http://localhost:8080 --username admin --password admin1234` and verify `stats.connected`, `capacity_limit`, `heartbeat_ttl_seconds`, `capacity_warning`, `auto_sync_enabled`.
- [x] Enforce rotated_token for heartbeat + federation data APIs (no public/peer token fallback).
  - Test: call `/v1/peers:heartbeat`, `/v1/events:fetch`, `/v1/events:ingest` with rotated_token -> 200; with public token or missing token -> 401/403.
  - Script: `./server/relay/scripts/test-federation-rotated-enforcement.sh --server http://localhost:8080 --peer-id relay-2 --peer-url http://relay_2:8080 --rotated-token <ROTATED_TOKEN> --public-token dev-public-token --request-signature-secret test-secret`.
- [x] Handshake returns short-lived join token for reciprocal mapping.
  - Spec: `docs/server/admin_ui_pages_spec.md` (join_token + join_token_expires_in).
  - Test: handshake response includes `join_token`; using it allows reciprocal join; token expires after TTL.
- [x] Periodic heartbeat sender (after successful handshake).
  - Behavior: reciprocal heartbeat (both sides send heartbeat to peers they joined/handshaked with).
    Receiver stores `last_seen_at`.
  - Auth: heartbeat must use rotated_token (no public token).
  - Test: run `./server/relay/scripts/test-federation-heartbeat-job.sh --receiver http://localhost:8081 --username admin --password admin1234 --peer-id relay` and verify `last_seen_at` updates.
  - Two-way test: ensure both relays have joined/handshaked each other; run heartbeat job on both; inbound list shows each peer online on the other relay.
  - Script updates: adjust heartbeat scripts to sign with rotated_token after handshake.
- [x] Heartbeat failure threshold + backoff reset rotated_token.
  - Spec: `docs/server/admin_ui_pages_spec.md` (ping/pong + N fails backoff reset).
  - Test: simulate N consecutive heartbeat failures (e.g., stop receiver or use bad token), verify rotated_token cleared and heartbeat pauses; once receiver is back, handshake job re-issues token.
- [x] Federation Peers layout revamp (Live Peers first + Known Peers + inbound summary).
  - Spec: `docs/server/admin_ui_pages_spec.md` (Peers Overview + Known Peers section).
  - Live Peers list: show connected peers plus peers that advertise a public token; include Connect/Disconnect actions (connect prompts for public token and adds mapping; disconnect disables mapping), Link column (↔️/➡️/⬅️), Online indicator, Last Seen, Remote Conn/Cap; Top peers placeholder shown as N/A.
  - Known Peers: rename Join Network to Known Peers; table stores configured mappings/contact list (previous connections).
  - Inbound peers: keep collapsed/summary view under Live Peers.
  - Definitions: Online = heartbeat within TTL; Connected = mapping + handshake + recent sync activity.
- [x] Show connectivity bounds + enforcement in Peers list (min/max peers warning + disable add when exceeding).
  - [x] This Relay public token inline edit (UI + API).
    - [x] UI: Create/Copy/Replace/Revoke buttons + inline Save/Cancel input; created time shown under token; Generate fills a valid token.
    - [x] API: `GET /admin/api/federation/self`, `POST /admin/api/federation/self/public-token`, `DELETE /admin/api/federation/self/public-token`.
    - [x] Validation: min 12 chars, only letters/numbers/hyphen.
  - Test: verify Live Peers renders before Known Peers; Connect creates mapping, Disconnect disables mapping; inbound summary collapses/expands; known peers list matches mappings table.

## Federation Detail Enhancements
- [x] Peer detail actions (resync/clear backoff).
  - Test: click "Trigger Resync" and "Clear Backoff".
  - Expected: API returns 200 and UI shows success toast; errors show inline status.
- [x] Peer detail heartbeat status (online/offline).
  - Test: send heartbeat then wait beyond TTL; refresh detail page.
  - Expected: status flips from online to offline after TTL.
- [x] Peer detail mapping info (auto_sync + sync_gids).
  - Test: create mapping with auto_sync + gids; open peer detail.
  - Expected: mapping fields render and match DB values.
- [x] Peer detail refresh controls (manual/auto).
  - Test: use refresh button or wait interval; confirm data updates.
  - Expected: last_sync_at/history update without reload.
- [x] Federation join network UI (operator input + preview).
  - Test: type Peer URL + token + GIDs → preview updates (token shows “set/missing”, GIDs parsed).

## 2) Metrics-Integrated Dashboard
- [x] Metrics adapter API (parse `/metrics` and expose JSON).
  - Note: rate fields are derived from in-memory snapshots between successive `/admin/api/metrics/summary` calls.
  - Normalize counters/gauges for charts (requests, errors, DB pool, lag).
- [x] Dashboard UI widgets (operator snapshot).
  - Health/ready, error rate, request rate, DB pool usage, federation lag, rate limit spikes.
- [x] Metrics detail API (per domain).
- [x] Metrics detail UI (per domain).
  - Traffic, DB, Federation, Background jobs.

## 3) Operations & Reliability UI
- [x] Backup status API (last backup + drill status).
- [x] Backup status UI.
  - Show: last backup time, age, last status, restore drill history.
- [x] Retention/cleanup API (current profile + last run).
- [x] Retention/cleanup UI.
  - Show: active retention profile, last cleanup duration, next run.
- [x] Alerts/Incidents API (active alerts + recent incidents + ack endpoint).
- [x] Alerts/Incidents UI.
  - Show: active alerts and recent incidents (link to runbooks).
- [x] Runbooks download (PDF) from Alerts/Incidents UI.
  - Phase 3: finalize and expand runbook content before production use.
- [x] Backup restore drill staleness warning (server + UI banner).

## 4) Admin Core UX
- [x] Navigation and layout system (header, footer, hamburger).
  - Consistent page templates + form controls.
- [x] Shared breadcrumbs component (avoid per-page duplication).
- [x] Session-aware UI states.
  - Hide login when authenticated; auto-redirect to dashboard.
- [x] Session UI (auth controls).
  - Show session state + logout + change password.
- [x] Audit log API (read-only).
- [x] Audit log UI (viewer + filters).
 - [x] Settings: toggle auto-connect for discovered peers.
   - Phase 3: implement discovery + auto-connect behavior (backend + UI).

## 5) Security & Access
- [x] Security policy for federation join (URL + token).
  - Risks: shared token = single-factor; token leak grants broad access; no allowlist widens attack surface; no binding between token and peer URL.
  - Mitigations: per-peer tokens + rotation; bind token to peer id/url in server; require HMAC-signed join; limit peer access to federation endpoints until ACL exists; keep banlist enforced.
- [x] Signed banlist import (verify signature before apply).
  - Test note: `docs/tests/notes/banlist_signed_import.md`.
- [x] Enforce min/max peers on server (reject add/join when max reached).
- [ ] Phase 3 note: introduce RBAC + multi-approver workflow for bulk bans/imports.
- [ ] Phase 3 note: peer trust scoring and protected peers (cannot be banned without higher approval).
  - Peer Trust Scoring (draft):
    - Availability/uptime history and incident rate.
    - Sync quality: low failure rate, no spam/backpressure abuse.
    - Data integrity: valid signatures, consistent payloads.
    - Operational transparency: published contact, runbook, disclosure policy.
    - Organizational credibility: known institution or verified community steward.
    - Hosting context: stable infrastructure, legal jurisdiction for rights protections.
    - Governance: peer accepted by multiple trusted relays (web-of-trust).
    - Scores determine ban policy tier (normal vs protected vs emergency-only).
- [ ] Phase 3 note: audit log + diff review for bulk banlist changes.
- [ ] Phase 3 note (optional): allowlist mode for federation join.
  - Enforce allowlist at join/handshake (default deny when enabled).
  - Admin CRUD + import/export for allowlist entries.
  - UI toggle + status banner (when allowlist mode is active).
- [ ] Phase 3 note: auto topology / peer selection for auto-sync nodes.
  - Distributed peer selection to keep network bounded (min/max peers).
  - Use health telemetry, hysteresis, and seed fallbacks to avoid churn/partition.
- [ ] Phase 3 note: define concrete retention profiles (e.g., community/strict/emergency)
  and map them to cleanup + TTL settings.
- [x] Sensitive action confirmations (ban, delete, bulk import).
- [x] IP allowlist notice in UI (read-only banner).
- [x] Settings API (read-only config summary + safe ops).
- [x] Settings UI (read-only config summary + safe ops).
  - Show effective config (masked secrets) and log locations.
- [x] Settings UI warnings: mark restart-required fields + explain read-only scope.

## 6) Testing & Docs
- [x] UI smoke checklist (login, dashboard, banlist CRUD, reputation CRUD).
- [x] Update `docs/server/admin_ui_development_guideline.md` with new page patterns.
- [x] Operator guide updates (new screens + workflows).
