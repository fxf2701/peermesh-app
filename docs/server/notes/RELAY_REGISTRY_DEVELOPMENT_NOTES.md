# Relay & Registry Development Notes

Purpose:
- Lightweight handoff notes for ongoing relay + registry server work.
- Record concrete implementation details after each task so future work does not require rereading full chat history.
- Keep this file focused on technical changes, validation evidence, and follow-up risks.

Primary references:
- Architecture:
  - `docs/server/INTERNET_RELAY_OPTION.md`
  - `docs/server/NODE_OPERATOR_GUIDE.md`
- Protocol:
  - `docs/specs/RELAY_PROTOCOL_SPEC.md`
  - `docs/server/FEDERATION_PROTOCOL.md`
  - `docs/server/REGISTRY_PROTOCOL.md`
  - `docs/SECURITY.md`
- Operations:
  - `server/relay/docs/relay_backup_restore_runbook.md`
  - `server/relay/docs/relay_deploy_rollback_runbook.md`
  - `server/relay/docs/relay_admin_operator_guide.md`
  - `server/registry/docs/registry_admin_operator_guide.md`
- Relay Admin UI:
  - `docs/server/admin_ui_development_guideline.md`
  - `docs/server/admin_ui_pages_spec.md`
  - `server/relay/docs/relay_admin_ui_guide.md`
- Registry Admin UI:
  - `docs/server/registry_admin_development_guideline.md`
  - `docs/server/registry_admin_pages_spec.md`
  - `server/registry/docs/registry_admin_ui_guide.md`

Related tracking:
- `docs/milestones/Phase_3_2_Task_List.md`

## Current Architecture Snapshot

- Relay service source lives under `server/relay/`.
- Registry service source lives under `server/registry/`.
- Relay and Registry are separate services with separate responsibilities:
  - Relay: event ingest/fetch, federation, relay admin operations, storage and forwarding.
  - Registry: relay discovery, relay trust/status governance, policy/revocation feeds, registry admin operations.
- Docker service names such as `relay`, `relay_2`, and `registry` are Docker-network names only.
- Host browser URLs:
  - Relay Admin: `http://127.0.0.1:8080/admin`
  - Relay 2 Admin: `http://127.0.0.1:8081/admin`
  - Registry Admin: `http://127.0.0.1:8090/admin`

Android transport boundary:
- BLE mesh and cloud relay sync are treated as separate foreground-service
  paths.
- Shared source of truth is the local DB only.
- Feature/UI code may persist local events and enqueue BLE outbox work, but
  should not directly call relay push for normal user events.
- Relay sync service is responsible for cloud push/pull on its own tick.
- `SYS.ACK` cloud propagation uses a separate in-memory buffer that flushes
  into the DB during relay ticks; BLE ACK handling remains unchanged.
- Cross-transport bridging is layered on top of that boundary at the relay-sync
  tick, not by having BLE call cloud directly.

## 2026-06-11 - BLE <-> Cloud Bridge Policy Validation

Context:
- Recent 3-device field rounds were used to validate the current cross-transport
  policy with:
  - A = cloud only
  - B = BLE + cloud
  - C = BLE only
- `cloud -> BLE bridge` remains opt-in and is additionally gated by peer
  `cloud_state` learned from DTN `hello`.

Validated behavior:
- Round with B dual-homed and bridge enabled behaved as intended:
  - cloud-originated events could reach BLE-only peers through the dual-homed
    bridge node
  - BLE-originated events could still propagate outward and then up to cloud
- Round with the same topology but B bridge disabled also behaved as intended:
  - A <-> B over cloud remained healthy
  - B <-> C over BLE remained healthy
  - C -> A/B over BLE plus BLE -> cloud remained healthy
  - A -> C did not propagate, because:
    - A had no BLE path
    - C had no cloud path
    - B was the only dual-homed bridge candidate and `cloud -> BLE bridge`
      was disabled

Conclusion:
- The last two rounds confirm current bridge policy is functioning as designed,
  not accidentally over-forwarding.
- Delivery now depends on the actual transport topology plus the explicit
  `cloud -> BLE bridge` setting on dual-homed nodes, which matches product
  intent.

Operational interpretation:
- `BLE -> cloud` bridge is effectively part of the default dual-homed behavior.
- `cloud -> BLE` bridge is conditional:
  - Settings toggle must be enabled
  - peer must advertise `cloud_state=disabled|stale`
  - missing `cloud_state` hint does not trigger bridging
- Therefore, a cloud-only sender cannot reach a BLE-only receiver unless some
  dual-homed peer is both eligible and allowed to bridge.

## 2026-05-31 - Relay Docker Layout / Startup Fix

Context:
- Relay container failed at startup with:
  - `Error: Cannot find module '/app/scripts/migrate.js'`
- Runtime entrypoint executes `npm run migrate:up`, which maps to `node scripts/migrate.js up`.

Root cause:
- `server/relay/Dockerfile` copied only `package*.json` and `docker-entrypoint.sh`.
- `COPY src`, `COPY scripts`, and `COPY migrations` were commented out.
- The new repo layout had moved relay code into `server/relay`, while some compose paths still reflected the old flat `server/` layout.

Changes:
- Updated `server/relay/Dockerfile`:
  - copy `src` to `/app/src`
  - copy `scripts` to `/app/scripts`
  - copy `migrations` to `/app/migrations`
  - copy `config` to `/app/config`
- Updated `server/docker-compose.yml`:
  - relay build context now points to `./relay`
  - postgres init SQL paths now point to `./relay/config` and `./relay/migrations`
  - relay runtime mounts now use `./relay/config` and `./relay/data`
  - backup/prometheus/grafana mounts now point to `./relay/...`
- Updated `server/docker-compose.dev.yml`:
  - relay and relay_2 dev mounts now use `./relay/src`, `./relay/scripts`, `./relay/migrations`, and relay data folders
  - this prevents empty legacy folders under `server/` from hiding image contents
- Updated `server/start.sh`:
  - default compose file is now `server/docker-compose.yml`
  - `--dev` switches to `server/docker-compose.dev.yml`
  - dev `multi` profile is enabled only in dev mode

Validation:
- `docker compose -f server/docker-compose.yml config` passed.
- `docker compose -f docker-compose.dev.yml --profile multi config` passed from `server/`.
- `sh -n server/start.sh` passed.
- `sh -n server/relay/docker-entrypoint.sh` passed.
- `docker compose -f server/docker-compose.yml build relay` passed after running outside the sandbox because Docker Buildx writes under `~/.docker`.

Follow-up:
- Remove obsolete Compose `version` warnings.
- Audit docs/scripts that still reference old flat paths such as `server/src`, `server/scripts`, or `server/migrations`.
- Decide whether stale generated folders under `server/` should be archived or removed after validation.

## 2026-06-06 - Android BLE / Cloud Strict Isolation

Context:
- Android managed cloud sync had been working functionally, but feature code
  still used direct relay fast paths for some event types.
- Product requirement is stricter separation:
  - BLE mesh service does BLE work only.
  - relay sync service does cloud work only.
  - both share only local DB state.

Changes:
- `CHAT.MSG` send path no longer calls direct relay push from
  `GroupChatActivity`.
- map event write path no longer calls direct relay push from
  `GroupMapActivity`.
- live trail event write path no longer calls direct relay push from
  `LiveTrailEventWriter`.
- `SYS.ACK` cloud propagation now uses a dedicated `SeenAckCloudBuffer`:
  - UI visibility enqueues cloud ACK intent in memory.
  - relay sync flushes buffered ACKs into local `events` during the next sync
    tick.
  - BLE ACK behavior remains unchanged in `MeshAckManager`.
- relay foreground sync interval in normal mode was reduced from `30s` to
  `15s` so cloud latency stays acceptable after removing direct relay push.

Resulting model:
- local event persisted once
- BLE path reads from BLE outbox / dispatcher
- cloud path reads from relay sync tick
- no feature-layer direct relay push for normal events

Validation:
- `./gradlew :app:compileOfficialDebugKotlin` passed after the refactor.
- Device report rounds confirmed:
  - managed message push / pull still works
  - `ACK.seen` now propagates through cloud independently of BLE
  - `relay_push_state.last_push_event_id` advances to local `SYS.ACK` events
    when appropriate

Residual:
- old `FAILED_FINAL` `sys_metadata` / `relay_seed` backlog rows still exist in
  historical local DBs, but are not part of the new transport boundary.

## 2026-06-06 - Android BLE -> Cloud Bridge

Context:
- Dual-homed Android nodes should be able to republish BLE-originated user
  events into managed cloud relay sync without violating the BLE/cloud service
  boundary.

Changes:
- Relay push candidate loading now excludes `source=relay` and `source=dtn`
  explicitly.
- BLE-originated events (`source=mesh`) are now bridged to cloud only for
  explicitly allowed types:
  - chat events
  - map events
  - live trail events
  - `SYS.MEMBER.REMOVED`
  - `SYS.ACK`
- Non-bridgeable BLE event types are skipped intentionally, and relay push
  cursor still advances so they do not starve later bridgeable events.

Resulting model:
- BLE service remains unchanged.
- Relay sync service republish decision happens only during cloud sync ticks.
- `source=relay` events are never echoed back into cloud.
- `source=dtn` events are not treated as cloud bridge candidates.

Validation:
- `RelayPushManagerCursorTest` covers:
  - same-second cursor safety
  - BLE-originated chat bridging
  - cursor advance across intentionally skipped BLE-only events
- `./gradlew :data:testDebugUnitTest --tests app.peermesh.data.relay.RelayPushManagerCursorTest :app:compileOfficialDebugKotlin` passed.

Residual:
- `cloud -> BLE bridge` is still pending as a separate task.

## 2026-06-07 - Android Cloud -> BLE Bridge

Context:
- Dual-homed Android nodes should be able to republish relay-originated user
  events into BLE without collapsing the BLE/cloud service boundary.

Changes:
- Added `CloudToBleBridge`, wired from `IngestNotifier` in `PeerMeshApp`.
- Relay-originated events are evaluated only after successful local ingest into
  the DB.
- Cloud -> BLE republish is gated by peer-reported BLE `hello` cloud hints:
  - peers advertise `cloud_state` and `last_cloud_success_at`
  - missing cloud hint does not trigger bridge
  - only peers reporting `disabled` or `stale` cloud state cause bridge
- Feature is user-controlled in Android Settings and defaults to `OFF`.
- Eligible relay-originated event types are republished into BLE outbox with
  `source=relay_bridge`:
  - chat events
  - map events
  - live trail events
  - `SYS.MEMBER.REMOVED`
- `SYS.ACK` and other `SYS.*` metadata remain intentionally excluded from
  cloud -> BLE bridging in this baseline to avoid cross-transport control
  chatter and loop risks.

Resulting model:
- Relay/cloud service remains unchanged; it still only ingests/pulls on its own
  tick/lifecycle.
- BLE service remains unchanged; it only drains the persistent BLE outbox.
- The bridge happens after local relay ingest, by enqueueing a BLE outbox item
  from local DB state.
- Loop prevention is preserved because BLE -> cloud bridge excludes
  `source=relay`, while cloud -> BLE bridge only reacts to locally ingested
  `source=relay` rows.

Validation:
- `CloudToBleBridgeTest` covers:
  - relay-originated chat bridging
  - relay-originated `SYS.MEMBER.REMOVED` bridging
  - exclusion of `SYS.ACK`
  - exclusion of `source=mesh` / `source=dtn`
  - invalid relay payload drop

## 2026-05-31 - Relay Admin UI Runtime Check

Context:
- Request was to follow Relay Admin UI guidance and open the relay dashboard.
- Relevant docs read:
  - `server/relay/docs/relay_admin_ui_guide.md`
  - `docs/server/admin_ui_pages_spec.md`
  - `docs/server/admin_ui_development_guideline.md`

Findings:
- `http://relay:8080/admin` does not work from the host browser because `relay` is a Docker DNS service name.
- Host browser must use the published host port:
  - `http://127.0.0.1:8080/admin`
  - `http://127.0.0.1:8081/admin` for relay_2

Validation:
- `docker ps` showed:
  - `disaster-app-relay` mapped `0.0.0.0:8080->8080/tcp`
  - `disaster-app-relay-2` mapped `0.0.0.0:8081->8080/tcp`
- `GET http://127.0.0.1:8080/healthz` returned `200`.
- `GET http://127.0.0.1:8080/admin/dashboard` returned dashboard HTML `200`.
- `GET /admin/assets/admin.js` returned `200`.
- `GET /admin/assets/admin.css` returned `200`.
- Admin login:
  - `admin/admin` returned `401`.
  - `admin/admin1234` returned `200` and set `admin_session`.
- Session/API checks:
  - `GET /admin/api/auth/me` returned `200`.
  - `GET /admin/api/metrics/summary` returned `200`.
  - `GET /admin/api/metrics/detail?domain=traffic` returned `200`.
  - `GET /admin/api/retention/status` returned `200`.

Known unavailable dashboard cards:
- `GET /admin/api/backup/status` returned `503 backup_status_unavailable`.
- `GET /admin/api/alerts` returned `503 alerts_unavailable`.

Follow-up:
- Configure backup status source for dev/prod.
- Configure alert source for dev/prod.
- Add an automated Relay Admin UI smoke script for login + dashboard APIs.

## 2026-05-31 - Registry Server Current-State Review

Context:
- Request was to review Registry server status and prepare Registry Admin UI implementation.
- Relevant docs read:
  - `docs/server/REGISTRY_PROTOCOL.md`
  - `server/registry/docs/registry_admin_ui_guide.md`
  - `docs/server/registry_admin_pages_spec.md`
  - `docs/server/registry_admin_development_guideline.md`
  - `server/registry/docs/registry_admin_operator_guide.md`

Initial registry implementation:
- Service: `server/registry`
- Runtime: Node.js + SQLite via `better-sqlite3`
- Public endpoints present before Admin UI work:
  - `GET /healthz`
  - `GET /health`
  - `GET /readyz`
  - `POST /v1/registry/relays:heartbeat`
  - `GET /v1/registry/relays`
  - `GET /v1/registry/relays/:relay_id`
- Registry DB had:
  - `relays`
  - `used_nonces`
- Relay heartbeat client exists at:
  - `server/relay/src/registry_heartbeat_client.js`

Important protocol gap:
- `docs/server/REGISTRY_PROTOCOL.md` defines registry signing as:
  - `METHOD`
  - `REQUEST_TARGET`
  - `TIMESTAMP_SECONDS`
  - `SHA256(raw_body_bytes)`
- Current registry implementation and relay heartbeat client still use:
  - timestamp
  - nonce
  - raw request body in canonical string
- This was not migrated in the same task to avoid breaking active relay heartbeats.

Follow-up:
- Migrate registry server and relay heartbeat client signing together.
- Add compatibility tests for old/new signing during transition if needed.

## 2026-05-31 - Registry Governance Data Model Foundation

Context:
- Registry Admin UI needs relay trust management, status management, federation eligibility, revocation feed, policy feed, and audit log support.

Changes in `server/registry/src/db.js`:
- Added relay governance columns:
  - `trust_level TEXT NOT NULL DEFAULT 'community'`
  - `status TEXT NOT NULL DEFAULT 'active'`
  - `federation_enabled INTEGER NOT NULL DEFAULT 0`
- Added auto-migration for existing SQLite DB files using `PRAGMA table_info(relays)` and `ALTER TABLE` for missing columns.
- Added indexes:
  - `idx_relays_trust_level`
  - `idx_relays_status`
- Added `registry_audit` table:
  - `id`
  - `actor`
  - `action`
  - `resource_type`
  - `resource_id`
  - `reason`
  - `created_at`
- Added store methods:
  - `listRelays()`
  - `updateRelayTrust(relayId, trustLevel, now)`
  - `updateRelayStatus(relayId, status, now)`
  - `updateRelayFederation(relayId, enabled, now)`
  - `dashboard(now, ttlSeconds)`
  - `recordAudit(...)`
  - `listAudit(limit)`
  - `revocations(now)`
  - `policy()`

Bug fixed during validation:
- First implementation attempted to create indexes on `trust_level` and `status` before adding missing columns to an existing DB.
- Registry crashed with:
  - `SqliteError: no such column: trust_level`
- Fixed by moving index creation after the auto-migration block.

Validation:
- Registry restarted against the existing dev DB without schema crash.
- Existing relays were readable with default `trust_level=community`, `status=active`, and `federation_enabled=false`.

Follow-up:
- Add certificate persistence model.
- Add policy persistence instead of a derived/hard-coded policy view.
- Add versioned revocation feed metadata.

## 2026-05-31 - Registry Admin Auth and API Foundation

Context:
- Registry Admin UI requires separate admin endpoints under `/admin/*`.
- Response convention from docs:
  - success: `{ "ok": true, "data": {} }`
  - error: `{ "ok": false, "error": { "code": "...", "message": "..." } }`

Changes:
- Added `server/registry/src/admin_auth.js`:
  - in-memory session store
  - `registry_admin_session` HttpOnly cookie
  - simple username/password config
- Added config fields in `server/registry/src/config.js`:
  - `REGISTRY_ADMIN_USERNAME`
  - `REGISTRY_ADMIN_PASSWORD`
  - `REGISTRY_ADMIN_SESSION_TTL_SECONDS`
- Added admin route handling in `server/registry/src/index.js`.

Admin auth endpoints:
- `POST /admin/api/auth/login`
- `POST /admin/api/auth/logout`
- `GET /admin/api/auth/me`

Admin data endpoints:
- `GET /admin/api/dashboard`
- `GET /admin/api/relays`
- `GET /admin/api/relays/:id`
- `PATCH /admin/api/relays/:id/trust`
- `PATCH /admin/api/relays/:id/status`
- `PATCH /admin/api/relays/:id/federation`
- `GET /admin/api/revocations`
- `POST /admin/api/revocations/rebuild`
- `GET /admin/api/policy`
- `GET /admin/api/audit`

Bug fixed during validation:
- Admin route handler sent a response, but `handleRequest` continued into public route fallback and tried to send another response.
- Registry crashed with:
  - `ERR_HTTP_HEADERS_SENT`
- Fixed by returning immediately whenever the path starts with `/admin` after admin handling.

Validation:
- `POST /admin/api/auth/login` with `admin/admin1234` returned `200` and set `registry_admin_session`.
- `GET /admin/api/dashboard` returned counts including two active relays.
- `GET /admin/api/relays` returned `relay` and `relay-2`.
- `PATCH /admin/api/relays/relay/trust` changed `relay` to `verified`.
- `GET /admin/api/audit` showed `relay_trust_updated`.
- `PATCH /admin/api/relays/relay/trust` reset `relay` back to `community` after smoke testing.
- `GET /v1/registry/relays` still worked after governance schema changes.

Security notes:
- Current admin auth is intentionally minimal and suitable only for dev/internal testing.
- Before production:
  - use hashed/file-backed credentials or external auth
  - add CSRF protection
  - enforce HTTPS
  - document credential rotation

## 2026-05-31 - Registry Admin UI Phase 1

Context:
- Implement first usable Registry Admin UI following:
  - `server/registry/docs/registry_admin_ui_guide.md`
  - `docs/server/registry_admin_pages_spec.md`
  - `docs/server/registry_admin_development_guideline.md`

Files added:
- `server/registry/src/public/admin-registry/index.html`
- `server/registry/src/public/admin-registry/relays.html`
- `server/registry/src/public/admin-registry/assets/registry_admin.css`
- `server/registry/src/public/admin-registry/assets/registry_admin.js`

Static routes:
- `GET /admin`
- `GET /admin/`
- `GET /admin/relays`
- `GET /admin/assets/registry_admin.css`
- `GET /admin/assets/registry_admin.js`

Implemented UI:
- Login/logout flow.
- Dashboard KPI cards:
  - active relays
  - community relays
  - verified relays
  - trusted relays
  - suspended relays
  - revoked relays
  - banned relays
  - federation-enabled relays
- Relay Directory table:
  - relay ID
  - endpoint
  - region
  - trust level
  - status
  - federation flag
  - last seen
