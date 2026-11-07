# Nearby Rooms Phase 1 Backlog

Context: technical backlog for starting Nearby Rooms implementation based on [docs/specs/NEARBY_ROOM_BLUEPRINT.md](../specs/NEARBY_ROOM_BLUEPRINT.md), mapped onto the current Android repo structure and concrete files/modules.

Important constraint: Nearby Rooms only operate within the BLE mesh.
- No cloud discovery.
- No internet room listing.
- No DTN transport.
- No relay-first architecture for rooms.
- No background multi-room transport processing.
- Do not use private-group `gid` as the room identity.
- All UX and data flows must reinforce that the room is temporary, nearby-only, and public within physical proximity.

## Security model for Nearby Rooms

Nearby Rooms must not use the same security model as Private Groups.

### Product boundary

Private Group:
- private
- persistent
- invitation/passphrase based
- encrypted with a group key

Nearby Room:
- public within BLE proximity
- temporary
- no passphrase
- no owner approval
- must not promise strong privacy

Technical consequences:
- do not reuse Private Group `gid` or `group key` for Nearby Rooms
- do not force Nearby Rooms through the current secure-group envelope flow just to align the domain model
- do not let the UX imply that room chat is private E2EE

### Phase 1 security posture

Phase 1 is local data plus a UI skeleton, so it does not need a dedicated encrypt/decrypt implementation for Nearby Rooms yet.

This must still be decided up front:
- local room data is ephemeral-first
- cleanup after expiry/close is mandatory
- UI copy must warn that the room is visible to nearby people

### BLE metadata rule

When Phase 2 starts:
- BLE room advertisement may only contain minimal metadata:
  - `room_id`
  - `room_name`
  - `owner_device_id`
  - `created_at`
  - `expires_at`
  - approximate `participant_count`, if available
  - `protocol_version`
- It must not broadcast:
  - private group credentials
  - passphrases
  - join tokens
  - plaintext invitation payloads

### Room message rule

Nearby Room messages are public-to-nearby traffic, not private-group confidential traffic.

Backlog decisions:
- do not reuse `SecureKeyStore.loadGroupKey(gid)` for Nearby Room messages
- do not impose group-key-based E2EE expectations on Nearby Room messages
- if authenticity is added later, prioritize sender authentication/signatures before confidentiality

### Room key dissemination rule

If a temporary room-scoped message key is used, it must follow active-room mesh rules rather than owner-only server-style rules.

Required behavior:
- when a user joins a Nearby Room and does not yet have the `room key`, the app must send a `KEY_REQUEST` broadcast immediately
- any user who is:
  - currently active inside that same Nearby Room
  - already has the `room key`
  - and can currently reach the requester over BLE mesh
  may reply with a `KEY_BOOTSTRAP`
- key dissemination must not depend on the owner being the only responder
- if the joining user still does not have the `room key`, the app must retry `KEY_REQUEST` every `30s`
- retry must stop immediately when:
  - the user receives the key
  - the user leaves the room
  - the app is no longer active in that room
  - Bluetooth is disabled
  - BLE mesh transport stops

Traffic guardrails:
- a device that already has the key must not keep broadcasting `KEY_BOOTSTRAP` on a timer
- `KEY_BOOTSTRAP` should normally be sent only in response to a `KEY_REQUEST`
- responders should use a short cooldown keyed by `room_id + requester_device_id` to avoid bootstrap floods when multiple peers hear the same request

### Invite to Group rule

`Invite to Group` is the highest-security boundary inside Nearby Rooms.

Mandatory rules:
- do not leak a private-group passphrase or join secret into public room messages
- do not embed secure join credentials into BLE room metadata
- invitations must use targeted payloads if the required primitive exists

Acceptable MVP fallback if targeted encryption is not ready:
1. The sender sends an invitation request to a participant.
2. The participant accepts.
3. The app transitions to the existing secure private-group join flow.

### Storage and retention rule

Nearby Room data must be treated as temporary:
- the default room TTL is 30 minutes
- a closed/expired room must be hidden or cleaned up locally
- room messages must not continue to appear as normal long-term history after expiry

### Review gate

If a Nearby Room implementation decision requires:
- a shared secret before joining
- DTN transport or DTN carrier state
- relay/cloud to decrypt or distribute room state
- embedding private-group credentials into room payloads

then that decision crosses the product boundary and must be rejected or moved into a Private Group flow.

