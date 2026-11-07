# Phase 2 Task List (Mobile Clients)
## Phase 2.1: Android (reference implementation)

## 1) Project Setup
- [x] Android project scaffold (Gradle, flavors, build configs).
- [x] App settings + environment loader (dev/prod).
- [x] Local storage bootstrap (SQLite + migrations).
- [x] Decide JSON library + canonical JSON encoding.
- [x] Decide DB layer (Room/SQLDelight/raw SQLite).
- [x] Define module layout (core/data/transport/security/ui).
- [x] Define shared core module boundaries for event/reducer/clock helpers.
- [x] Core module contents: EventEnvelope, EventCore, EventTypes, Clock/newer, TTL helpers.
- [x] Decide MVP vs Full for pending tables (chat_pending_edits/map_pending_patches).

## 2) Data Model & Crypto
- [x] Event schema + envelope models (event_id = mid).
- [x] Standardize IDs: gid format + event_id (hash-based, per spec).
- [x] Time model: `time.t` epoch seconds + optional `time.lt`.
- [x] Define entity IDs: marker_id/area_id stable and independent of event_id.
- [x] KDF implementation (scrypt or Argon2id) with salt storage.
- [x] MAC canonicalization + verification (HMAC-SHA256).
- [x] Encrypt/decrypt pipeline for envelope payload (enc + sig).
- [x] Anti-replay cache for event_id (per gid, TTL 24h).
- [x] Canonical JSON rules for MAC: envelope without `sig`, stable key ordering, UTF-8.
- [x] Key management: store gid/kid/created_at/updated_at (no plaintext passphrase).
- [x] Passphrase storage policy: secure storage vs re-entry UX.
- [x] Android crypto storage: Keystore/EncryptedSharedPreferences.
- [x] Choose crypto primitives (Tink/libsodium).
- [x] Key id (`kid`) derivation: base64url(truncate(SHA-256(group_key || salt), 8 bytes)) and attach to event meta.

## 3) Storage Layer
- [x] Events table (append-only + unique index).
- [x] Groups table (gid, kid, metadata).
- [x] Materialized tables for chat + map (chat_messages, map_layers, map_markers, map_areas).
- [x] Group-only chat model (remove chat_threads; all messages scoped by gid).
- [x] Map support tables: map_layers, map_marker_field_clock (if per-field LWW).
- [x] Indexes for gid/event_id/created_t/type.
- [x] Reducer transaction wrapper (insert + reduce).
- [x] Event identity alignment (event_id == mid) + dedupe enforcement.
- [x] DB pragmas: WAL, foreign_keys, synchronous=NORMAL.
- [x] Migration scaffolding (v1 create + forward-only).
- [x] Android DAOs/entities: idempotent insert + materialized upserts.

## 4) Ingest Pipeline
- [x] Wire BLE receive callback → `onEnvelopeReceived(blob)`.
- [x] Parse envelope → verify MAC → decrypt → parse EventCore.
- [x] Schema validation (required keys, types, size limits).
- [x] Dedupe check (DB + optional in-memory LRU).
- [x] Transaction: INSERT OR IGNORE events + apply reducer.
- [x] Expired-on-arrival handling (drop or tombstone policy).

## 5) Reducers
- [x] EventFactory for CHAT/MAP core types.
- [x] Chat reducer (MSG/EDIT/DEL, LWW + tie-breaker).
- [x] Chat reducer: handle REPLY + optional pending edits (MVP can skip).
- [x] Map reducer (MARKER/AREA, LWW + tombstone).
- [x] Map reducer: support AREA events + optional LAYER.SET.
- [x] TTL expiry handling (meta.ttl_s precedence).
- [x] Reducer updates: filter active status rules.
- [x] Sys metadata reducer: group name + user profile updates (encrypted in transit).