- Relay search.
- Governance actions:
  - Verify
  - Trust
  - Suspend
  - Ban
  - Enable/disable federation
- Policy feed display.
- Revocation feed display.
- Audit log display.

Validation:
- `GET /admin` returned HTML `200`.
- `GET /admin/relays` returned HTML `200`.
- `GET /admin/assets/registry_admin.js` returned JS `200`.
- Registry image build passed.
- Registry container restart passed after migration/order fixes.
- Admin login and API calls passed with dev credentials.

Follow-up:
- Add dedicated pages:
  - Certificates
  - Trust Management
  - Revocations
  - Policy Feed
  - Audit Log
  - Metrics
  - Settings
- Add relay detail page.
- Add pagination/sort to large tables.
- Add stronger confirmation flow with mandatory reason for dangerous actions.
- Improve loading/error/empty states.
- Do a full mobile/desktop visual pass.

## 2026-05-31 - Phase 3.2 Milestone Tracker

Context:
- Requested a milestone task list for relay + registry server and to tick tasks already completed.

Created:
- `docs/milestones/Phase_3_2_Task_List.md`

Scope:
- Architecture alignment.
- Relay runtime packaging.
- Relay Admin UI runtime checks.
- Relay protocol/federation hardening.
- Registry runtime baseline.
- Registry protocol alignment.
- Registry governance data model.
- Registry Admin API.
- Registry Admin UI.
- Registry operations.
- Integration/smoke tests.
- Known gaps/follow-up notes.

Current state:
- Completed items reflect the relay packaging fixes, relay admin smoke checks, Registry Admin UI phase 1, registry governance schema/API additions, and manual smoke tests.
- Open items capture protocol signing alignment, relay hardening, certificate lifecycle, persistent policy/revocation feeds, production admin auth hardening, and automated tests.

## 2026-05-31 - Registry Admin UI Spec Alignment Pass

Context:
- Re-read updated references:
  - `docs/server/REGISTRY_PROTOCOL.md`
  - `server/registry/docs/registry_admin_operator_guide.md`
  - `server/registry/docs/registry_admin_ui_guide.md`
- Goal was to adjust the current Registry Admin UI/API to match the revised governance-console model.

Protocol changes:
- Updated registry request signing implementation to the documented v1 canonical model:
  - `METHOD`
  - `REQUEST_TARGET`
  - `TIMESTAMP_SECONDS`
  - `SHA256(raw_body_bytes)`
- Updated relay heartbeat client to send v1 registry signatures without `X-Registry-Nonce`.
- Registry verifier keeps a backward-compatible legacy path for old nonce + raw-body signatures during transition.
- Updated `server/registry/scripts/sign-heartbeat.js` to generate v1 signatures.
- Public relay discovery now includes:
  - `protocol_version`
  - `registry_version`
- Added public protocol feeds:
  - `GET /v1/registry/policy`
  - `GET /v1/registry/revocations`

Registry data/API changes:
- Added `relay_certificates` table for relay identity certificate lifecycle.
- Added certificate store methods:
  - list
  - issue
  - renew
  - revoke
- Added `result` to `registry_audit` records and auto-migration for existing DB files.
- Dashboard API now includes registry health metadata and recommended metric placeholders:
  - heartbeats/minute
  - discovery requests/minute
  - heartbeat failures
  - certificate issues
  - certificate revocations
  - policy updates
  - audit events
- Policy feed now includes:
  - `deny_relays`
  - `suspended_relays`
  - `minimum_trust_level_for_auto_sync`
  - `allowed_statuses_for_discovery`
- Added admin endpoints:
  - `GET /admin/api/metrics`
  - `GET /admin/api/certificates`
  - `POST /admin/api/certificates`
  - `PATCH /admin/api/certificates/:id/renew`
  - `PATCH /admin/api/certificates/:id/revoke`
  - `PATCH /admin/api/policy`

Registry Admin UI changes:
- Added Relay Details page:
  - `GET /admin/relays/:id`
  - shows relay identity, endpoint, region, trust, status, federation flag, first/last seen, capabilities, latest certificate, and relay-specific audit history
- Added Certificates page:
  - `GET /admin/certificates`
  - supports certificate issue form and renew/revoke actions
- Updated Relay Directory actions:
  - replaced separate Verify/Trust buttons with Promote Trust / Demote Trust workflow
  - kept trust level and status as separate badges
  - removed federation toggle from primary UI actions because federation capability should be informational/governed by trust policy, not a relay operations control
- Updated Audit Log display to include `result`.
- Dashboard now displays registry health and additional governance/traffic widgets from the updated guide.

Validation:
- `npm run check` in `server/registry` passed.
- `node --check server/registry/src/public/admin-registry/assets/registry_admin.js` passed.
- `node --check server/relay/src/registry_heartbeat_client.js` passed.
- Rebuilt registry container and restarted relay containers.
- Relay heartbeats continued to return `200` after v1 signing update.
- Admin login passed with dev credentials.
- Smoke-tested:
  - `GET /admin`
  - `GET /admin/relays/relay`
  - `GET /admin/certificates`
  - `GET /admin/api/dashboard`
  - `GET /admin/api/relays`
  - `GET /admin/api/relays/relay`
  - `GET /admin/api/certificates`
  - `POST /admin/api/certificates`
  - `GET /v1/registry/policy`
  - `GET /v1/registry/revocations`

Follow-up:
- Persist policy edits instead of only auditing `PATCH /admin/api/policy`.
- Add certificate validity constraints, one-active-certificate policy, and stronger verified-relay workflow enforcement.
- Add real discovery request/minute and heartbeat failure counters.
- Add dedicated pages for Trust Management, Revocations, Policy Feed, Audit Log, Metrics, and Settings.
- Add automated tests for v1 signature validation, legacy signature compatibility, body tampering, timestamp skew, and replay behavior.

## 2026-05-31 - Registry-Orchestrated Federation Design Update

Context:
- Product direction changed from relay operators manually adding peers to registry-controlled managed federation.
- Registry should remain the authority that decides which relay peers may connect, under short-lived sessions.
- Federation data-plane should move from periodic pull-only sync to push-first propagation with scoped reconciliation.

Docs updated:
- `docs/server/REGISTRY_PROTOCOL.md`
- `docs/server/FEDERATION_PROTOCOL.md`
- `server/registry/docs/registry_admin_ui_guide.md`

Protocol decisions recorded:
- Registry is now the managed federation control-plane authority.
- Registry still does not proxy, store, ingest, fetch, or inspect PeerMesh event payloads.
- Relay-to-relay event traffic remains direct data-plane traffic.
- Managed federation relays must request short-lived federation sessions from registry.
- Relays must not self-select federation peers in official or managed federation networks.
- Relays must reject federation traffic outside active registry-issued sessions.
- Session grants include:
  - `session_id`
  - source relay
  - assigned peers
  - allowed `gid` scope
  - permissions such as `push` and `pull_reconcile`
  - limits
  - `expires_at`
  - `policy_version`
  - registry signature
- Push-first federation endpoint was specified:
  - `POST /v1/federation/events:push`
- Cursor fetch remains available only as scoped reconciliation inside an active federation session.

Registry protocol additions:
- `POST /v1/registry/federation/sessions`
- `POST /v1/registry/federation/sessions/{session_id}:renew`
- `POST /v1/registry/federation/sessions/{session_id}:revoke`
- Federation policy fields:
  - `minimum_trust_level_for_federation`
  - `federation_session_ttl_seconds`
  - `max_peers_per_session`
  - `allowed_statuses_for_federation`
  - `allowed_federation_permissions`

Registry Admin UI guide additions:
- Federation Sessions module.
- Federation Topology module.
- Dashboard federation session widgets.
- Relay detail federation section.
- Audit events for session issued, denied, revoked, and topology rebalanced.
- UI guidance to avoid manual add-peer workflows in managed federation mode.

Follow-up implementation work:
- Add registry session tables and API.
- Add registry topology assignment/policy engine.
- Add relay registry session client.
- Add relay push queue and `/v1/federation/events:push`.
- Gate existing pull/fetch reconciliation behind active session validation.
- Deprecate static/manual peer add flow for managed mode.
- Add admin UI pages for Federation Sessions and Federation Topology.

## 2026-05-31 - Security Policy Update for Managed Federation

Context:
- Transport security was reviewed against the new registry-orchestrated federation model.
- Existing docs still described federation mostly as peer allowlists, shared tokens and optional mTLS.
- The security policy needed to define registry-issued sessions as the mandatory managed federation authorization layer.

Docs updated:
- `docs/SECURITY.md`
- `docs/milestones/Phase_3_2_Task_List.md`

Security policy decisions recorded:
- Managed federation requires registry coordination.
- Registry is the federation control plane.
- Relays are the event data plane.
- Registry must not carry PeerMesh event payloads.
- Managed relay deployments should require request signatures for non-public write APIs.
- Request signing should use:
  - method
  - request target
  - timestamp seconds
  - SHA256 raw body hash
- Request replay protection should use a bounded request-level replay cache.
- Replay protection must not depend on fields inside opaque event `body`.
- Federation traffic must require active registry-issued sessions.
- Session grants should include:
  - source relay
  - destination relay
  - allowed gids
  - allowed operations
  - limits
  - policy version
  - expiry
  - registry signature
- Push-first federation is the data-plane strategy, with scoped reconciliation for correctness.
- Push-only federation is explicitly not sufficient.
- Admin security requirements now include CSRF protection, HTTPS, IP allowlists/private access, hashed credentials, explicit reasons for dangerous actions and full audit logging.

Task list updates:
- Added relay request replay cache tasks.
- Added mandatory managed/private write signing task.
- Added Managed Federation Session Security section.
- Added Push-First Federation Data Plane section.
- Added Registry Admin API/UI tasks for federation sessions and topology.
- Added integration test tasks for session issue/revoke, replay cache, push, revoked sessions and unauthorized gids.
- Updated known gaps to mark registry signing and certificate lifecycle baseline as complete.

## 2026-05-31 - Relay Request Signature Policy Decision

Context:
- Decided that managed/production relay deployments should not expose unsigned relay write APIs.
- `REQUEST_SIGNATURE_ENABLED` as an optional security flag is too easy to misconfigure in production.

Decision:
- Production and managed relay mode should require request signatures by default.
- Relay startup should fail fast when signature-required mode is active but the signing secret is missing.
- Unsigned relay requests should only be possible through an explicit dev/test escape hatch.
- Recommended future env semantics:
  - `REQUEST_SIGNATURE_REQUIRED=1`
  - `REQUEST_SIGNATURE_SECRET=...`
  - `ALLOW_UNSIGNED_DEV_REQUESTS=1` only for local development or tests
- Health checks, static assets and explicitly public read-only endpoints can remain unsigned.
- App write/private fetch requests should be signed.
- Federation data-plane should not reuse the app request-signature flag; it should use registry-issued federation sessions and federation-specific signatures.
- Signature enforcement must be paired with a request-level replay cache.

Implementation notes:
- Keep dev usability, but make unsafe mode explicit and noisy in logs.
- Avoid one global HMAC secret as the long-term final design for all clients.
- Longer-term direction should consider per-install, per-client, per-relay or capability-scoped credentials.

## 2026-05-31 - Step 1 Implemented: Relay Request Signature Hardening

Scope:
- Implemented the first managed-federation implementation step.
- Focused on relay transport security only.

Code changes:
- Added signature-required relay config semantics:
  - `REQUEST_SIGNATURE_REQUIRED`
  - `REQUEST_SIGNATURE_SECRET`
  - `ALLOW_UNSIGNED_DEV_REQUESTS`
  - `REQUEST_SIGNATURE_REPLAY_TTL_SECONDS`
  - `REQUEST_SIGNATURE_REPLAY_MAX_ENTRIES`
- Kept legacy `REQUEST_SIGNATURE_ENABLED=1` as a compatibility alias for enabling required signatures.
- Production mode or `SERVER_MODE=managed` now requires relay request signatures unless the explicit dev/test escape hatch is enabled.
- Relay startup now fails fast when signatures are required but `REQUEST_SIGNATURE_SECRET` is missing.
- Added high-visibility warning log when unsigned dev/test mode is enabled.
- Added request-level replay cache for valid request signatures.
- Replay cache uses Redis when available and in-memory TTL cache otherwise.
- App request signatures are enforced for:
  - `POST /v1/events:ingest`
  - `GET /v1/events:fetch`
- Health checks, static/admin handling and existing peer-authenticated federation requests are outside app request-signature enforcement.
- Existing peer-authenticated federation fetch/ingest remains separate from app request signatures until registry-issued federation sessions are implemented.
- Removed relay storage replay dependency on `event.body.nonce`.

Files changed:
- `server/relay/src/config.js`
- `server/relay/src/request_signatures.js`
- `server/relay/src/routes.js`
- `server/relay/src/storage.js`
- `server/relay/src/app.js`
- `server/relay/scripts/test-request-signature.js`
- `server/.env.example`
- `server/.env.dev`
- `docs/milestones/Phase_3_2_Task_List.md`

Validation:
- `node --check` passed for modified relay JS files.
- Config policy checks passed for:
  - production without secret fails
  - production with secret requires signatures
  - explicit required mode requires signatures
  - unsigned dev override disables required signatures
- Request signature replay cache unit check passed.

Follow-up:
- Run container-level request signature smoke test after rebuilding/restarting relay in the target environment.
- Add full automated test coverage for replay rejection through the HTTP route.
- Replace legacy peer-token federation with registry-issued session validation in step 2 and later.

## 2026-05-31 - Step 2 Implemented: Registry Federation Session Baseline

Scope:
- Implemented the second managed-federation implementation step.
- Focused on registry control-plane session issuance, renewal, revocation, policy checks and signing.
- Relay session client and relay data-plane enforcement remain for later steps.

Code changes:
- Added registry federation session configuration:
  - `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET`
  - `REGISTRY_FEDERATION_SESSION_TTL_SECONDS`
  - `REGISTRY_FEDERATION_MAX_PEERS_PER_SESSION`
  - `REGISTRY_FEDERATION_POLICY_VERSION`
  - `REGISTRY_FEDERATION_MIN_TRUST_LEVEL`
  - `REGISTRY_FEDERATION_REQUIRE_CERTIFICATE`
  - `REGISTRY_FEDERATION_MAX_EVENTS_PER_MINUTE`
  - `REGISTRY_FEDERATION_MAX_BYTES_PER_MINUTE`
  - `REGISTRY_FEDERATION_MAX_IN_FLIGHT`
- Added `federation_sessions` SQLite table with:
  - `session_id`
  - source relay
  - assigned peers
  - requested gids
  - permissions
  - limits
  - `issued_at`
  - `expires_at`
  - `policy_version`
  - `registry_signature`
  - `revoked_at`
- Added session manager module:
  - validates session requests
  - checks source relay status, heartbeat freshness, federation enabled flag, trust level and optional active certificate
  - assigns eligible active peers from registry state
  - signs registry session grants
  - generates per-peer short-lived session tokens bound to source relay, destination relay, allowed gids, permissions and expiry
- Added signed public registry endpoints:
  - `POST /v1/registry/federation/sessions`
  - `POST /v1/registry/federation/sessions/{session_id}:renew`
  - `POST /v1/registry/federation/sessions/{session_id}:revoke`
- Added audit events:
  - `federation_session_issued`
  - `federation_session_denied`
  - `federation_session_renewed`
  - `federation_session_revoked`
- Extended registry policy feed with managed federation fields.
- Extended registry dashboard data with active federation session count.
- Relay heartbeat metadata now advertises `federation` capability so registry policy can identify technically eligible relays.
- Dev compose now sets registry federation session defaults.

Files changed:
- `server/registry/src/config.js`
- `server/registry/src/db.js`
- `server/registry/src/federation_sessions.js`
- `server/registry/src/index.js`
- `server/registry/scripts/test-federation-sessions.js`
- `server/registry/package.json`
- `server/registry/.env.example`
- `server/docker-compose.dev.yml`
- `server/relay/src/registry_heartbeat_client.js`
- `docs/milestones/Phase_3_2_Task_List.md`

Validation:
- `npm run check` passed in `server/registry`.
- `npm run test:federation-sessions` passed:
  - session issue
  - policy denial
  - expired session renewal denial
  - session renew
  - session revoke
- `node --check server/relay/src/registry_heartbeat_client.js` passed.
- `docker compose -f server/docker-compose.dev.yml config` passed with the existing Compose `version` warning.

Follow-up:
- Step 3 should add the relay registry session client and active session cache.
- Relay-side validation for incoming federation traffic is not implemented yet.
- Registry Admin UI session listing/revocation remains for the later Admin UI step.
- Session token tamper validation belongs with relay-side session validation.

## 2026-05-31 - Step 3 Implemented: Relay Session Client

Scope:
- Implemented the third managed-federation implementation step.
- Focused on relay-side registry session acquisition, renewal and active-session cache.
- Federation push endpoint and relay-side data-plane enforcement remain for later steps.

Code changes:
- Added shared relay registry request signing helper:
  - `server/relay/src/registry_request_signing.js`
- Updated relay registry heartbeat client to reuse the shared signing helper.
- Added relay registry session client:
  - issues sessions through `POST /v1/registry/federation/sessions`
  - renews sessions through `POST /v1/registry/federation/sessions/{session_id}:renew`
  - can revoke sessions through `POST /v1/registry/federation/sessions/{session_id}:revoke`
  - caches only active, unexpired sessions
  - schedules refresh before expiry
  - backs off after denial or transient failure
  - exposes `status()` and `getActiveSession()`
- Added relay configuration:
  - `REGISTRY_FEDERATION_SESSIONS_ENABLED`
  - `REGISTRY_FEDERATION_SESSION_REFRESH_INTERVAL_SECONDS`
  - `REGISTRY_FEDERATION_SESSION_RETRY_SECONDS`
  - `REGISTRY_FEDERATION_SESSION_RENEW_AHEAD_SECONDS`
  - `REGISTRY_FEDERATION_SESSION_MAX_PEERS`
  - `REGISTRY_FEDERATION_SESSION_MAX_EVENTS_PER_MINUTE`
  - `REGISTRY_FEDERATION_SESSION_MAX_BYTES_PER_MINUTE`
- Wired the session client into relay app startup/shutdown.
- Relay shutdown attempts to revoke the active registry federation session with reason `relay_shutdown`.
- Added Relay Admin API status/refresh endpoints:
  - `GET /admin/api/federation/session`
  - `POST /admin/api/federation/session/refresh`
- Dev compose now enables registry federation sessions for both relay containers.

Files changed:
- `server/relay/src/registry_request_signing.js`
- `server/relay/src/registry_session_client.js`
- `server/relay/src/registry_heartbeat_client.js`
- `server/relay/src/config.js`
- `server/relay/src/app.js`
- `server/relay/src/routes.js`
- `server/relay/src/routes_admin.js`
- `server/relay/scripts/test-registry-session-client.js`
- `server/relay/package.json`
- `server/.env.example`
- `server/docker-compose.dev.yml`
- `docs/milestones/Phase_3_2_Task_List.md`

Validation:
- `npm run test:registry-session` passed:
  - session issue + cache
  - cache reuse before refresh deadline
  - session renewal
  - signed registry request headers
- `node --check` passed for modified relay JS files.

How to test:
- Unit smoke:
  - `cd server/relay && npm run test:registry-session`
- Container smoke after registry/relay rebuild:
  - ensure relays are trusted and federation-enabled in Registry Admin
  - call `GET /admin/api/federation/session` through Relay Admin session
  - expected `active: true` once registry policy allows a session

Follow-up:
- Step 4 should use active registry sessions for push-first federation fanout.
- Step 5 should validate incoming federation traffic against the active session.
- Current static federation sync still exists until data-plane migration is implemented.

## Managed Federation Step 4 Status (Push-First Data Plane)

Status: Transport path complete; security enforcement is not complete.

Review note:
- Step 4 adds the push transport path, but inbound `POST /v1/federation/events:push` must not be considered registry-session trusted yet.
- Current inbound verification checks that the request signature matches the `X-Federation-Token` supplied by the caller.
- The receiver does not yet validate that the token belongs to a registry-issued active session for the claimed source relay, destination relay, operation, gid scope and expiry.
- Treat this as a data-plane transport baseline only. Step 5 must add real registry-session authorization before the endpoint is production-safe.