## Phase 1 goal

Phase 1 only delivers local data and a UI skeleton:
- Nearby Room models and local persistence
- Nearby Rooms list screen
- Create Nearby Room flow
- Room detail/chat screen skeleton
- Local expiration timer

Phase 1 does not need:
- BLE room metadata advertisement
- actual BLE discovery scanning
- mesh propagation for room messages
- real cross-device participant sync
- Group invitation bridge

## Presence and transport lifecycle rules

Nearby Room participation is foreground and room-scoped.

Mandatory rules:
- A user is considered present in a Nearby Room only while the app is open and the user is actively inside that room.
- Leaving a Nearby Room screen to open a Private Group counts as leaving the Nearby Room.
- Switching from one Nearby Room to another counts as leaving the previous room.
- If the app is closed or the user is not currently inside any Nearby Room, the app must stop processing Nearby Room transport state.
- The app must process transport/internal data only for the single Nearby Room the user is currently in.
- The app must not keep Nearby Room transport state updated for every discovered room.
- If the user leaves a room but keeps the app open, the app should still refresh the Nearby Rooms list and approximate participant counts so the user can see which rooms are still live.

Reason:
- This avoids wasting BLE bandwidth and processing on rooms the user is not actively participating in.
- Nearby Room is an active-presence feature, not a passive subscription feature.
- Room discovery/list freshness is still useful while browsing, even when the user is not actively present inside a room.

Implementation consequences for later phases:
- BLE advertise/scan/update paths for Nearby Rooms must be gated by the current active room, not by the full set of discovered rooms.
- Any room-specific transport cache, dedupe state, or participant refresh loop must be scoped to one active room at a time.
- The room lifecycle needs explicit enter/leave hooks instead of assuming passive background membership.
- Discovery/list updates and room-live counters should be treated separately from active-room transport handling.

## BLE traffic control rules

Nearby Rooms need explicit BLE budget rules so discovery remains useful without turning into multi-room background sync.

### Mode separation

The app should treat Nearby Rooms as two BLE modes:

1. Discovery mode
- active while the user is on the Nearby Rooms list screen
- allowed work:
  - scan room advertisement metadata
  - update room freshness
  - update approximate participant counts
  - update room live/expired/stale visibility
- forbidden work:
  - room chat transport
  - participant detail sync for non-active rooms
  - invitation transport for non-active rooms
  - moderation/state sync for non-active rooms

2. Active room mode
- active while the user is inside a specific Nearby Room
- allowed work:
  - room-specific transport for that one room only
  - message send/receive for that room
  - participant state updates for that room
  - room owner moderation signals for that room
- constrained work:
  - discovery scan may continue at reduced frequency if needed for list freshness
- forbidden work:
  - room-specific transport for any other room

### Single active room rule

At most one Nearby Room may own room-specific BLE transport state at a time.

Required consequences:
- entering room B means room A is immediately no longer the active transport room
- opening a Private Group means no Nearby Room remains active
- if no Nearby Room is active, only discovery-mode BLE work may continue while the discovery screen is open

### Scan cadence rule

Discovery mode should not scan at a flat high-frequency rate forever.

Recommended behavior:
- immediate scan burst when entering the Nearby Rooms list screen
- short high-frequency discovery window right after screen entry
- adaptive backoff to a lower refresh cadence while the screen remains open
- stop or sharply reduce discovery work when the screen is no longer visible or the app loses foreground

This rule exists to prevent the discovery screen from becoming a sustained BLE drain.

### Participant count rule

Approximate participant count for non-active rooms must come from lightweight room-level metadata.

Required constraints:
- do not derive discovered-room participant counts by subscribing to full participant-level transport for every visible room
- do not turn browsing the room list into passive membership sync across many rooms
- if accurate count is unavailable, show an approximate or missing value rather than increasing BLE traffic

### Stale room eviction rule

Room list freshness needs a concrete stale-room policy.

Required behavior:
- if a room has not been seen for a defined number of discovery windows or time budget, mark it stale
- stale rooms should be hidden or clearly marked before formal room TTL expiry if nearby advertising disappears
- room TTL and room visibility are related but not identical:
  - TTL answers whether the room should still exist
  - freshness answers whether the room is still currently reachable nearby

### Discovery versus membership rule

Discovery must not imply room membership.

