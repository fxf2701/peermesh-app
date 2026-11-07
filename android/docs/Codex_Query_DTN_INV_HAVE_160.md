# Codex Prompt — Reconstruct the 160-ID DTN `INV_HAVE` Inventory from Device A

## Context

We are investigating BLE/DTN runtime stability in the PeerMesh Disaster App.

During the chat-only real-device test, Device B received this control envelope from Device A:

```text
13:48:39.141
DTN inv_have recv
gid=H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q
peer_id=F6C01431201D82C6
session_id=F6C01431201D-1785566879970-71adf6d9
ids=160
data=0
bytes=7584
chunks=45
```

The Activity Log records only `ids=160`; it does **not** contain the 160 event IDs themselves. We need to reconstruct the exact or best-supported list from Device A's Room database and identify the corresponding event types, ages, sources, and relationships.

The current Android source snapshot is the source of truth.

Important project principle:

> Stability is more important than latency or aggressive optimization. Do not modify production behavior during this investigation. This is a read-only evidence-gathering task.

## Relevant current implementation

Inspect these files before querying:

```text
data/src/main/java/app/peermesh/data/dtn/RoomDtnInventoryStore.kt
data/src/main/java/app/peermesh/data/db/dao/EventsDao.kt
data/src/main/java/app/peermesh/data/db/entity/EventEntity.kt
transport/src/main/java/app/peermesh/transport/ble/dtn/DtnInventoryBuilder.kt
transport/src/main/java/app/peermesh/transport/ble/dtn/DtnForwardPolicy.kt
transport/src/main/java/app/peermesh/transport/ble/dtn/DtnInventoryModels.kt
transport/src/main/java/app/peermesh/transport/ble/dtn/DtnSyncEngine.kt
```

Known DB name:

```text
peer_mesh.db
```

Known group ID:

```text
H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q
```

Known Device A stable peer ID:

```text
F6C01431201D82C6
```

Known session:

```text
F6C01431201D-1785566879970-71adf6d9
```

Approximate inventory build/send time:

```text
2026-08-01 13:48:29–13:48:39 local device time
```

## Goal

Determine:

1. The exact 160 event IDs included in that `INV_HAVE`, if reconstructable.
2. If exact reconstruction is impossible, produce the closest reproducible candidate list and explain precisely why exactness cannot be guaranteed.
3. For each candidate event, identify:
   - `event_id`
   - `type`
   - `source`
   - `created_t`
   - `inserted_at`
   - age at inventory build time
   - `actor_did`
   - `ref_root`
   - `ref_parent`
   - `ref_target`
   - `invalid_at`
   - DTN inventory tier
   - DTN priority
4. Summarize counts by:
   - event type
   - source
   - inventory tier
   - age bucket
   - Map vs Chat vs System vs other
5. Determine whether the 160 IDs mainly represent:
   - historical chat events,
   - Map events,
   - system metadata,
   - or a mixed durable-history inventory.
6. Explain whether the same historical IDs can be advertised repeatedly across reconnects/session rotations.
7. Identify whether `repairSuppressionStore.filterPeerSatisfied(...)` could have removed any candidate IDs for this peer/session and whether that prevents exact reconstruction from the `events` table alone.

## Constraints

- Do not edit production code.
- Do not change Room schema.
- Do not clear app data.
- Do not mutate database rows.
- Do not infer the list by merely taking arbitrary recent rows.
- Reproduce the actual code path as closely as possible:
  - cursor traversal,
  - scan limit,
  - tier classification,
  - transfer sorting,
  - deduplication,
  - limit,
  - peer-satisfied suppression.
- Preserve the original database before doing anything.
- All DB access must be read-only.

## Step 1 — Inspect and document the exact inventory algorithm

Before querying DB, explain in code terms how the 160 IDs are selected.

At minimum document:

```text
RoomDtnInventoryStore.listInventoryAfter(...)
→ EventsDao.loadDtnCursorWindow(...)
→ DtnInventoryBuilder.buildHaveWindow(...)
→ DtnInventoryTier.classify(...)
→ DtnForwardPolicy.sortForTransfer(...)
→ repairSuppressionStore.filterPeerSatisfied(...)
→ matches.keys.take(limit)
```

Determine the exact values used for this packet:

- inventory `tier`
- requested `limit`
- `scanLimit`
- initial cursor
- `nowSeconds`
- remote peer ID used for suppression
- number of scan batches
- whether this was the first `INV_HAVE` window or a later cursor window
- whether the same session had earlier/later `INV_HAVE` windows

Do not assume the limit is 160 merely because the result had 160 IDs. Confirm the configured limit and why the packet stopped at 160.

## Step 2 — Pull Device A database safely

Prefer an approach compatible with the installed debug build.

Example workflow, adjust package name/flavor after confirming it:

```bash
adb -s <DEVICE_A_SERIAL> shell am force-stop <PACKAGE_NAME>

adb -s <DEVICE_A_SERIAL> exec-out \
  run-as <PACKAGE_NAME> \
  cat databases/peer_mesh.db \
  > /tmp/device-a-peer_mesh.db

adb -s <DEVICE_A_SERIAL> exec-out \
  run-as <PACKAGE_NAME> \
  cat databases/peer_mesh.db-wal \
  > /tmp/device-a-peer_mesh.db-wal

adb -s <DEVICE_A_SERIAL> exec-out \
  run-as <PACKAGE_NAME> \
  cat databases/peer_mesh.db-shm \
  > /tmp/device-a-peer_mesh.db-shm
```

If `run-as` is unavailable, report that and use another non-destructive debug-safe method. Do not root or modify the device.

Record:

```text
adb serial
package name
APK flavor/build type
database file sizes
SHA-256 for DB/WAL/SHM copies
copy timestamp
```