### Step 4A Completed - Outbound Push Queue

Implemented:

* Added relay push queue (`federation_push.js`).
* Added federation request signing:

  * METHOD
  * REQUEST_TARGET
  * TIMESTAMP_SECONDS
  * SHA256(raw_body)
* Added asynchronous outbound federation fanout.
* Fanout is restricted to peers assigned by the active registry-issued federation session.
* Added retry/backoff support.
* Added per-peer gid filtering.
* Added batching support.
* Added federation push metrics:

  * federation_push_queue_depth
  * federation_push_in_flight
  * federation_push_enqueued_total
  * federation_push_success_total
  * federation_push_fail_total
  * federation_push_drops_total

### Step 4B Completed - Inbound Federation Push Endpoint

Implemented:

* Added:

  * POST /v1/federation/events:push

* Implemented required federation headers:

  * X-Federation-Session
  * X-Federation-Token
  * X-Federation-Timestamp
  * X-Federation-Signature

* Added inbound federation signature verification using the same canonical federation signing model as outbound push.

Important limitation:

* The signature key is currently the request-supplied `X-Federation-Token`.
* This proves only that the sender knows the token it sent in the same request.
* It does not yet prove the token was issued by the registry or is scoped to this relay pair.
* Step 5 must replace this with active session lookup and grant validation.

* Added federation event ingestion path:

  * validate request
  * validate signature
  * ingest events through relay storage
  * return HTTP 202 Accepted

* Added federation validation failure metric:

  * federation_push_validation_failures_total

Current behavior:

Relay A
↓
Push Queue
↓
Signed Push Request
↓
Relay B
↓
Transport Signature Check
↓
Storage Ingest

### Step 4C Completed - Unit Coverage

Added outbound federation push tests:

* federation_push_signature
* federation_push_no_session
* federation_push_fanout

Added inbound federation route tests:

* federation_push_route_accepts_signed_push
* federation_push_route_rejects_missing_headers
* federation_push_route_rejects_bad_signature

Current test coverage:

Outbound:

* signing
* queueing
* batching
* gid filtering
* peer filtering
* retry-safe fanout

Inbound:

* required headers
* transport signature check
* storage ingest path
* rejection behavior

### Follow-Up Validation

Recommended but not required for Step 4 completion:

* container-level relay-to-relay federation push integration test
* federation push smoke test inside docker-compose multi-relay environment

These are verification tasks rather than missing transport implementation work.

### Current State

Local relay ingest
↓
Federation Push Queue
↓
Registry Session Peer Fanout
↓
Signed Push Requests
↓
Receiving Relay Transport Check
↓
Storage Ingest

Step 4 transport path is functionally complete.

Security status:

* Not production-safe until Step 5 session enforcement lands.
* `server/relay/src/relay.zip` is an untracked artifact observed during review and should not be committed.

Step 5 will focus on session enforcement:

* source relay validation
* destination relay validation
* gid scope validation
* operation validation
* session expiry validation
* revoked session validation
* reconciliation scoping

## Managed Federation Step 5 Status - Inbound Push Session Enforcement

Status: Inbound push grant validation was implemented first; Step 5 now also includes revoked-session validation and scoped reconciliation.

Implemented:

* Registry now attaches a registry-signed peer grant to each assigned peer in a federation session.
* Peer grant fields:
  * `session_id`
  * `source_relay_id`
  * `destination_relay_id`
  * `endpoint`
  * `allowed_gids`
  * `permissions`
  * `expires_at`
  * `policy_version`
  * `session_token`
  * `registry_signature`
* Relay outbound push now sends:
  * `session_id`
  * `source_relay_id`
  * `destination_relay_id`
  * `peer_grant`
  * `events`
* Relay inbound push now validates before storage ingest:
  * request HMAC using session token
  * peer grant is present
  * peer grant registry signature is valid
  * header session id matches grant session id
  * header token matches grant token
  * body source relay matches grant source relay
  * body destination relay matches grant destination relay
  * grant destination relay matches local relay id
  * grant operation allows `push`
  * grant has not expired
  * every event gid is allowed by the peer grant
* Relay dev config now supports:
  * `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET`

Security improvement:

* A caller can no longer choose an arbitrary `X-Federation-Token` and self-sign an inbound push.
* The receiver requires a registry-signed peer grant binding token, source relay, destination relay, gid scope, operation and expiry.

Validation:

* `cd server/relay && npm run test:federation-push`
* `cd server/relay && npm run test:federation-push-route`
* `cd server/registry && npm run test:federation-sessions`
* `cd server/relay && npm run test:registry-session`

New inbound push rejection coverage:

* missing federation headers
* bad request signature
* arbitrary caller-supplied token
* unauthorized gid
* expired peer grant
* mismatched destination relay
* peer grant for another relay

Remaining Step 5 work:

* Add container-level multi-relay smoke test after reconciliation scoping is complete.

## Managed Federation Step 5 Status - Revocation Feed and Scoped Reconciliation

Implemented:

* Registry exposes federation session revocations at `GET /v1/registry/federation/revocations`.
* Relay registry session client polls and caches revoked session ids.
* Relay inbound federation push rejects cached revoked sessions with `session_revoked`.
* Relay `GET /v1/events:fetch` now supports managed federation reconciliation headers:
  * `X-Federation-Session`
  * `X-Federation-Token`
  * `X-Federation-Operation: pull_reconcile`
  * `X-Federation-Grant`
  * `X-Federation-Timestamp`
  * `X-Federation-Signature`
* Managed federation fetch is exempt from app request signatures because it uses federation-specific HMAC + registry-signed grant validation.
* Relay rejects reconciliation when:
  * session headers or grant are missing
  * request signature is invalid
  * operation is not `pull_reconcile`
  * grant operation does not allow `pull_reconcile`
  * requested gid is outside grant scope
  * grant source/destination/session/token do not match
  * session has expired or is cached as revoked
* Federation sync uses registry session peers when `REGISTRY_FEDERATION_SESSIONS_ENABLED=1`.
* Static/manual peer mappings remain available only outside managed federation mode.

Validation:

* `cd server/relay && npm run test:federation-push-route`
* `cd server/relay && npm run test:federation-push`
* `cd server/relay && npm run test:registry-session`
* `cd server/registry && npm run test:federation-sessions`
* `cd server/registry && npm run check`
* `docker compose -f server/docker-compose.dev.yml config`
* Targeted relay ESLint on changed files:
  * `./node_modules/.bin/eslint src/federation.js src/federation_push.js src/routes.js src/registry_session_client.js scripts/test-federation-push-route.js scripts/test-registry-session-client.js`

Known validation note:

* Full `cd server/relay && npm run lint` still fails because of pre-existing lint debt in admin/browser JS and older scripts; changed Step 5 files pass targeted ESLint.


Remaining planned steps:
- Step 6:
  - Browser-level smoke and operator polish for Registry Admin federation views.
  - Optional follow-up APIs such as force-renew/export-session-grant if still needed by operators.

Current important files:
- Registry:
  - `server/registry/src/federation_sessions.js`
  - `server/registry/src/db.js`
  - `server/registry/src/index.js`
  - `server/registry/scripts/test-federation-sessions.js`
- Relay:
  - `server/relay/src/federation_push.js`
  - `server/relay/src/registry_session_client.js`
  - `server/relay/src/registry_request_signing.js`
  - `server/relay/src/registry_heartbeat_client.js`
  - `server/relay/src/routes.js`
  - `server/relay/src/routes_admin.js`
  - `server/relay/src/federation.js`
  - `server/relay/src/storage.js`
  - `server/relay/scripts/test-federation-push.js`
  - `server/relay/scripts/test-federation-push-route.js`
  - `server/relay/scripts/test-registry-session-client.js`
- Dev environment:
  - `server/docker-compose.dev.yml`
  - `server/.env.example`

Validation commands that currently pass:
- Relay session client:
  - `cd server/relay && npm run test:registry-session`
- Relay push transport:
  - `cd server/relay && npm run test:federation-push`
  - `cd server/relay && npm run test:federation-push-route`
- Registry session baseline:
  - `cd server/registry && npm run check`
  - `cd server/registry && npm run test:federation-sessions`
- Compose config:
  - `docker compose -f server/docker-compose.dev.yml config`
  - Existing warning about obsolete Compose `version` is known and not related to managed federation.

## Managed Federation Step 6 Status - Registry Admin Sessions and Topology

Implemented:

* Registry Admin API now exposes:
  * `GET /admin/api/federation/sessions`
  * `GET /admin/api/federation/sessions/:id`
  * `POST /admin/api/federation/sessions/:id/revoke`
  * `POST /admin/api/federation/topology/rebalance`
* Session revoke and topology rebalance now require explicit operator reason on the backend.
* Registry Admin static UI now includes:
  * `/admin/federation`
  * `/admin/federation/sessions/:id`
* Federation page shows:
  * session list
  * session status and expiry
  * topology snapshot
  * recent session denials
  * revoke and rebalance actions
* Session details page shows:
  * source relay
  * assigned peers
  * permissions
  * allowed gids
  * audit history
* Added smoke script:
  * `cd server/registry && npm run test:admin-federation`
  * exercises admin login, relay governance setup, session list/detail, revoke reason enforcement, revoke success, rebalance reason enforcement, and rebalance success

Validation note:

* In this environment, `npm run test:admin-federation` exits successfully and is treated as pass, although npm does not echo the script's internal `PASS` lines.

## Managed Federation Compose Smoke

Implemented:

* Added `cd server/relay && npm run test:managed-federation-compose`
* The script assumes dev compose host ports:
  * registry: `8090`
  * relay: `8080`
  * relay_2: `8081`
* Smoke flow:
  * login to Registry Admin
  * promote `relay` and `relay-2` to `trusted`
  * enable federation for both relays
  * wait for active registry-issued sessions for both relays
  * ingest an event into relay A
  * verify relay B can fetch the propagated event

Validated:

* `docker compose -f server/docker-compose.dev.yml --profile multi up -d --build`
* `cd server/relay && npm run test:managed-federation-compose`
* Observed pass output:
  * `PASS managed_federation_registry_admin_login`
  * `PASS managed_federation_registry_governance`
  * `PASS managed_federation_sessions_active relay:1 relay-2:1`
  * `PASS managed_federation_ingest_source`
  * `PASS managed_federation_propagation_target`

Runtime testing notes:
- Dev compose enables `REGISTRY_FEDERATION_SESSIONS_ENABLED=1` for both relays.
- Registry default federation policy requires `trusted` relays.
- For container smoke testing, use Registry Admin to:
  - promote both relays to `trusted`
  - enable federation for both relays
  - wait for heartbeats
  - call Relay Admin `GET /admin/api/federation/session`
- Expected relay session status after policy allows it:
  - `enabled: true`
  - `active: true`
  - `session.peers` contains assigned peers when another eligible relay exists

Important design constraints:
- Registry is control plane only.
- Registry must not store or proxy PeerMesh event payloads.
- Relays are data plane.
- Managed federation relays must not self-select peers.
- Static/manual peer mappings still exist but should not be used for managed federation after Step 4/5.
- Push-first federation must be non-blocking for app ingest.

## Managed Federation Browser Smoke - Registry Admin UI

Implemented:

* Added `cd server/registry && npm run test:admin-federation-browser`
* Added headless Chrome smoke script at:
  * `server/registry/scripts/test-admin-federation-browser.js`
* Browser smoke covers:
  * Registry Admin login
  * federation session list page
  * session detail page
  * revoke action through the actual UI
  * logout back to login screen

Implementation notes:

* The script uses Chrome DevTools Protocol directly through a minimal built-in WebSocket client, so it does not add Playwright or Puppeteer dependencies.
* Default target URL is `http://127.0.0.1:8090`.
* Default credentials follow current dev compose defaults:
  * `admin`
  * `admin1234`
* Chrome teardown now waits for process exit before removing the temporary profile directory to avoid false-negative cleanup failures.

Validated:

* `docker compose -f server/docker-compose.dev.yml --profile multi up -d --build`
* `cd server/registry && npm run test:admin-federation-browser`
* Observed pass output:
  * `PASS admin_federation_browser_login_and_list`
  * `PASS admin_federation_browser_session_detail`
  * `PASS admin_federation_browser_revoke_action`
  * `PASS admin_federation_browser_logout`

## Registry Baseline Smoke Script

Implemented:

* Added `cd server/registry && npm run test:smoke`
* Added smoke script:
  * `server/registry/scripts/test-smoke.js`
* The script starts a temporary local registry process with an isolated SQLite DB and verifies:
  * `GET /healthz`
  * `POST /admin/api/auth/login`
  * `GET /admin/api/dashboard`
  * `GET /admin/api/relays`
  * `GET /admin/api/policy`
  * `GET /admin/api/revocations`
  * `GET /admin/api/audit`

Implementation notes:

* The smoke test seeds relay state through unsigned test heartbeats with `REGISTRY_REQUIRE_SIGNATURE=0`.
* It writes two admin mutations before reading revocations and audit:
  * promote `relay-a` to `trusted`
  * suspend `relay-b`
* This gives deterministic assertions for:
  * relay directory state
  * revocation feed content
  * audit log entries
* The same smoke script now also covers:
  * `GET /readyz`
* Readiness assertion currently verifies:
  * HTTP `200`
  * `db: "ok"`

Validated:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:smoke`
* In this environment, `npm run test:smoke` exits successfully and is treated as pass, although npm does not echo the script's internal `PASS` lines.

## Registry Admin CSRF Protection

Implemented:

* Registry Admin session login now returns a per-session `csrf_token`.
* `GET /admin/api/auth/me` now also returns the active `csrf_token`.
* All Registry Admin mutation endpoints now require `X-CSRF-Token`, including:
  * logout
  * relay trust/status/federation updates
  * policy update
  * revocation rebuild
  * federation session revoke
  * topology rebalance
  * certificate issue/renew/revoke
* Missing or invalid token returns:
  * HTTP `403`
  * `error.code = "csrf_invalid"`

Frontend changes:

* `server/registry/src/public/admin-registry/assets/registry_admin.js` now stores the CSRF token from:
  * login response
  * `auth/me` session restore
* The admin UI automatically sends `X-CSRF-Token` on non-GET admin API requests.
* Logout clears the in-memory token after the request completes.

Test changes:

* `server/registry/scripts/test-smoke.js` now verifies that an admin mutation without CSRF token is rejected with `403 csrf_invalid`.
* `server/registry/scripts/test-admin-federation.js` now sends the CSRF token on all admin mutations.
* `server/relay/scripts/test-managed-federation-compose.js` now sends the CSRF token when calling Registry Admin governance endpoints.

Validated:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:smoke`
* `cd server/registry && npm run test:admin-federation`
* `docker compose -f server/docker-compose.dev.yml --profile multi up -d --build`
* `cd server/registry && npm run test:admin-federation-browser`
* Browser smoke still passes:
  * `PASS admin_federation_browser_login_and_list`
  * `PASS admin_federation_browser_session_detail`
  * `PASS admin_federation_browser_revoke_action`
  * `PASS admin_federation_browser_logout`

## Registry Admin Relay Directory and Audit Query Support

Implemented:

* `GET /admin/api/relays` now supports:
  * `q`
  * `trust_level`
  * `status`
  * `sort`
  * `order`
  * `limit`
  * `offset`
* `GET /admin/api/audit` now supports:
  * `q`
  * `actor`
  * `action`
  * `resource_type`
  * `sort`
  * `order`
  * `limit`
  * `offset`
* Both endpoints now return page metadata:
  * `total`
  * `limit`
  * `offset`
  * effective filter/sort fields

Implementation notes:

* Query support is implemented in the HTTP layer on top of current store methods.
* This keeps the change small and avoids widening the DB abstraction before larger data volumes require it.
* Default relay sort is:
  * `relay_id asc`
* Default audit sort is:
  * `created_at desc`

Validated:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:smoke`
* Smoke assertions now verify:
  * filtered relay directory query with paging
  * filtered audit query with paging

## Registry Admin Credential Hardening

Implemented:

* Registry Admin no longer depends on plaintext password config by default.
* Supported secure credential sources now are:
  * `REGISTRY_ADMIN_PASSWORD_HASH`
  * `REGISTRY_ADMIN_CREDENTIALS_FILE`
* Supported file-backed credential JSON shape:
  * `username`
  * `password_hash`
* Password hash format is:
  * `scrypt$N$r$p$salt_hex$derived_key_hex`
* Plaintext admin password is now only available as an explicit compatibility escape hatch:
  * `REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD=1`
  * together with `REGISTRY_ADMIN_PASSWORD`

Implementation notes:

* `server/registry/src/config.js` now fails startup when no secure admin credential source is configured.
* `server/registry/src/admin_auth.js` now verifies admin passwords against scrypt hashes.
* Invalid password hash format now fails at startup instead of failing only on login attempt.
* Added helper script:
  * `cd server/registry && npm run admin:hash-password -- <password>`
* Dev compose registry now uses `REGISTRY_ADMIN_PASSWORD_HASH` instead of plaintext password env.

Validation:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:smoke`
* `cd server/registry && npm run test:admin-federation`

## Registry Signing Regression Coverage

Implemented:

* Added `cd server/registry && npm run test:signing`
* Added script:
  * `server/registry/scripts/test-signing.js`
* Coverage now verifies registry write-signing behavior on the real heartbeat route for:
  * valid v1 body-hash signature
  * invalid signature
  * timestamp skew rejection
  * body tampering rejection
  * legacy nonce replay rejection
  * current v1 replay behavior

Important behavior note:

* The current v1 body-hash signing path does not yet maintain a bounded replay cache.
* The new regression test documents current behavior explicitly:
  * same signed v1 request replayed within the timestamp window is still accepted
* Legacy nonce-based compatibility requests still reject second-use replay with:
  * `nonce_replay`

Validation:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:signing`

## Registry Schema Migration Regression Coverage

Implemented:

* Added `cd server/registry && npm run test:schema-migration`
* Added script:
  * `server/registry/scripts/test-schema-migration.js`
* The migration test builds a real old-style SQLite fixture with:
  * legacy `relays` table without governance columns
  * legacy `registry_audit` table without `result`
  * legacy `used_nonces`
* The script then starts the real registry process against that fixture and verifies:
  * startup succeeds
  * legacy relay remains readable through admin API
  * defaulted governance fields appear on legacy relay
  * legacy audit rows remain readable through admin API
  * migrated audit rows default `result` to `success`
* After shutdown, the script inspects the migrated SQLite file and verifies:
  * new relay columns exist
  * `registry_audit.result` exists
  * `relay_certificates` table exists
  * `federation_sessions` table exists
  * expected post-migration indexes exist

Implementation notes:

* The old DB fixture is created with Python stdlib `sqlite3` because direct host-side `better-sqlite3` loading is not reliable in this environment.
* Migration validation still exercises the real Node registry binary and the real HTTP admin routes.

Validation:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:schema-migration`

## Registry Policy Persistence

Implemented:

* Added persisted SQLite policy storage through new table:
  * `registry_policy`
* Registry now keeps a single persisted policy row with the current feed fields:
  * `deny_relays`
  * `suspended_relays`
  * `minimum_trust_level_for_auto_sync`
  * `allowed_statuses_for_discovery`
  * `minimum_trust_level_for_federation`
  * `allowed_statuses_for_federation`
  * `federation_session_ttl_seconds`
  * `max_peers_per_session`
  * `allowed_federation_permissions`
  * `federation_policy_version`
* `GET /admin/api/policy` and `GET /v1/registry/policy` now read from persisted policy state.
* `PATCH /admin/api/policy` now validates and persists policy changes instead of only writing audit entries.

Runtime behavior changes:

* Public relay discovery now respects persisted policy:
  * `allowed_statuses_for_discovery`
  * `deny_relays`
* Managed federation session issuance/renewal now respects persisted policy:
  * `minimum_trust_level_for_federation`
  * `allowed_statuses_for_federation`
  * `federation_session_ttl_seconds`
  * `max_peers_per_session`
  * `allowed_federation_permissions`
  * `federation_policy_version`

