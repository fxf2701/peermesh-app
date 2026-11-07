# Nearby Room BLE Development Note

## Implementation Note Discipline

Instruction for future Codex/AI implementation turns:

1. Update this note incrementally during implementation, not only at the end.
2. After each meaningful milestone, append the current state here before moving to the next step.
3. A milestone includes at least one of:
   - completed code-path change
   - completed DB/migration change
   - completed transport/protocol change
   - completed test/compile verification
   - newly discovered blocker or revised decision
4. Each update should record:
   - what was changed
   - what was verified
   - what remains next
5. If a build/test fails, record the failure and the current hypothesis before continuing.
6. Treat this note as the running handoff state so another Codex/AI can resume work without reconstructing context from logs, diffs, or memory.

## Scope

This note captures the current implementation state of Nearby Room BLE discovery/advertising so work can continue in another thread without reconstructing context from logs and commits.

## Current Branch Context

- Working branch: `feature/nearby-rooms-phase1`
- Recent relevant code commit: `f348e177` (`Add nearby room BLE refresh diagnostics and UTF-8 codec support`)
- Current work is still focused on fixing BLE discovery/fetch of Nearby Rooms before implementing transport prioritization/rate limiting.

## Product/Protocol Constraints

- Nearby Rooms work only inside BLE mesh.
- Nearby Rooms do not use DTN semantics for their own room lifecycle.
- A user only processes active-room transport for the room they are currently inside.
- When not inside a Nearby Room, the app may still refresh the Nearby Rooms listing and participant counts for discovery purposes.
- The Rooms Listing refresh must trigger the BLE discovery pipeline for fresh metadata collection, then upsert BLE results into DB, then let UI re-read DB.

## Relevant Code Paths

### Advertisement path

- App session state: `android/app/src/main/java/app/peermesh/nearby/ActiveNearbyRoomSessionManager.kt`
- App advertisement coordinator: `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt`
- App payload cache: `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleStateCache.kt`
- Transport advertisement provider hook:
  - `android/transport/src/main/java/app/peermesh/transport/adapter/MeshTransport.kt`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
  - `android/transport/src/main/java/app/peermesh/transport/ble/nordic/NordicMeshTransport.kt`

### Discovery ingest path

