# Phase 2 Task List — Android / iOS
Offline-first • Mobile clients • BLE Mesh (Android: custom BLE transport) • Event Log + Reducers • SQLite

> Scope reminder (Phase 2): **Offline-first**, **server optional**, **no blockchain/consensus**.  
> Goal: ship a working offline mesh chat + map-sharing core with optional relay sync.

---

## 0) Project Setup & Shared Conventions (Both Platforms)

### 0.1 Repo / modules
- [ ] Create `core/` module (shared concepts, schemas, reducers) mirrored on both platforms:
  - `EventEnvelope`, `EventCore`
  - `EventTypes` constants
  - `Clock` + `newer()` compare
  - TTL / expiry helpers
- [ ] Decide JSON library and canonical JSON encoding (stable ordering recommended).
- [ ] Decide storage strategy:
  - Android: Room or SQLDelight (or raw SQLite)
  - iOS: GRDB (recommended) or SQLite.swift (or raw SQLite)

### 0.2 Naming & IDs
- [ ] Standardize `gid` format: `base32(hash(passphrase))`
- [ ] Standardize IDs:
  - `event_id`: UUIDv4 (string)
  - `mid`: UUIDv4 (envelope-level) or reuse `event_id` if you want 1 id
  - `marker_id` / `area_id`: stable IDs independent of event id
- [ ] Decide time model:
  - `time.t`: epoch seconds
  - `time.lt`: Lamport optional (keep field but can be null in MVP)

### 0.3 “Out of order” handling policy (MVP vs Full)
- MVP option:
  - [ ] Ignore `pending_*` tables initially (simpler)
- Full Phase 2 option:
  - [ ] Implement `chat_pending_edits` and `map_pending_patches`

---

## 1) BLE Transport Integration

### Shared requirements
- [ ] Wire BLE receive callback → `onEnvelopeReceived(blob)`
- [ ] Wire send method → `broadcastEnvelope(blob)`
- [ ] Implement hop TTL decrement and forward:
  - [ ] Drop if TTL <= 0
  - [ ] Forward only if not seen before (dedupe first)
- [ ] Implement throttling:
  - [ ] Max forwards per time window
  - [ ] Random jitter delay (small) before forwarding (reduces collision)
- [ ] Implement “don’t rebroadcast from server” rule:
  - [ ] Tag events with source = `mesh|server`

### Android tasks
- [ ] Implement custom BLE transport + configure permissions (Bluetooth, location requirements per API level)
- [ ] Background behavior decisions:
  - [ ] Foreground service for scanning/advertising (if needed by SDK/OS policy)
  - [ ] Battery optimization exclusions guidance (UX note)
- [ ] Implement lifecycle hooks:
  - [ ] Start mesh in active group
  - [ ] Pause/stop mesh when no group selected

### iOS tasks
- [ ] Add Berkanan SDK via SPM/CocoaPods (per availability)
- [ ] Configure Bluetooth permissions and background modes as permitted
- [ ] Implement lifecycle hooks similar to Android:
  - [ ] Start mesh when group active
  - [ ] Stop/idle mesh otherwise

---

## 2) Crypto / Group Layer (Local-only)

> Goal: support **private group** by passphrase-derived key.  
> Phase 2 can start with MAC-only, then add encryption; but recommended to include encryption early.

### Shared tasks
- [ ] Implement passphrase → `gid` derivation (hash + base32)
- [ ] Implement passphrase → `group_key` derivation (KDF; store only `gid/kid`, never store plaintext)
- [ ] Implement envelope encryption + MAC:
  - [ ] `enc(ct)` containing serialized EventCore JSON
  - [ ] `sig(mac)` over `(v,gid,mid,ts,ttl,enc)` (exact canonicalization defined)
- [ ] Implement verify → decrypt pipeline:
  - [ ] Verify MAC first (if applicable)
  - [ ] Decrypt ciphertext
  - [ ] Parse JSON EventCore
- [ ] Key management:
  - [ ] Store `gid`, `kid`, `created_at`, `updated_at`
  - [ ] Store passphrase only in secure storage if you choose (Keychain/Keystore), or require re-entry (product decision)

### Android tasks
- [ ] Use Android Keystore / EncryptedSharedPreferences for sensitive material
- [ ] Choose crypto primitives from platform libs (e.g., Tink) or libsodium wrapper

### iOS tasks
- [ ] Use Keychain for sensitive material
- [ ] Choose crypto primitives (CryptoKit or libsodium)

---

## 3) SQLite Schema & DB Layer (Client)

### Shared tasks
- [ ] Implement schema v1:
  - [ ] `groups`
  - [ ] `events` (PK: gid,event_id)
  - [ ] `chat_threads`, `chat_messages`
  - [ ] `map_layers`, `map_markers`, `map_areas`
  - [ ] Optional: `chat_pending_edits`, `map_pending_patches`, `map_marker_field_clock`
- [ ] Apply recommended pragmas:
  - [ ] WAL enabled (where applicable)
  - [ ] `foreign_keys=ON` (if you use FKs)
  - [ ] `synchronous=NORMAL` (mobile-friendly)
- [ ] Implement DB migrations:
  - [ ] v1 create
  - [ ] forward-only migration scaffolding

### Android tasks
- [ ] Choose DB framework:
  - [ ] Room (with manual SQL for performance) **or**
  - [ ] SQLDelight
- [ ] Define entities & DAOs for:
  - [ ] events insert (idempotent)
  - [ ] materialized upserts for chat/map
  - [ ] queries for UI

### iOS tasks
- [ ] Choose DB framework:
  - [ ] GRDB (recommended) **or**
  - [ ] SQLite.swift
- [ ] Define migrations + repositories for same operations as Android

---

## 4) Ingest Pipeline (Transactional Reducer)