Required behavior:
- seeing a room in the list means the room is nearby, not that the user is participating in it
- discovery metadata processing must stay separate from room-presence and room-message processing
- no participant-level receive path should start just because a room appears in discovery

### BLE priority rule

BLE budget should prioritize the current user task.

Priority rules:
- on the Nearby Rooms discovery screen:
  - prioritize room discovery/list freshness
  - do not run active-room transport unless the user is actually inside a room
- inside an active Nearby Room:
  - prioritize room-specific transport for that room
  - discovery work should be reduced to a lower-frequency background cadence if it remains enabled

### UX risk control rule

The active-presence model can cause missed room events by design.

Required UX guardrails:
- the app must communicate that Nearby Room activity is live only while the user is inside the room
- leaving the room to open another room or a Private Group should be treated as leaving the live room session
- if the user returns later, the room should be treated as a fresh re-entry rather than as uninterrupted passive membership

## Discovery/Transport design decisions

This section turns the BLE guardrails into implementable design rules for later phases.

### 1. Explicit boundary between discovery metadata and room transport

Nearby Rooms should use two separate BLE pipelines.

#### Discovery pipeline

Purpose:
- keep the Nearby Rooms list fresh
- show which rooms are live nearby
- show lightweight room metadata only

Allowed inputs:
- BLE room advertisements
- BLE scan results carrying room metadata only

Allowed outputs:
- `NearbyRoomEntity(source = BLE)`
- `last_seen_at_ms`
- `participant_count` approximate
- room freshness state such as live/stale/expired

Forbidden behavior:
- no room message processing
- no participant detail sync
- no invitation transport
- no moderation state sync
- no implicit room membership

#### Active-room transport pipeline

Purpose:
- handle live room traffic for the single room the user is actively inside

Allowed inputs:
- active room session state
- BLE packets scoped to the current active room

Allowed outputs:
- room messages for the active room
- participant state for the active room
- room moderation signals for the active room
- room invitation transport for the active room, if implemented later

Drop rule:
- if a packet belongs to room-specific transport and `roomId != activeRoomId`, drop it immediately

Implementation target:
- define packet classes early:
  - `DISCOVERY_METADATA`
  - `ACTIVE_ROOM_TRAFFIC`
- classify every Nearby Room BLE payload into one of those classes before processing

### 2. Scan cadence policy

Discovery scan must be adaptive rather than flat high-frequency polling.

#### Recommended phases

1. Immediate entry burst
- trigger when the user opens the Nearby Rooms discovery screen
- run a short low-latency scan burst immediately
- goal: fill the list quickly for first paint

2. Short active discovery window
- keep a higher discovery cadence for a brief period after screen entry
- goal: stabilize room list and participant counts while the user is deciding where to enter

3. Steady-state backoff
- reduce to a lower discovery cadence while the screen remains open
- goal: keep the list fresh without sustained BLE drain

4. Stop condition
- stop discovery scanning when:
  - the discovery screen is no longer visible
  - the app loses foreground
  - a stricter active-room policy disables list refresh during room participation

Implementation requirement:
- the scan cadence must be owned by a dedicated mode controller, not spread across activities ad hoc

### 3. Stale-room eviction policy

Room existence and room freshness must be treated as separate concepts.

#### Existence

Source:
- `expires_at_ms`

Meaning:
- whether the room should still exist according to room TTL

#### Freshness

Source:
- `last_seen_at_ms`

Meaning:
- whether the room is still reachable nearby right now

#### Required policy

- if `now >= expires_at_ms`, mark the room `EXPIRED`
- if `now - last_seen_at_ms > staleTimeoutMs`, mark the room `STALE`
- stale rooms should be hidden or visibly degraded before formal TTL expiry if nearby advertisements disappear

Recommended implementation:
- define a freshness state separate from room state if needed at the UI layer:
  - `LIVE`
  - `STALE`
  - `EXPIRED`
- compute `staleTimeoutMs` relative to discovery cadence, for example several missed discovery windows

UX rule:
- do not leave a room in the list as if it were live just because its TTL has not elapsed

### 4. Cheap participant count rule

Approximate participant count for non-active rooms must come from lightweight room-level metadata.

#### Required source

- any peer currently active inside a Nearby Room may advertise that room's lightweight discovery metadata
- discovery must not depend on the owner being present or being the only metadata source
- `participant_count_approx` should be taken from the most recent valid metadata seen for that `room_id`

#### Forbidden source