- Screen trigger: `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt`
- Scan burst request: `BleMeshRuntime.get(...).requestDiscoveryBurst(...)`
- BLE scan parse and metadata dispatch:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- App discovery listener and DB upsert:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt`

### Diagnostics

- Activity-log label registration:
  - `android/app/src/main/java/app/peermesh/debug/ActivityLogBootReceiver.kt`
  - `android/app/src/main/java/app/peermesh/TestActivity.kt`
- BLE diagnostics logger:
  - `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`

## Current Status Snapshot

### Incremental Update: 2026-06-14 owner outbound message retry for room source fan-out

- New send rule implemented for local room owner:
  - owner should not treat the first failed nearby-room message broadcast as final failure
  - if owner cannot reach any active peer on the first attempt, the message stays local and is retried in background until at least one active peer is reached or the room becomes inactive/expired
- Implementation changes:
  - `NearbyRoomPeerSnapshotStore` now retains the latest advertised `room_id` per nearby device so owner-send targeting can prefer peers that are actively advertising the same room
  - added `NearbyRoomOwnerMessageRetryCoordinator` as a single-thread retry queue for owner-authored nearby-room messages
  - owner initial send in `NearbyRoomActivity` now targets only peers advertising the same `room_id`
  - if that owner-targeted send returns `false`, the message is queued for retry instead of surfacing immediate send-failed toast
  - once a retry hits at least one target, the message is marked `SENT` and relay-offer fan-out resumes from that message
- Current rationale:
  - room owner is the primary source for nearby-room message dissemination
  - transient absence of an active peer in scan/selection should not discard owner-originated messages
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - validate on devices that owner message stays local, retries in background, and flips to sent when a room peer comes back into range

### Incremental Update: 2026-06-14 periodic cleanup for expired/closed nearby rooms

- New maintenance flow implemented:
  - app now enqueues a dedicated `NearbyRoomCleanupWorker` on startup
  - worker runs via `WorkManager` every `30 minutes`
  - all DB work stays off the main thread because cleanup runs only inside the worker/repository background path
- Cleanup policy:
  - first mark any overdue `ACTIVE` rooms as `EXPIRED`
  - clean stale relay offer/want rows by their own expiry timestamps
  - then purge full local room data only for terminal rooms that have already been:
    - `EXPIRED` for at least `30 minutes`, or
    - `CLOSED` for at least `30 minutes`
- Rationale:
  - keeps local DB from accumulating dead nearby-room state
  - avoids deleting a room immediately at the same moment UI may still be showing an expiry/close dialog
- Implementation details:
  - `PeerMeshApp` now enqueues `NearbyRoomCleanupWorkManager`
  - repository adds `cleanupTerminalRoomData(...)`
  - DAO adds `listTerminalRoomIdsForCleanup(...)`
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - validate on device that terminal nearby-room rows disappear after retention without affecting active room UX

### Incremental Update: 2026-06-14 single active nearby-room ownership rule

- New product rule implemented:
  - each local user may own only one `ACTIVE` nearby room at a time
  - to create another nearby room, user must first close the current owned room
- Enforcement:
  - repository now checks for an existing local-owned active room before creating a new one
  - if one exists, create is rejected even if another code path bypasses the Discovery UI
  - Discovery create dialog now catches that rejection and shows a user-facing error toast instead of creating a second room
  - Discovery list refresh now also hides the `Create Nearby Room` button whenever the local DB already contains one local-owned active room
- Rationale:
  - keeps owner-side BLE advertisement/state simpler
  - avoids ambiguous multi-room ownership behavior for the same device
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - validate on device that pressing create while already owning one active room shows the rejection toast and does not create a second room

### Incremental Update: 2026-06-14 participant close-room dialog no longer auto-finishes on next timer tick

- Root cause found for the participant close-room regression:
  - on close receive, room data is purged immediately and close notice is stored
  - `NearbyRoomActivity` timer then loads `room == null`
  - first tick consumes the stored notice and shows the blocking owner-closed dialog
  - next tick sees `room == null` again, but the notice has already been consumed, so the old code fell through to `finish()`
  - result: participant saw the dialog and still got kicked out immediately before pressing `OK`
- Fix implemented:
  - when `room == null` and the close-room dialog is already shown or already handled, activity now stays in place instead of calling `finish()`
  - navigation back to Discovery remains only on dialog `OK`
- Remaining next:
  - compile and verify the participant close dialog stays on screen until explicit `OK`

### Incremental Update: 2026-06-14 join-time room access loading overlay and 60s aggressive bootstrap retry

- New join-time UI behavior implemented for participant peers:
  - when a peer has just entered a room but does not yet have room-chat access, the chat area is covered by a loading overlay
  - overlay reuses the same 4-dot loading animation as discovery, but larger and centered in the chat frame
  - overlay stays visible until room access is ready, instead of exposing the chat UI immediately
- Access-ready rule:
  - local owner: ready immediately
  - participant: ready only when:
    - room is still `ACTIVE`
    - bootstrap key is present locally
    - room metadata is already `RESOLVED`
- Retry behavior:
  - on room enter, participant starts a `60s` aggressive retry window
  - during that window, `NearbyRoomActivity` forces a key-request retry every `1s`
  - if access is still unavailable when the `60s` window expires, the app now shows a timeout toast and jumps back to discovery
- Implementation changes:
  - `activity_nearby_room.xml` now wraps the chat content in a `FrameLayout` and adds a blocking access-loading overlay
  - `NearbyRoomsLoadingDotsView` now supports configurable dot size/spacing so room-screen loader can be larger than discovery loader
  - `NearbyRoomTransportManager` now exposes `isChatAccessReady(roomId)`
  - `NearbyRoomActivity` now:
    - tracks a join-access retry deadline
    - toggles the overlay every timer tick
    - force-retries bootstrap requests during the first `60s` after room entry for non-owner peers
- Verified so far:
  - first compile attempt failed in `NearbyRoomsLoadingDotsView` due to Kotlin property initialization rules after adding configurable dot-size/spacing attrs
  - second compile attempt failed for the same view because Kotlin still rejected captured local-var initialization inside the styled-attrs lambda
  - fix updated again to use direct `obtainStyledAttributes(...)` / `recycle()` without lambda capture
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - validate on devices that a joining participant sees the loading cover until bootstrap key lands and chat access becomes available
  - validate that join access timeout exits back to discovery after `60s`

### Incremental Update: 2026-06-14 main-thread DB crash fixed for room access overlay

- Crash root cause identified from device report:
  - `NearbyRoomActivity` was querying Room-backed state from the main thread while evaluating join-access overlay state
  - specifically, UI-thread calls were reaching repository-backed access checks during activity start / timer refresh
- Fix implemented:
  - room access state calculation now runs on a background thread in `NearbyRoomActivity`
  - UI thread only receives and applies the computed access snapshot
  - room cover loader size was also reverted to match the same 4-dot size used on Discovery
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - verify on device B that entering Nearby Room no longer crashes from main-thread DB access

### Incremental Update: 2026-06-14 room access cover switched to tinted blur over chat background

- UI polish implemented for room access cover:
  - cover no longer uses a flat dark fill
  - cover now captures the underlying chat frame, applies a local bitmap blur, and displays it behind the loading dots
  - a translucent `colorSurface` tint is layered above the blurred snapshot so the cover follows the room/chat background color in both themes
  - room cover loading dots were aligned back to the same size as the Discovery loader
- Implementation details:
  - `activity_nearby_room.xml` overlay now contains:
    - blurred snapshot `ImageView`
    - translucent tint layer using `?attr/colorSurface`
    - existing loading dots centered above both
  - `NearbyRoomActivity` now regenerates the blurred snapshot when the access overlay becomes visible
  - blur implementation uses an in-app bitmap stack-blur path so it works on Android 11 without adding a new dependency
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - verify on device that the access cover visually matches the chat background with soft blur underneath

### Incremental Update: 2026-06-14 owner-only room close and blocking close/expiry dialogs

- Room close permission tightened:
  - participant peers can no longer close a nearby room
  - only the local owner now sees `Close room` in the room menu
- Owner close UX changed:
  - `Close room` menu item is now rendered in red
  - tapping it no longer closes immediately
  - app now shows a confirmation dialog warning that the room will be closed permanently and other members will lose access
  - only after user presses `OK` does the actual close/broadcast run
- Receiver/expiry UX changed:
  - when a non-owner peer learns that the owner closed the room, app now shows a blocking dialog with `OK`
  - pressing `OK` exits back to Discovery
  - when the room expires locally, app now also shows a blocking expiry dialog with `OK` and then exits to Discovery
- Additional behavior tightened:
  - Discovery list now hides all `CLOSED` and `EXPIRED` rooms, regardless of whether they originated locally or from BLE
  - on room-close receive, transport no longer forces the participant activity/session to jump out immediately
  - however, local DB purge still happens immediately on close receive so the stale room data disappears right away
  - room close is now only finalized at the UI/navigation level when the user presses `OK` in the close dialog
  - if a user opens a stale room entry and then receives the owner-close state while inside that room screen, the same close dialog flow is used before returning to Discovery
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - validate owner close confirm flow, participant no-close rule, remote close dialog timing, stale-open close dialog, and expiry dialog on device

### Incremental Update: 2026-06-13 room-close propagation rule agreed

- New rule accepted:
  - when owner closes a room, owner must broadcast an explicit close-room transport event
  - devices currently active in that room must mark the room closed locally when they receive that event
  - those active devices must relay the same close-room event once to nearby active peers
  - active room UI must then exit back to discovery
- Implementation direction:
  - add a dedicated nearby-room transport envelope kind for room closure
  - update receiver active-room handling so close notices are processed only for the matching active room
  - dedupe relays locally so one close event does not loop forever
  - make `NearbyRoomActivity` jump to discovery when local room state becomes `CLOSED`
- Remaining next:
  - implement transport kind, receiver close handling, relay dedupe, and active-room UI exit behavior
  - compile and verify the close-room path

### Incremental Update: 2026-06-13 room-close propagation implemented

- Transport/protocol implemented:
  - added `NEARBY_ROOM_CLOSED_V1` to nearby-room transport envelope/codec
  - envelope now also carries `closed_at_ms` for authoritative room-close timing
- Owner close flow implemented:
  - local owner close now:
    - marks room closed in DB
    - broadcasts `NEARBY_ROOM_CLOSED_V1`
    - exits active room and returns to discovery
- Receiver/relay flow implemented:
  - active-room receiver now handles `NEARBY_ROOM_CLOSED_V1`
  - on receive:
    - mark room closed locally
    - clear room key via repository close-from-transport path
    - switch nearby-room session state back to discovery
    - relay the same close notice once to nearby active peers
  - relay dedupe is done locally using `room_id + sender_device_id + closed_at_ms`
- UI behavior implemented:
  - `NearbyRoomActivity` now auto-navigates back to discovery when the loaded room state is `CLOSED`
  - this covers both local-owner close and remote close-notice receive
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passed
- Remaining next:
  - validate on devices that owner close produces:
    - one close broadcast from owner
    - one relay per receiving active peer
    - discovery-screen navigation on receivers
  - confirm duplicate close relays do not loop indefinitely in logs

### Incremental Update: 2026-06-13 remote close cleanup rule agreed

- New refinement:
  - when an active peer receives room-close from another peer, it should:
    - relay the close notice first
    - then delete that room and its related local room data from DB
    - then jump back to discovery UI
    - then show a toast that the room was closed by the owner
- Implementation direction:
  - add repository purge helper for room + participants + messages + invite quota + key
  - add a small UI notice bridge so transport-side close handling can request a toast on the active room screen before/while it exits

### Incremental Update: 2026-06-13 remote close cleanup implemented

- Remote close receive path changed:
  - on `NEARBY_ROOM_CLOSED_V1` receive by an active peer:
    - mark room closed locally
    - attempt one relay of the close notice
    - purge local room data:
      - room row
      - nearby room messages
      - nearby room participants
      - invite quota
      - room key
    - record a pending UI notice
    - switch nearby-room session back to discovery
- UI behavior changed:
  - `NearbyRoomActivity` now checks for a pending owner-close notice when the room disappears from DB
  - if present, it shows a toast that the room was closed by the owner, then navigates to discovery
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passed
- Remaining next:
  - validate on devices that receivers:
    - relay first
    - then remove the room from local DB
    - then show the owner-close toast while returning to discovery

### Incremental Update: 2026-06-14 raw expiry UI debug added

- Temporary debug rendering added:
  - discovery room list now shows raw `expires_at_ms` alongside the human `Expires in ...` label
  - active nearby-room screen now also shows raw `expires_at_ms` alongside the room expiry countdown
- Implementation note:
  - both render sites include an inline `TODO` comment to remove the raw debug suffix after expiry debugging is complete
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed

### Incremental Update: 2026-06-14 discovery list loading indicator added

- Discovery UI now shows a lightweight loading indicator while the rooms list is refreshing:
  - custom 4-dot animated loader
  - light-gray color for both light and dark theme
  - positioned near the bottom of the screen as an overlay above the discovery list
- Implementation detail:
  - added `NearbyRoomsLoadingDotsView`
  - hooked loader visibility into discovery refresh trigger/start/finish paths in `NearbyRoomsActivity`
  - loader is also hidden on `onPause()` and on refresh skip paths
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed

### Incremental Update: 2026-06-14 discovery reload cooldown and expiry authority tightened

- Discovery refresh gesture updated:
  - manual edge refresh cooldown is now `30s` instead of `60s`
  - when user hits cooldown, UI now shows a toast with the remaining seconds before reload is allowed
- Loader visual updated:
  - all 4 loading dots now use the same size as the original first/largest dot
- Expiry authority updated:
  - remote participant rooms now prefer owner-authoritative `expires_at_ms` from:
    - metadata response
    - key bootstrap transport
  - this replaces the previous `max(existing, authoritative)` merge behavior that could preserve a coarser, later BLE-derived expiry on participants
- Rationale:
  - the old merge logic could leave participant `expires_at_ms` ahead of owner by keeping a rounded-up advertisement-derived value
  - the new logic makes owner-provided transport metadata authoritative for remote rooms
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed

### Incremental Update: 2026-06-14 created_at_ms chosen as authoritative room-time field

- New decision:
  - room countdown should be anchored on owner-authoritative `created_at_ms`
  - `created_at_ms` is the UTC epoch-millis timestamp when the owner originally created the room
  - local `expires_at_ms` should be derived from `created_at_ms + ROOM_TTL_MS`
- Transport impact:
  - full metadata pull should consume owner `created_at_ms` instead of trusting owner `expires_at_ms`
  - joined-peer bootstrap sync should also consume owner `created_at_ms`
- Reason:
  - this gives one canonical origin timestamp for the room lifetime
  - it avoids preserving a participant-side expiry that drifted from advertisement rounding/merge behavior

### Incremental Update: 2026-06-14 raw room-time debug expanded for owner/member comparison

- Temporary debug rendering updated:
  - discovery room list now shows both raw `created_at_ms` and raw `expires_at_ms`
  - active nearby-room screen now also shows both raw fields next to the human countdown label
- Transport diagnostics updated:
  - `Nearby room key bootstrap send/recv` activity logs now include both `created_at_ms` and `expires_at_ms`
  - `Nearby room metadata response send/recv` activity logs now include both `created_at_ms` and `expires_at_ms`
- Purpose:
  - make owner/member drift analysis easier by exposing both the canonical room-creation timestamp and the derived expiry timestamp in UI and logs at the same time
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed

### Incremental Update: 2026-06-14 raw room-time UI debug removed after diagnostics pass

- UI cleanup completed:
  - removed raw `created_at_ms` / `expires_at_ms` suffixes from discovery rows
  - removed raw `created_at_ms` / `expires_at_ms` suffixes from the active room expiry line
- Diagnostics retained:
  - transport activity logs still include both fields for owner/member comparison during further BLE testing
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed

### Incremental Update: 2026-06-14 owner-authoritative created_at_ms enforcement tightened

- Root cause identified for owner/participant countdown drift:
  - BLE advertisement only carries coarse `ttlRemainingMinutes`, so deriving `created_at_ms` from advertisement was inherently lossy
  - discovery upsert previously used `max(existing.expires_at_ms, advertisement.expiresAtMs)`, which could ratchet participant expiry forward across repeated scans
  - resolved participants could then re-serve the drifted room-time to other peers
- Fix implemented:
  - BLE advertisement decode now sets `created_at_ms = 0` to make it explicit that advertisement is not an authoritative source for room creation time
  - discovery upsert no longer derives or ratchets authoritative room-time from advertisement once metadata has been resolved
  - unresolved discovery rows may keep coarse advertisement `expires_at_ms` only as a temporary liveness hint before full metadata is fetched
  - `created_at_ms` authoritative updates now come only from nearby-room transport metadata/bootstrap paths
  - transport envelopes now carry `owner_device_id` so receivers can preserve the true owner identity separately from the immediate sender
  - bootstrap sender identity now uses the actual sending device, while still carrying `owner_device_id` for authoritative post-join metadata targeting
  - post-join forced metadata refresh now prefers targeting the owner device when the bootstrap envelope includes it
- Expected behavior after this fix:
  - participant `created_at_ms` should converge to the exact owner-originated room creation timestamp
  - repeated discovery scans should no longer push participant countdown ahead of owner countdown
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed

### Incremental Update: 2026-06-14 short nearby-room transport wire format introduced

- New wire-format rule accepted and documented:
  - nearby-room transport should use short JSON field names and short `kind` values to reduce BLE payload overhead
  - rollout must remain backward compatible with the legacy long-field JSON format
- Mapping documented in both:
  - `docs/specs/NEARBY_ROOM_BLUEPRINT.md`
  - this development note
- Implementation direction:
  - keep the internal Kotlin envelope model unchanged
  - change only the codec boundary:
    - encode -> short wire format
    - decode -> accept both short and legacy long format
- Expected benefit:
  - meaningful payload reduction for nearby-room message/control packets without forcing broad code-path churn

### Incremental Update: 2026-06-14 relay recovery v1 spec written before implementation

- New spec direction documented first in `docs/specs/NEARBY_ROOM_BLUEPRINT.md`:
  - Nearby Room message recovery should use a lightweight pull-based relay layer, not DTN
  - DB remains authoritative on full `message_id`
  - relay layer adds `message_compact_id`
  - receiver dedupe gate uses `(room_id, message_compact_id)`
- Agreed packet set for v1:
  - `MO1` = message offer
  - `MF1` = message fetch request
  - `MD1` = message data response
- Agreed compact identity rules:
  - `room_id` stays compact at `6 bytes`
  - `message_compact_id` uses `12 bytes`
  - current derivation direction:
    - first `6 bytes` + last `6 bytes` of canonical full message id
- Agreed anti-loop / traffic rules:
  - sender fans out offer to nearby snapshot peers up to `10`
  - sender stores local offer ledger by `(room_id, message_compact_id, target_device_id)`
  - sender does not wait for target-side `ok/reject`
  - target accept/reject is local only and gates want-list upsert
  - do not send back toward:
    - message owner
    - message sender
- Agreed fairness rule:
  - want-list pull resolution should not permanently bias to one strongest peer
  - candidate sources should be resolved fairly, e.g. round-robin or randomized with cooldown
- Remaining next:
  - add data model/storage for offer ledger and want list
  - extend nearby-room transport codec/envelope with `MO1/MF1/MD1`
  - implement dedicated relay-recovery worker thread/state machine

### Incremental Update: 2026-06-14 relay recovery v1 implementation tranche started

- Completed in code for the first implementation tranche:
  - DB version bumped to `40`
  - added nearby-room relay persistence tables:
    - `nearby_room_relay_offers`
    - `nearby_room_relay_wants`
  - added nearby-room message `owner_device_id` storage so relay/data responses can preserve message origin separately from the current hop sender
  - added compact relay-id helper:
    - `NearbyRoomRelayIdCodec`
  - extended nearby-room transport envelope/codec with relay-recovery fields and packet kinds:
    - `MO1`
    - `MF1`
    - `MD1`
  - short-wire nearby-room codec now encodes/decodes those relay-recovery fields too
  - exposed a transport nearby-peer snapshot API:
    - initially added, then intentionally removed from shared transport after module-boundary review
  - added initial `NearbyRoomRelayRecoveryManager` with:
    - sender-side offer draining
    - target-side want draining
    - basic fetch timeout handling
  - moved nearby-peer snapshot ownership into nearby-room module:
    - `NearbyRoomPeerSnapshotStore`
    - fed by existing nearby-room discovery callbacks
- hooked relay manager into:
    - local nearby-room send success
    - remote `M2` message ingest success
    - incoming `MO1`
    - incoming `MF1`
    - incoming `MD1`
- Current implementation status:
  - this is the first runnable tranche, not the final tuned version
  - fairness and anti-loop behavior are implemented in a basic form and will likely need refinement after device testing
  - offer acceptance is local-only as agreed; sender does not wait for target ack
  - shared BLE transport APIs were trimmed back so nearby-room-specific peer snapshot logic now stays inside nearby-room code instead of `MeshTransport`
- Remaining next after compile/test:
  - verify schema/build correctness
  - validate end-to-end packet flow on devices:
    - local send -> offer queue
    - offer receive -> want insert
    - fetch request -> data response
    - data ingest -> onward offer
  - tighten fairness rotation / timeout behavior based on logs

### Incremental Update: 2026-06-13 post-join full metadata refresh added

- New rule implemented:
  - immediately after a member receives and stores `KEY_BOOTSTRAP`, force one full-metadata pull for that room
  - goal is to refresh `roomName` and re-confirm authoritative `expires_at_ms` from the room source right after join
- Implementation completed:
  - `NearbyRoomTransportReceiver` now treats successful bootstrap-key storage as the trigger for a post-join metadata refresh
  - `NearbyRoomMetadataFetchCoordinator` now supports a forced request path with:
    - `force=true`
    - optional target-device override
  - the forced path bypasses the normal "already resolved / retry interval" gate so a newly joined member can refresh immediately even if discovery metadata had already been resolved earlier
  - the post-join refresh targets the bootstrap sender first, instead of waiting for a later generic discovery refresh cycle
- Effect:
  - join flow now becomes:
    - receive/store room key
    - sync expiry from bootstrap envelope immediately
    - enqueue forced full metadata request
    - update expiry again if metadata response provides the stronger authoritative value
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passed
  - one parallel compile/test run hit transient Kotlin incremental/compiler cache noise, but standalone rerun passed cleanly
- Remaining next:
  - validate on device that right after join there is exactly one forced metadata request
  - confirm logs show `KEY_BOOTSTRAP` followed by:
    - `Nearby room post-join metadata refresh trigger`
    - `Nearby room metadata fetch queued` with `reason=post_join`
    - `Nearby room metadata fetch started`
    - `Nearby room metadata fetch send`
    - `Nearby room metadata response recv` or `timeout`
  - confirm member expiry alignment tightens after that sequence

### Incremental Update: 2026-06-13 post-join diagnostics expanded

- Additional diagnostics added so device-log review can trace one full post-join metadata cycle without inference:
  - `Nearby room post-join metadata refresh trigger`
  - `Nearby room metadata fetch queued`
  - `Nearby room metadata fetch started`
  - `Nearby room metadata fetch send`
  - `Nearby room metadata fetch resolved`
  - `Nearby room metadata fetch finished`
  - existing skip/timeout logs now also include `fetch_reason` and `force`
- Implementation detail:
  - metadata fetch coordinator now keeps richer per-room request state in both pending and in-flight maps
  - each request records:
    - `reason = discovery | post_join`
    - `force = true | false`
    - optional target-device override
- Verified so far:
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed when run sequentially
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passed when run sequentially
- Remaining next:
  - install and test on devices
  - inspect whether post-join path logs exactly one queued/started/send cycle per bootstrap

### Incremental Update: 2026-06-13 burst dedupe winner now uses real RSSI

- Spec/blueprint updated first to lock the intended rule:
  - one DB upsert per `roomId` per discovery burst
  - choose the burst winner before any discovery-row DB write
  - persist and later fetch metadata from that same winning source device
- Implementation completed:
  - `NearbyRoomDiscoveryListener` now carries `rssi`
  - `BleMeshTransport` now forwards scan-result RSSI when dispatching nearby-room discovery metadata
  - `NearbyRoomBleCoordinator` now records buffered sightings with real RSSI instead of placeholder `0`
  - discovery buffering logs now include RSSI so burst winner selection can be audited from activity logs
- Effect:
  - `NearbyRoomDiscoveryAccumulator` can now actually enforce the agreed "strongest source wins, recency tie-break" rule
  - the later single DB upsert for the burst now reflects signal quality rather than raw scan callback order
- Verified so far:
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passed
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed
  - one earlier standalone compile run hit transient Kotlin incremental/compiler state in `transport`, but immediate rerun passed cleanly
- Remaining next:
  - install the updated debug build and re-check device logs to confirm one winning source per room per burst and reduced DB churn in discovery
  - if device behavior is still noisy, add a flush-level log that explicitly prints burst winner vs discarded candidates for each `roomId`

### Incremental Update: 2026-06-13 TTL relay-collapse fix in progress

- New log set analyzed:
  - `logs/device-A_activity_log_live_20260613_211607-10684023.txt`
  - `logs/device-C_activity_log_live_20260613_211603-a39aadf9.txt`
- Confirmed issue:
  - nearby-room advertisement TTL byte was decaying too aggressively once multiple devices advertised the same `roomId`
  - symptom matched user report that visible expiration could appear to drop roughly one minute per second
- Refined root cause:
  - BLE discovery encode used floor division in minutes, so each relay hop could shave off up to 59 seconds
  - worse, `NearbyRoomRepository.upsertDiscoveredBleRoom()` allowed BLE discovery advertisements to overwrite `expires_at_ms` even for the local-owner room
  - with A, B, C all advertising the same room, shortened TTL values fed back into A/B/C repeatedly and collapsed room lifetime quickly
- Patch now added in working tree:
  - `NearbyRoomBleCodec.encode()` now rounds active TTL up to the next minute and keeps a minimum advertised TTL of 1 minute for any still-active room
  - `NearbyRoomRepository.upsertDiscoveredBleRoom()` now preserves authoritative local-owner room lifetime instead of letting relayed BLE discovery overwrite it
  - remote discovered rooms now keep the maximum known `expires_at_ms` from BLE discovery instead of shrinking on each relay/update
  - added a codec regression test for the minimum-1-minute TTL behavior
- Verified so far:
  - code patch applied cleanly
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomBleCodecTest --tests app.peermesh.nearby.NearbyRoomPolicyTest --tests app.peermesh.nearby.NearbyRoomTransportCodecTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passed
  - official debug Kotlin compile also passed as part of the same Gradle invocation (`:app:compileOfficialDebugKotlin`)
- Remaining next:
  - validate on-device whether expiry no longer collapses when multiple participants advertise the same room
  - if drift still appears, add explicit expiry diagnostics to discovery upsert logs (`advertised_expires_at_ms`, `stored_expires_at_ms`, source device)

### Incremental Update: 2026-06-13 discovery UI visibility gated on full metadata

- Product rule added:
  - a discovered room must not be shown in the discovery UI until full metadata has been resolved
  - in practice this means `metadata_status` must be `RESOLVED` before an active remote room is visible
- Patch applied:
  - `NearbyRoomDao.listVisible()` now filters active rooms by `metadata_status = RESOLVED`
  - unresolved discovered rooms still exist in DB and still participate in metadata fetch queue, but they remain hidden from the list UI until metadata response lands
- Verified so far:
  - code patch applied cleanly
  - `./gradlew --no-daemon :app:compileOfficialDebugKotlin` passed after the DAO visibility filter change
- Remaining next:
  - validate on device that discovery list remains empty until metadata response resolves each room

### Incremental Update: 2026-06-13 full metadata narrowed to roomName + expiresAtMs

- Product/protocol decision finalized:
  - advertisement discovery metadata remains minimal
  - full metadata only needs:
    - `roomName`
    - authoritative `expiresAtMs`
  - `createdAtMs` is not required in full metadata for current countdown needs
- Implementation change:
  - `NearbyRoomTransportReceiver` now passes `envelope.expires_at_ms` into repository metadata resolution
  - `NearbyRoomRepository.markRoomMetadataResolved()` now stores authoritative expiry from metadata response and recomputes `created_at_ms` from that expiry using `ROOM_TTL_MS`
  - this lets discovery UI converge to owner-authoritative room expiry after metadata fetch instead of staying on coarse advertisement TTL only
- Documentation/spec change:
  - `docs/specs/NEARBY_ROOM_BLUEPRINT.md` now explicitly documents:
    - BLE advertisement discovery payload structure
    - full metadata response structure
    - fetch flow: advertisement discovery first, metadata pull second, UI visible only after resolution
- Verified so far:
  - code patch applied cleanly
  - `./gradlew --no-daemon :app:testOfficialDebugUnitTest --tests app.peermesh.nearby.NearbyRoomBleCodecTest --tests app.peermesh.nearby.NearbyRoomPolicyTest --tests app.peermesh.nearby.NearbyRoomTransportCodecTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest :app:compileOfficialDebugKotlin` passed
- Remaining next:
  - validate on device that metadata response now tightens countdown to owner-authoritative expiry after room resolution

### Incremental Update: 2026-06-13 hidden-unresolved discovery regression fixed

- New regression observed in logs:
  - C received and upserted advertisement discovery metadata for A successfully
  - however discovery UI still showed `rooms=0`
  - no metadata fetch followed for the unresolved room
- Root cause:
  - after adding the rule "hide unresolved rooms from discovery UI", `NearbyRoomsActivity` was still enqueueing metadata fetch from `listVisibleRooms()`
  - unresolved rooms were therefore hidden from UI and also excluded from the metadata fetch queue
  - result: unresolved rooms became permanently invisible because they never progressed to `RESOLVED`
- Patch applied:
  - added `NearbyRoomRepository.listActiveRooms()`
  - discovery screen now:
    - reads `listActiveRooms()` to enqueue metadata fetch
    - reads `listVisibleRooms()` for UI rendering
  - refresh log now includes both `rooms` and `active_rooms` counts for easier diagnosis
- Verified so far:
  - code patch applied cleanly
- Remaining next:
  - compile and re-test on device to confirm unresolved hidden rooms still trigger metadata fetch and then appear after resolution

### Incremental Update: 2026-06-13 member countdown drift narrowed via key-bootstrap expiry sync

- New log analysis:
  - owner A kept advertising only coarse `ttlRemainingMinutes`
  - member C did not receive any `NEARBY_ROOM_METADATA_RESPONSE_V1` in the observed window
  - after join, C did receive `KEY_BOOTSTRAP` from owner
  - `KEY_BOOTSTRAP` envelope already carries exact `expires_at_ms`, but code was only storing the room key and ignoring the authoritative expiry
- Root cause for post-join countdown drift:
  - member could join and receive room key successfully while still keeping a coarse advertisement-derived room expiry
  - without metadata response, countdown could remain almost one minute behind owner
- Patch applied:
  - added `NearbyRoomRepository.syncRoomExpiryFromTransport()`
  - `NearbyRoomTransportReceiver` now calls that sync when `KIND_KEY_BOOTSTRAP_V1` is received
  - effect: after join/bootstrap, member room expiry is promoted to the authoritative `expires_at_ms` carried by owner transport
- Verified so far:
  - code patch applied cleanly
- Remaining next:
  - compile and re-test on device to confirm member countdown aligns after key bootstrap

## Current Discovery Payload Contract

### BLE advertisement discovery metadata

- `protocolVersion`: `1 byte`
- `roomId`: `6 bytes`
- `participantCount`: `1 byte`
- `ttlRemainingMinutes`: `1 byte`
- `nameLength`: `1 byte`
- total nearby-room discovery payload currently emitted: `10 bytes`
- current `nameLength` is `0` because room name is no longer carried in advertisement

### Full metadata response (`NEARBY_ROOM_METADATA_RESPONSE_V1`)

- transport envelope contains:
  - `room_id`
  - `sender_device_id`
  - `owner_device_id` optional
  - `sender_display_name` optional
  - `created_at_ms` authoritative owner room-creation time
  - `expires_at_ms` derived room expiry carried for compatibility/debug
  - `target_device_id`
  - `room_name`
  - `participant_count`
- app-layer full metadata currently consumed from that response:
  - `room_name`
  - `created_at_ms`
  - `participant_count`
  - `sender_display_name` optional

## Current Fetch Flow

1. Discovery screen triggers BLE discovery burst.
2. Scan-side advertisement discovery payload is decoded and inserted into DB as a hidden unresolved room.
3. Hidden unresolved room keeps:
   - placeholder name
   - `metadata_status = PENDING`
   - `metadata_source_device_id = best source seen so far`
4. Metadata fetch coordinator enqueues unresolved room ids.
5. Queue sends targeted `NEARBY_ROOM_METADATA_REQUEST_V1` with low concurrency.
6. Advertiser replies with `NEARBY_ROOM_METADATA_RESPONSE_V1`.
7. Repository resolves:
   - full `room_name`
   - authoritative `created_at_ms`
8. Only then does `metadata_status = RESOLVED`, and the room becomes visible in discovery UI.

## Nearby Room Transport Wire Mapping

Current agreed wire-format rule:

- encode nearby-room transport using short JSON keys and short `kind` values
- decode must remain backward compatible with legacy long JSON keys and long `kind` values during rollout

### Short `kind` mapping

- `M2` -> `NEARBY_ROOM_MESSAGE_V2`
- `KB1` -> `NEARBY_ROOM_KEY_BOOTSTRAP_V1`
- `KR1` -> `NEARBY_ROOM_KEY_REQUEST_V1`
- `MR1` -> `NEARBY_ROOM_METADATA_REQUEST_V1`
- `MS1` -> `NEARBY_ROOM_METADATA_RESPONSE_V1`
- `RC1` -> `NEARBY_ROOM_CLOSED_V1`

### Short field mapping

- `k` -> `kind`
- `r` -> `room_id`
- `sd` -> `sender_device_id`
- `od` -> `owner_device_id`
- `sn` -> `sender_display_name`
- `ct` -> `created_at_ms`
- `ex` -> `expires_at_ms`
- `mid` -> `message_id`
- `ctxt` -> `ciphertext`
- `n` -> `nonce`
- `kv` -> `key_version`
- `rk` -> `room_key`
- `td` -> `target_device_id`
- `rn` -> `room_name`
- `pc` -> `participant_count`
- `cl` -> `closed_at_ms`

## Current Direction For Burst Deduping

New agreed rule:

- advertisement discovery should not upsert DB on every scan callback
- during one discovery burst, each `roomId` should be written to DB at most once
- if many advertisers relay the same `roomId`, only one best candidate source should win for that burst
- best candidate should prefer stronger RSSI, with recency as tie-breaker
- full metadata fetch should also be seeded from that same best candidate source
- burst room budget should be bounded, currently targeting `10` rooms maximum
- the burst winner must be chosen before any DB upsert for that `roomId`, so callback order does not override the stronger source

Reason:

- this reduces DB churn
- this reduces repeated expiry churn from many relay sources
- this prevents multiple advertisers from repeatedly overwriting the same room inside one burst
- this keeps advertisement discovery lightweight while still allowing full metadata follow-up

### What is fixed

- The earlier failure mode where app state advanced before transport accepted advertisement refresh has been instrumented and addressed.
- `refreshNearbyRoomAdvertisement()` now reports acceptance/rejection state cleanly enough to distinguish transport timing issues from app-layer payload issues.
- BLE codec now supports UTF-8 room names, including emoji, and no longer rejects non-ASCII names purely because they are not printable ASCII.
- The BLE codec also had an off-by-one payload sizing bug:
  - payload allocation used `5 + ROOM_ID_BYTES + nameBytes.size`
  - the actual header size is `4 + ROOM_ID_BYTES + nameBytes.size`
  - this caused otherwise valid names such as `Room A #1` to overflow the 17-byte cap and collapse to an empty payload

