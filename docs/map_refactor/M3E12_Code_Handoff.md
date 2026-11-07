# M3E12 handoff — recover a missing canonical map `ADD`

## Scope and current revision

This document hands over the unfinished M3E12 work: recovery when a device
receives a later map mutation or dedicated action but missed the original
canonical `MAP.MARKER.ADD` or `MAP.AREA.ADD`.

Current branch and snapshot:

* Branch: `fix/m3e12-defer-repair-replay`
* Commit: `672fb06b` — `M3E12 — add durable map dependency repair`
* This commit compiles and has focused unit/compile verification, but the
  real-device BLE recovery scenario below is **not yet fixed**.

Do not discard unrelated untracked files in the worktree.

## 1. The main problem

Consider three devices in one group:

1. Device B creates marker **B3** (kind **Potential risk**) while device A is
   offline or BLE is disabled.
2. B's initial canonical `MAP.MARKER.ADD` does not reach A.
3. A later becomes reachable. B confirms the marker, updates it, or marks it
   no longer active.
4. A receives the later event and displays a generic **Map synced** banner,
   but the marker/area is absent from both the map and list.

The banner is valid only in the narrow sense that the later event was received
and durably handled. It is not evidence that the item exists locally. The
later event is not sufficient to create the item because immutable canonical
creation data (owner, geometry/kind/category/lifecycle context) must come from
the original `ADD`.

The same issue affects marker and area, canonical `UPD`/`DEL`, Situation
actions (confirmation/update/no-longer-active), and Coordination actions
(acknowledgement/note) whenever their target is missing.

## 2. Intended contract: defer → repair → ADD → replay

### Defer

When a valid remote mutation/action references a marker or area that is absent:

* Never synthesize an `ADD` from the later event.
* Persist the exact event durably rather than rejecting and forgetting it.
* Return `AcceptedDeferred` only after that persistence commits.
* Retain the canonical event ID in `ref.root`; `ref.target` remains the map
  item ID.

Canonical mutations use the `pending_canonical_map_events` inbox. Dedicated
Situation/Coordination actions use the existing `pending_target` state, but
must also produce `AcceptedDeferred` when `ref.root` is available.

### Repair

Each unresolved `ref.root` is a durable request for the missing `ADD`.

* Cloud/relay repair fetches exact event IDs independently of the normal
  forward-only relay cursor.
* BLE/DTN repair sends a small targeted `INV_WANT(ref.root)` to **every newly
  encountered peer**, not only the marker owner. Any peer whose durable event
  history contains the root can reply with `DATA`.
* A failed peer must not globally suppress the request. Per-peer or per-session
  dedupe/backoff is acceptable. Once the ADD materializes or reaches terminal
  rejection, the request disappears.

### ADD

The peer/relay returns the exact immutable ADD event. It goes through the
normal verifier/ingest path and materializes canonical domain state first.

### Replay

In the same transactional reconciliation:

1. Replay deferred canonical mutations in deterministic total order.
2. Then replay pending dedicated Situation/Coordination actions for the exact
   target.
3. Publish one post-commit map notification so UI reconciliation sees the
   final item rather than intermediate state.

The flow must be idempotent under duplicate BLE/cloud delivery and survive
process restart.

## 3. Latest real-device test result and evidence

Latest failed test logs:

* Device B: `logs/device-B_activity_log_live_20260731_125142-94b3b1ef.txt`
* Device A: `logs/device-A_activity_log_live_20260731_125141-dd3b50ac.txt`

Observed sequence (times are from the activity logs):

| Time | Device | Evidence | Meaning |
| --- | --- | --- | --- |
| 12:52:33 | B | Creates ADD `O9nHfkLLoVJYZ95w9MVfSQ4FVJEBZVDW1ArhPzqMHk8` | The canonical marker ADD exists on B, target `AB0Qu…`. |
| 12:55:04 | B | Sends later dedicated map action `ejhGLI8V…` | A can receive a mutation/action but did not receive the ADD. |
| 12:55:06 | A | `Map event deferred`; `Map canonical repair requested`; `Map canonical repair scheduled` | The new defer and repair-trigger classification works. |
| about 12:55:49 | B | Receives A's HELLO and builds `INV_HAVE` with 250 IDs, about 11.7 KB / 69 chunks | The generic inventory is large and enters the BLE control queue. |
| afterwards | A | No `DTN inv_want sent`; no matching DTN DATA/ADD | The actual root request was never sent, so there is nothing to replay. |