Validation:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:smoke`
* `cd server/registry && npm run test:admin-federation`
* `cd server/registry && npm run test:federation-sessions`
* The smoke script now verifies:
  * policy patch succeeds
  * public policy feed reflects persisted values
  * discovery output follows persisted policy
  * persisted policy survives registry restart on the same DB

## Registry Revocation Feed Persistence and Versioning

Implemented:

* Added persisted SQLite revocation feed storage through new table:
  * `registry_revocation_feed`
* Revocation feed is now a published snapshot with:
  * `version`
  * `generated_at`
  * `revoked_relays`
  * `items`
* `GET /admin/api/revocations` and `GET /v1/registry/revocations` now return the persisted snapshot instead of deriving live output on every request.
* `POST /admin/api/revocations/rebuild` now:
  * computes a fresh snapshot from current relay state
  * increments the persisted version
  * publishes the new snapshot to SQLite

Runtime behavior changes:

* Revoking or banning a relay does not automatically mutate the published revocation feed snapshot.
* The published revocation feed only changes after explicit rebuild.
* Dashboard `last_revocation_feed_publish` now comes from the persisted feed publish timestamp rather than inferred audit history.

Validation:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:smoke`
* `cd server/registry && npm run test:schema-migration`
* `cd server/registry && npm run test:admin-federation`
* The smoke script now verifies:
  * revocation feed exposes `version`
  * feed snapshot does not change before rebuild
  * rebuild increments version
  * rebuilt snapshot includes newly revoked relay
  * revocation snapshot persists across restart on the same DB

## Registry Admin Credential Rotation Guide

Implemented:

* Expanded `server/registry/docs/registry_admin_operator_guide.md` with a concrete credential rotation runbook.
* The guide now covers:
  * file-backed credential rotation using `REGISTRY_ADMIN_CREDENTIALS_FILE`
  * hash-in-env rotation using `REGISTRY_ADMIN_PASSWORD_HASH`
  * verification steps
  * rollback steps
  * operational notes about existing admin sessions and maintenance windows

Design alignment:

* The runbook matches the current hardened credential model already implemented in registry code.
* The guide explicitly treats plaintext admin password mode as a compatibility-only escape hatch, not a normal operating mode.

## Registry Schema Auto-Migration Rollback Checks

Implemented:

* Expanded `server/registry/docs/registry_admin_operator_guide.md` with a dedicated rollback-check section for registry schema auto-migration.
* The new section covers:
  * pre-deploy checks before a schema-moving release
  * post-deploy verification checks
  * rollback decision criteria
  * safe rollback procedure using DB backup + previous binary
  * explicit non-goals for unsafe manual SQLite downgrades

Design alignment:

* The guidance matches the current registry implementation model:
  * SQLite schema auto-migration at startup
  * forward-compatibility regression testing via old fixture migration test
  * no guaranteed reverse compatibility of older binaries against newly migrated DB files

## Registry SQLite Backup and Restore Runbook

Implemented:

* Expanded `server/registry/docs/registry_admin_operator_guide.md` with a dedicated SQLite backup/restore section for registry operations.
* The new section covers:
  * default DB path expectations
  * backup scope
  * recommended backup timing
  * file-level backup procedure
  * WAL/SHM handling note
  * restore procedure
  * minimal restore verification checklist
  * operational cautions for schema-moving releases

Design alignment:

* The runbook matches the current registry runtime:
  * single SQLite DB file configured by `REGISTRY_DB_PATH`
  * persisted policy feed
  * persisted revocation feed
  * auto-migrated registry schema on startup

## 2026-06-01 - Relay Event Envelope Alignment

Implemented:

* Aligned relay storage/fetch/export behavior with `docs/specs/RELAY_PROTOCOL_SPEC.md` and `docs/server/FEDERATION_PROTOCOL.md`.
* The canonical relay event envelope remains:
  * `gid`
  * `eid`
  * `ts`
  * `exp`
  * `body`
  * `sig`
* `body` is now treated as the opaque JSON application payload in storage, not as a place to store the whole event envelope.

Code changes:

* `server/relay/src/storage.js`
  * `ingestEvents()` now stores `event.body` into the DB `events.body` column.
  * `fetchEvents()` reconstructs the full event envelope from DB columns before returning results.
  * legacy rows where `events.body` already contains a full envelope are still read compatibly by unwrapping `body.body`.
* `server/relay/scripts/export-events.js`
  * exports canonical full event envelopes instead of raw DB rows.
  * also unwraps legacy stored-envelope rows.
* `server/relay/scripts/test-fetch.js`
  * added `fetch_canonical_envelope` regression coverage that checks DB storage shape, fetch response shape, and legacy stored-envelope row compatibility.
* `docs/server/FEDERATION_PROTOCOL.md`
  * updated the push example so `body` is shown as an opaque JSON object, matching `../../specs/RELAY_PROTOCOL_SPEC.md`.

Validation:

* `node --check server/relay/src/storage.js`
* `node --check server/relay/scripts/export-events.js`
* `node --check server/relay/scripts/test-fetch.js`
* `cd server/relay && npx eslint src/storage.js scripts/export-events.js scripts/test-fetch.js`
* `cd server/relay && npm run test:federation-push`
* `cd server/relay && npm run test:federation-push-route`
* `server/relay/scripts/run-validation-tests.sh`
* `server/relay/scripts/run-fetch-tests.sh`
* `cd server/relay && npm run test:managed-federation-compose`

Operational note:

* First managed federation smoke attempt timed out because relay started before `relay-2` was trusted/federation-enabled and received a session with `peers=0`.
* Restarting `disaster-app-relay` after registry governance was configured forced a fresh session with `relay:1 relay-2:1`; the smoke then passed.

## 2026-06-01 - Client-Managed Relay Session Architecture Decision

Decision:

* The registry now formally owns client bootstrap for managed internet-relay access.
* Official architecture direction is:
  * client knows registry protocol
  * registry returns candidate relays plus a short-lived access grant
  * client chooses a relay from that candidate set
  * relay validates the access grant and establishes a pairwise session
  * client talks directly to the relay inside that pairwise session
  * registry stays off the event data plane

Design intent:

* Do not require clients to ship or maintain a hard-coded relay list beyond bootstrap registry endpoints.
* Do not require operators to manually coordinate relay choice for clients.
* Keep policy, trust, health, capacity, and abuse controls centralized in the registry control plane.
* Keep event transport direct between client and relay.

Required protocol/docs updates completed:

* `docs/server/REGISTRY_PROTOCOL.md`
  * now documents client access-grant bootstrap, renewal/reassignment, candidate relay selection, pairwise session establishment, and fallback behavior
* `docs/specs/RELAY_PROTOCOL_SPEC.md`
  * now documents that managed client access may require registry-issued access-grant headers plus a pairwise client-relay session in addition to request signing
* `docs/SECURITY.md`
  * now documents client access-grant validation plus pairwise session enforcement as a control-plane/data-plane layer distinct from federation sessions
* `docs/milestones/Phase_3_2_Task_List.md`
  * now tracks registry bootstrap/session tasks and relay enforcement/tests for managed client access

Follow-up implementation work intentionally left open:

* registry client access-grant bootstrap endpoint
* registry client access-grant renewal / reassignment endpoint
* relay-side grant validation and pairwise session establishment on ingest/fetch
* managed client access-grant + pairwise-session smoke coverage
- Reconciliation remains only as scoped repair inside active registry sessions.
- Relay must treat event `body` as opaque.
- Do not reintroduce replay dependence on `event.body.nonce`.

Known implementation gaps to avoid missing:
- Step 4 now uses active registry sessions for push-first federation fanout.
- Existing static federation sync in `server/relay/src/federation.js` still exists.
- Incoming federation traffic is not yet validated against registry-issued sessions.
- Current inbound push signature verification uses the request-supplied `X-Federation-Token`; Step 5 must validate that token against an active registry-issued session grant before ingesting.
- Session token tamper validation should be implemented with relay-side session validation.
- Registry Admin federation pages/API are implemented for sessions, session details, revoke, and topology rebalance.
- Full container-level integration test for relay + registry sessions is still missing.
- Untracked `server/relay/src/relay.zip` is a local artifact and must not be committed.

Suggested Step 5 implementation approach:
- Add relay-side session grant validation before accepting `POST /v1/federation/events:push`.
- Validate:
  - `X-Federation-Session` maps to an active registry-issued session known to this relay
  - token matches a peer grant in that session
  - source relay is authorized
  - destination relay is this relay
  - operation includes `push`
  - every event gid is allowed by the peer grant
  - session has not expired or been revoked
  - registry signature over the session grant is valid
- Add rejection tests for:
  - arbitrary caller-supplied token
  - expired session
  - revoked session
  - unauthorized gid
  - unauthorized source relay
  - wrong destination relay
- Only after this validation exists should the inbound push endpoint be treated as trusted.
- Keep old static federation paths available until reconciliation scoping is complete, but do not expand them.

## 2026-06-01 - Encrypted Client Payload Envelope

Decision:

* `event.body` is now defined as an encrypted client payload envelope for production traffic.
* Relay and registry infrastructure still see top-level routing/control metadata (`gid`, `eid`, `ts`, `exp`, sessions, source/destination relays), but not plaintext app content.
* The first documented encrypted body version uses `AES-256-GCM` with `kid`, `nonce`, `ct`, and `tag`.
* Clients derive AEAD additional authenticated data from immutable relay metadata so ciphertext cannot be transplanted across event ids, groups, or expiry windows.
* `kid` is only a key/version identifier. Raw group/session keys must never be sent to relay or registry services.

Docs updated:

* `docs/specs/RELAY_PROTOCOL_SPEC.md`
  * defines the encrypted `event.body` schema and relay constraints
* `docs/server/FEDERATION_PROTOCOL.md`
  * clarifies that federation peers forward encrypted `body` unchanged and validate only top-level/session scope
* `docs/SECURITY.md`
  * documents confidentiality limits, metadata still visible to relays, nonce uniqueness, and no relay key escrow
* `docs/milestones/Phase_3_2_Task_List.md`
  * marks envelope definition done and adds client implementation/test follow-ups

Follow-up implementation work:

* add relay compatibility tests proving ingest, fetch, export, and federation preserve encrypted `event.body` without mutation
* gate plaintext `event.body` compatibility behind explicit dev/test mode

## 2026-06-01 - Android Relay Encrypted Payload Implementation

Implemented:

* Android relay push now emits the documented encrypted relay body shape instead of embedding the full legacy `EventEnvelope` inside `RelayEvent.body`.
* Android relay pull now verifies and decrypts the new relay payload format before sending the resulting event into local ingest.
* Relay pull keeps backward compatibility for legacy relay rows whose `body` still contains the old serialized `EventEnvelope`.
* Relay payload verification now binds `gid`, `eid`, `ts`, `exp`, `body.v`, `body.enc`, and `body.kid` as AEAD additional authenticated data and separately MAC-signs the relay event metadata/body tuple.

Code paths updated:

* `android/core/src/main/java/app/peermesh/core/relay/RelayModels.kt`
  * adds a typed `RelayEncryptedBody` model
* `android/security/src/main/java/app/peermesh/security/crypto/RelayPayloadCrypto.kt`
  * adds relay-specific AES-GCM body encryption plus HMAC signing/verification
* `android/data/src/main/java/app/peermesh/data/relay/RelayEventMapper.kt`
  * emits encrypted relay payloads and requires a `kid`