### What is still failing

- Nearby Rooms discovery still does not produce usable room metadata on other devices.
- The latest logs show that the bottleneck has moved again:
  - transport-side rejection is no longer the primary blocker in the observed test window
  - the remaining product/design blocker is BLE advertisement payload budget

## Main Issue Being Debugged

### Symptom

Device C still does not see the Nearby Room created by device A in Rooms Listing, even when A is active inside the room.

### Log set

- `logs/device-A_activity_log_live_20260613_174231-0d057fe2.txt`
- `logs/device-C_activity_log_live_20260613_174228-16c2373b.txt`

## What The Logs Showed

### On device A

- The app-side room advertisement state was updated after entering the room:
  - `Nearby room advertisement state ... updated_payload_bytes=17`
- This means the app cache already contained a room metadata payload for BLE advertisement.
- However, there were no matching transport-side confirmation labels such as:
  - `Nearby room advertise provider`
  - `Nearby room advertise payload`
- A kept logging repeated:
  - `Nearby room advertisement refresh skipped ... reason=unchanged_payload`

### On device C

- C had nearby presence before entering Rooms Listing:
  - `NEARBY_COUNT_BREAKDOWN ... nearby_total=1`
- Then C opened discovery and triggered multiple overlapping scan bursts immediately:
  - token `1`, `2`, `3` at screen entry
  - later token `6`, `7`, `8`, `9`
