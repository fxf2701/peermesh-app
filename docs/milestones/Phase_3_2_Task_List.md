# Phase 3.2 Task List (Relay + Registry Server)

Purpose:
- Track server-side work for the new `server/relay` + `server/registry` architecture.
- Keep relay transport/federation responsibilities separate from registry discovery/governance responsibilities.
- Primary references:
  - `docs/server/INTERNET_RELAY_OPTION.md`
  - `docs/server/NODE_OPERATOR_GUIDE.md`
  - `docs/specs/RELAY_PROTOCOL_SPEC.md`
  - `docs/server/FEDERATION_PROTOCOL.md`
  - `docs/server/REGISTRY_PROTOCOL.md`
  - `docs/SECURITY.md`
  - `server/relay/docs/relay_admin_ui_guide.md`
  - `server/registry/docs/registry_admin_ui_guide.md`
  - `docs/server/registry_admin_pages_spec.md`
  - `docs/server/registry_admin_development_guideline.md`

## 0) Architecture Alignment

- [x] Split server implementation into dedicated relay and registry services under `server/relay` and `server/registry`.
- [x] Keep Relay Admin UI and Registry Admin UI responsibilities separate in docs and implementation.
- [x] Use Docker Compose service names (`relay`, `relay_2`, `registry`) only for container-to-container networking.
- [x] Confirm host browser access uses host ports:
  - Relay Admin: `http://127.0.0.1:8080/admin`
  - Relay 2 Admin: `http://127.0.0.1:8081/admin`
  - Registry Admin: `http://127.0.0.1:8090/registry/admin`
- [x] Update all server docs that still reference the old flat `server/src`, `server/scripts`, or `server/migrations` layout.
- [x] Remove or archive stale generated/legacy server directories once the new layout is fully validated.
- [x] Establish public Phase 3.2 baseline endpoints:
  - website `https://peermesh.app`
  - registry `https://registry.peermesh.app`
  - first relay `https://relay.peermesh.app`
  - operator mailbox `hello@peermesh.app`

## 1) Relay Runtime Packaging

- [x] Fix `server/relay/Dockerfile` so the image includes `src`, `scripts`, `migrations`, and `config`.
- [x] Fix missing `/app/scripts/migrate.js` startup failure in relay container.
- [x] Update production Compose relay build context to `./relay`.
- [x] Update production Compose mounts to use `./relay/config` and `./relay/data`.
- [x] Update dev Compose mounts to use `./relay/src`, `./relay/scripts`, `./relay/migrations`, and relay data folders.
- [x] Update `server/start.sh` to select `server/docker-compose.yml` by default and use `server/docker-compose.dev.yml` only with `--dev`.
- [x] Prevent dev `multi` profile from being enabled for production mode.
- [x] Verify relay image build succeeds after packaging fixes.
- [x] Verify relay `/healthz` and `/admin/dashboard` are reachable from host.
- [x] Remove obsolete Compose `version` field warnings.

## 2) Relay Admin UI Runtime Check

- [x] Read Relay Admin UI guide and page spec before testing.
- [x] Confirm `/admin` and `/admin/dashboard` serve dashboard HTML.
- [x] Confirm `/admin/assets/admin.js` and `/admin/assets/admin.css` serve successfully.
- [x] Confirm admin login works with current dev credentials.
- [x] Confirm `/admin/api/auth/me` works with session cookie.
- [x] Confirm dashboard metrics APIs work:
  - `/admin/api/metrics/summary`
  - `/admin/api/metrics/detail?domain=traffic`
  - `/admin/api/retention/status`
- [x] Identify currently unavailable dashboard cards:
  - `/admin/api/backup/status` requires backup status configuration.
  - `/admin/api/alerts` requires alert source configuration.
- [x] Configure dev backup status source so the Backup card can show non-placeholder state.
- [x] Configure dev alert source so the Alerts card can show non-placeholder state.
- [x] Add a lightweight Relay Admin UI smoke script for login + dashboard APIs.

## 2.1) Client <-> Relay Managed Access

- [x] Define encrypted client payload envelope for `event.body` so relays store and forward ciphertext, not plaintext app content.
- [x] Implement client-side encryption/decryption for the encrypted `event.body` envelope.
- [x] Add relay compatibility tests proving ingest, fetch, export, and federation preserve encrypted `event.body` without mutation.
- [x] Gate plaintext `event.body` compatibility behind explicit dev/test mode.
- [x] Add registry client access-grant bootstrap endpoint and contract aligned with `docs/server/REGISTRY_PROTOCOL.md`.
- [x] Add registry client access-grant renewal / reassignment endpoint aligned with `docs/server/REGISTRY_PROTOCOL.md`.
- [x] Add registry relay-selection policy for managed client access.
- [x] Add registry client abuse-report intake endpoint and scoring/correlation rules for relay candidate selection.
- [x] Add false-report resistance for client abuse reports:
  - grant binding
  - duplicate collapse
  - per-client/per-IP/per-grant rate limiting
  - corroboration thresholds
  - score decay
- [x] Add relay-side enforcement for registry-issued client relay access grants in managed client access mode.
- [x] Define and implement relay pairwise session establishment for managed client access.
- [x] Add relay client-session endpoints for:
  - `POST /v1/client/session:open`
  - `POST /v1/client/session/:session_id:renew`
  - `POST /v1/client/session/:session_id:close`