This conclusion is based on the logs, not merely UI behaviour:

* A did persist/schedule the missing-root repair; therefore the old bug where
  dedicated actions were classified as ordinary accepted events is resolved.
* No targeted BLE WANT or returned ADD appears in A's log.
* The current implementation creates dependency WANTs only after receiving a
  remote `INV_HAVE`; A did not reach that point because the large generic
  `INV_HAVE` was queued/retried before it arrived.

The generic **Map synced** banner on A is acceptable. What is missing is the
transport repair work following it.

## 4. What has been solved

The current commit provides the following pieces:

* New locally created map mutations/actions are enriched with a canonical
  `ref.root` where the root ADD can be resolved.
* Room schema 53 adds `pending_canonical_map_events` for durable canonical
  mutation deferral.
* `RoomMapEventProcessor` defers missing canonical mutations and replays them
  after the ADD arrives.
* A missing-target dedicated Situation/Coordination action that carries
  `ref.root` now returns `AcceptedDeferred` after its pending row commits.
* `EventIngestor` emits a canonical-repair request after deferred acceptance;
  relay scheduler/work manager can pull it forward.
* Relay supports targeted event-ID fetch (`event_ids`) without changing the
  normal cursor and the server validates/caps these IDs.
* Pending roots are exposed by `RoomDtnInventoryStore` from both canonical and
  dedicated-action pending stores.
* Diagnostics were added for local map commit, defer, repair request/schedule,
  relay repair pull and grouped map activity logs in Test activity.
* Existing compile/test checks run successfully before the latest device test:
  `:transport:testDebugUnitTest --tests app.peermesh.transport.ble.dtn.DtnSyncCoreTest`,
  `:data:compileDebugKotlin`, `:data:compileDebugAndroidTestKotlin`, and
  `:app:compileEmergencyDebugKotlin`.

## 5. What remains to fix (highest priority)

### Send the BLE targeted WANT without waiting for generic `INV_HAVE`

This is the blocker. `DtnSyncEngine.onHave()` currently appends unresolved
roots to a WANT only after a remote inventory packet is received. That is too
late under weak BLE because a large `INV_HAVE` may remain queued or be lost.

Implement a lightweight path which sends a dependency `INV_WANT` as soon as a
local DTN session starts / local HELLO is sent. It must not need the remote
peer's identity in the packet: the BLE connection already provides the route;
`DtnControlPacket.peerId` identifies the sender.

Suggested implementation approach:

1. Add a public `DtnSyncEngine` helper such as
   `buildDependencyRepairWantPacket(gid, sessionId)` that reads
   `store.listDependencyRepairWants(gid, limit)` and returns a small
   `INV_WANT` containing only the unresolved root IDs (or `null` if empty).
2. At the runtime point that queues `buildHelloPacket`, queue this packet
   immediately after HELLO, without waiting for `onHave`.
3. Preserve a once-per-local-session guard. A new peer/session must receive a
   fresh WANT; clearing on disconnect is correct. Do not clear merely because
   a previous peer did not provide the data.
4. Keep the present `onHave` injection only as a fallback, or refactor both
   paths to share one dedupe helper. Avoid duplicate root WANTs in a session.
5. Verify `onWant()` serves the root by exact ID from durable `EventsDao`
   history, outside the live outbound batch/window. Its current architecture
   appears intended to do this, but prove it with a test.
6. Add activity-log or DTN diagnostics that clearly distinguish a dependency
   root WANT from ordinary inventory wants.

Expected proof after the change:

`A: deferred → repair request → DTN dependency WANT sent` →
`B: receives WANT, returns DATA(root ADD)` →
`A: inserts ADD → replays action → marker/area appears in map and list`.

### Remaining validation

* Test the exact scenario above with two/three real devices and deliberately
  missed ADD, then later confirmation, update, no-longer-active, and
  Coordination actions.
* Repeat for marker and area.
* Test cloud-only targeted repair and mixed cloud/BLE duplicate delivery.
* Confirm restart/offline persistence, caps/expiry, malformed event hard
  rejections, and no repair loop after ADD materializes.
* Do not mark M3E12 complete until the manual cases in
  `docs/map_refactor/M3E12_Manual_Test_Script.md` have real device evidence.

## 6. Relevant files

### Core flow