- Several bursts were restored with:
  - `reason=token_superseded`
- C re-read local DB and kept getting:
  - `Nearby rooms refresh db done ... rooms=0`
- In that log window there was no evidence of successful metadata ingest:
  - no `Nearby room discovery metadata recv`
  - no `Nearby room discovery metadata decoded`
  - no `Nearby room discovery metadata upserted`

## Root Cause Analysis

## Root cause 1: Advertisement state was remembered too early

- `NearbyRoomBleCoordinator` was remembering the last advertised `(roomId, payload)` before knowing whether transport had actually accepted and applied the advertisement refresh.
- If the first refresh happened while transport was not ready to restart advertising, the app-layer cache moved forward but the BLE advertiser could remain on the old manufacturer payload.
- After that, every later refresh for the same payload was skipped as `unchanged_payload`, so the radio could stay stuck advertising base-only metadata with no nearby-room payload.

### Why this matches the logs

- A had `updated_payload_bytes=17`, so app state was correct.
- A did not emit `Nearby room advertise provider` / `Nearby room advertise payload`, so transport-side re-advertise likely did not actually happen for that payload revision.
- A then repeatedly skipped future refreshes as unchanged.

## Root cause 2: Rooms Listing generated overlapping discovery bursts

- `NearbyRoomsActivity` triggered discovery refresh too many times on screen entry:
  - once in `onCreate()`
  - again in `onResume()`
  - again immediately from the refresh loop