* `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
  * routes relay fetches through the relay-specific decrypt/verify path
* `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`
  * accepts verified relay events directly and preserves legacy relay-envelope fallback
* `android/core/src/main/java/app/peermesh/core/encoding/Base64Url.kt`
  * adds JVM fallback so crypto unit tests do not depend on Android's mocked Base64 stub

Validation:

* `./gradlew :security:testDebugUnitTest --tests app.peermesh.security.crypto.RelayPayloadCryptoTest`
* `./gradlew :data:testDebugUnitTest --tests app.peermesh.data.relay.RelayEventMapperTest`

Current limitation:

* Full `:data:testDebugUnitTest` is not green because pre-existing `EnvelopeSenderTest` expectations do not match the current batching pipeline; this is outside the relay encrypted body path changed here.

## 2026-06-01 - Relay Encrypted Body Compatibility Tests

Implemented coverage:

* ingest + fetch:
  * `server/relay/scripts/test-fetch.js`
  * adds `fetch_encrypted_body_round_trip` to verify an encrypted relay `body` survives API ingest, DB storage, and API fetch without semantic mutation
* export:
  * `server/relay/scripts/test-export-events.js`
  * inserts an encrypted relay event directly into Postgres, runs `export-events.js`, then verifies the exported JSONL line preserves `body` and `sig`
  * `server/relay/scripts/test-export-events.sh` now calls the semantic export test wrapper
* federation route/unit:
  * `server/relay/scripts/test-federation-push-route.js`
  * verifies `POST /v1/federation/events:push` forwards encrypted `body` to storage unchanged
* federation compose/e2e:
  * `server/relay/scripts/test-managed-federation-compose.js`
  * now uses an encrypted relay `body` fixture and asserts the propagated event on the target relay preserves `body` and `sig`

Validation run:

* `node --check server/relay/scripts/test-fetch.js`
* `node --check server/relay/scripts/test-export-events.js`
* `node --check server/relay/scripts/test-managed-federation-compose.js`
* `cd server/relay && npm run test:federation-push-route`
* `ENV_FILE=/home/truong/projects/disaster-app/server/.env.dev ./scripts/test-export-events.sh`
* relay test instance on port `18080` + `cd server/relay && npm run test:fetch`

Notes:

* Equality checks were switched from `JSON.stringify(...)` to `util.isDeepStrictEqual(...)` because Postgres may return JSON object keys in a different order without changing the actual value.
* Managed federation compose coverage was updated but not rerun in this session because the full registry + multi-relay stack was not active.

## 2026-06-01 - Registry Client Access Grant Bootstrap Baseline

Implemented:

* `POST /v1/registry/client/sessions` now issues a registry-signed client relay access grant aligned with `docs/server/REGISTRY_PROTOCOL.md`.
* Grants are persisted in SQLite so renewal, abuse-report correlation, and later relay-side validation can reference a stable `grant_id`.
* Candidate selection baseline is now active in the registry:
  * filters relays by freshness, discovery status, trust floor, deny/suspend policy, transport hint, and `store_forward` capability
  * prefers relays whose `region` matches the client `network_hints.region`
  * returns a bounded candidate list

Grant shape emitted by the endpoint:

* `grant_id`
* `issued_at`
* `expires_at`
* `policy_version`
* `client_instance_id`
* `candidate_relays`
* `allowed_gids`
* `permissions`
* `limits`
* `access_token`
* `registry_signature`
* `connection_mode`

Code paths added/updated:

* `server/registry/src/client_access_grants.js`
  * request validation, candidate selection, access-token creation, registry signature generation
* `server/registry/src/db.js`
  * adds `client_access_grants` table and store helpers
* `server/registry/src/index.js`
  * wires `POST /v1/registry/client/sessions`
* `server/registry/src/config.js`
  * adds client access grant TTL / candidate / signing / limit config knobs
* `server/registry/scripts/test-client-access-grants.js`
  * self-contained integration test for the bootstrap endpoint

Validation:

* `node --check server/registry/src/client_access_grants.js`
* `node --check server/registry/src/index.js`
* `node --check server/registry/scripts/test-client-access-grants.js`
* `cd server/registry && npm run test:client-access-grants`

Still intentionally not implemented in this task:

* grant renewal / reassignment endpoint
* abuse-report intake and false-report resistance
* relay-side grant enforcement
* client-relay pairwise session establishment

## 2026-06-01 - Registry Client Access Grant Renewal / Reassignment Baseline

Implemented:

* `POST /v1/registry/client/sessions/{session_id}:renew` now renews an existing client access grant in place.
* Renewal preserves the existing `grant_id` and `client_instance_id`, but refreshes:
  * `issued_at`
  * `expires_at`
  * `candidate_relays`
  * `allowed_gids`
  * `permissions`
  * `limits`
  * `access_token`
  * `registry_signature`
* Renewal supports reassignment by allowing updated `network_hints`, `requested_gids`, and `requested_operations`.
* Renewal rejects:
  * missing/expired/revoked grants
  * `client_instance_id` mismatch
  * invalid requested operations
  * invalid network transport hints
  * empty candidate results after policy/selection filtering

Code paths updated:

* `server/registry/src/client_access_grants.js`
  * adds grant renewal logic on top of the existing bootstrap issuer
* `server/registry/src/db.js`
  * adds persistent update support for stored client access grants
* `server/registry/src/index.js`
  * wires the `:renew` route and audit logging
* `server/registry/scripts/test-client-access-grants.js`
  * now covers renew success, reassignment, mismatch rejection, and invalid transport rejection

Validation:

* `node --check server/registry/src/client_access_grants.js`
* `node --check server/registry/src/index.js`
* `node --check server/registry/scripts/test-client-access-grants.js`
* `cd server/registry && npm run test:client-access-grants`

Still intentionally not implemented in this task:

* relay-side validation/enforcement of client access grants
* client-relay pairwise session establishment
* abuse-report intake and false-report resistance

## 2026-06-01 - Registry Relay-Selection Policy For Managed Client Access

Implemented:

* Registry-managed client access grant selection now reads its policy from the persisted admin policy document rather than relying only on env defaults.
* Client access policy now directly controls:
  * `minimum_trust_level_for_client_access`
  * `allowed_statuses_for_client_access`
  * `client_access_grant_ttl_seconds`
  * `max_candidates_per_client_access_grant`
  * `allowed_client_access_permissions`
  * `client_access_policy_version`
  * `prefer_same_region_for_client_access`
* Candidate relay ordering now obeys `prefer_same_region_for_client_access`:
  * when `true`, same-region relays are preferred first
  * when `false`, trust / freshness / relay-id ordering wins even if region differs
* Grant issue and renew now fail with `no_allowed_permissions` when the request asks only for operations that the current client access policy does not permit.
* Registry admin policy tests now prove that a persisted policy patch changes real access-grant behavior, not just the policy feed output.

Code paths updated:

* `server/registry/src/client_access_grants.js`
  * consumes persisted client access policy fields during candidate filtering, ordering, TTL/version assignment, and permission narrowing
* `server/registry/src/db.js`
  * persists and restores client access policy fields as part of the registry policy document
* `server/registry/src/index.js`
  * validates admin policy patches for client access policy fields
* `server/registry/scripts/test-client-access-grants.js`
  * now logs into Registry Admin, patches policy, and verifies policy-driven trust floor, max-candidate trimming, permission denial, TTL/version propagation, and region-preference behavior

Validation:

* `node --check server/registry/src/client_access_grants.js`
* `node --check server/registry/src/index.js`
* `node --check server/registry/src/db.js`
* `node --check server/registry/scripts/test-client-access-grants.js`
* `cd server/registry && npm run test:client-access-grants`

Still intentionally not implemented in this task:

* relay-side enforcement of registry-issued client access grants
* client-relay pairwise session establishment
* abuse-report intake and false-report resistance

## 2026-06-01 - Relay-Side Enforcement Of Registry-Issued Client Access Grants

Implemented:

* Relay now has a dedicated client access grant validator for managed client access mode.
* When `REGISTRY_CLIENT_ACCESS_GRANTS_ENABLED=1`, relay enforces registry-issued client access grants on:
  * `POST /v1/events:ingest`
  * `GET /v1/events:fetch`
* Enforcement is applied only to app/client traffic:
  * federation traffic stays on the existing federation grant/session path
  * legacy peer traffic stays on the rotated peer-token path
* Relay validates:
  * access token presence and equality
  * registry signature integrity
  * grant expiry
  * relay membership in the candidate relay set
  * requested operation within grant permissions
  * requested `gid` scope within the grant
* Relay startup now rejects invalid managed-client-access configuration when grant enforcement is enabled without:
  * relay self id
  * registry client access signing secret

Implementation note:

* Because pairwise client-relay session establishment is still pending, the current baseline expects the request to carry the full registry grant payload in `X-Relay-Access-Grant` so the relay can verify it locally.
* The next managed-client-access steps should add pairwise session establishment and then decide whether data-plane requests keep carrying the full grant or switch to a relay-cached `grant_id` + session binding model.

Code paths updated:

* `server/relay/src/client_access_grants.js`
  * registry grant decoding, signing compatibility, and relay-side validation
* `server/relay/src/config.js`
  * adds managed client access grant config and startup guards
* `server/relay/src/routes.js`
  * applies client access grant enforcement to ingest/fetch only when enabled
* `server/relay/scripts/test-client-access-grant-route.js`
  * route-level coverage for missing grant, valid grant, wrong relay, expired grant, gid mismatch, operation mismatch, and disabled mode
* `server/relay/package.json`
  * adds `test:client-access-grant-route`

Validation:

* `node --check server/relay/src/client_access_grants.js`
* `node --check server/relay/src/config.js`
* `node --check server/relay/src/routes.js`
* `node --check server/relay/scripts/test-client-access-grant-route.js`
* `cd server/relay && npm run test:client-access-grant-route`

## 2026-06-01 - Relay Pairwise Session Establishment Foundation

Implemented:

* Relay now has a dedicated pairwise client-relay session manager for managed client access mode.
* The manager defines the relay-side session model for:
  * `session_id`
  * `relay_id`
  * `grant_id`
  * `client_instance_id`
  * narrowed `allowed_gids`
  * narrowed `permissions`
  * `issued_at`
  * `expires_at`
  * `relay_nonce`
  * `session_binding_token`
  * client/relay session material
  * cached underlying grant scope and expiry
* Session open logic now exists at the core layer and enforces:
  * relay identity match
  * registry grant validity
  * client instance binding
  * scope narrowing from requested gids/operations to grant-authorized scope
  * per-client active session cap
* Session renew logic now exists at the core layer and keeps the same `session_id` while refreshing:
  * scope
  * expiry
  * `relay_nonce`
  * `session_binding_token`
  * session material
* Session close logic now evicts the session immediately.
* Request-binding validation now exists for the future data plane and checks:
  * session existence
  * session expiry
  * `session_binding_token`
  * `grant_id`
  * client instance binding
  * operation scope
  * `gid` scope
* App lifecycle now instantiates and shuts down the pairwise session manager so the next endpoint task only needs HTTP wiring.

Important scope note:

* This task implements the pairwise session model and runtime manager, but does not yet expose the HTTP endpoints.
* The next task should wire:
  * `POST /v1/client/session:open`
  * `POST /v1/client/session/:session_id:renew`
  * `POST /v1/client/session/:session_id:close`

Code paths updated:

* `server/relay/src/client_relay_sessions.js`
  * core session open / renew / close / validate manager
* `server/relay/src/client_access_grants.js`
  * grant validator now supports scope-less validation for session-open bootstrap
* `server/relay/src/config.js`
  * adds pairwise-session config and startup guards
* `server/relay/src/app.js`
  * app lifecycle now instantiates and shuts down the session manager
* `server/relay/scripts/test-client-relay-sessions.js`
  * coverage for open, wrong relay, scope rejection, renew, binding validation, per-client cap, and close
* `server/relay/package.json`
  * adds `test:client-relay-sessions`

Validation:

* `node --check server/relay/src/client_relay_sessions.js`
* `node --check server/relay/src/client_access_grants.js`
* `node --check server/relay/src/config.js`
* `node --check server/relay/src/app.js`
* `node --check server/relay/scripts/test-client-relay-sessions.js`
* `cd server/relay && npm run test:client-relay-sessions`
* `cd server/relay && npm run test:client-access-grant-route`

## 2026-06-01 - Relay Client Session Endpoint Wiring

Implemented:

* Relay now exposes the baseline managed client session endpoints:
  * `POST /v1/client/session:open`
  * `POST /v1/client/session/{session_id}:renew`
  * `POST /v1/client/session/{session_id}:close`
* These endpoints are wired directly to the pairwise session manager added in the previous task.
* Client session endpoint requests now participate in the authenticated relay request-signature flow when request signing is enabled.
* `session:open` and `session:renew` require the full registry access grant in `X-Relay-Access-Grant`.
* `session:close` does not require the grant again, but still requires normal request signing and client-instance validation against the active session.

Current behavior:

* `session:open`
  * parses the request body
  * verifies request signature when enabled
  * decodes the registry access grant from `X-Relay-Access-Grant`
  * delegates to the session manager and returns `201` with the new session
* `session:renew`
  * verifies request signature when enabled
  * requires the registry access grant header
  * delegates to the session manager and returns `200` with the refreshed session
* `session:close`
  * verifies request signature when enabled
  * delegates to the session manager and evicts the session immediately

Code paths updated:

* `server/relay/src/routes.js`
  * adds the three HTTP endpoints and request-signature enforcement for them
* `server/relay/scripts/test-client-relay-session-routes.js`
  * route-level coverage for signature requirement, session open, renew, close, and renew-without-grant rejection
* `server/relay/package.json`
  * adds `test:client-relay-session-routes`

Validation:

* `node --check server/relay/src/routes.js`
* `node --check server/relay/src/app.js`
* `node --check server/relay/scripts/test-client-relay-session-routes.js`
* `cd server/relay && npm run test:client-relay-session-routes`

## 2026-06-01 - Managed Client Data-Plane Session Enforcement

Implemented:

* Relay managed client data-plane now enforces both layers on:
  * `POST /v1/events:ingest`
  * `GET /v1/events:fetch`
* Validation order on managed client app traffic is now:
  * request signature when enabled
  * registry access grant validation
  * pairwise client-relay session validation
  * existing request validation / rate limit / storage flow
* The relay now requires:
  * `X-Relay-Access-Grant`
  * `X-Relay-Access-Token`
  * `X-Client-Relay-Session`
  * `X-Client-Relay-Session-Token`
  for managed client app data-plane requests when the relevant features are enabled.
* Pairwise session validation binds data-plane traffic to:
  * the same `grant_id`
  * the same `client_instance_id`
  * the session-scoped `gid` set
  * the session-scoped allowed operations

Behavior now covered:

* reject missing session headers even when the registry grant is otherwise valid
* accept data-plane traffic when both grant and pairwise session are valid
* reject wrong `session_binding_token`
* reject `gid` outside the active pairwise session scope even if the registry grant still allows it

Code paths updated:

* `server/relay/src/routes.js`
  * adds pairwise session validation for managed client ingest/fetch
* `server/relay/scripts/test-managed-client-data-plane-route.js`
  * route-level coverage for missing session, valid ingest, wrong session token, and session-scope mismatch
* `server/relay/package.json`
  * adds `test:managed-client-data-plane-route`

Validation:

* `node --check server/relay/src/routes.js`
* `node --check server/relay/scripts/test-managed-client-data-plane-route.js`
* `cd server/relay && npm run test:managed-client-data-plane-route`
* `cd server/relay && npm run test:client-access-grant-route`
* `cd server/relay && npm run test:client-relay-session-routes`
* `cd server/relay && npm run test:client-relay-sessions`

## 2026-06-01 - Managed Client Access Grant Regression Coverage

Implemented:

* Managed client access regression coverage is now anchored on the real data-plane route flow, not only isolated grant-validation helpers.
* The managed data-plane regression suite now proves that relay rejects:
  * missing registry access grant
  * expired registry access grant
  * registry grant whose candidate set does not include the current relay
  * registry grant whose `gid` scope does not cover the request
* These checks run while a valid pairwise session already exists, which verifies that grant failures still win before the request reaches storage.

Code paths updated:

* `server/relay/scripts/test-managed-client-data-plane-route.js`
  * expanded with missing-grant, expired-grant, wrong-relay-grant, and out-of-scope-grant cases

Validation:

* `node --check server/relay/scripts/test-managed-client-data-plane-route.js`
* `cd server/relay && npm run test:managed-client-data-plane-route`

## 2026-06-01 - Managed Client Pairwise Session Regression Coverage

Implemented:

* Managed client access regression coverage now also proves pairwise session failures on the real data-plane path.
* The managed data-plane suite now rejects:
  * pairwise session bound to a different `grant_id`
  * pairwise session that has expired even when the registry grant is still valid
* These cases are checked after a valid session was initially established, so the regression coverage now reflects mid-flight session invalidation rather than only bootstrap failures.

Code paths updated:

* `server/relay/scripts/test-managed-client-data-plane-route.js`
  * expanded with `grant_mismatch` and `client_session_expired` route cases

Validation:

* `node --check server/relay/scripts/test-managed-client-data-plane-route.js`
* `cd server/relay && npm run test:managed-client-data-plane-route`

## 2026-06-01 - Managed Client Candidate Retry / Failover Coverage

Implemented:

* Managed client access regression coverage now includes retry/failover behavior across multiple authorized relay candidates using the same registry-issued grant.
* The new failover suite proves:
  * client can skip a locally blocked relay and successfully open a session on the next authorized candidate
  * client can recover from one relay rejecting `session:open` and still open against another authorized candidate with the same grant
  * once the same grant expires, failover attempts against later authorized candidates are rejected

Implementation note:

* This suite is intentionally client-flow oriented and uses multiple relay routers with different relay identities/config to simulate candidate ordering and failover decisions without adding new runtime behavior.

Code paths updated:

* `server/relay/scripts/test-managed-client-failover.js`
  * self-contained retry/failover coverage across `relay-a` and `relay-b`
* `server/relay/package.json`
  * adds `test:managed-client-failover`

Validation:

* `node --check server/relay/scripts/test-managed-client-failover.js`
* `cd server/relay && npm run test:managed-client-failover`

## 2026-06-01 - Managed Client Access End-To-End Smoke

Implemented:

* Added a self-contained end-to-end smoke test for the managed client access flow:
  * registry heartbeat registration
  * registry grant issuance
  * relay pairwise session open
  * signed managed-client ingest
  * signed managed-client fetch
* The smoke test uses:
  * a lightweight HTTP registry stub that preserves the registry contract needed for heartbeats and client grant issuance
  * two real relay HTTP servers built from the current router/session code with in-memory storage
* This keeps the smoke flow fully automated in the local dev environment without depending on native SQLite registry modules being installed locally.

Smoke coverage now proves:

* client can obtain a short-lived relay access grant from the registry contract
* client can open a pairwise session on the selected relay using that grant
* client can ingest data through the managed data-plane with request signature, grant, and session headers
* client can fetch the same event back through the managed data-plane on the selected relay

Code paths updated:

* `server/relay/scripts/test-managed-client-smoke.js`
  * end-to-end managed client smoke
* `server/relay/package.json`
  * adds `test:managed-client-smoke`

Validation:

* `node --check server/relay/scripts/test-managed-client-smoke.js`
* `cd server/relay && npm run test:managed-client-smoke`

## 2026-06-01 - Registry Client Abuse-Report Intake Baseline

Implemented:

* `POST /v1/registry/client/reports` now accepts registry-signed client abuse reports aligned with `docs/server/REGISTRY_PROTOCOL.md`.
* Abuse reports are persisted in SQLite and bound to an issued `grant_id`, `relay_id`, and `client_instance_id`.
* Registry candidate selection now consumes abuse state in two baseline ways:
  * temporary pair cooldown for the reporting `client_instance_id` against the reported `relay_id`
  * global relay deprioritization based on recent report count plus distinct-client corroboration bonus
* Admin policy persistence now carries baseline client-abuse knobs for:
  * abuse report acceptance window
  * deprioritize window
  * per-client pair cooldown
  * single-report weight
  * distinct-client bonus

Current baseline behavior:

* Reports require a known `grant_id`.
* `client_instance_id` must match the bound grant.
* `relay_id` must be one of the relay candidates authorized by that grant.
* Reports outside the configured post-expiry acceptance window are rejected.
* A single client report does not globally ban a relay, but it can immediately keep that relay out of subsequent grants for the same client during cooldown.
* Multiple recent reports from distinct clients lower the relay's candidate priority for later grants.

Still intentionally deferred to the next task:

* duplicate collapse
* per-client / per-IP / per-grant rate limiting
* corroboration thresholds for severe actions
* score decay

Code paths updated:

* `server/registry/src/client_abuse_reports.js`
  * report validation, grant binding, persistence entrypoint, and selection-effect calculation
* `server/registry/src/client_access_grants.js`
  * candidate selection now reads abuse score and pair cooldown state
* `server/registry/src/db.js`
  * adds `client_abuse_reports` table plus abuse summary/store helpers
  * persists/restores baseline abuse-policy fields
* `server/registry/src/index.js`
  * wires `POST /v1/registry/client/reports`
  * records audit events for accepted/denied client abuse reports
* `server/registry/src/config.js`
  * adds env-config defaults for abuse report acceptance, cooldown, and scoring
* `server/registry/scripts/test-client-abuse-reports.js`
  * covers report accept, unauthorized relay reject, same-client cooldown, and cross-client deprioritization

Validation:

* `node --check server/registry/src/client_abuse_reports.js`
* `node --check server/registry/src/client_access_grants.js`
* `node --check server/registry/src/index.js`
* `node --check server/registry/src/db.js`
* `node --check server/registry/scripts/test-client-abuse-reports.js`
* `cd server/registry && npm run test:client-abuse-reports`
* `cd server/registry && npm run test:client-access-grants`

## 2026-06-01 - Registry Client Abuse False-Report Resistance

Implemented:

* Registry client abuse-report handling now includes the false-report resistance controls called for in `docs/server/REGISTRY_PROTOCOL.md`.
* Duplicate reports are collapsed within a `(grant_id, relay_id, reason, time bucket)` boundary instead of minting new abuse weight.
* Report submission is now rate-limited on three scopes:
  * `client_instance_id`
  * source IP
  * `grant_id`
* Severe automatic selection impact now requires corroboration rather than a single untrusted report:
  * same-client pair cooldown still applies immediately
  * global relay suppression from future introductions now requires corroborated abuse state
* Abuse score contribution is now time-decayed across the deprioritize window instead of remaining flat until the window edge.

Current effect on candidate selection:

* one client report can immediately keep the same `client_instance_id` away from the same relay during cooldown
* one client report can still lower relay priority slightly for other clients
* relay removal from new candidate lists now happens only after corroboration threshold is reached by distinct clients and/or decayed score

Policy/config fields added:

* `client_abuse_duplicate_bucket_seconds`
* `client_abuse_rate_limit_window_seconds`
* `client_abuse_max_reports_per_client_window`
* `client_abuse_max_reports_per_ip_window`
* `client_abuse_max_reports_per_grant_window`
* `client_abuse_corroboration_client_threshold`
* `client_abuse_corroboration_score_threshold`

Code paths updated:

* `server/registry/src/client_abuse_reports.js`
  * duplicate collapse
  * rate-limit enforcement
  * corroboration evaluation
  * decayed abuse score calculation
* `server/registry/src/client_access_grants.js`
  * candidate selection now suppresses globally introduced relays only when abuse state is corroborated
* `server/registry/src/db.js`
  * adds duplicate/rate-limit helper queries
  * persists/restores the new abuse hardening policy fields
* `server/registry/src/index.js`
  * validates admin policy updates for the new abuse hardening knobs
* `server/registry/scripts/test-client-abuse-reports.js`
  * now covers duplicate collapse, per-client rate limit, per-grant rate limit, per-IP rate limit, corroboration-driven suppression, and score decay

Validation:

* `node --check server/registry/src/client_abuse_reports.js`
* `node --check server/registry/src/client_access_grants.js`
* `node --check server/registry/src/index.js`
* `node --check server/registry/src/db.js`
* `node --check server/registry/scripts/test-client-abuse-reports.js`
* `cd server/registry && npm run test:client-abuse-reports`
* `cd server/registry && npm run test:client-access-grants`

## 2026-06-01 - Android Client Local Relay Block Runtime

Implemented:

* Android client now has a local relay blocklist runtime for relay URLs.
* Blocked relays are filtered out centrally by `RelaySyncSettings.resolveRelayBaseUrls(...)`, so the block applies to:
  * foreground/background relay sync
  * manual relay push from chat
  * manual relay push from map
  * live-trail relay push
* Relay sync now auto-blocks a relay locally when the relay returns pairwise-session abuse indicators such as:
  * `grant_mismatch`
  * `client_session_token_mismatch`
  * `client_session_expired`

Current behavior:

* A block is stored locally in app prefs with:
  * relay URL
  * reason
  * blocked-at timestamp
  * expiry timestamp
* Default local block TTL is currently 6 hours.
* Once blocked, the relay is skipped by relay URL resolution until the block expires or is cleared.
* At this step the Android app has best-effort abuse-report submission plumbing:
  * local relay-block handling can attempt `POST /v1/registry/client/reports`
  * the app records/report-uses per-relay abuse context when available
  * registry base URL can be supplied separately from relay base URL
* Automatic report submission is still incomplete for the managed architecture because the Android runtime does not yet capture and persist the full registry-issued grant context during client access bootstrap.
* When that grant context is missing, the app logs the local block and skips registry report submission instead of sending a partial/invalid report.

Code paths updated:

* `android/app/src/main/java/app/peermesh/relay/RelayLocalBlockStore.kt`
  * shared-pref backed local relay block store
* `android/app/src/main/java/app/peermesh/relay/RelayLocalBlockPolicy.kt`
  * maps managed pairwise-session violation codes to local block reasons
* `android/app/src/main/java/app/peermesh/relay/RelaySyncSettings.kt`
  * filters blocked relays from resolved relay URL lists
* `android/app/src/main/java/app/peermesh/relay/RelaySyncRunner.kt`
  * applies local block on qualifying relay push/pull failures, logs the action, and triggers best-effort registry abuse reporting
* `android/app/src/main/java/app/peermesh/relay/RelayRegistryAbuseReportContextStore.kt`
  * shared-pref backed store for per-relay registry abuse report context
* `android/app/src/main/java/app/peermesh/relay/RelayRegistryAbuseReportClient.kt`
  * minimal HTTP client for `POST /v1/registry/client/reports`
* `android/app/src/main/java/app/peermesh/relay/RelayRegistryAbuseReporter.kt`
  * best-effort reporter that skips when no registry/grant context is available
* `android/transport/src/main/java/app/peermesh/transport/relay/RelayHttpClient.kt`
  * parses structured relay error codes from JSON error responses
* `android/data/src/main/java/app/peermesh/data/relay/RelayPushManager.kt`
* `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
  * now surface relay HTTP status + error code so the runner can classify abusive session failures
* `android/app/src/test/java/app/peermesh/relay/RelayLocalBlockPolicyTest.kt`
  * unit coverage for block classification
* `android/app/src/test/java/app/peermesh/relay/RelayRegistryAbuseReportClientTest.kt`
  * unit coverage for registry abuse report client configuration failure path

Validation:

* `./gradlew :transport:testDebugUnitTest`
* `./gradlew :data:testDebugUnitTest --tests app.peermesh.data.relay.*`
* `./gradlew :app:testOfficialDebugUnitTest --tests app.peermesh.relay.RelayLocalBlockPolicyTest`

## 2026-06-02 - Gate Plaintext Relay Body Compatibility Behind Dev/Test Mode

Implemented:

* Android relay pull/ingest compatibility for legacy plaintext relay `event.body` is now opt-in only.
* Production behavior now prefers the encrypted relay body envelope exclusively.
* Legacy plaintext relay bodies are accepted only when the app is running in explicit developer mode.

Current behavior:

* `RelayPayloadCrypto.verifyAndDecrypt(...)` remains the primary relay receive path.
* If encrypted relay-body verification fails, legacy plaintext fallback now runs only when `RelaySyncSettings.isDeveloperMode(...)` is true.
* The fallback is shared through a dedicated helper so the same gating applies to:
  * `EnvelopeReceiverImpl.onRelayEventReceived(...)`
  * `RelayPullManager` when used with a generic `EnvelopeReceiver`
* When a legacy plaintext relay body is accepted, the app writes an activity log entry so dev/test use is visible during troubleshooting.

Code paths updated:

* `android/data/src/main/java/app/peermesh/data/relay/LegacyRelayPayloadCompatibility.kt`
  * central legacy plaintext decoder with explicit `allowPlaintextBody` gate
* `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`
  * relay legacy fallback now depends on the compatibility flag and logs acceptance
* `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
  * generic relay-body decode path now respects the same compatibility flag
* `android/app/src/main/java/app/peermesh/relay/RelaySyncRunner.kt`
  * enables the compatibility flag only from developer mode
* `android/data/src/test/java/app/peermesh/data/relay/LegacyRelayPayloadCompatibilityTest.kt`
  * verifies legacy plaintext relay bodies are rejected by default and accepted only with the flag enabled

Validation:

* `./gradlew :data:testDebugUnitTest --tests app.peermesh.data.relay.LegacyRelayPayloadCompatibilityTest --tests app.peermesh.data.relay.RelayEventMapperTest`
* `./gradlew :app:compileOfficialDebugKotlin`

## 2026-06-02 - Relay Ingest Idempotency Under Transient DB Failures

Implemented:

* Signed app-ingest requests no longer burn the request-signature replay key before relay has actually accepted responsibility for the ingest result.
* `POST /v1/events:ingest` now defers replay-key commit until:
  * DB write succeeds and relay returns `200`, or
  * ingest retry queue accepts the batch and relay returns `202`

Current behavior:

* A transient DB failure after request-signature verification no longer causes a same-request retry to be rejected as `signature_replay`.
* Once the retry succeeds, or once relay has queued the ingest for retry, the same signed request is then replay-blocked as expected.
* Other signed app routes keep immediate replay-cache behavior unchanged.

Code paths updated:

* `server/relay/src/request_signatures.js`
  * replay cache now exposes `has(...)` in addition to `remember(...)`
* `server/relay/src/routes.js`
  * app-ingest uses deferred replay protection
  * replay key is committed only after `200` or `202`
  * replay-cache commit failures are logged without turning a successful ingest into a false negative
* `server/relay/scripts/test-ingest-idempotency-retry.js`
  * route-level regression:
    * first signed ingest fails with transient DB error
    * second retry with identical signature succeeds
    * third attempt is rejected as `signature_replay`
* `server/relay/package.json`
  * adds `test:ingest-idempotency-retry`

Validation:

* `node --check server/relay/src/request_signatures.js`
* `node --check server/relay/src/routes.js`
* `node --check server/relay/scripts/test-ingest-idempotency-retry.js`
* `node server/relay/scripts/test-ingest-idempotency-retry.js`

## 2026-06-02 - Atomic Nonce / Replay Reservation

Implemented:

* Relay nonce replay protection no longer performs a non-atomic `has(...)` followed by `add(...)`.
* Join/handshake/heartbeat nonce reservation now uses a single atomic `remember(...)` primitive.

Current behavior:

* For Redis-backed nonce protection, replay reservation now relies directly on `SET ... NX EX ...`, so only one concurrent caller can claim a nonce.
* For in-memory nonce protection, the same `remember(...)` interface is used and returns:
  * `true` for the first successful reservation
  * `false` for duplicates
* `storage.isJoinNonceReplay(...)` now maps directly to the atomic reservation result instead of stitching together separate read/write steps.

Code paths updated:

* `server/relay/src/storage.js`
  * `createNonceCache(...)` now exposes `remember(...)`
  * Redis mode uses a single atomic write path
  * in-memory mode performs duplicate check + insert inside one cache operation
  * `isJoinNonceReplay(...)` now returns replay based on atomic reservation outcome
  * exports `createNonceCache` for direct regression coverage
* `server/relay/scripts/test-nonce-cache-atomic.js`
  * verifies duplicate reservation behavior for:
    * in-memory cache
    * Redis-style cache semantics
* `server/relay/package.json`
  * adds `test:nonce-cache-atomic`

Validation:

* `node --check server/relay/src/storage.js`
* `node --check server/relay/scripts/test-nonce-cache-atomic.js`
* `cd server/relay && npm run test:nonce-cache-atomic`

## 2026-06-02 - Private Relay Signatures With Federation Sync Regression Coverage

Implemented:

* Added route-level regression coverage proving private relay request signatures and federation sync auth do not conflict.

Current behavior:

* When relay private request signatures are enabled:
  * unsigned app fetch requests are rejected
  * unsigned app ingest requests are rejected
  * correctly signed app fetch requests still succeed
  * federation fetch requests authenticated by federation session headers still succeed without `x-relay-signature`

Code paths updated:

* `server/relay/scripts/test-private-signature-federation-sync.js`
  * verifies app-private auth remains enforced
  * verifies authenticated federation fetch still bypasses app request-signature enforcement as designed
* `server/relay/package.json`
  * adds `test:private-signature-federation-sync`

Validation:

* `node --check server/relay/scripts/test-private-signature-federation-sync.js`
* `cd server/relay && npm run test:private-signature-federation-sync`

## 2026-06-02 - Relay Peer Quarantine State Baseline

Implemented:

* Relay peer mappings now support an explicit `peer_state` instead of relying only on `enabled=true|false`.
* Baseline states implemented:
  * `active`
  * `quarantined`

Current behavior:

* `enabled=false` still disables the peer completely.
* `enabled=true` with `peer_state=quarantined` now blocks peer use across the federation runtime where it matters:
  * inbound peer-authenticated stats requests
  * inbound peer-authenticated ingest requests
  * join/handshake/heartbeat processing against existing quarantined peers
  * outbound federation auto-sync / `triggerResync(...)`
* Capacity and auto-sync active-peer counts now exclude quarantined peers.
* Quarantine can optionally carry:
  * `quarantine_reason`
  * `quarantined_until`
* If `quarantined_until` has already passed, the effective runtime state falls back to `active` without needing an immediate DB rewrite.

Code paths updated:

* `server/relay/migrations/016_add_peer_mapping_state.up.sql`
* `server/relay/migrations/016_add_peer_mapping_state.down.sql`
  * adds `peer_state`, `quarantine_reason`, `quarantined_until`
* `server/relay/src/peer_mapping_state.js`
  * shared state normalization and effective-state helpers
* `server/relay/src/storage.js`
  * peer-mapping reads now expose `effective_state`
  * active counts and inbound-peer status now respect quarantine
* `server/relay/src/routes.js`
  * peer-authenticated routes now reject quarantined peers with `peer_quarantined`
  * join capacity checks count only active peers
* `server/relay/src/routes_admin.js`
  * admin peer-mapping create/update now accepts `peer_state`
  * quarantined peers require `quarantine_reason`
  * capacity checks only count peers that are effectively active
* `server/relay/src/federation.js`
  * outbound auto-sync and manual `triggerResync` now skip quarantined peers
* `server/relay/scripts/test-peer-quarantine-state.js`
  * regression coverage for inbound peer block + outbound resync skip
* `server/relay/package.json`
  * adds `test:peer-quarantine-state`

Validation:

* `node --check server/relay/src/peer_mapping_state.js`
* `node --check server/relay/src/storage.js`
* `node --check server/relay/src/routes.js`
* `node --check server/relay/src/routes_admin.js`
* `node --check server/relay/src/federation.js`
* `node --check server/relay/scripts/test-peer-quarantine-state.js`
* `cd server/relay && npm run test:peer-quarantine-state`

## 2026-06-02 - Per-Peer Federation Abuse / Quota Metrics

Implemented:

* Added explicit per-peer federation runtime metrics for abuse pressure and quota headroom.

Current behavior:

* Runtime now publishes per-peer gauges:
  * `federation_peer_abuse_score{peer="..."}`
  * `federation_peer_quarantined{peer="..."}`
  * `federation_peer_quota_remaining{peer="...",quota="in_flight"}`
  * `federation_peer_quota_remaining{peer="...",quota="queue"}`
* Abuse score is a runtime signal composed from:
  * consecutive federation sync failures
  * heartbeat failure count
  * backpressure drop count
  * quarantine penalty
* Quota remaining reflects current federation headroom for each peer and drops to `0` while the peer is quarantined.

Code paths updated:

* `server/relay/src/metrics.js`
  * adds metric definitions for peer abuse score, quarantine state, and quota remaining
* `server/relay/src/federation.js`
  * tracks per-peer sync failures, heartbeat failures, backpressure drops, and quarantine state
  * updates peer quota and abuse gauges whenever runtime state changes
* `server/relay/scripts/test-federation-peer-metrics.js`
  * verifies sync failure raises peer abuse score
  * verifies quota gauges exist
  * verifies quarantined peer emits quarantine gauge and abuse score
* `server/relay/package.json`
  * adds `test:federation-peer-metrics`

Validation:

* `node --check server/relay/src/metrics.js`
* `node --check server/relay/src/federation.js`
* `node --check server/relay/scripts/test-federation-peer-metrics.js`
* `cd server/relay && npm run test:federation-peer-metrics`

## 2026-06-02 - Production Federation Mode Guidance

Implemented:

* Documentation now explicitly recommends permissioned federation as the production baseline.

Current guidance:

* Official or managed production federation should be permissioned federation.
* Registry-approved relay membership, trust/status governance, and short-lived registry-issued sessions are the normal production posture.
* Public open federation is not the recommended production mode for official PeerMesh infrastructure.
* Independent operators may still choose a more open posture for isolated/self-owned networks, but that is a different risk decision from the default production recommendation.

Docs updated:

* `docs/SECURITY.md`
* `docs/server/FEDERATION_PROTOCOL.md`
* `docs/server/INTERNET_RELAY_OPTION.md`

## 2026-06-02 - DB Failure Then Same-Event Retry Regression

Implemented:

* Added a regression test focused on event-level retry semantics after a transient DB failure.

Current behavior covered:

* first ingest attempt for an event may fail with transient DB error
* retrying the exact same event after that failure should still be accepted
* retrying the same event again after a successful commit should return `duplicate`

This complements, but is separate from, the signed-request replay regression:

* `test-ingest-idempotency-retry.js` proves request-signature replay protection is committed only after success
* `test-db-retry-same-event.js` proves same-event retry semantics remain correct even without relying on signature replay behavior

Code paths updated:

* `server/relay/scripts/test-db-retry-same-event.js`
  * route-level regression for transient DB failure followed by same-event retry
* `server/relay/package.json`
  * adds `test:db-retry-same-event`

Validation:

* `node --check server/relay/scripts/test-db-retry-same-event.js`
* `cd server/relay && npm run test:db-retry-same-event`

## 2026-06-02: Dedicated Relay Request Replay-Cache Regression

Summary:

* Added a dedicated relay request replay-cache regression test so the cache behavior is covered directly, not only through broader route-level signature tests.

Current behavior covered:

* the first valid signed request is accepted into the replay cache
* replaying the exact same signed request is rejected by the replay cache
* a different signed request at the same timestamp still produces a distinct replay key and remains acceptable
* both the in-memory replay cache path and the Redis `SET NX EX` path are exercised

Code paths updated:

* `server/relay/scripts/test-request-replay-cache.js`
  * primitive regression coverage for `verifySignature()` + `createRequestReplayCache()`
* `server/relay/package.json`
  * adds `test:request-replay-cache`

Validation:

* `node --check server/relay/scripts/test-request-replay-cache.js`
* `cd server/relay && npm run test:request-replay-cache`

## 2026-06-02: Signed Relay Heartbeat Compatibility Test

Summary:

* Added an automated compatibility test that exercises the real relay registry heartbeat client against a real registry server with request signing enabled.

Current behavior covered:

* `server/relay/src/registry_heartbeat_client.js` successfully registers a relay through `POST /v1/registry/relays:heartbeat` when both sides share the same registry signing secret
* the relay shows up in the public registry relay directory after a valid signed heartbeat
* a relay using the wrong registry shared secret is rejected with `401` and does not get registered

Code paths updated:

* `server/registry/scripts/test-signed-heartbeat-compat.js`
  * starts a temporary registry instance and drives it via the real relay heartbeat client
* `server/registry/package.json`
  * adds `test:signed-heartbeat-compat`

Validation:

* `node --check server/registry/scripts/test-signed-heartbeat-compat.js`
* `cd server/registry && npm run test:signed-heartbeat-compat`

## 2026-06-02: Registry Admin Metrics Page

Summary:

* Implemented the missing Registry Admin metrics page and connected it to a richer `/admin/api/metrics` payload.

Current behavior covered:

* `/admin/metrics` now serves a dedicated admin page with authenticated refresh flow and shared navigation
* `/admin/api/metrics` now exposes:
  * heartbeat rate per minute and per second
  * discovery rate per minute and per second
  * certificate issue/revocation counters and active certificate count
  * policy update and audit event counters
  * federation session issued/denied/active/expired/revoked counters
* The page renders KPI cards plus detailed traffic/federation breakdowns using the shared admin JS bundle.

Code paths updated:

* `server/registry/src/index.js`
  * serves `/admin/metrics`
  * expands the admin metrics payload
* `server/registry/src/db.js`
  * derives richer metrics counts from audit history, certificates and federation session inventory
* `server/registry/src/public/admin-registry/metrics.html`
  * dedicated metrics page
* `server/registry/src/public/admin-registry/assets/registry_admin.js`
  * renders metrics cards and detail panels
* `server/registry/src/public/admin-registry/*.html`
  * adds the Metrics link to shared admin navigation
* `server/registry/scripts/test-admin-metrics.js`
  * smoke coverage for the metrics page route and metrics API payload

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/db.js`
* `node --check server/registry/scripts/test-admin-metrics.js`
* `cd server/registry && npm run test:admin-metrics`

## 2026-06-02 - Registry Private Self-Hosted Mode

Implemented a first-class registry deployment profile for independent networks that still want registry-managed federation:

* Added `REGISTRY_PRIVATE_MODE=1` in `server/registry/src/config.js`.
* Registry config now defaults `federationMinTrustLevel` to `community` when private mode is enabled.
* Registry policy now persists `registry_deployment_mode` with:
  * `official_managed`
  * `private_self_hosted`
* Default policy baseline in private mode now relaxes:
  * `minimum_trust_level_for_auto_sync` -> `community`
  * `minimum_trust_level_for_federation` -> `community`
* Admin policy patching can switch `registry_deployment_mode`; switching profiles reapplies the baseline trust floors, and explicit trust fields in the same patch can still override those defaults.
* Added federation-session coverage proving community relays can obtain managed federation sessions in private mode.
* Added smoke coverage proving the deployment mode persists through the admin policy API and restart.

Validation:

* `node --check server/registry/src/config.js`
* `node --check server/registry/src/db.js`
* `node --check server/registry/src/index.js`
* `node --check server/registry/scripts/test-federation-sessions.js`
* `node --check server/registry/scripts/test-smoke.js`
* `cd server/registry && npm run test:federation-sessions`
* `cd server/registry && npm run test:smoke`

## 2026-06-02 - Federation Grant Tampering Coverage

Expanded relay-side federation validation tests to prove registry-signed peer grants are rejected when callers tamper with signed fields after issuance.

Added coverage in `server/relay/scripts/test-federation-push-route.js` for:

* push request where caller recomputes the outer federation request signature but mutates `peer_grant.session_token`
* push request where caller mutates `peer_grant.allowed_gids` to widen scope
* fetch request where caller mutates `peer_grant.permissions` to escalate from `push` to `pull_reconcile`
* fetch request where caller mutates `peer_grant.destination_relay_id`

Each of these now fails specifically with `invalid_registry_signature`, proving relay-side validation rejects post-sign tampering instead of merely falling through to later scope checks.

Validation:

* `node --check server/relay/scripts/test-federation-push-route.js`
* `cd server/relay && npm run test:federation-push-route`

## 2026-06-02 - Federation Session Limit Enforcement

Implemented baseline relay-side enforcement for managed federation session quotas.

Key changes:

* Added `server/relay/src/federation_session_limits.js` for per-session rolling-window accounting.
* Inbound `POST /v1/federation/events:push` now enforces:
  * `max_events_per_minute`
  * `max_bytes_per_minute`
  * `max_in_flight`
* Inbound managed `GET /v1/events:fetch` with `pull_reconcile` grant now enforces the same limits against the returned event batch.
* Registry-signed peer grants now include signed `limits` so the destination relay has enough data to enforce the source session quota.
* Added route coverage for:
  * push event-rate overflow
  * push byte-rate overflow
  * push in-flight overflow
  * cumulative fetch event-rate overflow within the same session window

Validation:

* `node --check server/relay/src/federation_session_limits.js`
* `node --check server/relay/src/federation_push.js`
* `node --check server/relay/src/routes.js`
* `node --check server/registry/src/federation_sessions.js`
* `node --check server/relay/scripts/test-federation-push-route.js`
* `node --check server/registry/scripts/test-federation-sessions.js`
* `cd server/relay && npm run test:federation-push-route`
* `cd server/relay && npm run test:federation-push`
* `cd server/relay && npm run test:private-signature-federation-sync`
* `cd server/registry && npm run test:federation-sessions`

## 2026-06-02 - Managed Client Abuse Report Smoke

Added smoke coverage for the client local-block plus registry abuse-report control flow.

Coverage now proves:

* a simulated client can locally skip a blocked relay candidate and still open a session on the next authorized relay
* the same client can submit `POST /v1/registry/client/reports` against the registry-issued grant for the blocked relay
* the next grant for that same client excludes the reported relay during pair cooldown
* a different client still sees the relay, proving a single report does not immediately suppress global introduction

Implementation:

* added `server/relay/scripts/test-managed-client-abuse-smoke.js`
* added `npm run test:managed-client-abuse-smoke`

Validation:

* `node --check server/relay/scripts/test-managed-client-abuse-smoke.js`
* `cd server/relay && npm run test:managed-client-abuse-smoke`

Note:

* This smoke covers the server-side/reporting contract with a simulated client flow.
* Android app runtime submission of abuse reports is still separate follow-up work; the current app only applies the local block.

## Running Follow-Up Queue

- [x] Migrate Registry request signing to the documented body-hash canonical model and update relay heartbeat client at the same time.
- [x] Add automated registry smoke script.
- [x] Add registry schema migration test with an old DB fixture.
- [x] Implement Registry certificate lifecycle model/API/UI.
- [x] Persist Registry policy feed baseline in SQLite and apply it to discovery/federation decisions.
- [x] Add Registry Admin CSRF protection.
- [x] Harden Registry Admin credentials.
- [x] Add Registry Admin metrics page.
- [x] Implement registry-issued federation sessions.
- [x] Implement registry-orchestrated federation topology.
- [x] Implement relay registry session client and active session cache.
- [x] Implement relay push-first federation data-plane.
- [x] Replace optional `REQUEST_SIGNATURE_ENABLED` with signature-required managed/production semantics and explicit unsigned dev escape hatch.
- [x] Add relay request-level replay cache for signed requests.
- [x] Remove relay replay dependency on opaque `body.nonce`.
- [ ] Configure Relay Admin backup and alert sources.
  * dev backup source is now configured
  * dev alert source is now configured

## 2026-06-02: Dev Relay Alert Source

Summary:

* Configured the dev relay environment so the Relay Admin Alerts card reads a real alert log file instead of returning `alerts_unavailable`.

Current behavior covered:

* `.env.dev` now points `ALERT_LOG_PATH` at `/var/backups/relay/alerts.log`
* the dev relay already mounts `./relay/backups` into `/var/backups/relay`, so the admin alert route can read a host-managed file
* seeded dev alert files:
  * `server/relay/backups/alerts.log`
  * `server/relay/backups_2/alerts.log`
* the seeded primary file includes:
  * one active alert line
  * one acknowledged incident line

Validation:

* `docker compose -f server/docker-compose.dev.yml up -d relay`
* authenticated `GET /admin/api/alerts` on `http://127.0.0.1:8080` returned `200`
* returned payload included:
  * `items` with an active warning alert
  * `incidents` with an acknowledged incident entry

## 2026-06-02: Relay Admin Dashboard Smoke Script

Summary:

* Added a lightweight Relay Admin smoke script focused on login plus the dashboard APIs/cards that should work in the configured dev environment.

Current behavior covered:

* static routes:
  * `/admin/dashboard`
  * `/admin/assets/admin.js`
  * `/admin/assets/admin.css`
* authenticated admin APIs:
  * `POST /admin/api/auth/login`
  * `GET /admin/api/auth/me`
  * `GET /admin/api/metrics/summary`
  * `GET /admin/api/metrics/detail?domain=traffic`
  * `GET /admin/api/backup/status`
  * `GET /admin/api/retention/status`
  * `GET /admin/api/alerts`

Code paths updated:

* `server/relay/scripts/test-admin-dashboard-smoke.js`
  * simple end-to-end smoke against a running relay admin instance
* `server/relay/package.json`
  * adds `test:admin-dashboard-smoke`

Validation:

* `node --check server/relay/scripts/test-admin-dashboard-smoke.js`
* `cd server/relay && npm run test:admin-dashboard-smoke`

## 2026-06-02: Registry Trust History Endpoint

Summary:

* Added a dedicated Registry Admin trust-history endpoint backed by the existing audit log so operators can query relay trust transitions without scraping the full audit feed.

Current behavior covered:

* `GET /admin/api/trust` returns trust transition entries derived from:
  * `relay_verified`
  * `relay_trust_promoted`
  * `relay_trust_demoted`
* supports:
  * `relay_id` filter
  * `actor` filter
  * `limit`
  * `offset`
* each returned item includes:
  * `relay_id`
  * `actor`
  * `action`
  * `resulting_trust_level`
  * `current_trust_level`
  * `current_status`
  * `reason`
  * `created_at`

Code paths updated:

* `server/registry/src/index.js`
  * adds `GET /admin/api/trust`
* `server/registry/scripts/test-trust-history.js`
  * integration coverage for all-history and relay-filtered trust history
* `server/registry/package.json`
  * adds `test:trust-history`

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/scripts/test-trust-history.js`
* `cd server/registry && npm run test:trust-history`

## 2026-06-02: Registry Admin Confirmation + Reason Guards

Summary:

* Tightened dangerous Registry Admin actions so they now require both:
  * an explicit confirmation dialog in the UI
  * a non-empty reason enforced by the server

Current behavior covered:

* client-side dangerous actions now flow through a shared confirmation helper before prompting for reason:
  * trust change
  * relay status change
  * certificate issuance
  * certificate renewal
  * certificate revocation
  * federation session revocation
  * federation topology rebalance
* server-side routes now reject missing reasons with `reason_required` for:
  * `PATCH /admin/api/relays/:id/trust`
  * `PATCH /admin/api/relays/:id/status`
  * `POST /admin/api/certificates`
  * `PATCH /admin/api/certificates/:id/renew`
  * `PATCH /admin/api/certificates/:id/revoke`

Code paths updated:

* `server/registry/src/public/admin-registry/assets/registry_admin.js`
  * adds shared confirm+reason helper for dangerous actions
* `server/registry/src/index.js`
  * enforces server-side `reason_required` guards for trust/status/certificate mutations
* `server/registry/scripts/test-admin-reason-guards.js`
  * integration coverage for missing-reason rejections
* `server/registry/package.json`
  * adds `test:admin-reason-guards`

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `node --check server/registry/scripts/test-admin-reason-guards.js`
* `cd server/registry && npm run test:admin-reason-guards`

## 2026-06-02: Registry Admin Pagination + Sorting

Summary:

* Added server-driven pagination and sorting for the largest Registry Admin tables, plus matching UI controls.

Current behavior covered:

* Relay Directory:
  * search remains supported via `q`
  * sort/order controls wired to `GET /admin/api/relays`
  * paginated prev/next controls
* Audit Log:
  * sort/order controls wired to `GET /admin/api/audit`
  * paginated prev/next controls
* Federation Sessions:
  * `GET /admin/api/federation/sessions` now supports `limit`, `offset`, `sort`, `order`
  * UI sort/order controls and paginated prev/next controls

Code paths updated:

* `server/registry/src/public/admin-registry/index.html`
* `server/registry/src/public/admin-registry/relays.html`
* `server/registry/src/public/admin-registry/federation.html`
  * adds sort/order and pagination UI shells
* `server/registry/src/public/admin-registry/assets/registry_admin.css`
  * adds toolbar and pagination layout styles
* `server/registry/src/public/admin-registry/assets/registry_admin.js`
  * adds table state, server-driven fetch helpers, and shared pagination rendering
* `server/registry/src/index.js`
  * adds federation session sort/order/pagination support
* `server/registry/scripts/test-admin-pagination.js`
  * integration coverage for relay, audit, and federation-session pagination/sorting
* `server/registry/package.json`
  * adds `test:admin-pagination`

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `node --check server/registry/scripts/test-admin-pagination.js`
* `cd server/registry && npm run test:admin-pagination`
- [x] Resolve relay opaque event body drift against protocol docs.
- [x] Define encrypted client payload envelope for `event.body`.
- [x] Implement client-side encryption/decryption for encrypted `event.body`.
- [x] Add relay compatibility tests proving encrypted `event.body` is preserved without mutation.
- [x] Add registry client access-grant bootstrap endpoint and contract aligned with `docs/server/REGISTRY_PROTOCOL.md`.
- [x] Add registry client access-grant renewal / reassignment endpoint aligned with `docs/server/REGISTRY_PROTOCOL.md`.
- [x] Add registry relay-selection policy for managed client access.
- [x] Add registry client abuse-report intake endpoint and scoring/correlation rules for relay candidate selection.
- [x] Add false-report resistance for client abuse reports.
- [x] Add relay-side enforcement for registry-issued client relay access grants in managed client access mode.
- [x] Define and implement relay pairwise session establishment for managed client access.
- [x] Add client-side local relay block behavior for abusive pairwise sessions.
- [x] Gate plaintext `event.body` compatibility behind explicit dev/test mode.
- [x] Make relay ingest idempotency/replay handling safe under transient DB failures.
- [ ] Make relay private request signatures compatible with federation fetch.

## 2026-06-02: Registry Admin Metrics Page

Summary:

* Implemented the missing Registry Admin metrics page and connected it to a richer `/admin/api/metrics` payload.

Current behavior covered:

* `/admin/metrics` now serves a dedicated authenticated admin page
* `/admin/api/metrics` now exposes:
  * heartbeat rate per minute and per second
  * discovery rate per minute and per second
  * certificate issue/revocation counters and active certificate count
  * policy update and audit event counters
  * federation session issued/denied/active/expired/revoked counters
* Shared admin navigation now links to the new Metrics page.

Code paths updated:

* `server/registry/src/index.js`
* `server/registry/src/db.js`
* `server/registry/src/public/admin-registry/metrics.html`
* `server/registry/src/public/admin-registry/assets/registry_admin.js`
* `server/registry/src/public/admin-registry/*.html`
* `server/registry/scripts/test-admin-metrics.js`

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/db.js`
* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `node --check server/registry/scripts/test-admin-metrics.js`
* `cd server/registry && npm run test:admin-metrics`

## 2026-06-02: Dev Relay Backup Status Source

Summary:

* Configured the dev relay environment so the Relay Admin Backup card reads real marker files instead of returning `backup_status_unavailable`.

Current behavior covered:

* `.env.dev` now points:
  * `BACKUP_OK_FILE=/var/backups/relay/last_backup.ok`
  * `RESTORE_DRILL_OK_FILE=/var/backups/relay/last_restore_drill.ok`
* `server/docker-compose.dev.yml` now mounts:
  * `./relay/backups:/var/backups/relay` for the primary relay
  * `./relay/backups_2:/var/backups/relay` for the secondary relay
* Dev marker files were seeded for both relay instances.

Validation:

* `docker compose -f server/docker-compose.dev.yml up -d relay`
* relay startup remained healthy after the mount/env change
* authenticated `GET /admin/api/backup/status` on `http://127.0.0.1:8080` returned `200` with:
  * `last_status: "ok"`
  * `restore_drill_status: "ok"`

## 2026-06-03: Registry Admin Pagination + Panel States

Summary:

* Added server-driven pagination/sorting controls for large Registry Admin tables.
* Added explicit loading, empty, and error states across Registry Admin panels so failures stay localized instead of blanking the entire page.

Current behavior covered:

* Relay Directory, Audit Log, and Federation Sessions now expose page controls and sort selectors backed by admin APIs.
* `GET /admin/api/federation/sessions` now accepts `limit`, `offset`, `sort`, and `order`.
* Registry Admin pages now show:
  * loading placeholders while panel data is in flight
  * empty placeholders when a table/detail panel has no data
  * panel-local error placeholders when a single API call fails
* Audit loading on the Federation page is now independent from federation session/topology loading, so one failing panel no longer blocks the other.

Code paths updated:

* `server/registry/src/index.js`
* `server/registry/src/public/admin-registry/index.html`
* `server/registry/src/public/admin-registry/relays.html`
* `server/registry/src/public/admin-registry/federation.html`
* `server/registry/src/public/admin-registry/assets/registry_admin.css`
* `server/registry/src/public/admin-registry/assets/registry_admin.js`
* `server/registry/scripts/test-admin-pagination.js`

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `cd server/registry && npm run test:admin-pagination`
* `cd server/registry && npm run test:admin-metrics`

## 2026-06-03: Registry Admin Mobile/Dense Visual Pass

Summary:

* Tightened Registry Admin layout density for operator workflows on desktop.
* Added responsive card-style table rendering for narrow screens without changing API contracts or page routes.

Current behavior covered:

* Topbar now behaves better under dense nav usage and narrow widths.
* Panels, toolbars, KPI cards, detail cards, and inline forms now use denser spacing and stronger surface hierarchy.
* Large tables now collapse into stacked mobile cards using per-cell field labels instead of horizontal scrolling as the only fallback.
* Action buttons remain full-width and readable on small screens while preserving compact operator density on desktop.

Code paths updated:

* `server/registry/src/public/admin-registry/assets/registry_admin.css`
* `server/registry/src/public/admin-registry/assets/registry_admin.js`

Validation:

* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `cd server/registry && npm run test:admin-pagination`
* `cd server/registry && npm run test:admin-metrics`
* `cd server/registry && npm run test:admin-federation-browser`
  * blocked in this session because no registry was reachable at `http://127.0.0.1:8090`

## 2026-06-03: Registry Admin Env Example Documentation

Summary:

* Updated the registry env example and operator guide so admin username, password configuration modes, and admin session TTL are documented in one place.

Current behavior covered:

* `server/registry/.env.example` now includes:
  * `REGISTRY_ADMIN_USERNAME`
  * `REGISTRY_ADMIN_PASSWORD_HASH`
  * `REGISTRY_ADMIN_CREDENTIALS_FILE`
  * `REGISTRY_ADMIN_SESSION_TTL_SECONDS`
  * `REGISTRY_ADMIN_ALLOW_PLAINTEXT_PASSWORD`
  * `REGISTRY_ADMIN_PASSWORD`
* The example now explicitly states:
  * file-backed or hashed credentials are preferred
  * plaintext password mode is dev/test only
  * `REGISTRY_ADMIN_CREDENTIALS_FILE` overrides username/hash envs when present
* `server/registry/docs/registry_admin_operator_guide.md` now includes a concrete dotenv example and notes for the 8-hour default session TTL.

Code paths updated:

* `server/registry/.env.example`
* `server/registry/docs/registry_admin_operator_guide.md`

Validation:

* `git diff --check`

## 2026-06-03: Server Docs Layout Cleanup

Summary:

* Updated active server-facing docs to use the split `server/relay` and `server/registry` layout instead of the legacy flat `server/src`, `server/scripts`, and `server/migrations` paths.

Coverage:

* refreshed the main developer/operator guides:
  * `docs/development/project_bootstrap_guide_vi.md`
  * `docs/server/developer_guide_vi.md`
  * `server/relay/docs/relay_admin_ui_guide.md`
  * `docs/server/admin_ui_development_guideline.md`
* refreshed relay runbooks and operational docs:
  * backup/restore
  * deploy/rollback
  * production guide
  * grafana dashboards
  * data export guide
* refreshed relay test docs and historical milestone/task references so command examples now point at:
  * `server/relay/scripts/...`
  * `server/relay/config/...`
  * `server/relay/migrations/...`
  * `server/start.sh`
* refreshed older compatibility/development notes that still pointed at `server/src/...` where the intent was current relay runtime code, not historical context

Validation:

* `rg -n "server/src|server/scripts|server/migrations|server/config|server/public" docs -g '*.md'`
  now only reports:
  * this development note entry describing the cleanup task
  * the Phase 3.2 task list line that records the completed task
* `git diff --check`

## 2026-06-03: Legacy Flat Server Tree Archived

Summary:

* Removed the empty legacy flat-layout directories from `server/` after confirming active runtime/config/docs had been retargeted to `server/relay` and `server/registry`.
* Archived stale local backup dumps from `server/server/backups/` into `server/legacy_archives/flat-server-backups/`.

Runtime/config cleanup completed:

* updated remaining live references in:
  * root `README.md`
  * `server/README.md`
  * `minipc_shell/*`
  * relay systemd unit examples
  * relay test wrappers / usage examples
* removed empty legacy directories:
  * `server/src`
  * `server/scripts`
  * `server/migrations`
  * `server/config`
  * `server/server`

Validation:

* `rg -n 'server/scripts/|./server/scripts|server/src/|server/config/|server/migrations/' server/relay server/README.md README.md minipc_shell -g '!node_modules/**'` returned no matches
* `find server -maxdepth 2 -type d | sort` shows only the split layout plus `server/legacy_archives`
* `git diff --check`

## 2026-06-03: Remove Obsolete Compose Version Warnings

Summary:

* Removed the deprecated top-level `version` field from the active Compose files now that modern Docker Compose ignores it and emits warnings.

Files updated:

* `server/docker-compose.yml`
* `server/docker-compose.dev.yml`
* `minipc_shell/docker-compose.yml`

Validation:

* `docker compose -f server/docker-compose.yml config`
* `docker compose -f server/docker-compose.dev.yml config`
* `docker compose -f minipc_shell/docker-compose.yml config`
* `git diff --check`

## 2026-06-03: Registry SQLite Backup Guidance

Summary:

* Promoted the existing registry SQLite backup notes into a dedicated runbook so operators have a single document for backup scope, timing, restore steps, and verification.

Docs updated:

* `server/registry/docs/registry_sqlite_backup_restore_runbook.md`
* `server/registry/docs/registry_admin_operator_guide.md`
* `docs/milestones/Phase_3_2_Task_List.md`

Coverage added/clarified:

* backup scope for registry SQLite state
* recommended backup timing around deploys and governance operations
* file-copy procedure for `.sqlite`, `-wal`, and `-shm`
* restore checklist
* minimal post-restore verification
* relationship to policy rollback and other registry incident runbooks

Validation:

* `git diff --check`

## 2026-06-03: Managed Federation Reconciliation Metrics

Summary:

* Added explicit relay metrics for managed federation reconciliation validation so `pull_reconcile` traffic can be distinguished from push traffic in runtime monitoring.

Runtime changes:

* Added metric definitions in `server/relay/src/metrics.js`:
  * `federation_reconciliation_total`
  * `federation_reconciliation_events_total`
  * `federation_reconciliation_validation_failures_total`
  * explicit definition for `federation_session_limit_rejections_total`
* Updated `server/relay/src/routes.js` so managed `GET /v1/events:fetch` with
  `X-Federation-Operation: pull_reconcile` now:
  * increments reconciliation failure metrics by `code` for:
    * missing session headers
    * wrong operation
    * invalid federation signature
    * invalid grant scope/signature/source/destination
    * revoked session
  * increments reconciliation authorization metric once the request passes session/grant validation
  * increments reconciliation event count metric based on returned event count
  * tags session limit rejections with `operation: pull_reconcile`

Validation:

* `node --check server/relay/src/metrics.js`
* `node --check server/relay/src/routes.js`
* `node --check server/relay/scripts/test-federation-push-route.js`
* `cd server/relay && npm run test:federation-push-route`
* `git diff --check`

## 2026-06-03: Managed Federation Revoked-Session Feed Completion

Summary:

* Completed the managed federation revocation-feed runtime path so a relay no longer keeps using a cached active session after the registry revocation feed marks that same `session_id` revoked.

Runtime changes:

* Updated `server/relay/src/registry_session_client.js`:
  * `refreshRevocations()` now checks whether the currently cached active session appears in the refreshed revocation feed.
  * When the active `session_id` is revoked, the client marks it revoked locally, forces the next refresh window, and logs `registry_federation_active_session_revoked`.
  * `getActiveSession()` now returns `null` for a revoked cached session even if its TTL has not expired yet.
  * `status()` now reports revoked cached sessions as inactive with `status: "revoked"`.
* Added relay-side regression coverage in `server/relay/scripts/test-registry-session-client.js` proving:
  * revocation polling marks the active session revoked
  * `getActiveSession()` stops returning the revoked session
  * status output switches to inactive/revoked
* Added federation push regression coverage in `server/relay/scripts/test-federation-push.js` proving outbound fanout stops once the managed session client no longer exposes an active session after revocation.

Validation:

* `node --check server/relay/src/registry_session_client.js`
* `node --check server/relay/scripts/test-registry-session-client.js`
* `node --check server/relay/scripts/test-federation-push.js`
* `cd server/relay && npm run test:registry-session`
* `cd server/relay && npm run test:federation-push`
* `git diff --check`

## 2026-06-03: Registry SQLite Maintenance-Window Checklist

Summary:

* Tightened the registry operations docs so deploy/rollback work against the SQLite-backed registry has an explicit maintenance-window checklist instead of only a general recommendation.

Documentation changes:

* Updated `server/registry/docs/registry_sqlite_backup_restore_runbook.md`:
  * added a dedicated `Maintenance Window Checklist`
  * covers pre-window recording, clean service stop, DB/WAL/SHM copy, post-change verification, and rollback trigger conditions
  * clarified that the checklist should be used for deploy, rollback, policy repair, and signing/admin credential rotation work that may depend on DB recovery
* Updated `server/registry/docs/registry_admin_operator_guide.md`:
  * linked the registry backup section directly to the new maintenance-window checklist
* Updated `docs/milestones/Phase_3_2_Task_List.md`:
  * marked the maintenance-window checklist task complete
  * refreshed `Current top 5` so it only lists real remaining follow-up work

Validation:

* `git diff --check`

## 2026-06-03: Registry Audit Export

Summary:

* Added Registry Admin audit export for operator download workflows.

Current behavior covered:

* New endpoint: `GET /admin/api/audit/export`
* Supported formats:
  * `format=csv`
  * `format=jsonl`
* Export reuses the same audit query model as the admin audit table:
  * `sort`
  * `order`
  * `actor`
  * `action`
  * `resource_type`
  * `resource_id`
  * `q`
* Audit export links are now present on the admin pages that show audit data, including relay/session detail pages.
* Detail-page exports use `resource_type` + `resource_id` scoping so operators can export only the current relay or federation session history.

Code paths updated:

* `server/registry/src/index.js`
* `server/registry/src/db.js`
* `server/registry/src/public/admin-registry/index.html`
* `server/registry/src/public/admin-registry/relays.html`
* `server/registry/src/public/admin-registry/federation.html`
* `server/registry/src/public/admin-registry/relay_details.html`
* `server/registry/src/public/admin-registry/federation_session_details.html`
* `server/registry/src/public/admin-registry/assets/registry_admin.js`
* `server/registry/scripts/test-audit-export.js`
* `server/registry/package.json`

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `node --check server/registry/scripts/test-audit-export.js`
* `cd server/registry && npm run test:audit-export`

## 2026-06-03: Registry Admin Production Access Guidance

Summary:

* Added explicit production guidance for protecting Registry Admin behind HTTPS and outer access controls.

Current behavior/documentation covered:

* Registry Admin is now documented as a privileged control-plane surface, not a public web app.
* Production guidance now explicitly recommends:
  * HTTPS at a reverse proxy or ingress
  * private/container-only registry network placement where possible
  * outer controls before app login:
    * VPN/private network access
    * IP allowlists
    * optional edge mTLS
  * avoiding direct public exposure of the raw registry admin port
* The guidance also clarifies that app-level login is necessary but not sufficient on its own.

Docs updated:

* `server/registry/docs/registry_admin_operator_guide.md`
* `server/registry/docs/registry_admin_ui_guide.md`
* `docs/SECURITY.md`

Validation:

* `git diff --check`

## 2026-06-05: Registry Admin Relay Onboarding Bundle

Summary:

* Added a Registry Admin onboarding-bundle export flow so registry operators can hand off registry-owned relay secrets without opening raw registry `.env` files.

Current behavior covered:

* New endpoint: `POST /admin/api/onboarding/relay-bundle`
* Dashboard now includes a `Relay Onboarding Bundle` panel.
* Operator can:
  * enter relay ID or operator label
  * choose which registry-managed sections to include
  * provide a mandatory reason
  * copy or download a generated `.env` snippet
* Export action is now audited as:
  * `relay_onboarding_bundle_exported`

Bundle scope:

* Exports only registry-owned values such as:
  * `REGISTRY_SHARED_SECRET`
  * `REGISTRY_CLIENT_ACCESS_GRANT_SIGNING_SECRET`
  * `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET`
* Does not export:
  * relay-local secrets such as `CLIENT_RELAY_SESSION_BINDING_SECRET`
  * federation mTLS private keys / certificates / CA files

Rationale:

* Registry should distribute only the secrets it actually owns.
* Relay-local secrets and mTLS material remain operator-managed on the relay side.

Validation:

* `node --check server/registry/src/index.js`
* `node --check server/registry/src/public/admin-registry/assets/registry_admin.js`
* `node --check server/registry/scripts/test-admin-onboarding-bundle.js`
* `cd server/registry && npm run test:admin-onboarding-bundle`
* `git diff --check`

## 2026-06-03: Federation Session Signing Key Rotation Runbook

Summary:

* Added an explicit runbook for rotating `REGISTRY_FEDERATION_SESSION_SIGNING_SECRET` in managed federation deployments.

Current behavior/documentation covered:

* the runbook states the current implementation limitation clearly:
  * single active signing secret
  * no dual-key verification window
  * rotation must be treated as a coordinated cutover across Registry and relays
* documented:
  * pre-rotation checklist
  * secret generation requirements
  * Registry-first then relay rollout order
  * session refresh / stale session revocation expectations
  * post-rotation verification
  * rollback back to the previous shared secret
  * future improvement direction for key IDs / dual-key support

Docs updated:

* `server/registry/docs/registry_federation_session_signing_key_rotation_runbook.md`
* `server/registry/docs/registry_admin_operator_guide.md`

Validation:

* `git diff --check`

## 2026-06-03: Compromised Relay Session Incident Runbook

Summary:

* Added a registry-side incident response runbook for compromised managed federation relay sessions.

Current behavior/documentation covered:

* documented response flow for:
  * session-level containment
  * relay-level containment
  * topology rebalance after containment
  * revocation feed rebuild when relay revocation state must be republished
* the runbook maps directly to controls that already exist today:
  * federation session revoke
  * relay suspend / revoke / ban
  * audit export
  * revocation feed rebuild
  * topology rebalance
* documented current runtime limitations:
  * no bulk revoke-by-relay endpoint
  * explicit revocation feed rebuild
  * operator-coordinated incident response for broader cases

Docs updated:

* `server/registry/docs/registry_compromised_relay_session_incident_runbook.md`
* `server/registry/docs/registry_admin_operator_guide.md`

Validation:

* `git diff --check`

## 2026-06-03: Registry Policy Rollback Runbook

Summary:

* Added an explicit rollback runbook for bad managed-federation policy publishes that affect session issuance or topology assignment.

Current behavior/documentation covered:

* the runbook reflects current runtime behavior:
  * policy persists immediately
  * new issue/renew flows use the latest policy
  * active sessions may outlive a bad policy until revoked or expired
  * topology rebalance is explicit
* documented rollback sequence:
  * restore safe policy first
  * then assess existing sessions
  * revoke/reissue sessions if required
  * rebalance topology if assignments were distorted
* documented current limitations:
  * no stored named policy history snapshots
  * rollback depends on operators knowing the last good values
  * no one-click mass reissue after rollback

Docs updated:

* `server/registry/docs/registry_policy_rollback_runbook.md`
* `server/registry/docs/registry_admin_operator_guide.md`

Validation:

* `git diff --check`

## 2026-06-04: Android Managed Client Relay Runtime Baseline

Summary:

* Android relay sync now has a first real managed-client runtime path instead of only direct relay URL push/pull.
* When registry base URL plus registry/relay request-signing secrets are configured, the app now:
  * requests a registry-issued access grant
  * selects an eligible candidate relay
  * opens a signed pairwise relay session
  * pushes and pulls through relay data-plane headers required by managed mode
  * closes the relay session after the sync run

Android/runtime changes:

* Added managed-client config fields through Android `BuildConfig`:
  * `RELAY_REQUEST_SIGNATURE_SECRET`
  * `REGISTRY_REQUEST_SIGNATURE_SECRET`
* Extended `AppSettings` / `EnvironmentLoader` so relay sync can read:
  * relay base URL
  * registry base URL
  * relay signing secret
  * registry signing secret
* Added shared request-signing helper in `android/core/src/main/java/app/peermesh/core/net/RequestSigning.kt`.
* Added managed-client protocol models in `android/core/src/main/java/app/peermesh/core/relay/ManagedClientAccessModels.kt`.
* Added `android/transport/src/main/java/app/peermesh/transport/relay/RegistryManagedRelayClient.kt` for:
  * signed `POST /v1/registry/client/sessions`
* Extended `android/transport/src/main/java/app/peermesh/transport/relay/RelayHttpClient.kt` to support:
  * managed relay session open
  * managed relay session close
  * managed data-plane headers on ingest/fetch
  * existing relay request signing on these new paths
* Added `android/app/src/main/java/app/peermesh/relay/ManagedRelayAccessCoordinator.kt`:
  * issues a registry grant per sync run
  * skips locally blocked relays
  * tries multiple candidates when `session:open` is rejected
  * records grant context for abuse reporting once a relay session is established
* Updated `android/app/src/main/java/app/peermesh/relay/RelaySyncRunner.kt`:
  * managed mode now uses registry-first bootstrap instead of direct seed relay URLs
  * legacy direct relay sync remains as fallback when managed-client config is absent
* Updated registry abuse report plumbing:
  * `RelayRegistryAbuseReportClient` now supports signed registry requests
  * `RelayRegistryAbuseReporter` now passes registry signing secret
  * managed grant context is now captured at successful session establishment, so automatic report submission no longer depends on a future context-capture task

Current limitation after this baseline:

* Android currently opens a fresh registry grant + relay session per sync run and closes it afterward.
* Persistent grant/session reuse and session renewal across sync windows is still a follow-up task if we want lower control-plane overhead and better short-outage continuity.

Validation:

* `cd android && ./gradlew :transport:compileDebugKotlin`
* `cd android && ./gradlew :app:compileOfficialDebugKotlin`
* `cd android && ./gradlew :app:testOfficialDebugUnitTest --tests app.peermesh.relay.RelayRegistryAbuseReportClientTest`

## 2026-06-04: Android Managed Grant/Session Reuse And Renew

Summary:

* Android managed relay sync no longer issues a fresh registry grant and closes a fresh relay session on every sync run.
* The app now persists the active managed relay binding locally and reuses it across sync runs when still valid.
* When expiry approaches, the app now renews the registry grant and/or relay pairwise session instead of always restarting from scratch.

Android/runtime changes:

* Added `android/app/src/main/java/app/peermesh/relay/ManagedRelayAccessStore.kt`:
  * shared-pref backed storage for the active managed relay binding
  * stores relay URL, relay id, registry grant, and relay pairwise session
* Extended `RegistryManagedRelayClient` with signed grant renewal support:
  * `POST /v1/registry/client/sessions/{grant_id}:renew`
* Extended `RelayHttpClient` with signed relay session renewal support:
  * `POST /v1/client/session/{session_id}:renew`
* Reworked `ManagedRelayAccessCoordinator` to:
  * load cached binding first
  * reuse it directly when both grant and session are still healthy
  * renew the grant when close to expiry
  * renew the relay session when close to expiry
  * reopen a relay session from the cached grant when the session has expired but the grant is still valid
  * fall back to full reissue only when reuse/renew/open from cached authorization is no longer possible
* Updated `RelaySyncRunner`:
  * managed path now calls `acquire(...)` instead of always bootstrapping from scratch
  * managed relay sessions are no longer closed automatically after every sync run
  * when a relay is locally blocked for abuse, any cached managed binding for that relay is cleared immediately

Current continuity behavior:

* Short registry outages now degrade better:
  * if cached grant + session are still valid, sync continues without touching the registry
  * if the session has expired but the cached grant is still valid, the app can reopen a relay session directly from that cached grant
* Registry control-plane calls now happen mostly on:
  * first bootstrap
  * near-expiry renewal
  * failover/reissue after cached authorization is no longer usable

Validation:

* `cd android && ./gradlew :app:clean :app:compileOfficialDebugKotlin`
* `cd android && ./gradlew :app:testOfficialDebugUnitTest --tests app.peermesh.relay.RelayRegistryAbuseReportClientTest`

## 2026-06-04: Android Managed Relay Client-Level Tests

Summary:

* Added focused JVM tests for the Android managed relay coordinator so the reuse/renew/failover behavior is locked by unit coverage instead of only manual reasoning.
* Introduced small transport interfaces in the `transport` module so the app coordinator can inject fake registry and relay clients without breaking module boundaries.

Test/runtime seam changes:

* Added `android/transport/src/main/java/app/peermesh/transport/relay/ManagedRelayAccessTransports.kt`:
  * `RegistryManagedRelayTransport`
  * `RelayManagedSessionTransport`
* Updated `ManagedRelayAccessCoordinator` to depend on injectable factories for those interfaces.
* Kept binding persistence injectable via `ManagedRelayBindingStore` so tests can exercise reuse and mutation behavior without Android shared preferences.

New test coverage:

* Added `android/app/src/test/java/app/peermesh/relay/ManagedRelayAccessCoordinatorTest.kt`
* Covers:
  * cached binding reuse when both grant and session are healthy
  * grant + session renewal when both are near expiry
  * reopening a relay session directly from cached grant material when the old session has expired
  * candidate failover when the first relay rejects `session:open`

Validation:

* `cd android && ./gradlew :app:compileOfficialDebugKotlin :app:testOfficialDebugUnitTest --tests app.peermesh.relay.ManagedRelayAccessCoordinatorTest --tests app.peermesh.relay.RelayRegistryAbuseReportClientTest`

## 2026-06-04: Android Managed Relay Runtime Reauth Retry

Summary:

* Managed relay sync now retries once inside the same sync run when the relay rejects cached authorization with a recoverable managed-auth failure.
* This closes the gap where the app could hold a cached grant/session, hit a stale auth error on push or pull, and then stop without attempting a fresh bootstrap until the next scheduled sync.

Runtime changes:

* Added `android/app/src/main/java/app/peermesh/relay/ManagedRelaySyncRuntime.kt`:
  * isolates managed relay bootstrap + cycle execution
  * retries once on:
    * `grant_mismatch`
    * `client_session_expired`
    * `client_session_token_mismatch`
  * clears the cached binding before reacquiring so the next attempt does not reuse the same stale relay/session material
* Updated `RelaySyncRunner` to delegate the managed path through `ManagedRelaySyncRuntime`.
* `RelaySyncRunner` now captures recoverable auth failure codes from managed push/pull results and feeds them into the retry decision.

Tests:

* Added `android/app/src/test/java/app/peermesh/relay/ManagedRelaySyncRuntimeTest.kt`
* Covers:
  * no retry when the first managed cycle is healthy
  * clear + reacquire + retry after recoverable auth failure
  * preserving the first failure while annotating retry failure when reauth cannot be completed

Validation:

* `cd android && ./gradlew :app:compileOfficialDebugKotlin :app:testOfficialDebugUnitTest --tests app.peermesh.relay.ManagedRelayAccessCoordinatorTest --tests app.peermesh.relay.ManagedRelaySyncRuntimeTest --tests app.peermesh.relay.RelayRegistryAbuseReportClientTest`

## 2026-06-05: Android Managed Relay Build Injection + UI Readiness

Summary:

* Android release builds can now inject registry-managed cloud-sync config at build time instead of silently compiling empty managed-relay secrets into the APK.
* Cloud-sync UI now treats registry-managed bootstrap as a valid relay path even when the app has no direct relay seed URL configured locally.

Build/runtime changes:

* Updated `android/app/build.gradle.kts`:
  * reads managed relay build inputs from:
    * Gradle `-P...` properties
    * environment variables
    * `android/local.properties`
  * supports:
    * `PEERMESH_RELAY_BASE_URL`
    * `PEERMESH_REGISTRY_BASE_URL`
    * `PEERMESH_RELAY_REQUEST_SIGNATURE_SECRET`
    * `PEERMESH_REGISTRY_REQUEST_SIGNATURE_SECRET`
* Updated `android/README.md` with the new build-time inputs and clarified that managed mode requires only the registry URL plus the registry request-signing secret.
* Added `RelaySyncUiEvaluator` so header/status surfaces share one rule for direct-relay URLs plus registry-managed readiness.
* Updated `StatusIndicatorUpdater`:
  * the relay header icon no longer falls back to yellow purely because `relayUrls` is empty
  * a fully configured managed registry path now counts as cloud-sync ready
* Updated `StatisticsActivity`:
  * `Cloud Sync` now shows ready when managed mode is configured, even without direct seed relay URLs
  * managed-only readiness now displays as `Cloud Sync: registry-managed`
* Updated `RelaySyncCoordinator`:
  * the foreground/periodic scheduler no longer aborts startup purely because the app has no direct relay seed URLs
  * a fully configured managed registry path now counts as sufficient to start the relay sync loop
* Added `KnownRelayStore`:
  * relays that complete sync successfully are cached locally for 30 days
  * cache entries are ordered by most recent success
* Updated `RelaySyncRunner`:
  * registry-managed sync still tries the registry first on every normal run
  * if managed bootstrap fails before any relay session is established, or if managed reauth fails after a stale session, the app can temporarily fall back to the cached known-relay list
  * successful direct or managed relay cycles now refresh that relay's known-relay TTL
* Added `ManagedRelayFallbackPolicy` so the registry-failure fallback rule is explicit and testable.
* Updated Android managed relay bootstrap/runtime:
  * `ManagedRelayAccessCoordinator.isConfigured()` no longer requires a static relay URL or relay request-signing secret
  * managed relay session open/renew/close now rely on registry-issued grants plus relay-issued session headers instead of app-level relay signatures
* Updated relay managed-client routes:
  * registry-managed client requests carrying the required grant/session headers bypass the legacy app request-signature requirement
  * direct/private relay mode still keeps `X-Relay-Signature` enforcement for unsigned non-managed clients
  * client session renew/close now validate the managed session token before accepting unsigned managed requests

Tests:

* Added `android/app/src/test/java/app/peermesh/relay/RelaySyncUiEvaluatorTest.kt`
* Covers:
  * managed config counts as a valid relay path without direct relay URLs
  * the relay indicator stays on when managed mode is configured and online
  * the indicator remains paused only when neither direct nor managed relay path exists
* Added `android/app/src/test/java/app/peermesh/relay/ManagedRelayFallbackPolicyTest.kt`
  * covers bootstrap-failure fallback
  * covers stale-session reauth failure fallback

## 2026-06-20: Registry Moderation Reports Workspace Baseline

Summary:

* Registry now has a dedicated `client_moderation_reports` workspace for Android `report peer` and `report message`.
* This is separate from relay/federation abuse reporting and separate from relay event replication.
* Android moderation reports can now be submitted end-to-end to the registry, reviewed by the registry owner/admin, annotated, resolved, and audited.

Registry-side changes:

* Added `client_moderation_reports` persistence and indexes in `server/registry/src/db.js`.
* Added `server/registry/src/client_moderation_reports.js`:
  * validates `peer` / `message` moderation report payloads
  * enforces allowlisted reason codes
  * supports idempotent duplicate handling by `report_id`
* Added `POST /v1/registry/client/moderation-reports` in `server/registry/src/index.js`.
* Added admin moderation-report APIs:
  * list reports
  * report detail
  * status transition
  * operator note creation
* Added registry audit coverage for:
  * report received
  * duplicate retry collapse
  * report denied
  * operator status changes
  * operator notes

Admin UI changes:

* Added moderation-report list page and detail page under registry admin.
* Registry admin can now:
  * inspect report metadata and evidence
  * change report status
  * add operator notes
  * review audit history for the report

Android-side changes:

* `android/app/src/main/java/app/peermesh/moderation/ModerationRepository.kt`
  * now parses moderation-report registry responses
  * logs `reportStatus`, duplicate-collapse info, and error code when available
* `android/app/src/main/java/app/peermesh/moderation/ModerationSyncWorkManager.kt`
  * one-time moderation sync now uses exponential WorkManager backoff
* `android/app/src/main/java/app/peermesh/moderation/ModerationReportSyncPolicy.kt`
  * centralizes retry eligibility and permanent-failure classification
* `retry_waiting` moderation reports now respect a bounded per-report retry delay based on `attemptCount` and `lastAttemptAtMs`

Policy/docs alignment:

* Updated `docs/public/peermesh-moderation-policy.md` so public wording now matches the shipped registry moderation workflow more closely.

Validation:

* `cd server/registry && npm run check`
* `cd server/registry && npm run test:client-moderation-reports`
* `cd android && ./gradlew :app:compileEmergencyDebugKotlin`

Known remaining gaps:

* moderation-report export / redaction policy is not finalized
* moderation-report unit coverage now exists for sync policy behavior, but full app unit-test execution is currently blocked by unrelated compile failures in existing nearby-room tests
* broader Moderation V2 work such as rejoin governance, membership consistency, and control-event hardening remains open

## 2026-07-03: Moderation Report Source IP Minimization

Summary:

* Reporter IP is no longer treated as moderation report evidence.
* Registry operator UI and admin API report objects no longer expose reporter `source_ip`.
* Report evidence exports must not include reporter `source_ip`.
* Raw reporter IP is used only for transient rate limiting.

Registry-side changes:

* Added `client_moderation_report_ip_events` as a transient rate-limit table.
* Added pruning for IP rate-limit events outside the short abuse-control window.
* Added source-IP moderation report rate limiting with duplicate retry preservation.
* Stopped writing raw IP to the primary `client_moderation_reports` row.
* Existing raw `source_ip` values in `client_moderation_reports` are nulled during DB initialization.

Policy:

* Ordinary moderation review should use user-submitted report metadata and optional plaintext evidence only.
* Any future IP-visible forensic workflow must be a separate higher-privilege workflow with explicit audit, retention, and privacy-policy review.

## Handoff - Cross-Transport Bridge Limitation

Current limitation:
- The current BLE <-> cloud bridge is only a `member bridge`.
- A dual-homed node can bridge traffic for a group only when it is itself a
  member of that same group and can fully ingest that group's events.
- In practice this means:
  - bridge eligibility is group-scoped
  - a stranger node running the app cannot help carry `A <-> C` traffic unless
    it also belongs to that group
  - the current system does not yet support opportunistic third-party carriage
    across unrelated peers

Why this happens:
- The current bridge works on normal group events after local ingest, not on an
  opaque ciphertext bundle that can be forwarded blindly.
- Therefore the bridge node needs the same group-level access/context as any
  ordinary member node.

Proposed follow-up direction:
- Add a `blind opaque bridge` / `opaque carrier` model.
- Goal:
  - allow any intermediate node with available transport to carry encrypted
    payloads between disconnected peers without being a member of the group
  - keep payload contents unreadable to the bridge node
- High-level shape:
  - end-to-end encrypt group payloads before bridge-carrier handling
  - introduce an opaque bundle format that BLE and cloud can both store,
    forward, dedupe, expire, and acknowledge without decrypting application
    content
  - separate control-plane metadata from opaque data-plane forwarding
- This is a distinct scope from the current member-bridge implementation and
  should be handled in a separate thread.