* `android/data/src/main/java/app/peermesh/data/map/processing/RoomMapEventProcessor.kt` — transactional defer and replay; dedicated absent-target handling.
* `android/data/src/main/java/app/peermesh/data/map/processing/MapEventProcessingModels.kt` — `AcceptedDeferred` result model.
* `android/data/src/main/java/app/peermesh/data/ingest/EventIngestor.kt` — turns deferred results into repair notifications.
* `android/data/src/main/java/app/peermesh/data/ingest/IngestNotifier.kt` — repair callback contract.
* `android/app/src/main/java/app/peermesh/PeerMeshApp.kt` — application wiring to relay repair scheduling.
* `android/data/src/main/java/app/peermesh/data/map/local/RoomLocalMapEventWriter.kt` — local canonical root persistence/enrichment.
* `android/app/src/main/java/app/peermesh/GroupMapActivity.kt` — UI-originated UPD/DEL root enrichment.
* `android/app/src/main/java/app/peermesh/map/application/submission/SubmitMapInteraction.kt` and `android/app/src/main/java/app/peermesh/map/composition/MapInteractionSubmissionFactory.kt` — local dedicated interaction root references.
* `android/core/src/main/java/app/peermesh/core/factory/EventFactory.kt` — core event construction support.

### Durable pending state and DB

* `android/data/src/main/java/app/peermesh/data/db/entity/PendingCanonicalMapEventEntity.kt`
* `android/data/src/main/java/app/peermesh/data/db/dao/PendingCanonicalMapEventDao.kt`
* `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`
* `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`
* `android/data/schemas/app.peermesh.data.db.AppDatabase/53.json`
* `android/data/src/main/java/app/peermesh/data/db/dao/EventsDao.kt` — pending dedicated-action roots and durable event lookup.

### BLE / DTN (the current blocker lives here)

* `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnSyncEngine.kt`
  * `buildHelloPacket()` and session lifecycle
  * `onHello()`
  * `onHave()` — **current dependency WANT injection, too late**
  * `onWant()` / DATA response handling
  * control queue priorities (`INV_WANT` priority is already above `INV_HAVE`)
* `android/transport/src/main/java/app/peermesh/transport/ble/dtn/DtnInventoryModels.kt` — `listDependencyRepairWants` store contract.
* `android/data/src/main/java/app/peermesh/data/dtn/RoomDtnInventoryStore.kt` — durable unresolved root query.
* `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt` — injects pending DAO into store and is the likely place to queue WANT at local session start.

### Relay/cloud repair

* `android/app/src/main/java/app/peermesh/relay/RelaySyncWorkManager.kt`
* `android/app/src/main/java/app/peermesh/relay/RelaySyncCoordinator.kt`
* `android/app/src/main/java/app/peermesh/relay/RelaySyncRunner.kt`
* `android/data/src/main/java/app/peermesh/data/relay/RelaySyncScheduler.kt`
* `android/data/src/main/java/app/peermesh/data/relay/RelayPullManager.kt`
* `android/transport/src/main/java/app/peermesh/transport/relay/RelayHttpClient.kt`
* `server/relay/src/validation.js`
* `server/relay/src/storage.js`

### Tests and specification

* `android/data/src/androidTest/java/app/peermesh/data/map/processing/RoomMapEventProcessorTest.kt`
* `android/data/src/androidTest/java/app/peermesh/data/db/PendingCanonicalMapEventMigrationTest.kt`
* `android/transport/src/test/java/app/peermesh/transport/ble/dtn/DtnSyncCoreTest.kt`
* `android/data/src/test/java/app/peermesh/data/dtn/RoomDtnInventoryStoreTest.kt`
* `android/app/src/test/java/app/peermesh/MapActivityLogLabelGroupTest.kt`
* `docs/milestones/Refactor_Map_Activity-M3.md` — M3E12 definition/status.
* `docs/map_refactor/M3E12_Manual_Test_Script.md` — manual verification.

## Guardrails for the next implementation

* Do not use a UI refresh, delay, or banner as a substitute for materializing
  the missing ADD.
* Do not convert UPD/DEL/action into an implicit ADD.
* Do not send a large generic inventory merely to discover one root; the repair
  WANT should be a small high-priority packet.
* Do not restrict repair to the original item owner. A peer that has the event
  in durable history is sufficient.
* Do not delete pending state after one failed peer exchange. Clear only after
  successful ADD materialization/replay or an explicit terminal validation
  result.