- This produced scan bursts that superseded each other before settling.
- The overlap increased noise and made discovery timing harder to reason about.

### Why this matches the logs

- C showed multiple `Nearby rooms refresh trigger` entries within the same second.
- C showed `restore_skipped ... reason=token_superseded` for multiple tokens.

## Patch Landed In Working Tree

### 1. Transport advertisement refresh now returns acceptance status

Changed:

- `android/transport/src/main/java/app/peermesh/transport/adapter/MeshTransport.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/nordic/NordicMeshTransport.kt`

Behavior:

- `refreshNearbyRoomAdvertisement()` now returns `Boolean`.
- `true` means the transport accepted the refresh request.
- `false` means transport was not ready, usually because it was not running or not in `FULL_RELAY`.

### 2. App only remembers advertised state after transport accepts refresh

Changed:

- `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt`

Behavior:

- The coordinator now checks whether the requested `(roomId, payload)` differs from the last applied state.
- It only persists that state after `boundTransport?.refreshNearbyRoomAdvertisement() == true`.
- If transport is unavailable, it logs:
  - `Nearby room advertisement refresh deferred`
- This keeps the change pending so a later timer tick can retry instead of being permanently suppressed by `unchanged_payload`.

### 3. Initial discovery burst storm was reduced

Changed:

- `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt`

Behavior:

- Removed the extra initial discovery refresh from `onCreate()`.
- `startRefreshLoop()` now schedules the next auto-refresh after `REFRESH_INTERVAL_MS` instead of posting one immediately.
- The discovery screen should now do one initial refresh on `onResume()` rather than stacking several bursts at startup.

### 4. Added new diagnostics label for the deferred case

Changed:

- `android/app/src/main/java/app/peermesh/debug/ActivityLogBootReceiver.kt`
- `android/app/src/main/java/app/peermesh/TestActivity.kt`

New label:

- `Nearby room advertisement refresh deferred`

### 5. Added explicit diagnostics for advertisement refresh rejection

Changed:

- `android/transport/src/main/java/app/peermesh/transport/adapter/MeshTransportDiagnosticsListener.kt`
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`
- `android/app/src/main/java/app/peermesh/debug/ActivityLogBootReceiver.kt`
- `android/app/src/main/java/app/peermesh/TestActivity.kt`

Behavior:

- Transport now emits a dedicated diagnostics callback when nearby-room advertisement refresh is rejected before enqueue.
- The callback logs:
  - `running`
  - `mesh_mode`
  - a structured reason such as:
    - `transport_not_running`
    - `mesh_mode_not_full_relay`
- New label:
  - `Nearby room advertisement refresh rejected`

### 6. BLE codec now accepts UTF-8 room names and fixes payload sizing

Changed:

- `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCodec.kt`
- `android/app/src/test/java/app/peermesh/nearby/NearbyRoomBleCodecTest.kt`

Behavior:

- Room names are encoded as UTF-8 instead of ASCII-only.
- Name truncation is now byte-budget based without cutting through a Unicode code point.
- UTF-8 decode is strict so malformed byte sequences are still rejected.
- The payload sizing bug was fixed so valid names that fit the wire budget are no longer turned into empty payloads by mistake.

## Latest Test Outcome

### Log set

- `logs/device-A_activity_log_live_20260613_182226-eb7a714c.txt`
- `logs/device-C_activity_log_live_20260613_182223-e3ea3ce3.txt`

### What those logs changed in the diagnosis

- The new run did **not** show repeated `refresh deferred` or `refresh rejected`.
- Instead, device A repeatedly logged:
  - `Nearby room advertisement state ... updated_payload_bytes=0`
  - followed by:
  - `Nearby room advertisement refresh skipped ... reason=unchanged_payload`
- Device C still only saw:
  - `mfg_bytes=10`
  - `nearby_payload_bytes=0`
  - `has_payload=false`
- So the latest failure was no longer transport readiness.
- At that point the real blocker became visible:
  - the BLE advertisement format only reserves 7 bytes for room name inside a 17-byte payload

## Current Design Constraint

### BLE advertisement budget is too small for human room names

- Current BLE wire budget:
  - total nearby-room payload cap: `17` bytes
  - room name byte budget inside that payload: `7` bytes
- This is not 7 characters.
- It is 7 UTF-8 bytes, which means:
  - short ASCII names may survive partially
  - Vietnamese names will truncate much earlier
  - emoji consume even more of the budget

### Product requirement that now matters

- Desired app behavior:
  - room names should support Vietnamese and emoji
  - room names should be allowed up to about 50 user-visible characters
  - a full metadata representation around 100 UTF-8 bytes is reasonable

- Current BLE advertisement design cannot satisfy that requirement directly.

## Recommended Direction

### Move to short advertisement plus full metadata fetch

Recommended protocol change:

1. Keep BLE advertisement minimal.
2. Advertise only compact discovery metadata such as:
   - `roomId`
   - `participantCount`
   - TTL / freshness
   - optional tiny preview only if it still fits safely
3. When another device discovers a candidate room, fetch full nearby-room metadata over GATT.
4. Carry the full UTF-8 room name in that fetched metadata payload, with a larger budget such as:
   - max 50 characters at app/UI level
   - max ~100 UTF-8 bytes on the fetched metadata wire format

### Why this is the right direction

- It decouples human-readable naming from the very tight BLE advertisement packet budget.
- It avoids fragile tradeoffs where every extra name byte crowds out other discovery fields.
- It allows Vietnamese and emoji support without forcing advertisement payload inflation.
- It fits the product goal better:
  - advertisement answers “something discoverable is nearby”
  - GATT metadata fetch answers “what is the actual room name and richer metadata”

## Expected Result After Next Test

### On device A

If the current wire format is still used, A should at least show:

- non-zero `updated_payload_bytes`
- then:
  - `Nearby room advertise provider`
  - `Nearby room advertise payload`
  - `BLE advertise started`

If the next protocol step is implemented, A should advertise compact room discovery metadata and make full room metadata fetchable over GATT.

### On device C

With the current design, C should at least start seeing non-zero nearby-room advertisement payload.

With the recommended redesign, C should:

- discover candidate rooms from advertisement
- fetch full metadata over GATT
- log:
  - `Nearby room discovery metadata recv`
  - `Nearby room discovery metadata decoded`
  - `Nearby room discovery metadata upserted`
- and then:
  - `Nearby rooms refresh db done ... rooms=1` or more

## If The Next Test Still Fails

The next most likely problem areas to inspect are:

1. Short advertisement schema for Nearby Rooms must be finalized.
2. Full metadata fetch path over GATT must be defined and implemented.
3. Discovery-side state machine must decide when to fetch full metadata versus only trust advertisement cache.
4. Manufacturer data assembly must still be rechecked once the compact advertisement schema is changed.
5. Listener binding order and discovery burst timing should still be re-verified after protocol changes.

## Recommended Next Test Checklist

1. Build and install the current working tree on all test devices.
2. Start activity logging with discovery markers enabled.
3. On A, create a room and stay active inside it.
4. On C, enter Rooms Listing and wait for the first discovery burst.
5. Confirm whether A logs:
   - non-zero `updated_payload_bytes`
   - `Nearby room advertise payload`
6. Confirm whether C logs either:
   - compact payload discovery with fetch trigger
   - or full metadata upsert after fetch, depending on implementation stage
7. Compare `rooms=` value in `Nearby rooms refresh db done`

## Current Expected Decision Boundary

- If A still produces `updated_payload_bytes=0`, the next bug is app-layer encoding/state assembly.
- If A produces non-zero payload and C still only sees `has_payload=false`, the next bug is advertisement assembly or scan parse.
- If compact advertisement works but full metadata never lands, the next bug is in the GATT metadata fetch path.

## Implementation Progress

### 2026-06-13 - Phase 2 discovery/fetch split in progress

Completed in code:

1. Kept BLE advertisement on the compact path.
2. Limited advertisement name handling to the tiny preview budget only.
3. Increased app-level room-name policy to:
   - max 50 characters
   - max 100 UTF-8 bytes
   - UTF-8 safe truncation without splitting emoji/code points
4. Added nearby-room metadata tracking in DB:
   - `metadata_status = PENDING|RESOLVED|FAILED`
   - `metadata_last_fetched_at`
   - `metadata_source_device_id`
5. Added DB migration `38 -> 39` for the new nearby-room metadata fields.
6. Changed discovery upsert behavior:
   - advertisement insert now stores a preview name only
   - latest advertiser is tracked separately in `metadata_source_device_id`
   - resolved full names are preserved instead of being overwritten by preview payloads
7. Added nearby-room metadata transport envelopes:
   - `NEARBY_ROOM_METADATA_REQUEST_V1`
   - `NEARBY_ROOM_METADATA_RESPONSE_V1`
8. Added targeted metadata fetch sending over existing directed BLE envelope path.
9. Added metadata response handling in `NearbyRoomTransportReceiver`.
10. Added a small fetch queue:
   - bounded concurrency = 2 rooms at a time
   - timeout-based fail marking
   - queue skips local/resolved/ineligible rooms
11. Hooked queueing into UI refresh flow:
   - discovery first upserts rooms into DB
   - room list refresh then enqueues unresolved rooms for full metadata fetch
   - room detail screen also enqueues a metadata refresh for that room

Current verification status:

- `./gradlew :data:clean :app:compileEmergencyDebugKotlin` passes after the above changes.
- `./gradlew :app:testEmergencyDebugUnitTest --tests app.peermesh.nearby.NearbyRoomBleCodecTest --tests app.peermesh.nearby.NearbyRoomPolicyTest --tests app.peermesh.nearby.NearbyRoomTransportCodecTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passes.
- `./gradlew :data:compileReleaseKotlin` passes and generated tracked schema `android/data/schemas/app.peermesh.data.db.AppDatabase/39.json`.
- Sequential build is required; parallel Gradle runs in this repo can corrupt Kotlin incremental caches and produce misleading failures.

