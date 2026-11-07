# Phase 3.1 — Technical Specification (Mobile Threads + Client UX)

## 1) Goals
Phase 3.1 focuses on **mobile threaded chat** on top of the group-only model and client UX
for thread discovery, navigation, and offline/online sync.

Primary goals:
- Threaded chat inside a group.
- Backward compatibility with Phase 2 group-only clients.
- Android threads UX and navigation.

Non-goals:
- Real-time guarantees.
- Global identity or centralized registry.
- Relay/federation upgrades (handled in Phase 3).

See also:
- `docs/specs/PHASE_3_TECHNICAL_SPEC.md` (Federation-only scope)

---

## 2) Thread Model

### 2.1 Concepts
- **Group (gid)** is the top-level privacy boundary.
- **Thread** is a topic container inside a group.
- A message belongs to exactly **one** thread.

### 2.2 Default Thread
To migrate Phase 2 data:
- Each group has an implicit **default thread** (`thread_id = gid`).
- All existing group messages map to the default thread.

### 2.3 Thread Lifecycle
Threads can be:
- `active`
- `archived`
- `deleted` (tombstoned)

Archived threads are hidden by default but can be reopened by policy.

---

## 3) Data Model

### 3.1 Tables
- `chat_threads`
  - `thread_id` (PK)
  - `gid`
  - `title`
  - `creator_did`
  - `status`
  - `created_at`, `updated_at`
  - `last_event_id`, `last_event_t`, `last_event_lt`, `last_event_actor`

- `chat_messages`
  - add `thread_id` (nullable; default thread if null)

- Optional: `chat_thread_members` (Phase 3+ if per-thread ACL is required)

### 3.2 Indexes
- `chat_threads(gid, updated_at)`
- `chat_messages(gid, thread_id, created_t)`

---

## 4) Events

### 4.1 New Event Types
- `CHAT.THREAD.CREATE`
- `CHAT.THREAD.UPDATE`
- `CHAT.THREAD.ARCHIVE`
- `CHAT.THREAD.DELETE`

### 4.2 Message Events
Existing `CHAT.MSG/EDIT/DEL` must include `thread_id` (default thread allowed).

---

## 5) Reducers

### 5.1 Thread Reducer
Applies LWW rules to:
- title
- status
- updated_at

### 5.2 Message Reducer
Validates that:
- `thread_id` exists for the gid, or falls back to default thread.
- archived threads reject new messages (policy-defined).

---

## 6) Backward Compatibility

### 6.1 Old clients (Phase 2)
- Ignore thread events.
- Still show messages in a flat group timeline.
- Can continue to push/pull events without breaking the network.

### 6.2 Migration
- Forward-only migrations.
- Backfill default thread without changing event payloads:
  - If `thread_id` is missing, assign default thread (`thread_id = gid`).

## 7) Security & Access Control
- Thread creation policy is enforced client-side (owner-only or member).
- Optional per-thread ACL is deferred (Phase 3+).
- Event validation ensures `thread_id` belongs to `gid` and respects thread status.

---

## 8) UX Requirements (Android)
- Threads list per group.
- Thread detail chat screen.
- Create thread UI with title.
- Manage thread (rename/archive/delete).
- Clear indication when thread is archived.

---

## 9) QA Scenarios
- Thread create restricted to owner (if policy).
- Thread rename conflict (LWW tie-break).
- Archived thread receives message (drop or reopen).
- Mixed clients (Phase 2 + Phase 3) in same relay mesh.

---

## 10) Optional: Message Retention (Phase 3.1)
- Default policy: **keep forever** (until user clears local DB).
- Per-group setting (in future Group Settings screen):
  - **Sender expiry**: delete locally after N time from send.
  - **Receiver expiry**: delete locally after N time from first seen.
- Local-only cleanup: applies per device, does not alter event payloads or relay data.
- Requires periodic cleanup job to purge expired rows from local DB.

---

## 11) Optional: BLE Privacy (Phase 3.1)
- Rotate BLE advertised `device_id` per session to avoid long-lived identifiers in release builds.

---

## 12) References
- `docs/specs/PHASE_2_TECHNICAL_SPEC.md`