- do not derive discovered-room participant counts by subscribing to participant-level traffic for every visible room
- do not reconstruct counts from many participant beacons across many rooms
- do not turn list browsing into passive room membership sync

#### Fallback behavior

- if an approximate count is unavailable, the UI should show:
  - no count
  - or a generic live-nearby indicator
- accuracy is less important than keeping BLE usage bounded

Implementation note:
- participant count on the discovery screen is a browsing hint, not a consistency-critical room state

### 5. Advertisement-based room discovery budget

Discovery should be driven by advertisement metadata, not by per-peer metadata fetch requests.

#### Discovery source rule

- a peer that is currently active inside a Nearby Room should advertise lightweight metadata for that room
- a peer that is not currently active inside a Nearby Room should not advertise room metadata
- discovery should parse room metadata directly from BLE advertisement/manufacturer data when present
- discovery should not require a request/response exchange just to learn that a room exists nearby

#### Multi-peer room visibility rule

- the same room may be advertised by multiple active peers at the same time
- the scanner must treat those advertisements as multiple observations of one logical room
- room discovery must dedupe by `room_id`, not by `owner_device_id` and not by peer address
- if five active peers advertise the same room, the result in the DB/UI should still be one room entry

#### Upsert merge rule

For each decoded advertisement:
- resolve `room_id`
- merge into one `NearbyRoomEntity`
- update `last_seen_at_ms`
- refresh `participant_count` only from the newest valid metadata sample
- do not create duplicate room rows for the same room because multiple peers are advertising it

If multiple peers advertise the same room during one discovery cycle:
- prefer the freshest sample
- if timestamps are equivalent, prefer the stronger-RSSI sample
- if the payload conflicts in non-critical fields, keep the existing room and only refresh freshness

#### Per-burst processing budget

One discovery burst must have a bounded processing budget.

Required limits:
- define `maxMetadataPeersPerBurst`
- define `maxUniqueRoomsPerBurst`
- define `minRoomRefreshIntervalMs` per `room_id`

Recommended starting values:
- `maxMetadataPeersPerBurst = 8`
- `maxUniqueRoomsPerBurst = 6`
- `minRoomRefreshIntervalMs = 10_000`

Behavior:
- process only the highest-priority metadata-bearing peers within the current burst budget
- stop creating new room upserts once the unique-room budget is exhausted
- still allow an already-known room to refresh `last_seen_at_ms` within a lighter update path if needed

#### Peer prioritization rule

When more peers advertise room metadata than the burst budget allows, prioritize in this order:
- stronger RSSI
- rooms not seen recently
- rooms not already present in the local DB
- rooms currently marked stale

De-prioritize:
- duplicate advertisers for a room that was already refreshed in the same burst
- rooms refreshed too recently

#### Refresh throttling rule

Discovery should not rewrite the same room on every advertisement callback.

Required behavior:
- keep a short-lived in-memory throttle keyed by `room_id`
- if the same room is seen repeatedly within `minRoomRefreshIntervalMs`, do not perform a full DB rewrite every time
- repeated sightings inside the throttle window may still update an in-memory freshness cache for burst-level prioritization

Allowed reasons to bypass throttle:
- room not yet in DB
- room currently stale
- `participant_count` changed
- `expires_at_ms` moved materially
- room name/protocol version changed

#### Implementation target

Later phases should introduce an explicit discovery merge component, for example `NearbyRoomDiscoveryAccumulator`, with responsibilities:
- collect metadata-bearing peer sightings during a discovery burst
- dedupe sightings by `room_id`
- rank candidates under the burst budget
- decide whether each room should be:
  - inserted
  - refreshed
  - skipped due to throttle
  - skipped as a duplicate observation
- hand only the final merged room updates to `NearbyRoomRepository`

### 6. Proposed runtime objects

To keep the design enforceable, later phases should introduce explicit runtime state holders.

#### `ActiveNearbyRoomSession`

Suggested fields:
- `activeRoomId: String?`
- `enteredAtMs: Long?`
- `mode: IDLE | DISCOVERY | ACTIVE_ROOM`

Responsibilities:
- enter room
- leave room
- switch room
- clear room when opening a Private Group

#### `RoomDiscoveryRecord`

Suggested fields:
- `roomId`
- `name`
- `ownerDeviceId`
- `expiresAtMs`
- `participantCountApprox`
- `lastSeenAtMs`
- `freshnessState`