Open the copied DB read-only.

## Step 3 — First raw DB inventory query

Start with all valid events in the target group ordered exactly as Room's cursor query:

```sql
SELECT
    id,
    gid,
    event_id,
    type,
    source,
    created_t,
    inserted_at,
    actor_did,
    ref_root,
    ref_parent,
    ref_target,
    invalid_at,
    invalid_reason
FROM events
WHERE gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
  AND invalid_at IS NULL
ORDER BY inserted_at ASC, event_id ASC;
```

Also return:

```sql
SELECT COUNT(*)
FROM events
WHERE gid = 'H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q'
  AND invalid_at IS NULL;
```

This raw query is not yet the final answer; it is only the source window.

## Step 4 — Reproduce `buildHaveWindow()` exactly

Write a temporary read-only diagnostic script or JVM test outside production behavior that uses the same production classes where practical.

Preferred approach:

```text
- Load copied SQLite rows.
- Map them to DtnInventoryItem.
- Use the exact current DtnInventoryTier and DtnForwardPolicy code.
- Reproduce cursor scanning and sort order.
- Apply peer-satisfied suppression if its persisted state can be reconstructed.
- Build the candidate packet for the known peer/session/time.
```

Use inventory build timestamp derived from the session/log, not the current time.

If the exact `nowSeconds` is not directly logged, test the narrow plausible range around `13:48:29–13:48:39` and report whether classification/list membership changes within that range.

Produce:

```text
candidate_inv_have_160.csv
candidate_inv_have_160.json
```

CSV columns:

```text
ordinal
event_id
type
source
created_t
inserted_at
age_seconds_at_build
actor_did
ref_root
ref_parent
ref_target
inventory_tier
priority
suppressed_for_peer
category
```

Where `category` is one of:

```text
CHAT
MAP_CANONICAL
MAP_ACTION
SYSTEM
OTHER
```

## Step 5 — Check suppression and cursor state

Inspect the implementations and persistence used by:

```text
DtnRepairSuppressionStore
DtnSyncCursorStore
DtnPeerState
DtnAdvertised
```

Find the corresponding Room tables/DAOs.

For peer:

```text
3D3FA1C865F3B265
```

or whichever remote peer ID Device A used while building this packet, query any relevant rows that existed at the test time.

Answer:

- Were some event IDs suppressed because the remote peer was already known to have them?
- Is suppression state timestamped?
- Can current DB state reproduce historical suppression at 13:48?
- Was the 160-ID packet generated from cursor `null`, or from a persisted/continued cursor?
- Could the packet be a later inventory window rather than the first 160 eligible IDs?

If historical suppression/cursor state is no longer recoverable, state that exact reconstruction is impossible from the current DB alone.

## Step 6 — Query batch/outbound correspondence

For each of the 160 candidate event IDs, find any related durable outbound batch records.

Discover the actual table names/schema from current source. Do not guess.

Return, where available:

```text
event_id
batch_id
batch_state
attempt_count
next_retry_at
target_device_id / explicit targets
created_at
updated_at
last_failure_reason
terminal status
```

Also list candidate events that have no outbound batch row because they entered DTN inventory through durable history rather than the current APP outbound queue.

This distinction is important:

```text
INV_HAVE inventory membership
!=
currently pending APP outbound batch
```

## Step 7 — Produce SQL that can be run directly

Create a reusable SQL file:

```text
docs/diagnostics/query_dtn_inv_have_160.sql
```

It should include:

1. raw valid group events;
2. counts by type/source;
3. age buckets;
4. joins to outbound batch tables;
5. joins to pending Map repair tables;
6. joins to DTN cursor/suppression/advertised state;
7. queries for the known session and peer where represented in DB.

If tier classification/priority cannot be represented purely in SQL, clearly mark those fields as requiring the Kotlin diagnostic script.

## Step 8 — Report

Produce:

```text
docs/diagnostics/dtn_inv_have_160_report.md
```

Required report structure:

### A. Executive conclusion

State one of:

```text
EXACT LIST RECONSTRUCTED
BEST-SUPPORTED CANDIDATE LIST
EXACT RECONSTRUCTION NOT POSSIBLE
```

### B. Why

Explain all assumptions and missing historical state.

### C. 160-ID list

Include the full ordered list or link to the generated CSV/JSON.

### D. Type/source breakdown

Example:

```text
CHAT.MSG: ...
MAP.MARKER.ADD: ...
MAP.AREA.ADD: ...
MAP.ITEM.CONFIRM: ...
SYS.USER: ...
other: ...
```

### E. Age distribution

At least:

```text
<5 minutes
5–30 minutes
30 minutes–6 hours
6–24 hours
1–7 days
>7 days
```

### F. Outbound batch relationship

Explain how many IDs:

```text
currently pending batch
retryable batch
terminal/sent batch
no batch row / durable-history only
```

### G. BLE runtime relevance

Answer:

- How much control payload is generated from this list?
- Is the inventory mostly stale history?
- Is the same list likely to be advertised repeatedly?
- Does this support the hypothesis that generic inventory traffic can occupy the BLE lane despite no new Map interaction?
- Do not propose production changes yet; evidence only.

## Deliverables

Return:

```text
docs/diagnostics/dtn_inv_have_160_report.md
docs/diagnostics/query_dtn_inv_have_160.sql
tools/diagnostics/reconstruct_dtn_inv_have.kt
artifacts/candidate_inv_have_160.csv
artifacts/candidate_inv_have_160.json
```

Also paste a concise summary containing:

```text
exact/candidate status
number of IDs
top event types
oldest/newest event age
number linked to active/retryable outbound batches
number that are durable-history only
whether repeated inventory advertisement is confirmed
```

Do not implement any transport fix in this task.
