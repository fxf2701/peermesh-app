# BLE Mesh Handoff - Updated 2026-04-02

## Current Focus

The stack is no longer blocked by the older sender-churn, replay-order, and ACK-noise problems.

Current priority order is:

1. harden direct-path first-batch delivery at the start of each burst
2. make relay compensation missing-peer-only instead of nearby-peer-first
3. reduce current-burst damage during `device_id_rotated`
4. only after reliability is stable, optimize battery

Current top blockers are:

1. the first batch of a burst can still die on both peers with `queued_no_progress_timeout`
2. relay compensation still wastes work on non-essential nearby peers instead of focusing only on the peer still missing the batch
3. rotation windows can still knock one peer out of a current burst
4. direct-path quality still decides most rounds because relay recovery is not mature yet

## Latest Validation Snapshot (2026-04-02 16:22)

Round files:

- `logs/device-A_activity_log_20260402_162232-55208db3.txt`
- `logs/device-B_activity_log_20260402_162233-2190e9eb.txt`
- `logs/device-C_activity_log_20260402_162233-b241a18e.txt`

Observed coverage:

- `A -> B = 9/10` (missing `#1`)
- `A -> C = 9/10` (missing `#1`)

What this confirms:

- direct path is still strong enough to reach high coverage when rotation/retry windows are favorable
- relay queueing and terminal callbacks are active (`queued/delivered/failed` exist)

What this does not solve:

- first-batch failure pattern is still present:
- `#1` failed on both peers with `queued_no_progress_timeout` and `0/75`
- relay is still not missing-peer-only:
- attempts still involve non-essential nearby peer `0A81...`
- occasional old backlog item can still appear in receiver logs (example observed on `C`)

## Current Architecture

### Receiver Path

Current receiver behavior:

- receive BLE payload
- reassemble payload
- if payload is `APP_BATCH`:
  - verify batch signature
  - extract events
  - ingest events
  - render from DB/UI refresh path
  - optionally seed relay compensation state for nearby peers

Relevant files:

- `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`
- `android/data/src/main/java/app/peermesh/data/ingest/EventIngestor.kt`
- `android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt`
- `android/app/src/main/java/app/peermesh/GroupChatActivity.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`

### Sender Path

Sender direction is clear:

- local chat persists outbound work first
- `UserOutbox -> OutboundBatchDispatcher` is the sender path for local outbound chat
- receiver forwards only when relay policy says the destination peer is still missing the batch

Relevant files:

- `android/app/src/main/java/app/peermesh/UserOutbox.kt`
- `android/app/src/main/java/app/peermesh/OutboundBatchDispatcher.kt`
- `android/data/src/main/java/app/peermesh/data/transport/EnvelopeSender.kt`
- `android/data/src/main/java/app/peermesh/data/transport/AppBatcher.kt`

### Persistent Outbound Queue

A DB-backed outbound queue is in place.

Schema/files:

- `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchEventEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchStatus.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchLane.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchPeerDeliveryEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchPeerDeliveryState.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/OutboundBatchDao.kt`
- `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`
- `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`

Current queue behavior:

- local outbound batches are inserted as `FRESH`
- replay/retry work is demoted to `BACKLOG`
- if any `FRESH` exists, dispatcher dispatches only `FRESH`
- `BACKLOG` runs only when no `FRESH` remains ready
- append/coalescing is allowed up to `MAX_EVENTS_PER_BATCH = 5`
- practical batch size is still limited by payload size, not count limit

### Peer Delivery Ledger

There is now a real peer-level ledger:

- rows are keyed by `batch_id x device_id`
- states currently used:
  - `PENDING`
  - `IN_FLIGHT`
  - `DELIVERED`
  - `FAILED_RETRYABLE`
  - `FAILED_FINAL`

Current behavior:

- direct send completion/failure updates peer rows
- relay seeding creates `PENDING` rows for candidate peers
- relay only targets peers still `PENDING` or `FAILED_RETRYABLE`
- sticky retry is kept per destination peer

This is the right direction, but relay recovery is still not mature enough.

## Fixes That Matter

### 1. Batch-only envelope model

Implemented:

- shared metadata moved to batch level
- batch signature is the main integrity unit
- sender emits `APP_BATCH v2`

Task reference:

- `[PH3-DTN][BLP-F][P0-RX-8.21]`

### 2. No-target sender backoff

Implemented:

- sender does not retry hot when there is no usable BLE peer
- this removed the old `A`-side churn and UI lag problem

Task reference:

- `[PH3-DTN][BLP-F][P0-RX-8.22]`

### 3. `FRESH` vs `BACKLOG`

Implemented:

- replay backlog no longer blocks current local bursts
- `FRESH` has priority over `BACKLOG`

Task reference:

- `[PH3-DTN][BLP-F][P0-RX-8.23]`

### 4. Replay timestamp correctness

Implemented and verified in later rounds:

- `EventIngestor` stores `events.created_t` from original `core.time.t`
- replay/current messages now keep original `created_s`
- render no longer rewrites replay time to current arrival time

Relevant file:

- `android/data/src/main/java/app/peermesh/data/ingest/EventIngestor.kt`

### 5. ACK disable for message-delivery testing

Implemented:

- delivered/seen ACK traffic is disabled during this reliability phase
- this removed log and UI noise and made current-burst behavior easier to evaluate

Relevant file:

- `android/app/src/main/java/app/peermesh/ble/MeshAckManager.kt`

### 6. Peer retry scheduling and relay gating

Implemented:

- peer-level retry rows
- sticky retry targeting
- `next_retry_at` is now a hard gate in dispatcher
- relay forwarding is no longer blind global forwarding

Task references:

- `[PH3-DTN][BLP-F][P0-RX-8.24]`
- `[PH3-DTN][BLP-F][P0-RX-8.25]`

### 7. Rotation recovery grace

Implemented:

- recently rotated peers are kept available briefly by `deviceId`
- this improved some rounds where `#7/#8` used to drop exactly when address rotation happened

Relevant file:

- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`

This helped in some direct-path rounds, but is not yet proven stable enough on its own.

### 8. Duplicate transport-complete bug fix

Implemented:

- `BLE batch send complete` no longer fires repeatedly for the same `batch_id + device_id`
- dispatcher no longer re-kicks repeatedly from that duplicate callback path

### 9. Batch reconciliation no longer ignores `PENDING`

Implemented:

- a batch is not considered done just because one peer is `DELIVERED`
- if another peer is still `PENDING` or `FAILED_RETRYABLE`, the batch remains retryable

This was a correctness bug and needed fixing.

## What No Longer Needs Priority

The following items should not drive current work:

- battery optimization
- trying to force larger multi-event batches in healthy direct-path rounds
- old export/log-capture issues
- replay time/order as a primary blocker
- ACK behavior as a current priority
- duplicate batch-complete callback investigation as an open issue
- reconciling `PENDING` vs `DELIVERED` as an open issue

Those items were either fixed or are no longer the main failure source.

## Main Problems Still Open

### 1. First-Batch Direct Path Is Still Fragile

Current reality:

- the first batch of a burst can still die on both peers with `queued_no_progress_timeout`
- when that happens, the whole round starts behind and later recovery has to compensate for preventable damage

### 2. Rotation Windows Still Damage Current Bursts

Common failure pattern:

- both peers are selected
- one or both rotate during send
- lane continuity breaks or the first post-rotate send is deferred too long
- one peer misses a subset of the current burst

### 3. Relay Compensation Is Still Not Missing-Peer-Only

Observed in recent relay rounds:

- relay can still spend attempts on `0A81...` or another non-essential nearby peer
- delivered-once-per-batch helps avoid repeated waste, but the first attempt can still go to the wrong peer
- this confirms the remaining fix is selection policy, not a static blacklist

### 4. Direct Path Still Decides Most Rounds

Current reality:

- when `A` direct-sends well, rounds can be excellent
- when direct path misses, relay still does not reliably fill the gaps

## Mesh/DTN Mature Checklist

### Must-have

- [x] current burst is never blocked by replay backlog
- [x] replay/current messages keep original `created_s`
- [x] no ACK noise in current reliability test rounds
- [x] no duplicate render for the same `eventId`
- [x] duplicate `BLE batch send complete` spam is fixed
- [x] sender does not hot-spin when there is no peer
- [ ] UI ordering is based on true event creation time in all edge cases
- [ ] each batch eventually reaches a terminal peer state:
- [ ] `DELIVERED`
- [ ] or `FAILED_FINAL`
- [ ] with `A/B/C` online sẵn, coverage is stably near `10/10` on both `B` and `C`
- [ ] address rotation does not materially damage a current burst

### Should-have

- [x] sender tracks peer-specific delivery state well enough to avoid blind relay replay
- [x] relay retry can stay sticky to the intended destination peer
- [ ] a peer that joins a bit late still catches most recent backlog
- [ ] relay via an intermediate peer works consistently enough to rescue direct-path misses
- [ ] retry/replay has bounded TTL and budget
- [ ] batches queued for a peer do not starve indefinitely behind a bad lane
- [ ] retry after rotate preferentially recovers the peer still missing the batch

### Later

- [ ] battery optimization
- [ ] adaptive scan duty cycle
- [ ] advanced traffic budgeting
- [ ] summary-based missing exchange between peers

## Two-Branch Fix Plan

### Branch 1: Direct Path First-Batch Hardening

Goal:

- stop `#1`-style failures where the first batch of a burst dies on both peers before useful progress starts

Plan:

1. add a stricter first-batch gate per peer
- do not allow the first `APP_BATCH` on a newly usable lane until the lane is truly ready:
- `CONNECTED`
- services discovered
- and MTU known for payloads that would otherwise split pathologically