Responsibilities:
- list rendering source
- stale/live decision source
- no membership semantics

#### `BleModeController`

Suggested modes:
- `IDLE`
- `DISCOVERY`
- `ACTIVE_ROOM(roomId)`

Responsibilities:
- own scan cadence
- own BLE budget priority
- decide which packet classes are allowed in each mode
- ensure only one room may run room-specific transport at a time

## Architecture principles

1. Nearby Room is a separate scope parallel to Private Group.
2. Do not inject room logic into the existing `GroupChatActivity` and `ChatReducer`.
3. Build a dedicated room reducer/data path so TTL, moderation, and invitation rules stay separate.
4. Phase 1 may be local-only, but schema names and activity names must be ready for Phase 2 BLE discovery.
5. BLE-only is a product boundary: if a path requires relay/cloud to function, that path does not belong in the Nearby Rooms MVP.
6. Nearby Rooms do not use DTN. If a design depends on DTN batching, DTN manifests, or DTN capsule delivery, that design is out of scope for this feature.
7. Nearby Room transport is active-room-only. The app should process Nearby Room transport state for at most one room at a time.

## Modules in scope

### `android/data`
- `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`
- `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/*`
- `android/data/src/main/java/app/peermesh/data/db/entity/*`
- `android/data/src/main/java/app/peermesh/data/reducer/*`