- [x] Define and implement relay behavior for managed client access on:
  - `POST /v1/events:ingest`
  - `GET /v1/events:fetch`
- [x] Add client-side local relay block behavior for abusive pairwise sessions.
- [x] Add regression tests for missing, expired, wrong-relay and out-of-scope client access grants.
- [x] Add regression tests for expired or mismatched client-relay pairwise sessions.
- [x] Add regression tests for candidate retry/failover with:
  - same valid grant
  - blocked relay
  - relay rejection of `session:open`
  - expired grant before failover
- [x] Add automated managed client access-grant + pairwise-session smoke test.
- [x] Add abuse-report smoke coverage for client block + registry report flow.
- [x] Add Android managed client access runtime baseline:
  - signed registry grant bootstrap
  - candidate selection / failover on `session:open`
  - signed relay pairwise session open / close
  - managed data-plane headers on relay push / pull
  - grant-context capture for automatic abuse-report submission
- [x] Add Android grant/session reuse + renew baseline:
  - persist managed relay binding locally
  - reuse still-valid grant/session across sync runs
  - renew grant/session before expiry
  - reopen relay session from cached grant during short registry outages
- [x] Add Android client-level tests for managed relay access:
  - cached binding reuse
  - grant/session renew near expiry
  - reopen from cached grant during registry outage
  - candidate failover after `session:open` rejection
- [x] Add managed client runtime reauth/retry on recoverable relay auth failures:
  - clear cached binding on `grant_mismatch` / `client_session_expired` / `client_session_token_mismatch`
  - reacquire once and retry the managed relay cycle in the same sync run
  - cover success and retry-failure outcomes with JVM tests
- [x] Add build-time Android managed relay config injection:
  - read registry/relay managed sync config from `android/local.properties`
  - allow environment-variable and `-P...` overrides for CI / release builds
- [x] Update Android cloud-sync status UI for registry-managed mode:
  - treat registry-managed bootstrap as a valid cloud-sync path even without direct relay seed URLs
  - surface registry-managed readiness in the Statistics page
  - stop showing the header relay icon as paused when managed cloud sync is fully configured
- [x] Allow Android relay scheduler/service startup in registry-managed mode without direct relay seed URLs.
- [x] Add Android known-relay fallback for managed cloud sync:
  - remember relays that have completed sync successfully
  - expire known-relay entries after 30 days
  - keep registry as the primary candidate source
  - fall back to known relays only when managed bootstrap / reauth through registry fails
- [x] Remove Android managed-cloud-sync dependence on static relay config:
  - managed mode now requires only the registry base URL plus registry request-signing secret
  - managed relay data-plane requests authenticate via registry grant + relay-issued session token
  - direct relay URL / relay request-signing secret remain legacy inputs for non-managed/private relay mode only
- [x] Enforce Android transport boundary between BLE mesh and managed cloud sync:
  - feature/UI code no longer calls direct relay push for `CHAT.MSG`, map events, or live trail events
  - relay sync owns cloud push/pull on its own tick
  - `SYS.MEMBER.REMOVED` is treated as a bridgeable system event for relay propagation
  - `SYS.ACK` cloud propagation uses a separate in-memory buffer flushed during relay ticks
  - normal relay foreground sync cadence reduced to `15s` after removing direct relay fast paths
- [x] Validate current BLE <-> cloud bridge policy with 3-device field rounds:
  - topology `A=cloud only`, `B=BLE+cloud`, `C=BLE only` behaved as expected
  - when dual-homed bridge was available and enabled, cloud-originated events could reach BLE-only peers
  - when the only dual-homed bridge had `cloud -> BLE bridge` disabled, `A -> C` correctly did not propagate while `B -> C`, `C -> B`, and BLE -> cloud fan-out still worked
  - the last two rounds therefore confirm bridge behavior is policy-driven, not accidental over-forwarding
- [x] Add BLE -> cloud bridge on dual-homed Android nodes:
  - relay sync may republish BLE-originated user events from local DB into cloud relay sync
  - keep BLE foreground service behavior unchanged
  - add dedupe / replay protection so BLE-originated events are not re-bridged indefinitely
  - define which event types are bridgeable and which remain local-only / transport-local
- [x] Add cloud -> BLE bridge on dual-homed Android nodes:
  - BLE mesh outbox may republish relay-originated user events into BLE broadcast on eligible nodes
  - keep relay/cloud foreground service behavior unchanged
  - add loop prevention so cloud-originated events do not bounce indefinitely between BLE and cloud
  - define ACK / metadata bridge policy separately from `CHAT.MSG`, map, and live trail events
  - current baseline bridges relay-originated chat, map, live trail, and `SYS.MEMBER.REMOVED` events; `SYS.ACK` and other `SYS.*` metadata remain excluded
  - bridge is gated by peer-reported `cloud_state` from BLE `hello`; missing hint does not trigger bridge
  - feature is user-controlled in Settings and defaults to `OFF`

## 3) Relay Protocol / Federation Hardening