2. add a short first-batch warmup window
- after a lane becomes usable, wait a very short window before letting the first data batch in
- this should avoid the recurring `0/75` start-of-burst failures

3. fail and requeue first-batch stalls earlier
- if the first batch is queued but still has no first progress quickly, fail it early and requeue it as fresh direct retry
- do not let it become the slow head-of-line blocker for the burst

Current status:

- in progress
- implemented in transport:
- short warmup window after `services_discovered`
- new pending reason `deferred_first_batch_warmup`
- stricter `deferred_until_mtu_known` gate on connected lanes
- validation result: high overall round coverage is possible, but `#1` dual fail (`0/75`) still occurs, so this branch is not complete

### Branch 2: Missing-Peer-Only Relay Compensation

Goal:

- make relay compensation target only the peer still missing a given batch, instead of treating all nearby peers as roughly equal

Plan:

1. keep relay selection keyed strictly by `batch_id x device_id`
- only peers in `PENDING`
- or due `FAILED_RETRYABLE`
- are valid relay targets for that batch

2. never re-forward the same batch to a peer already marked `DELIVERED`
- this is now partly enforced in transport state and should remain a hard rule
- the rule is per batch, not a global peer blacklist

3. tighten relay seeding
- do not seed every nearby peer as missing by default
- seed only peers with actual missing-peer evidence from the direct path or peer ledger state

4. treat `sourceDeviceId` as authoritative metadata
- the sender now embeds `src_device_id` in `APP_BATCH`
- relay scheduling should prefer this field over weaker address/scan inference whenever present

5. keep non-essential peers like `0A81...` eligible only when they are genuinely missing that batch
- no hard blacklist
- but also no equal priority with the known missing peer

Current status:

- not started as a full branch yet
- partial groundwork already exists:
- per-batch delivered exclusion in transport state
- `src_device_id` embedded in `APP_BATCH`
- relay observability is complete enough to validate this branch

## Recommended Next Steps

1. finish Branch 1 by making first-batch gate catch the `#1` dual-fail case consistently
2. start Branch 2 by tightening relay seeding to missing-peer evidence only (not broad nearby default)
3. enforce missing-peer-first selection per `batch_id` while keeping per-batch `DELIVERED` exclusion hard
4. keep non-essential peers eligible only when they are truly missing that batch, without global blacklist
5. after both branches stabilize, revisit rotation recovery and late-join catch-up

## Review Format For Future Rounds

Each round review should include:

1. findings first
2. parallel timeline:
- `time`
  - `A`: ...
  - `B`: ...
  - `C`: ...
3. per-device behavior summary
4. explicit comparison against this handoff checklist:
- which `[x]` items were reinforced
- which `[ ]` items remain open
- any regression

## Files To Open First

1. `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
2. `android/app/src/main/java/app/peermesh/OutboundBatchDispatcher.kt`
3. `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`
4. `android/data/src/main/java/app/peermesh/data/db/dao/OutboundBatchDao.kt`
5. `android/data/src/main/java/app/peermesh/data/db/entity/OutboundBatchPeerDeliveryEntity.kt`
6. `android/data/src/main/java/app/peermesh/data/ingest/EventIngestor.kt`
7. `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`

## Files To Review Next (For Handoff Team)

Branch 1 (direct first-batch hardening):

1. `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- review `broadcastEnvelopeToTargets(...)`
- review `shouldDeferUntilMtuKnown(...)`
- review `shouldDeferFirstBatchWarmup(...)`
- review `onServicesDiscovered(...)` warmup setup

2. `android/app/src/main/java/app/peermesh/OutboundBatchDispatcher.kt`
- confirm sender-side retry sequencing does not immediately re-stress first-batch lane after a dual fail

Branch 2 (missing-peer-only relay compensation):

1. `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- review `relayCandidateDeviceIds(...)`
- review `executeBatchForwardAttempt(...)`
- review `selectRelayTargetForDeviceIds(...)`
- review per-batch delivered exclusion state

2. `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`
- review `selectRelayBatchTargetDeviceIds(...)`
- review relay seed and pending-row behavior

3. `android/data/src/main/java/app/peermesh/data/db/dao/OutboundBatchDao.kt`
- review peer ledger transitions (`PENDING`, `IN_FLIGHT`, `DELIVERED`, `FAILED_RETRYABLE`, `FAILED_FINAL`)

Shared model + metadata:

1. `android/core/src/main/java/app/peermesh/core/model/EventModels.kt`
- `AppBatchEnvelope.src_device_id`

2. `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`
- verify receiver meta remains compatible with `src_device_id` flow

Round logs to replay first:

1. `logs/device-A_activity_log_20260402_162232-55208db3.txt`
2. `logs/device-B_activity_log_20260402_162233-2190e9eb.txt`
3. `logs/device-C_activity_log_20260402_162233-b241a18e.txt`