### `android/app`
- `android/app/src/main/java/app/peermesh/MainActivity.kt`
- `android/app/src/main/java/app/peermesh/*Activity.kt`
- `android/app/src/main/res/layout/*`
- `android/app/src/main/res/menu/drawer_menu.xml`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/AndroidManifest.xml`

### `android/core`
- Phase 1 does not require room event types yet.
- Avoid introducing APIs that would force the wrong model in Phase 2.

### `android/transport`
- Phase 1 does not modify transport.
- Only note BLE mesh interface points that will be needed in Phase 2.
- Do not route Nearby Rooms through DTN transport.
- Do not keep BLE transport state hot for rooms the user is not actively inside.

## Data model to implement immediately in Phase 1

### Room aggregate

Create new entities under `android/data/src/main/java/app/peermesh/data/db/entity/`:

1. `NearbyRoomEntity.kt`
- `room_id: String`
- `name: String`
- `owner_device_id: String`
- `owner_display_name: String?`
- `created_at_ms: Long`
- `expires_at_ms: Long`
- `closed_at_ms: Long?`
- `state: String`
  `ACTIVE | EXPIRED | CLOSED`
- `participant_count: Int?`
- `is_local_owner: Boolean`
- `last_seen_at_ms: Long`
- `source: String`
  `LOCAL | BLE`

2. `NearbyRoomParticipantEntity.kt`
- `room_id: String`
- `device_id: String`
- `display_name: String?`
- `joined_at_ms: Long`
- `last_seen_at_ms: Long`
- `role: String`
  `OWNER | PARTICIPANT`
- `status: String`
  `ACTIVE | LEFT | KICKED`
- `invitation_blocked: Boolean`

3. `NearbyRoomMessageEntity.kt`
- `room_id: String`
- `message_id: String`
- `sender_device_id: String`
- `sender_display_name: String?`
- `body: String`
- `created_at_ms: Long`
- `received_at_ms: Long`
- `ttl_expires_at_ms: Long`
- `delivery_state: String`
  `LOCAL | SENT | RECEIVED`

4. `NearbyRoomInviteQuotaEntity.kt`
- local-only helper for the Phase 1/5 rate-limit skeleton
- no UI needed yet, but add the schema now to avoid later refactors

Note:
- Phase 1 may not need `NearbyRoomInvitationEntity` yet, but it is reasonable to add it now if the goal is to reduce migration churn later.
- If added now, keep it local-only with no transport logic.

## DAO backlog

Create new DAO files under `android/data/src/main/java/app/peermesh/data/db/dao/`:

1. `NearbyRoomDao.kt`
- `upsert(room)`
- `findById(roomId)`
- `listActive(nowMs)`
- `listVisible(nowMs)`
- `markExpired(nowMs)`
- `markClosed(roomId, closedAtMs)`
- `deleteExpired(retentionCutoffMs)` or `hideExpired()`

2. `NearbyRoomParticipantDao.kt`
- `upsert(participant)`
- `listActiveParticipants(roomId)`
- `findParticipant(roomId, deviceId)`
- `markLeft(roomId, deviceId, atMs)`
- `markKicked(roomId, deviceId, atMs)`
- `deleteByRoom(roomId)`

3. `NearbyRoomMessageDao.kt`
- `upsertMessage(message)`
- `listMessages(roomId, limit)`
- `listMessagesBefore(roomId, beforeCreatedAt, beforeMessageId, limit)`
- `deleteByRoom(roomId)`
- `deleteExpired(nowMs)`

4. `NearbyRoomInviteQuotaDao.kt`
- local counters keyed by `room_id + sender_device_id + target_device_id`

## Database backlog

### `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`

Required changes:
- add the new entities to `@Database(entities = [...])`
- bump the database `version`
- add abstract DAO accessors:
  - `nearbyRoomDao()`
  - `nearbyRoomParticipantDao()`
  - `nearbyRoomMessageDao()`
  - `nearbyRoomInviteQuotaDao()`

### `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`

Add a new migration that:
- creates `nearby_rooms`
- creates `nearby_room_participants`
- creates `nearby_room_messages`
- creates `nearby_room_invite_quotas`
- adds indexes:
  - `nearby_rooms(state, expires_at_ms)`
  - `nearby_rooms(last_seen_at_ms)`
  - `nearby_room_participants(room_id, status)`
  - `nearby_room_messages(room_id, created_at_ms)`
  - `nearby_room_messages(room_id, ttl_expires_at_ms)`

Phase 1 migration rules:
- do not touch existing private-group/chat tables unless required by the version bump
- migrations must be one-way and must not depend on network access

## Domain/service backlog for Phase 1

Phase 1 does not need actual BLE event ingest, but it still needs a local service layer so the UI does not write directly to DAOs.

Create a new package under `android/app/src/main/java/app/peermesh/nearby/` or `android/data/.../nearby/` and pick one direction early. To reduce UI coupling, Phase 1 should place this in `android/app`:

1. `NearbyRoomRepository.kt`
- create local room
- list visible rooms
- load room detail
- add local owner participant
- add local message
- expire rooms locally

2. `NearbyRoomExpiryManager.kt`
- local countdown helper
- batch helper to mark expired/closed rooms
- reusable across resume/refresh flows

3. `NearbyRoomPolicy.kt`
- `ROOM_TTL_MS = 30 * 60 * 1000L`
- room name length validation
- message length validation
- valid state transitions
- BLE-only guard notes for later phases

4. `NearbyRoomDrafts.kt` or `NearbyRoomUiMapper.kt`
- map entity to UI model
- compute countdown labels and safety copy

## UI backlog for Phase 1

### 1. Entry point

Modify:
- `android/app/src/main/res/menu/drawer_menu.xml`
- `android/app/src/main/res/values/ids.xml`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/java/app/peermesh/MainActivity.kt`
- drawer host activities:
  - `SettingsActivity.kt`
  - `StatisticsActivity.kt`
  - `AboutActivity.kt`
  - `TrailActivity.kt`
  - `AdditionalNoticesActivity.kt`
  - `TestActivity.kt`

Tasks:
- add a `Nearby Rooms` menu item
- route it to `NearbyRoomsActivity`
- do not merge this into `menu_home`, to keep Private Groups separate from Nearby Rooms

### 2. Nearby rooms list screen

Create:
- `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt`
- `android/app/src/main/java/app/peermesh/NearbyRoomAdapter.kt`
- `android/app/src/main/res/layout/activity_nearby_rooms.xml`
- `android/app/src/main/res/layout/item_nearby_room.xml`

The UI should include:
- intro copy: temporary, nearby-only, visible over local mesh, disappears after 30 minutes
- local empty states:
  - no local/discovered rooms yet
  - BLE mesh not ready
- `Create Nearby Room` CTA
- room list cards with:
  - room name
  - owner/device summary
  - time to expiry
  - participant count, if available
  - `Local` source badge in Phase 1

Phase 1 behavior:
- show the local room created by the owner
- no actual BLE scanning yet
- include a placeholder status line: `Nearby Rooms work only over BLE mesh. Discovery will appear here when BLE scanning is added.`

### 3. Create Nearby Room flow

Create:
- `android/app/src/main/java/app/peermesh/NearbyRoomCreateDialog.kt` or a simple `AlertDialog` helper inside the activity
- if needed:
  - `android/app/src/main/res/layout/dialog_create_nearby_room.xml`