- [x] Align relay event envelope implementation with `../specs/RELAY_PROTOCOL_SPEC.md` and `../server/FEDERATION_PROTOCOL.md`.
- [x] Resolve current opaque-body drift: decide whether `body` is canonical bytes/base64 or a JSON object and update docs/code accordingly.
- [x] Remove relay dependence on semantic fields inside event `body` for replay protection.
- [x] Add relay request replay cache keyed by timestamp + signature or timestamp + request target + body hash.
- [x] Replace optional `REQUEST_SIGNATURE_ENABLED` with signature-required managed/production semantics.
- [x] Add explicit unsigned dev/test escape hatch such as `ALLOW_UNSIGNED_DEV_REQUESTS=1`.
- [x] Fail relay startup when signature-required mode is active and `REQUEST_SIGNATURE_SECRET` is missing.
- [x] Log a high-visibility warning when unsigned dev/test mode is enabled.
- [x] Keep health checks, static assets and explicitly public read-only endpoints outside signature enforcement.
- [x] Ensure app write/private fetch requests require signatures in managed/private relay mode.
- [x] Keep federation data-plane auth separate from app request signatures and use registry-issued federation sessions.
- [x] Make ingest retry/idempotency safe when transient DB errors happen after validation.
- [x] Make nonce/replay checks atomic where Redis or DB-backed replay protection is used.
- [x] Make `REQUEST_SIGNATURE_ENABLED=1` compatible with authenticated federation fetches.
- [x] Add regression tests for private relay request signatures plus federation sync.
- [x] Add regression tests for DB failure followed by retrying the same event.
- [x] Introduce peer trust/quarantine states beyond simple enabled/disabled where needed.
- [x] Add per-peer federation abuse score/quota metrics.
- [x] Document recommended production federation mode as permissioned federation, not public open federation.

## 3.1) Managed Federation Session Security

- [x] Add registry federation session table/model:
  - `session_id`
  - source relay
  - assigned peers
  - allowed gids
  - permissions
  - limits
  - issued_at
  - expires_at
  - policy_version
  - revoked_at
- [x] Add registry session issuance endpoint:
  - `POST /v1/registry/federation/sessions`
- [x] Add registry session renewal endpoint:
  - `POST /v1/registry/federation/sessions/:session_id:renew`
- [x] Add registry session revocation endpoint:
  - `POST /v1/registry/federation/sessions/:session_id:revoke`
- [x] Add registry session grant signing.
- [x] Add short-lived session tokens scoped by source relay, destination relay, operation, gid and expiry.
- [x] Add registry topology policy engine for assigning eligible peers.
- [x] Enforce minimum trust/status/certificate policy before issuing sessions.
- [x] Enforce session TTL and deny expired or revoked sessions.
- [x] Add audit events:
  - `federation_session_issued`
  - `federation_session_denied`
  - `federation_session_renewed`
  - `federation_session_revoked`
  - `federation_topology_rebalanced`
- [x] Add relay registry session client.
- [x] Add relay-side active session cache.
- [x] Add relay-side validation for source relay, destination relay, operation, gid, expiry and registry signature.
  - Inbound push validation is implemented.
  - Scoped reconciliation validation is implemented.
- [x] Gate all managed federation traffic behind active registry-issued sessions.
  - Push data-plane is gated by registry-signed peer grant.
  - Managed reconciliation uses active registry session grants with `pull_reconcile`.
- [x] Deprecate static/manual peer mappings for managed federation mode.
  - Static/manual federation remains available only when `REGISTRY_FEDERATION_SESSIONS_ENABLED=0`.
- [x] Keep private registry mode available for self-hosted independent networks.
- [x] Add full tests for token tampering against relay-side validation.
- [x] Add registry baseline tests for session issue, renew, revoke, expiry and policy denial.

## 3.2) Push-First Federation Data Plane

- [x] Add relay push endpoint:
  - `POST /v1/federation/events:push`
- [x] Require active federation session headers:
  - `X-Federation-Session`
  - `X-Federation-Token`
  - `X-Federation-Timestamp`
  - `X-Federation-Signature`
  - Headers are required and inbound push now validates registry-signed peer grants.
- [x] Add push queue that runs after successful local ingest.
- [x] Fan out only to peers assigned by the active registry session.
- [x] Enforce per-session event count, byte count, in-flight and rate limits.
- [x] Make push failures non-blocking for app ingest.
- [x] Add retry/backoff for push failures.
- [x] Keep cursor-based reconciliation only inside active session with `pull_reconcile` permission.
- [x] Reject reconciliation when gid or operation is outside session scope.
- [x] Invalidate cached active sessions immediately when the registry revocation feed marks that `session_id` revoked.
- [x] Add metrics for:
  - push success
  - push failure
  - push queue depth
  - session validation failures
  - scoped reconciliation authorization / validation failures

Push transport and inbound push validation metrics are implemented.
Reconciliation validation metrics remain pending.
- [~] Add tests for duplicate push, expired event, unauthorized gid, unauthorized peer and revoked session.
  - Inbound push tests cover arbitrary caller token, expired grant, unauthorized gid and wrong destination.
  - Revoked session feed/test remains pending.

## 3.3) Moderation V1 Baseline

Purpose:
- Add the minimum moderation and user-safety controls needed for Google Play UGC / direct-interaction policy posture.
- Keep the implementation privacy-preserving, local-first, and compatible with encrypted/offline operation.

Status legend:
- `[x]` complete
- `[~]` in progress or partially complete
- `[ ]` not started