### Shared tasks
- [ ] Implement `ingestEnvelope(blob)`:
  1) parse envelope
  2) dedupe check (events PK)
  3) verify + decrypt
  4) validate event fields (gid/type/id/time)
  5) DB transaction:
     - insert into `events` (INSERT OR IGNORE)
     - apply reducer to materialized tables
     - commit
- [ ] Maintain in-memory LRU seen cache (optional optimization):
  - [ ] `seen(mid)` for quick drop before DB
- [ ] Implement strict schema validation:
  - [ ] required keys, type correctness, size limits (defensive)
- [ ] Implement “event too old” / “expired on arrival” behavior:
  - [ ] still store tombstone events for a short window (optional)
  - [ ] or drop expired non-critical events (MVP)

---

## 5) Chat: Data Layer Tasks

### Shared tasks
- [ ] Implement EventFactory for:
  - [ ] `CHAT.MSG`
  - [ ] `CHAT.REPLY`
  - [ ] `CHAT.EDIT` (optional MVP)
  - [ ] `CHAT.DEL`
- [ ] Implement reducer apply rules in DB:
  - [ ] upsert thread metadata (`chat_threads`)
  - [ ] insert message row (`chat_messages`)
  - [ ] LWW edit handling
  - [ ] tombstone delete
  - [ ] optional pending edit if message not present
- [ ] Queries for UI:
  - [ ] list threads sorted by last update
  - [ ] list messages by thread in chronological order
  - [ ] filter status = active (and optionally show deleted placeholders)

---

## 6) Map: Data Layer Tasks

### Shared tasks
- [ ] Implement EventFactory for:
  - [ ] `MAP.MARKER.ADD`
  - [ ] `MAP.MARKER.UPD`
  - [ ] `MAP.MARKER.DEL`
  - [ ] `MAP.AREA.ADD/UPD/DEL`
  - [ ] `MAP.LAYER.SET` (optional)
- [ ] Implement reducer apply rules in DB:
  - [ ] `map_markers` insert/upsert
  - [ ] LWW per-field (optional) via `map_marker_field_clock`
  - [ ] pending patch (optional)
  - [ ] tombstone delete
- [ ] Queries for UI:
  - [ ] list active markers by layer
  - [ ] geo bounding box query (optional performance)
  - [ ] category filter

---

## 7) TTL / Cleanup & Compaction

---

### Shared tasks
- [ ] Implement periodic cleanup job (foreground timer + background best-effort):
  - [ ] Mark expired `chat_messages` → status=expired
  - [ ] Mark expired `map_markers` / `map_areas` → status=expired
- [ ] Implement event log compaction policy:
  - [ ] delete old expired events older than retention window
  - [ ] keep tombstones longer than normal events (configurable)
- [ ] Prune pending tables:
  - [ ] delete pending edits/patches older than X hours

### Android tasks
- [ ] Schedule cleanup with WorkManager (best-effort) + in-app timer

### iOS tasks
- [ ] Schedule cleanup with BGTaskScheduler (best-effort) + in-app timer

---

## 8) Minimal UX Hooks (Platform UI Teams)

> You said you will build Chat + Map UI; these are the minimum hooks the data layer must expose.

### Shared tasks
- [ ] “Active group” selection:
  - [ ] join/create group by passphrase
  - [ ] start/stop mesh transport when group active
- [ ] “Send message”:
  - [ ] create event → store locally → broadcast
- [ ] “Add marker / area”:
  - [ ] create event → store locally → broadcast
- [ ] Status indicators:
  - [ ] show “mesh on/off”
  - [ ] show approximate nearby node count (optional `SYS.PRESENCE`)

---

## 9) Optional (Phase 2+) System Events

### Shared tasks
- [ ] `SYS.PRESENCE` beacon:
  - [ ] send periodically while mesh active
  - [ ] show device count (approx)
- [ ] `SYS.ALERT`:
  - [ ] allow high-priority broadcast messages (short text)

---

## 10) Testing Strategy (Phase 2)

### Unit tests (core)
- [ ] Event schema validation tests
- [ ] Clock compare (`newer`) tests
- [ ] TTL expiry tests
- [ ] Reducer tests:
  - [ ] out-of-order events (DEL before MSG, UPD before ADD)
  - [ ] duplicates
  - [ ] LWW edits and marker updates
  - [ ] TTL expiry behavior

### Integration tests (DB)
- [ ] Ingest pipeline transaction tests (insert event → materialized row)
- [ ] Compaction tests (expired rows marked & old events pruned)

### Field tests (device)
- [ ] Two devices exchange messages in airplane mode
- [ ] Multi-hop (3+ devices) propagation
- [ ] Duplicate storm handling (flood) stability

---

## 11) Definition of Done (Phase 2)

### Core
- [ ] Two devices can join same group (passphrase) and exchange:
  - [ ] chat messages
  - [ ] map markers
- [ ] Works with no internet connection
- [ ] Dedupe prevents message storms from duplicating state
- [ ] TTL expiry hides stale content
- [ ] Local DB survives app restart and state rebuild is correct

### Quality
- [ ] No crash under noisy mesh traffic
- [ ] Reasonable battery usage in foreground
- [ ] Clear logs/metrics for debugging (counts: received/accepted/duplicate/forwarded)

---

## 12) Deliverables Checklist (Quick)

- Transport: BLE send/receive/forward + throttle (Android: custom BLE transport; iOS: Berkanan SDK)
- Crypto: group derive + encrypt/MAC verify/decrypt
- Storage: SQLite schema v1 + migrations + WAL
- Ingest: transactional pipeline + dedupe
- Reducers: chat + map materialization
- Cleanup: TTL expiration + compaction
- Tests: unit + DB integration + field test script