Still pending from this phase:

1. Re-test on devices A/C to confirm:
   - discovery insert happens before metadata fetch
   - metadata requests are targeted to the tracked best candidate advertiser
   - full UTF-8 room names resolve back into the DB/UI
2. Decide whether “best candidate advertiser” should remain “latest sighting wins” or whether the listener contract should be widened to carry RSSI into app-layer selection.

### 2026-06-13 - New blocker from logs `191522/191519`

Observed failure:

- Device C still does not see room A in discovery.
- This failure happens before DB upsert / metadata fetch.

Evidence from device C log:

- During discovery windows, C repeatedly sees device A with:
  - `mfg_bytes=10`
  - `base_bytes=10`
  - `nearby_payload_bytes=0`
  - `has_payload=false`
- Example lines:
  - `19:15:41.054` discovery burst start
  - `19:15:45.839` refresh DB done with `rooms=0`
- No `Nearby room discovery metadata recv/decoded/upserted` events occur on C for room A.

Evidence from device A log:

- A initially advertises no nearby payload:
  - `19:15:22.917` `nearby_payload_bytes=0`
- After entering active room `6a9405421c04`, A believes it refreshed successfully:
  - `19:15:36.411` `Nearby room advertise payload ... nearby_payload_bytes=17 has_payload=true`
  - `19:15:37.426` same
  - `19:15:40.431` same