- [x] Implement tính năng block cho user.
  Scope:
  - local-only block
  - ẩn tin nhắn của peer đó khỏi chat của người block
  - hiệu lực offline-first
  - không tự động tác động đến toàn group
  Status:
  - chat flow implemented
  - blocked peers list + unblock entry added in Group Detail
  - manual UI verification passed

- [x] Implement tính năng remove user cho group owner (Member Removal V1).
  Scope:
  - owner-only group governance action
  - phát tán `member_removed` event và áp dụng removed state trên synced clients
  - removed member bị chặn gửi `CHAT.MSG`, `SYS.ACK`, map update, trail share, invite/share invite material và các group event mới
  - removed member chuyển sang read-only đối với group này và không còn moderation / message actions trong chat
  - synced valid members tiếp tục thấy và trao đổi group activity mới với nhau
  - removed member không thể rejoin bằng invitation material hiện có sau khi removal + rekey đã sync
  - không thay thế local block của từng user
  Notes:
  - phạm vi V1 chưa bao gồm owner-approved rejoin
  - enforcement hội tụ theo trạng thái sync; không bảo đảm thu hồi tức thời trên client chưa đồng bộ
  - known issue tạm hoãn: `sys_metadata` profile delivery chưa đủ tin cậy, có thể làm members list thiếu peer dù chat đã nhận message
  - xem thêm `docs/problems/sys_metadata_profile_delivery_gap.md`

- [ ] Implement member removal V2: owner-approved rejoin flow.
  Scope:
  - removed member có thể mở invitation link / QR của member hợp lệ để tạo `rejoin request`
  - request có `rejoin_request_id` riêng và TTL 24h
  - chỉ group owner mới có thể approve rejoin request
  - approval phải bind với đúng `rejoin_request_id`
  - removed status chỉ được clear khi rejoin hoàn tất hợp lệ
  - owner approval không hoạt động như generic bypass cho mọi invite cũ
  Notes:
  - cần update protocol, UX pending approvals và policy/public docs khi implement
  - xem design hiện tại trong `docs/private/remove_group_members/join_by_request_and_member_removal_design.md`

- [x] Implement tính năng report peer (lưu local và gửi lên cloud).
  Scope:
  - tạo abuse report từ member identity
  - lưu queue local trước
  - sync nền khi có network path
  - disclosure rõ dữ liệu nào sẽ được gửi
  Status:
  - local queue + client sync path implemented
  - manual UI verification passed

- [x] Implement tính năng report message (lưu local và gửi lên cloud).
  Scope:
  - tạo abuse report gắn với message cụ thể
  - mặc định metadata-only
  - plaintext chỉ gửi khi user opt-in
  - sync nền khi có network path
  Status:
  - local queue + client sync path implemented
  - manual UI verification passed

- [x] Implement `client_moderation_reports` workspace trên registry cho `report peer` và `report message`.
  Scope:
  - dùng `registry` làm moderation-report intake authority
  - thêm bảng riêng `client_moderation_reports`
  - thêm route `POST /v1/registry/client/moderation-reports`
  - validate payload `peer|message` theo contract hiện tại của Android
  - hỗ trợ idempotent retry theo `report_id`
  - ghi audit events cho accept / duplicate / deny / operator actions
  - không replicate report qua relay federation data-plane
  - thêm admin list/detail retrieval path cho operator review
  - thêm operator workflow tối thiểu: đổi status và thêm note
  Status:
  - registry persistence + ingest route implemented
  - admin list/detail endpoints implemented
  - operator status transition + note endpoints implemented
  - minimal admin UI page implemented
  - smoke coverage passed cho ingest, duplicate handling, admin retrieval, operator workflow và admin page routing
  - reporter `source_ip` is minimized:
    - not stored in primary moderation report evidence
    - not shown to operators
    - not exported with report evidence
    - used only for transient rate limiting
  Remaining:
  - none for the baseline registry workspace; broader Moderation V2 hardening remains tracked below
  References:
  - `docs/backlogs/registry-report-workspaces-overview.md`
  - `docs/backlogs/client-moderation-reports-workspace-spec.md`