## 6) BLE Transport (custom BLE transport)
- [x] Implement custom BLE transport (Android).
- [x] Wire send method → `broadcastEnvelope(blob)`.
- [x] BLE envelope broadcast + receive. (With gtag/split header: parse routing header before decrypt)
- [x] Switch BLE payload to GATT write (ADV for discovery only).
- [x] Chunking/reassembly for large payloads.
- [x] Flood control (jitter/backoff).
- [x] Dedupe of chunks and envelopes. (With gtag/split header: dedupe by gtag+mid when available)
- [-] BLE payload size limits + chunk cache TTL. (Tune: payload 2–4 KB, TTL 10–20s) [BL-CHUNK-TTL-TUNE]
- [x] TTL decrement + forward rules (drop if TTL <= 0). (With split header: TTL is in public header)
- [x] Throttling: max forwards per time window. (Consider per-gtag/per-source limits with split header)
- [x] Tag source mesh/server to avoid rebroadcast loops. (Tag should live in public header)
- [x] Android BLE permissions (API 31+ scan/advertise/connect).
- [x] Foreground service + notification channel (if required).
- [x] BLE mesh notification shows traffic totals + rates.
- [x] App lifecycle scan profile (foreground low-latency, background balanced + batch).
- [x] Battery optimization exclusions guidance (UX note).

## 6.1) BLE Mesh Scope Trim
Note:
- Legacy BLE mesh hardening backlog was trimmed from Phase 2.
- Current transport is considered operationally stable enough for this phase.
- Active DTN/carrier runtime work is tracked in `docs/tech_notes/DTN_DEVELOPMENT_NOTES.md` and `docs/milestones/Phase_3_Task_List.md`.
- Keep only Phase 2 items that still affect the Android product surface, release quality, or user-visible behavior.
- [x] Background health check: restart BLE mesh service if killed and conditions allow. (Remove debug logs before production release)
- [x] JSON-safe TTL update (avoid regex-based replace).

## 7) Relay Sync (Online)
- [x] Push new events to relay (opaque).
- [x] Pull by cursor (incremental sync).
- [x] Persist relay push cursor (avoid duplicate push on restart).
- [x] Sync scheduler + retry/backoff with jitter.
- [x] Cursor persistence per relay.
- [x] Move relay scheduler to background Service (Android).
- [x] Clock skew handling + tie-breaker rules in reducers.
- [x] Relay sync only when online (power/data guard).
- [x] Sync mode selector (Normal: online-only, Battery Saver: online-only, 15m interval).
- [x] Source tagging to prevent re-forward loops.
- [x] Seed relays list input (user-provided, Phase 2 scope).
- [x] Admin UI: peer mapping does not require gid (operator-agnostic; relay routes by client gid).
- [x] Limit concurrent relay sync tasks to avoid CPU/network spikes.
- [x] Coalesce overlapping relay sync requests (single-flight + pending run).
- [x] Surface partial success (push ok / pull fail) in status & logs. (Remove debug logs before production release)
- [x] Relay push: mark invalid events (parse_core/missing_sig) and skip on future pushes.
- [x] Relay push: advance cursor past dropped rows to avoid stuck batches.
- [x] Cleanup invalid events after retention window (default 7 days).
- [ ] Support relay ingest `202 queued` semantics in mobile app.
  - Current issue:
    - relay server can return HTTP `202` with `{status:\"queued\", queued:n}` when ingest retry queue accepts the request.
    - app currently parses every `2xx` ingest response as `RelayIngestResponse(accepted,rejected)` and can treat queued acceptance as a failure.
  - Goal:
    - app must distinguish:
      - `200` accepted/rejected result
      - `202` accepted-for-retry / queued result
- [ ] Decide and document relay request-signature compatibility mode.
  - Current issue:
    - relay server supports `REQUEST_SIGNATURE_ENABLED=1`
    - mobile app currently does not send request-signature headers for ingest/fetch
  - Decision needed:
    - either keep public unsigned relay mode for current mobile deployments
    - or implement request-signature support in app before enabling signed mode

### 7.1) Relay Test Deployment (Mini PC / Tailscale Funnel)
- [x] Sync `docker-compose.yml` + `server/` to mini pc (rsync script).
- [x] Use production compose (`docker-compose.yml`) on mini pc.
- [x] `server/start.sh` loads `server/.env` and passes `--env-file`.
- [x] Bind relay/Postgres/metrics ports to `127.0.0.1` only.
- [x] Configure Tailscale Funnel to expose relay over `https://<host>.<tailnet>.ts.net`.
- [x] Update VPN kill-switch to allow loopback + Docker bridge traffic.
- [x] Relay health gate before enabling Funnel on boot.
- [x] Enable auto-create partitions to avoid ingest failures on future timestamps.
- [ ] Decide whether to keep `REQUEST_SIGNATURE_ENABLED=0` for test or implement signature in app.
- [ ] Ensure app seed relay URL uses HTTPS funnel URL (no local IP for remote test).
- [ ] Re-check mobile relay compatibility after `202 queued` and signature-mode decision.
  - Goal:
    - confirm app push/pull works against the current deployed relay policy, not only local default mode