Fields:
- room name
- warning copy:
  - visible to nearby people for 30 minutes
  - people nearby can join without approval

Create action:
- generate `room_id`
- derive the owner from the local device identity
- create the local owner participant
- persist the room and open room detail immediately

### 4. Nearby room detail/chat skeleton

Create:
- `android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt`
- `android/app/src/main/java/app/peermesh/NearbyRoomMessageAdapter.kt`
- `android/app/src/main/res/layout/activity_nearby_room.xml`
- `android/app/src/main/res/layout/item_nearby_room_message.xml`

The screen should include:
- room name
- expiration timer
- public safety banner
- participant count
- chat list
- composer
- actions:
  - leave room
  - close room for local owner
  - participants

Phase 1 behavior:
- a local owner can enter the room immediately after creation
- local messages can be inserted into the local room DB
- no network/mesh send yet
- include a clear state line: `Phase 1 local room preview. Nearby sync over BLE mesh is not wired yet.`

### 5. Participant sheet skeleton

Create:
- `android/app/src/main/java/app/peermesh/NearbyRoomParticipantsBottomSheet.kt` or an equivalent dialog
- `android/app/src/main/res/layout/dialog_nearby_room_participants.xml`
- `android/app/src/main/java/app/peermesh/NearbyRoomParticipantAdapter.kt`

Phase 1:
- show the local owner participant
- show placeholders for role/status
- reserve UI actions:
  - `Invite to Group` disabled or hidden
  - `Kick participant` visible only for owner, disabled until a later phase

## Detailed file/module backlog

### `android/data/src/main/java/app/peermesh/data/db/entity/*`

Implement now:
- `NearbyRoomEntity.kt`
- `NearbyRoomParticipantEntity.kt`
- `NearbyRoomMessageEntity.kt`
- `NearbyRoomInviteQuotaEntity.kt`

Definition rules:
- use ASCII constants rather than Room enums to reduce migration friction
- define companion object constants for state/role/source strings if needed

### `android/data/src/main/java/app/peermesh/data/db/dao/*`

Implement now:
- `NearbyRoomDao.kt`
- `NearbyRoomParticipantDao.kt`
- `NearbyRoomMessageDao.kt`
- `NearbyRoomInviteQuotaDao.kt`

### `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`

Implement now:
- register the new entities and DAOs

### `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`

Implement now:
- add the new migration
- update the exported schema under `android/data/schemas/app.peermesh.data.db.AppDatabase/`

### `android/app/src/main/java/app/peermesh/nearby/*`

Implement now:
- `NearbyRoomRepository.kt`
- `NearbyRoomPolicy.kt`
- `NearbyRoomExpiryManager.kt`
- optional `NearbyRoomUiModels.kt`

### `android/app/src/main/java/app/peermesh/*Activity.kt`

Implement now:
- `NearbyRoomsActivity.kt`
- `NearbyRoomActivity.kt`
- `NearbyRoomAdapter.kt`
- `NearbyRoomMessageAdapter.kt`
- `NearbyRoomParticipantAdapter.kt`

Modify:
- `MainActivity.kt`
- all drawer host activities
- `PeerMeshFeatureUi.kt` if menu/capability titles should be centralized

### `android/app/src/main/res/layout/*`

Implement now:
- `activity_nearby_rooms.xml`
- `activity_nearby_room.xml`
- `item_nearby_room.xml`
- `item_nearby_room_message.xml`
- `dialog_create_nearby_room.xml`
- `dialog_nearby_room_participants.xml`
- optional empty-state include layouts

### `android/app/src/main/res/values/strings.xml`

Add strings for:
- menu label
- intro copy
- create warning
- room expiration
- room expired
- participant labels
- local preview disclaimer
- BLE-only copy

### `android/app/src/main/AndroidManifest.xml`

Register:
- `NearbyRoomsActivity`
- `NearbyRoomActivity`

## Work breakdown to start coding immediately

### Slice A: Data skeleton

Goal:
- the app builds with the new Room schema

Tasks:
1. Create the entity files.
2. Create the DAO files.
3. Update `AppDatabase.kt`.
4. Add the migration.
5. Export the new schema.

Done when:
- `android:data` and `android:app` compile
- a Room migration test can be added later, but the schema must at least generate cleanly now

### Slice B: Repository + local room creation