- [~] Moderation V2 task map.
  Purpose:
  - hoàn thiện moderation và group governance sau baseline V1
  - làm cho report pipeline, membership UX và control-event reliability đủ chặt để có thể coi moderation là coherent end-to-end
  References:
  - `docs/backlogs/moderation-v2-backlog.md`
  - `docs/backlogs/client-moderation-reports-workspace-spec.md`
  - `docs/public/peermesh-moderation-policy.md`
  - `docs/public/peermesh-group-membership-and-removal-policy.md`
  Hardening split for milestone planning:
  - Must-have để coi `Moderation V2 hardening` là complete trên Gantt:
    - owner-approved rejoin flow hoạt động end-to-end và bind đúng với `rejoin_request_id`
    - removed user không thể bypass bằng invitation material cũ
    - critical control events cho remove / rekey / rejoin converge ổn định qua repeated BLE runs
    - members list / governance state đủ đáng tin cho owner moderation decisions
    - public policy docs phản ánh đúng shipped behavior
    - moderation report pipeline cho `report peer` / `report message` có operator-facing outcome rõ ràng
  - Nice-to-have có thể làm sau khi đóng milestone:
    - richer diagnostics cho mixed success / failure moderation-report batches nếu ActivityLog hiện tại đã đủ dùng
    - additional UX polish hoặc fallback states không chặn governance correctness
  Workstream A: Rejoin protocol and state machine
  - [ ] Must-have: Finalize `SYS.MEMBER.REJOIN.REQUEST` payload, identity binding, TTL 24h, duplicate suppression, và anti-spam rules.
  - [ ] Must-have: Finalize `SYS.MEMBER.REJOIN.APPROVED` payload, owner-only authorization, request binding qua `rejoin_request_id`, và TTL 24h.
  - [ ] Must-have: Define local DB state cho `removed`, `pending rejoin`, `approved`, `expired`, `rejoined`.
  - [ ] Must-have: Implement removed-state clearing rules chỉ sau successful rejoin completion.
  - [ ] Must-have: Add tests cho request creation, approval, expiry, duplicate suppression, non-owner denial, và stale invite rejection.
  Workstream B: Critical control-event transport hardening
  - [ ] Must-have: Prioritize `SYS.MEMBER.REMOVED`, `SYS.GROUP.REKEY`, `SYS.MEMBER.REJOIN.REQUEST`, `SYS.MEMBER.REJOIN.APPROVED` như critical control traffic.
  - [ ] Must-have: Add stronger resend / replay behavior cho các control events trên.
  - [ ] Must-have: Add long retention policy cho critical control events trong degraded/offline conditions.
  - [ ] Nice-to-have: Add event-level observability từ creation tới ingest cho removal, rekey và rejoin flow.
  - [ ] Must-have: Add repair behavior khi client thiếu prerequisite control events.
  - [ ] Must-have: Add repeated real-device BLE transport test matrix cho remove / rekey / rejoin flows.
  Workstream C: Membership state consistency and governance UX
  - [ ] Must-have: Unify active-member derivation logic across members list, group settings và owner moderation views.
  - [ ] Must-have: Harden `sys_metadata` / profile delivery path để members list không thiếu peer khi group vẫn có activity.
  - [ ] Must-have: Add fallback member derivation khi profile metadata đến muộn hoặc thiếu.
  - [ ] Must-have: Ensure removed / rejoined state phản ánh nhất quán trên các UI surfaces.
  - [ ] Must-have: Implement owner rejoin-request review surface.
  - [ ] Must-have: Show clear states cho `removed`, `pending rejoin`, `approved`, `expired`, `rejoined`.
  - [ ] Nice-to-have: Add fallback / disabled-state UX khi deep links mở vào stale membership screens.
  - [ ] Must-have: Inventory mọi moderation entry point và apply capability gating nhất quán cho removed users trên chat, map, trail, member cards và settings.
  - [ ] Nice-to-have: Chốt canonical owner-governance flow và canonical personal-safety flow.
  Workstream D: Moderation report pipeline completion
  - [x] Complete `client_moderation_reports` registry workspace.
    Completed:
    - [x] backend ingest route and persistence
    - [x] schema and validation for peer / message reports
    - [x] idempotent duplicate handling by `report_id`
    - [x] admin / operator list-detail retrieval path
    - [x] operator status-transition actions
    - [x] operator notes support
    - [x] minimal admin UI page
    - [x] audit coverage for ingest and operator workflow
    - [x] Android client parse richer server response semantics for moderation-report submit path
    - [x] reporter `source_ip` excluded from operator evidence/export and retained only in transient rate-limit events
    Remaining:
    - none for the baseline report workspace
  - [~] Improve Android retry semantics cho moderation reports:
    - bounded retry or exponential backoff
    - clearer transient vs permanent failure handling
    - better diagnostics for mixed success / failure batches
    Status:
    - one-time moderation sync work now uses exponential WorkManager backoff
    - `retry_waiting` reports now honor bounded per-report retry delay based on `attemptCount` and `lastAttemptAtMs`
    - transient vs permanent failure handling remains aligned with HTTP status contract
    Remaining:
    - Nice-to-have: richer diagnostics for mixed success / failure batches if current ActivityLog detail is still insufficient
  - [~] Add report delivery / retry tests covering offline restart, transient failure, permanent failure, và duplicate retry.
    Status:
    - added unit coverage cho retry eligibility, exponential retry delay, duplicate-retry success classification, transient failure classification, và permanent failure classification
    Remaining:
    - [ ] Must-have: offline restart coverage end-to-end
    - full app unit-test run is currently blocked by unrelated compile failures in existing `NearbyRoomBleCodecTest`
  - [ ] Must-have: Ensure operator-facing evidence rằng report đã được accepted hoặc permanently failed.
  Workstream E: Copy, policy, and review readiness
  - [ ] Must-have: Refine user-facing copy để giải thích rõ local block vs owner remove vs rejoin request / approval.
  - [ ] Must-have: Explain delayed convergence in offline conditions mà không làm giảm trust.
  - [x] Keep public moderation policy aligned với shipped behavior.
  - [ ] Must-have: Keep public group membership / removal policy aligned với shipped behavior.
  - [ ] Nice-to-have: Prepare concise Google-review explanations cho Moderation V2 changes.
  Manual QA gate
  - [ ] Must-have: Owner removes B, B requests rejoin, owner approves, B rejoins successfully.
  - [ ] Must-have: Owner removes B, approval expires, B remains removed.
  - [ ] Must-have: Non-owner tries to approve rejoin and fails.
  - [ ] Must-have: Removed user scans old invite and cannot bypass owner approval.
  - [ ] Must-have: Valid member invites new user after removal + rekey without breaking existing member visibility.
  - [ ] Must-have: Members list remains correct across A / B / C after remove, rekey, and rejoin.
  - [ ] Must-have: Moderation report still works correctly through removal / rejoin edge cases.
  Exit bar
  - [ ] Owner-approved rejoin works and is request-bound.
  - [ ] Removed users cannot re-enter through stale invite material.
  - [ ] Critical control events converge reliably across repeated BLE runs.
  - [ ] Members list is trustworthy for owner governance.
  - [x] `report peer` / `report message` are end-to-end complete with backend ingest.
  - [ ] Public policy docs match actual shipped behavior.