- However, peers still only observe the 10-byte base manufacturer payload, so the intended nearby payload is not actually visible over the air.

Current hypothesis:

1. App-layer cache/codec is now producing the compact payload correctly.
2. The remaining bug is below app-layer decode and above peer DB ingest:
   - either advertiser refresh is not replacing the live manufacturer payload on-air
   - or the scan parser on peers is still reading only the base manufacturer region while ignoring appended nearby bytes
3. The repeated `BLE advertise started` log on A is not sufficient proof that peers receive the updated payload.
4. Later `mesh_mode=RECEIVE_ONLY` transitions on A worsen refresh reliability, but they do not explain the earlier miss because C already fails to see payload during the earlier window when A logs `has_payload=true`.

Next investigation target:

1. Inspect transport BLE advertiser path that assembles manufacturer data after `Nearby room advertise provider` returns 17 bytes.
2. Compare the actual bytes inserted into manufacturer payload vs the bytes extracted by peer scan-side `nearbyRoomPayload(record)`.
3. Add explicit diagnostics for:
   - outgoing manufacturer data total bytes and hex on advertiser side
   - incoming extracted nearby suffix bytes and hex on scan side

### 2026-06-13 - Follow-up fix for blocker `191522/191519`

Completed in code:

1. Added richer diagnostics for nearby-room BLE payloads:
   - advertiser-side now logs manufacturer-data hex and nearby-payload hex
   - scanner-side now logs manufacturer-data hex and extracted nearby-payload hex
2. Corrected BLE advertise logging semantics:
   - `BLE advertise started` should now correspond to callback success
   - transport no longer marks advertise success immediately after issuing the start request
3. Reduced nearby-room advertisement payload to minimal discovery data only:
   - advertisement no longer carries room-name preview bytes
   - full room name remains metadata-fetch only
4. Updated discovery ingest so an empty advertised name still inserts a room into DB with placeholder title:
   - placeholder = `Nearby room`
   - metadata fetch is responsible for resolving the real UTF-8 room name afterward

Refined hypothesis:

- The old compact payload still produced `manufacturerData=27 bytes`.
- With BLE advertising overhead, that is very likely too large for the actual advertising packet budget even though the app-layer codec itself returned a 17-byte nearby payload.
- This explains why the app believed it had payload ready while peers kept seeing only the 10-byte base manufacturer packet.

Current verification status after this fix:

- `./gradlew :transport:clean :app:compileEmergencyDebugKotlin :app:testEmergencyDebugUnitTest --tests app.peermesh.nearby.NearbyRoomBleCodecTest --tests app.peermesh.nearby.NearbyRoomPolicyTest --tests app.peermesh.nearby.NearbyRoomTransportCodecTest --tests app.peermesh.nearby.NearbyRoomDiscoveryAccumulatorTest` passes.

Next test expectation:

1. On advertiser A:
   - `Nearby room advertise payload` should now show:
     - `mfg_bytes=20`
     - `base_bytes=10`
     - `nearby_payload_bytes=10`
     - `has_payload=true`
2. On scanner C:
   - at least some scan records for A should now show:
     - `mfg_bytes=20`
     - `nearby_payload_bytes=10`
     - `has_payload=true`
   - then discovery should proceed into DB upsert and metadata fetch

### 2026-06-13 - Crash on device C during discovery ingest

Observed crash on device `251fd3e9`:

- Timestamp: `2026-06-13 19:39:05`
- Process: `app.peermesh`
- Top exception:
  - `IllegalStateException: Cannot access database on the main thread`
- Stack root:
  - `NearbyRoomDao_Impl.findById`
  - `NearbyRoomRepository.upsertDiscoveredBleRoom`
  - `NearbyRoomBleCoordinator.bind ... onNearbyRoomMetadata`
  - `BleMeshTransport$scanCallback$1.onScanResult`

Interpretation:

- BLE scan callback was delivering nearby-room metadata on the main thread.
- `NearbyRoomBleCoordinator` was doing DB discovery upsert inline inside that callback.
- As soon as scan-side discovery began seeing nearby payloads, Room main-thread protection crashed the app before metadata fetch could continue.

Completed fix:

1. Moved nearby-room discovery ingest work in `NearbyRoomBleCoordinator` onto a dedicated background `HandlerThread`.
2. Copied the incoming payload before posting work so callback-thread ownership is not reused unsafely.

Verification:

- `./gradlew :app:compileEmergencyDebugKotlin` passes after the crash fix.

Next expectation after rebuild/install:

- Device C should no longer crash on first nearby discovery callback.
- If advertisement payload is now visible over the air, C should proceed to:
  - `Nearby room discovery metadata recv`
  - `Nearby room discovery metadata decoded`
  - `Nearby room discovery metadata upserted`
  - then queued metadata fetch