## 8) Android UX/UI Tracking

Note:
- Android UX/UI work is now tracked in `docs/milestones/Phase_3_1_Task_List.md`.
- Phase 2 keeps product/runtime/data/reliability scope only.
- The core Android UI foundation is already implemented; remaining UI polish, copy, validation, and interaction tasks were moved to Phase 3.1.

## 9) Background Jobs
- [ ] Cleanup job (prune expired + old events).
- [ ] Mark expired chat/map entities as inactive/expired.
- [ ] Delete old expired events beyond retention window.
- [ ] Keep tombstones longer than normal events (configurable).
- [ ] Prune pending tables (edits/patches) older than X hours.
- [ ] Sync worker (foreground + periodic background).
- [ ] Backpressure / rate limiting for BLE ingress.
- [ ] Schedule cleanup with WorkManager + in-app timer.

## 9.1) Advanced (Post-MVP)
- [ ] Add pending tables: chat_pending_edits
- [ ] Add pending tables: map_pending_patches
- [ ] Reducer: apply pending edits when base entity arrives
- [ ] Cleanup job: prune pending tables older than X hours
- [ ] SYS.PRESENCE / SYS.ALERT event support

## 10) Testing
- [x] Unit tests: crypto (KDF, MAC canonicalization).
- [ ] Unit tests: reducers (LWW, tombstones).
- [ ] Unit tests: schema validation + out-of-order events.
- [ ] Unit tests: clock compare + TTL expiry behavior.
- [ ] Integration tests: BLE ingest pipeline.
- [ ] Integration tests: relay sync (push/pull).
- [ ] Integration tests: relay ingest `202 queued` handling.
- [ ] Integration tests: relay signed-mode compatibility decision (documented skip or real support).
- [ ] Integration tests: cleanup/compaction (expired rows, pruning).
- [ ] Field tests: 2-3 devices, airplane mode, multi-hop.
- [ ] Field tests: duplicate storm handling/noisy mesh stability.

## 11) Observability
- [ ] Log counters: received/accepted/duplicate/forwarded.
- [ ] Debug screen/log export for QA (optional).

## 12) Docs
- [x] Update Phase 2 operator/dev notes for Android setup.
- [ ] Add test notes for BLE + relay sync flows.

## 13) Definition of Done (Phase 2 Android)

### Core functionality
- [ ] Hai thiết bị Android join cùng group và trao đổi được chat + marker qua BLE.
- [ ] Khi có internet, relay sync push/pull hoạt động và không tạo bản sao.
- [ ] Relay sync remains correct when server responds `202 queued` on ingest.
- [ ] Dedupe hiệu quả khi flood (không nhân bản state).
- [ ] TTL ẩn đúng nội dung hết hạn, cleanup không xoá nhầm dữ liệu còn hạn.
- [ ] Hoạt động ổn khi không có internet.

### Reliability
- [ ] App không crash khi nhận traffic dày.
- [ ] DB bền vững sau restart (state rebuild đúng).
- [ ] BLE chạy ổn ở foreground service, UX có status rõ ràng.
- [ ] Battery usage hợp lý khi chạy foreground.

### Quality & Testing
- [ ] Unit tests xanh cho crypto/reducers/schema validation.
- [ ] Integration tests xanh cho ingest + relay sync.
- [ ] Field test 2-3 thiết bị trong airplane mode + multi-hop.
- [ ] Log counters hiển thị rõ received/accepted/duplicate/forwarded.

## 14) Feedbacks (recent UX fixes)
- [x] Chat send always allowed (offline + nearby=0); insert local message and show in UI.
- [x] Chat input + Send button lock until the newly sent message renders in chat list.
- [x] Chat message delivery status in bubble (sent/delivered/seen).
- [x] Relay push on Send only when relay sync enabled (except Battery Saver mode).
- [x] Chat UI refresh on relay status change (debounced 2s) to reflect delivery status updates.
- [x] Chat message meta/receipt text uses secondary color + smaller font for less emphasis.
- [-] Mitigate Samsung GATT 133 churn: longer cooldown (10–15s), reduce concurrent GATT when 133 repeats, skip connect for 30s on continuous failures.