- [ ] Implement màn hình Group Settings.
  Scope:
  - hiển thị members list
  - mỗi member item có nút `Remove` chỉ dành cho group owner
  - hiển thị `Blocked peers`
  - thêm action đóng hoặc xóa group với copy được chốt theo product decision
  Notes:
  - ưu tiên chuyển các chức năng quản trị/xã hội ra khỏi `Group Detail`
  - `Group Detail` nên tập trung vào metadata, profile, invite và thông tin group
  - tổ chức chức năng theo role:
    - với member thường: `Leave group`
    - với group owner: `Delete group`, `Remove member`
    - với mọi người: `Members`, `Blocked peers`
  Follow-up:
  - sau khi chốt core removal flow, quay lại xử lý known issue về độ tin cậy của `sys_metadata` / profile sync cho members list

- [~] Prepare Nearby Room moderation baseline.
  Purpose:
  - chuẩn bị scope moderation riêng cho Nearby Room mà không reuse máy móc Private Group governance
  - giữ Nearby Room moderation local-first, room-scoped và phù hợp với room TTL 30 phút / BLE-first transport
  Rollout order đã chốt:
  1. local block user
  2. report user
  3. report message
  4. owner mute in room
  References:
  - `docs/specs/NEARBY_ROOM_BLUEPRINT.md`
  - `docs/moderation/peermesh-play-moderation-model.md`
  - `docs/moderation/peermesh-android-moderation-implementation-spec.md`
  - `docs/public/peermesh-moderation-policy.md`
  Scope split:
  - [x] Must-have: Audit lại Nearby Room data model, reducer path, transport envelope và UI entry points để xác nhận phần nào tái sử dụng trực tiếp từ Private Group moderation, phần nào phải room-specific.
  - [x] Must-have: Define canonical local block semantics cho Nearby Room:
    - local-only trên device người block
    - hide message của blocked participant trong room đó
    - suppress invitation actions từ blocked participant trên device đó nếu có identity context
    - không tương đương kick/remove khỏi room
  - [x] Must-have: Define `report peer` contract cho Nearby Room:
    - `roomId`
    - `reportedPeerId`
    - `reporterPeerId`
    - `reasonCode`
    - timestamp
    - optional `messageId`
    - default metadata-only
  - [x] Must-have: Define `report message` contract cho Nearby Room:
    - bind đúng với `messageId`
    - optional plaintext chỉ khi user opt-in
    - reuse tối đa moderation report pipeline hiện tại nếu contract vẫn coherent
  - [x] Must-have: Define owner `mute in room` semantics cho Nearby Room:
    - room-scoped only
    - expires with room
    - không tạo durable membership revocation
    - không reuse `member_removed` hoặc Private Group rekey flow
    - propagate bằng mute/unmute event và enforce ở event/message layer, không drop BLE envelope
  - [x] Must-have: Decide persistence strategy cho Nearby Room moderation state:
    - có cần table riêng hay chỉ cần scope/target type mới trong existing moderation entities
    - TTL cleanup behavior khi room expire
    - migration requirement nếu schema đổi
  - [x] Must-have: Inventory UI surfaces cho Nearby Room moderation:
    - participant list
    - message overflow
    - owner-only participant actions
    - blocked/muted state copy
  - [x] Must-have: Add debug logging plan cho Nearby Room moderation propagation và reducer decisions theo cùng standard đã dùng cho edit/delete flow.
  - [ ] Must-have: Add implementation checklist + test matrix riêng cho Nearby Room moderation trước khi bắt đầu code.
  Non-goals for this baseline:
  - kick user khỏi Nearby Room
  - durable ban vượt quá room lifetime
  - complex moderator roles
  - server-authoritative membership enforcement cho Nearby Room

## 4) Registry Runtime Baseline

- [x] Confirm registry service runs as a separate Node + SQLite service.
- [x] Confirm registry container is exposed on host port `8090`.
- [x] Confirm registry `/healthz` works.
- [x] Confirm registry accepts signed relay heartbeats from relay services.
- [x] Confirm registry discovery API returns active relays:
  - `/v1/registry/relays`
  - `/v1/registry/relays/:relay_id`
- [x] Confirm relay heartbeat client registers both local dev relays in registry.
- [x] Add registry readiness endpoint coverage to smoke scripts.
- [x] Add registry backup guidance for SQLite database file.

## 5) Registry Protocol Alignment

- [x] Align registry request signing implementation with `docs/server/REGISTRY_PROTOCOL.md`.
- [x] Migrate from current nonce + raw-body canonical signing to documented body-hash signing:
  - `METHOD`
  - `REQUEST_TARGET`
  - `TIMESTAMP_SECONDS`
  - `SHA256(raw_body_bytes)`