Goal:
- a local room can be created from code without full UI

Tasks:
1. `NearbyRoomPolicy.kt`
2. `NearbyRoomRepository.kt`
3. helper to generate `room_id`
4. local owner participant bootstrap
5. local message insert helper
6. local expiry check

Done when:
- there is an API `createRoom(name)` returning `roomId`
- there is an API `postLocalMessage(roomId, body)`
- there is an API `listVisibleRooms()`

### Slice C: Nearby rooms list UI

Goal:
- a user can open Nearby Rooms, create a room, and see the created room in the list

Tasks:
1. menu item and navigation
2. `NearbyRoomsActivity`
3. adapter and layout
4. create dialog
5. empty state and BLE-only copy

Done when:
- Nearby Rooms opens from the drawer
- a local room can be created
- the list refresh shows the new room

### Slice D: Room detail/chat skeleton

Goal:
- room detail opens and local message preview works

Tasks:
1. `NearbyRoomActivity`
2. message adapter
3. participant summary
4. local leave/close actions
5. countdown timer
6. expired-room UI state

Done when:
- the room opens after creation
- a local message appears in the list
- the room transitions to expired state after TTL

## Explicitly deferred work after Phase 1

Do not implement these in this phase, but preserve extension points:

1. BLE room advertisement
- will likely touch `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
- will likely require a room metadata codec/module under `android/app/src/main/java/app/peermesh/ble/`
- must stay on the live BLE mesh path only, not DTN
- must only advertise the room while the user is actively inside that room

2. BLE room discovery scan pipeline
- scan result -> local `NearbyRoomEntity(source = BLE)`
- update `last_seen_at_ms` and approximate participant count
- no DTN replay or DTN backfill for room discovery
- discovery processing must not imply room membership or room-specific transport processing
- discovery/list refresh may continue while the app is open even if the user is not currently inside a Nearby Room

3. Room-scoped event model
- requires `ROOM.*` event types under `android/core`
- requires a dedicated reducer/ingestor route
- if transport is added later, it must use direct BLE mesh delivery rather than DTN
- room message and participant transport handling must be enabled only for the currently active room

4. Group invitation bridge
- should reuse the existing secure group join flow
- must not leak passphrase/join credentials into the public room

## Testing backlog for Phase 1

### `android/data/src/test/...`
- DAO tests for:
  - create/list active rooms
  - expire rooms
  - participant status transitions
  - message ordering

### `android/app/src/test/...`
- repository tests for:
  - create-room owner bootstrap
  - local leave/close state
  - TTL expiry logic

### Manual QA
1. Create a room from the Nearby Rooms screen.
2. Confirm the room appears in the list.
3. Open room detail.
4. Send a local message.
5. Leave the room.
6. Create a new room and close it early.
7. Fake expiry through clock injection if available.

## Open decisions to settle before Phase 2

1. `room_id` format
- recommend local UUID/ULID, not derived from passphrase

2. Owner identity field
- Phase 1 should use the local stable BLE device ID
- Phase 2 may add display name/avatar snapshots

3. Message identity
- Phase 1 can use a local UUID
- Phase 2 may switch to `EventIdFactory` if room messages move into the event pipeline

4. Package placement
- if the team wants reducers/services under `android/data`, the repository can move later
- Phase 1 should prioritize delivery speed and a clean separation between UI and business logic

## Recommended implementation order

1. `android/data` schema + DAO
2. `android/app/nearby` repository/policy/expiry
3. `NearbyRoomsActivity` + drawer wiring
4. `NearbyRoomActivity` + local message list
5. repository and DAO tests

## Acceptance criteria for Phase 1

1. The app has a `Nearby Rooms` menu item.
2. A user can create a local Nearby Room with a 30-minute TTL.
3. Room detail shows a countdown and a public/nearby-only warning.
4. A local user can post a public room message into the local list.
5. A local user can leave the room, and a local owner can close the room.
6. An expired room no longer allows local join/send.
7. There is no dependency on relay/cloud paths.
8. Nearby Room transport work is inactive unless the user is actively inside a specific Nearby Room.

## Out-of-scope gate

If implementation uncovers a task that requires:
- server APIs
- DTN transport
- relay sync
- internet discovery
- cloud persistence
- background processing for multiple Nearby Rooms at once

then that task must be split out of the Nearby Rooms Phase 1 backlog because it violates the BLE-only constraint.