- [x] Update relay heartbeat client signing to match the registry protocol.
- [x] Keep a backward-compatible transition window or migration flag if existing deployments need it.
- [x] Add tests for invalid signature, timestamp skew, body tampering, and replay behavior.
- [x] Add `protocol_version` and `registry_version` to discovery responses if required by the protocol spec.
- [x] Confirm registry discovery omits suspended, revoked, and banned relays.

## 6) Registry Governance Data Model

- [x] Add registry governance fields to relay records:
  - `trust_level`
  - `status`
  - `federation_enabled`
- [x] Add SQLite auto-migration for existing registry DB files.
- [x] Add registry audit table.
- [x] Add admin store methods for:
  - list all relays
  - update relay trust level
  - update relay status
  - update federation eligibility
  - dashboard counts
  - revocation feed view
  - policy view
  - audit log
- [x] Verify schema migration works against existing dev registry DB.
- [x] Add certificate table/model for relay identity certificates.
- [x] Add certificate issue/renew/revoke persistence.
- [x] Add policy persistence instead of returning a hard-coded policy view.
- [x] Add revocation feed rebuild persistence/versioning.

## 7) Registry Admin API

- [x] Add session-based Registry Admin auth.
- [x] Add admin login/logout/session endpoints:
  - `POST /registry/admin/api/auth/login`
  - `POST /registry/admin/api/auth/logout`
  - `GET /registry/admin/api/auth/me`
- [x] Add dashboard endpoint:
  - `GET /registry/admin/api/dashboard`
- [x] Add relay directory endpoints:
  - `GET /registry/admin/api/relays`
  - `GET /registry/admin/api/relays/:id`
- [x] Add governance mutation endpoints:
  - `PATCH /registry/admin/api/relays/:id/trust`
  - `PATCH /registry/admin/api/relays/:id/status`
  - `PATCH /registry/admin/api/relays/:id/federation`
- [x] Add revocation feed endpoint:
  - `GET /registry/admin/api/revocations`
  - `POST /registry/admin/api/revocations/rebuild`
- [x] Add policy feed endpoint:
  - `GET /registry/admin/api/policy`
- [x] Add audit endpoint:
  - `GET /registry/admin/api/audit`
- [x] Verify login + dashboard + relay directory APIs in dev container.
- [x] Verify governance mutation writes audit records.
- [x] Add certificate admin APIs:
  - `GET /registry/admin/api/certificates`
  - `POST /registry/admin/api/certificates`
  - `PATCH /registry/admin/api/certificates/:id/renew`
  - `PATCH /registry/admin/api/certificates/:id/revoke`
- [x] Add trust history endpoint.
- [x] Add pagination/search/sort query support for relay directory and audit APIs.
- [x] Add CSRF protection for Registry Admin mutations.
- [x] Replace plaintext dev admin password config with a file-backed or hashed credential model.
- [x] Add federation session admin APIs:
  - `GET /registry/admin/api/federation/sessions`
  - `GET /registry/admin/api/federation/sessions/:id`
  - `POST /registry/admin/api/federation/sessions/:id/revoke`
  - `POST /registry/admin/api/federation/topology/rebalance`
- [x] Add relay onboarding bundle export API for registry-owned secrets with mandatory reason and audit trail.

## 8) Registry Admin UI

- [x] Create Registry Admin static UI folder:
  - `server/registry/src/public/admin-registry/`
- [x] Add Registry Admin dashboard page at `/registry/admin`.
- [x] Add Relay Directory page at `/registry/admin/relays`.
- [x] Add shared Registry Admin CSS.
- [x] Add shared Registry Admin JS.
- [x] Add Relay Onboarding Bundle panel for one-time relay operator handoff:
  - relay ID / label input
  - registry-managed section selection
  - mandatory reason
  - copy / download `.env` snippet
- [x] Implement login/logout flow.
- [x] Implement dashboard KPI cards.
- [x] Implement relay directory table with search.
- [x] Implement promote/demote trust workflow and status action buttons.
- [x] Implement policy feed display.
- [x] Implement revocation feed display.
- [x] Implement audit log display.
- [x] Verify static routes serve HTML/JS/CSS.
- [~] Add dedicated pages for:
  - Federation Sessions
  - Federation Topology
  - Trust Management
  - Revocations
  - Policy Feed
  - Audit Log
  - Metrics
  - Settings
- [x] Add Certificates page.
- [x] Add relay detail page.
- [x] Add confirmation dialogs with mandatory reason for dangerous actions.
- [x] Add explicit reason requirement for session revocation and topology rebalancing.
- [x] Add pagination and sorting to large tables.
- [x] Add empty/error/loading states for all panels.
- [x] Add visual design pass for mobile and dense operator workflows.

## 9) Registry Operations

- [x] Verify registry image build succeeds after adding Admin UI assets.
- [x] Restart registry container and confirm it remains healthy.
- [x] Confirm registry logs show heartbeats and no startup crash after schema migration fix.
- [x] Add `REGISTRY_ADMIN_USERNAME`, `REGISTRY_ADMIN_PASSWORD`, and session TTL documentation to env examples.
- [x] Add registry admin credentials rotation guide.
- [x] Add registry SQLite backup/restore runbook section.
- [x] Add explicit SQLite maintenance-window checklist for registry deployment/rollback work.
- [x] Add registry deployment rollback checks for schema auto-migration.
- [x] Add registry metrics endpoint or admin metrics endpoint.
- [x] Add registry audit export.
- [x] Add production guidance for running Registry Admin behind HTTPS and additional access controls.
- [x] Add federation session signing key rotation runbook.
- [x] Add registry incident response runbook for compromised relay session.
- [x] Add policy rollback procedure for bad topology/session policy publish.
- [x] Validate public registry baseline deployment is live at `https://registry.peermesh.app`.
- [x] Validate first public relay baseline deployment is live at `https://relay.peermesh.app`.

## 10) Integration Tests / Smoke Tests

- [x] Manual smoke: registry `/healthz`.
- [x] Manual smoke: registry admin static dashboard route.
- [x] Manual smoke: registry admin login.
- [x] Manual smoke: registry admin dashboard API.
- [x] Manual smoke: registry admin relay directory API.
- [x] Manual smoke: registry governance trust update + audit.
- [x] Manual smoke: public registry discovery still works after governance schema changes.
- [x] Add automated registry smoke script for:
  - health
  - login
  - dashboard
  - relay directory
  - policy
  - revocations
  - audit
- [x] Add automated signed heartbeat compatibility test.
- [x] Add relay request replay-cache test.
- [x] Add registry federation session API smoke test.
- [~] Add relay federation push smoke test with registry-issued session.
  - Unit coverage completed.
  - Container-level multi-relay federation smoke script is implemented and passing.
- [x] Add revoked session rejection test.
- [x] Add unauthorized gid rejection test.
- [x] Add automated registry schema migration test using an old DB fixture.
- [x] Add browser-level smoke for Registry Admin UI.
- [x] Add relay + registry integration test in dev multi-profile Compose.
- [x] Manual smoke: public website baseline is live at `https://peermesh.app`.
- [x] Manual smoke: public registry and first relay are now the default externally reachable test endpoints.

## 11) Next Priority Tasks

Rules for this section:

* This section lists only the 5 highest-priority tasks to do next.
* Every task must also remain tracked and ticked in its own section above.
* After finishing one task:
  * remove it from this section
  * tick it in the section it belongs to
  * pull the next highest-priority task into this section

Current top 5:

1. Harden Registry Admin auth before production.
2. Finish hybrid client-managed relay access runtime behind the documented architecture.
3. Revisit Relay Admin backup and alert production source configuration.
4. Scope or replace relay federation trust/quarantine policy under registry-issued session policy.
5. Complete remaining dedicated Registry Admin pages and settings/operator polish.

## 12) Known Gaps / Follow-Up Notes

- [x] Registry signing has been migrated to the updated body-hash protocol model.
- [ ] Registry Admin auth is session-based but intentionally minimal; harden before production.
- [x] Registry certificate lifecycle model/API/UI baseline is implemented.
- [x] Registry policy feed baseline is now persisted in SQLite and applied to discovery/federation decisions.
- [x] Registry revocation feed baseline is now a persisted versioned snapshot published through rebuild.
- [x] Registry-issued managed federation session baseline is implemented.
- [x] Relay registry session client/cache baseline is implemented.
- [x] Public deployment baseline exists for website, registry, first relay, and contact mailbox.
- [~] Hybrid client-managed relay access architecture now has Android runtime baseline plus grant/session reuse and renew, but still needs more production hardening around long-lived session lifecycle polish, broader Android coverage, and multi-window continuity tests.
- [x] Client local relay block runtime is implemented; server-side abuse-report smoke coverage is done, and Android now captures managed grant context for automatic report submission after local abuse blocks.
- [x] Relay push-first federation transport path is implemented:
  - outbound push queue
  - signed federation push requests
  - inbound federation push endpoint
  - federation route tests

- [x] Inbound federation push endpoint validates registry-signed peer grants before ingest.
- [x] Managed federation session enforcement baseline:
  - source relay validation for inbound push is implemented
  - destination relay validation for inbound push is implemented
  - gid scope validation for inbound push is implemented
  - operation validation for inbound push is implemented
  - session expiry validation for inbound push is implemented
  - registry signature validation for inbound push is implemented
  - revoked session validation is implemented through registry revocation feed cache
  - cached active sessions are invalidated locally as soon as revocation feed polling sees that `session_id`
  - source relay fanout stops once the active session is invalidated
  - reconciliation scoping is implemented with `pull_reconcile`
- [x] Relay request signing lacks a request-level replay cache.
- [x] Relay request signing is currently controlled by optional `REQUEST_SIGNATURE_ENABLED`; managed/production mode should require signatures by default.
- [x] Relay still uses `body.nonce` for optional replay checks; this should be removed to preserve opaque payload semantics.
- [~] Android BLE DTN sync now has tiered inventory priority for fresh chat traffic:
  - implemented `high -> normal -> low` inventory tiers with recent chat message/reply/edit/delete metadata in `high`
  - `INV_HAVE` / `INV_WANT` now advance tier-by-tier so old backlog and `SYS.ACK` do not preempt new chat events
  - remaining gap: advertised DTN cursor still persists too early, so interrupted sessions can still skip items until cursor commit is tied to confirmed completion
- [ ] Relay Admin UI backup and alert cards need configured sources in dev/production.
- [ ] Relay federation trust/quarantine policy should be replaced or scoped by registry-issued session policy.
