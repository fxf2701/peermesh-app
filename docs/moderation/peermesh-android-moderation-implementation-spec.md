# PeerMesh Android Moderation Implementation Spec

This document translates the Play-oriented moderation model into an Android implementation plan for PeerMesh.

Companion document:

- [peermesh-play-moderation-model.md](peermesh-play-moderation-model.md)

This spec is intentionally narrow. It covers the first Android version that should be good enough for:

- direct user safety controls
- local-first and encrypted product constraints
- offline reporting
- clearer Google Play review posture

## 1. Scope

This V1 moderation implementation includes:

- local block peer
- local hide peer location / trail
- report peer
- report message
- owner remove member from group
- offline queue for reports
- later network delivery for queued reports

This V1 does not include:

- centralized proactive message scanning
- automatic moderation decisions based on content analysis
- cross-group global account enforcement
- full moderation dashboard

## 2. Product Rules

### 2.1 Local Block

Any member can locally block another peer.

Effects:

- blocked peer chat messages are hidden from the blocking user
- blocked peer chat events may still be received and persisted for relay / dedupe purposes, but local message materialization should be skipped where supported
- blocked peer live trail and location updates are hidden
- blocked peer presence can be hidden from member-facing UI where practical

Local block does not:

- remove the peer from the group for everyone else
- deny Nearby Room join or Private Group membership by itself
- suppress the blocking user's own send path
- delete already stored encrypted history from disk unless product chooses to
- require owner approval

### 2.2 Report Peer

Any member can report a peer from:

- group member list
- group chat header or member sheet
- map / trail identity surfaces

### 2.3 Report Message

Any member can report a specific message from the message overflow menu.

### 2.4 Owner Remove Member

The group owner can remove a member from the current group.

Effects:

- removed member no longer appears as active in group membership
- future group traffic from removed member is rejected once membership state converges
- removed member may later request rejoin, but must not be restored automatically by invitation alone

### 2.5 Nearby Room Moderation Order

Nearby Room should not reuse the full Private Group moderation model mechanically.

For Nearby Room MVP, moderation should be implemented in this order:

1. local block user
2. report user
3. report message
4. owner mute in room

Interpretation:

- participant self-protection comes first
- report flows come next
- owner moderation remains a lightweight room-scoped control
- owner mute is not equivalent to private-group member removal

Owner mute for Nearby Room should be propagated as room-scoped mute or unmute events and enforced in the event or message layer, not by dropping BLE envelopes.

## 3. Android Data Model

Minimum entities:

- `BlockedPeerEntity`
- `PendingModerationReportEntity`
- `ModerationActionEntity`
- `NearbyRoomMuteStateEntity`

Suggested storage:

- Room table in the app database
- local write-first behavior

### 3.1 BlockedPeerEntity

Suggested fields:

- `id: Long`
- `blockedPeerId: String`
- `scope: String`
- `groupId: String?`
- `hideMessages: Boolean`
- `hideLocation: Boolean`
- `hideTrail: Boolean`
- `reasonCode: String?`
- `createdAtMs: Long`
- `updatedAtMs: Long`
- `expiresAtMs: Long?`

Rules:

- prefer `scope = "global_local"` when the same peer identity is stable across Private Group and Nearby Room
- preserve compatibility with older scoped rows during migration or audit cleanup
- uniqueness should be `(blockedPeerId, groupId, scope)`

### 3.2 PendingModerationReportEntity

Suggested fields:

- `reportId: String`
- `groupId: String`
- `reporterPeerId: String`
- `reportedPeerId: String`
- `targetType: String`
- `messageId: String?`
- `eventId: String?`
- `reasonCode: String`
- `userNote: String?`
- `includePlaintext: Boolean`
- `plaintextExcerpt: String?`
- `ciphertextRef: String?`
- `deliveryState: String`
- `attemptCount: Int`
- `createdAtMs: Long`
- `lastAttemptAtMs: Long?`
- `deliveredAtMs: Long?`

Enums:

- `targetType`: `peer`, `message`
- `deliveryState`: `pending`, `retry_waiting`, `delivered`, `failed_permanent`

### 3.3 ModerationActionEntity

Suggested fields:

- `actionId: String`
- `groupId: String?`
- `actorPeerId: String`
- `targetPeerId: String`
- `actionType: String`
- `createdAtMs: Long`
- `metadataJson: String?`

Enums:

- `actionType`: `local_block`, `local_unblock`, `hide_location`, `hide_trail`, `report_peer`, `report_message`, `owner_remove_member`

This table is useful for:

- local audit
- debugging
- future export into support diagnostics

## 4. Android Code Modules

Recommended placement in the current app structure:

- moderation persistence in `data`
- moderation policy / use cases in `core` or `app`
- UI entry points in `app`

Suggested classes:

- `ModerationRepository`
- `BlockedPeerRepository`
- `ModerationReportRepository`
- `ModerationSyncWorker`
- `ModerationPolicy`
- `ModerationUiActions`

Suggested Android files:

- `app/peermesh/moderation/BlockedPeerRepository.kt`
- `app/peermesh/moderation/ModerationRepository.kt`
- `app/peermesh/moderation/ModerationSyncWorker.kt`
- `app/peermesh/moderation/ModerationPolicy.kt`
- `app/peermesh/moderation/ReportReason.kt`

## 5. UI Entry Points

### 5.1 GroupChatActivity

Add moderation actions in two places:

- message overflow menu
- member identity sheet or chat header member actions

Message overflow actions:

- `Report message`
- `Block peer`

Member actions:

- `Block peer`
- `Report peer`
- `Hide location`
- `Hide trail`

### 5.2 Group Detail / Member List

For each member:

- `Block peer`
- `Report peer`
- if current user is owner: `Remove from group`

### 5.3 GroupMapActivity

When opening a peer marker or live trail identity card:

- `Hide this peer's location`
- `Hide this peer's trail`
- `Block peer`
- `Report peer`

### 5.4 Trusted Contacts / Trail Screens

If a blocked peer is currently a trusted contact:

- show they are blocked
- prevent new automatic live sharing to that peer unless user explicitly unblocks them

## 6. UX Flow Details

### 6.1 Block Peer Flow

Trigger:

- user taps `Block peer`

Dialog copy should explain:

- this only affects your device
- this peer's messages and location/trail will be hidden from you
- your device may still relay encrypted or transport envelopes for mesh continuity even when the message is hidden locally
- group owner is not automatically notified

Buttons:

- `Block`
- `Cancel`

Post-action:

- hide peer content immediately
- show snackbar: `Peer blocked on this device`

### 6.2 Report Peer Flow

Trigger:

- user taps `Report peer`

Flow:

1. choose reason
2. optional note
3. optional checkbox: `Also block this peer on this device`
4. disclosure screen describing what data will be sent
5. confirm

Success state:

- `Report saved`
- if offline: `It will be sent when a network path is available`

### 6.3 Report Message Flow

Trigger:

- user taps `Report message`

Flow:

1. reason picker
2. optional note
3. optional checkbox: `Include message text in report`
4. optional checkbox: `Also block this peer on this device`
5. confirm disclosure

Default should be:

- message id and metadata included
- plaintext excluded unless user explicitly opts in

### 6.4 Owner Remove Member Flow

Trigger:

- owner taps `Remove from group`

Dialog copy should explain:

- the member will lose future access to this group after membership sync
- local blocks by other members are not changed
- invite rotation may still be needed

Post-action options:

- `Remove`
- `Remove and rotate invite` if supported

## 7. Local Enforcement Rules

### 7.1 Chat Rendering

Before rendering a message in `GroupChatActivity`:

- resolve sender peer id
- check `BlockedPeerEntity` for current group
- if blocked and `hideMessages = true`, do not render the message normally

Possible UI choices:

- fully hide the row
- or render a collapsed placeholder like `Message hidden from blocked peer`

For V1, fully hiding is simpler.

### 7.2 Notifications

If a push / local notification is generated from blocked peer content:

- suppress notification body
- ideally suppress the notification entirely for blocked messages

### 7.3 Live Trail / Map

When building peer marker sets or trail overlays:

- filter blocked peer ids
- filter peers with `hideLocation` or `hideTrail`

### 7.4 Invite Handling

If invite payload processing can resolve the source peer:

- suppress auto-apply behavior for blocked peers

If the current invite format cannot identify the sender safely:

- leave this as non-goal for V1

## 8. Report Payload Design

### 8.1 Peer Report Payload

Suggested payload:

```json
{
  "report_id": "uuid",
  "group_id": "gid",
  "reporter_peer_id": "peerA",
  "reported_peer_id": "peerB",
  "target_type": "peer",
  "reason_code": "harassment",
  "user_note": "optional",
  "created_at_ms": 1760000000000,
  "app_version": "0.1.0",
  "protocol_version": 1
}
```

### 8.2 Message Report Payload

Suggested payload:

```json
{
  "report_id": "uuid",
  "group_id": "gid",
  "reporter_peer_id": "peerA",
  "reported_peer_id": "peerB",
  "target_type": "message",
  "message_id": "msg123",
  "reason_code": "spam",
  "user_note": "optional",
  "include_plaintext": false,
  "plaintext_excerpt": null,
  "ciphertext_ref": "local-db-row-or-event-id",
  "created_at_ms": 1760000000000,
  "app_version": "0.1.0",
  "protocol_version": 1
}
```

## 9. Sync Strategy

### 9.1 Background Delivery

Use WorkManager for deferred report sync.

Recommended trigger conditions:

- network available
- registry / moderation endpoint configured

Worker behavior:

- fetch `pending` reports
- send one by one or in small batches
- mark success
- apply backoff on transient failure

### 9.2 No Network Case

If the app remains fully offline:

- report stays local
- UI continues to show report as saved
- user can optionally export a support bundle later if needed

### 9.3 Delivery Destination

V1 delivery can target:

- a simple HTTPS moderation endpoint
- or a registry-adjacent abuse endpoint if that architecture already exists

The endpoint should not require blanket message upload.

## 10. Owner Removal Implementation Notes

This depends on current group membership architecture, but V1 should aim for:

- membership change event signed by owner
- event propagated through existing group sync path
- receivers reject future group participation from removed peer after event is applied
- `SYS.MEMBER.REMOVED` must be treated as a high-priority membership control event
- follow-up group key rotation must use the same high-priority control class
- both membership control events should use a long transport TTL for delayed and degraded delivery conditions
- when both are emitted in the same workflow, `SYS.MEMBER.REMOVED` must be sent and applied before the rotation event

If immediate cryptographic exclusion is not possible in V1:

- still perform logical removal
- rotate group join or invite material
- document residual limitations clearly

### 10.1 Removed Member Rejoin

V1 rejoin for a removed member should use an owner-approved request flow:

- removed peer may open a normal invitation link or QR code
- if the peer is currently marked removed on that synced client, the client must not join immediately
- instead, the client emits `SYS.MEMBER.REJOIN.REQUEST`

`SYS.MEMBER.REJOIN.REQUEST` should contain:

- `rejoin_request_id`
- `target_actor_id`
- `requested_at`
- `expires_at`

Rules:

- request TTL should be 24 hours
- the removed peer may not create another active request for the same group while one non-expired request already exists
- ordinary group activity remains blocked for that removed peer

The owner may later emit `SYS.MEMBER.REJOIN.APPROVED`.

`SYS.MEMBER.REJOIN.APPROVED` should contain:

- `rejoin_request_id`
- `target_actor_id`
- `approved_at`
- `expires_at`

Rules:

- only the current group owner may emit this event
- approval TTL should be 24 hours
- owner may not emit a second active approval for the same request

Approval validity must be bound to the request by `rejoin_request_id`.

An approval is valid only if:

- the referenced request exists
- the request has not expired
- `target_actor_id` matches between request and approval
- approval sender is the current owner

This acts like a lock-and-key pair:

- request = lock
- approval = key

Invitation possession alone is therefore not enough for a removed user to rejoin.

## 11. Strings And Copy

Add explicit strings for:

- `Block peer`
- `Unblock peer`
- `Report peer`
- `Report message`
- `Hide location`
- `Hide trail`
- `This affects only your device`
- `Report saved and will be sent later when network is available`
- `Include message text in this report`
- `This report may include limited metadata needed to identify the reported content`

Avoid misleading copy such as:

- `PeerMesh cannot do anything because chats are encrypted`
- `Reporting sends all conversation data`

## 12. Safety Screen

Add a simple page or dialog accessible from Settings or About:

- explain local block
- explain report queue
- explain what report data is sent
- explain owner remove behavior

This helps Play review and reduces user confusion.

## 13. Testing Matrix

Minimum Android tests:

- block peer hides chat rows
- block peer hides map marker and trail
- report peer writes pending local report
- report message writes pending local report with correct message id
- offline report remains queued after app restart
- online sync marks queued report delivered
- owner remove action only visible to owner
- non-owner cannot invoke remove
- blocked peer no longer triggers visible notifications

Manual QA scenarios:

- two-device chat, one peer blocked
- report while offline, then reconnect and sync
- owner removes member, group state converges correctly
- hide-only controls for location and trail without full peer block

## 14. Recommended Delivery Order

Implement in this order:

1. local persistence tables
2. local block enforcement in chat rendering
3. local hide enforcement in map / trail rendering
4. UI actions in chat and member list
5. report queue creation
6. WorkManager sync
7. owner remove UI and membership event
8. settings / disclosure copy

## 15. Release Gate For Moderation V1

Before Play submission, Android should satisfy all of the following:

- any member can block another peer locally in-app
- any member can report a peer in-app
- any member can report a message in-app
- owner can remove a member from the group
- reports can be created while offline
- reports show clear disclosure before sending optional plaintext
- blocked peer content is actually suppressed in chat and map/trail UI

## 16. Bottom Line

For Android, the first acceptable PeerMesh moderation implementation is not a heavy centralized system.

It is:

- local block
- local hide
- local report queue
- delayed report sync
- owner remove

That is the smallest implementation that matches PeerMesh's architecture while giving the app a much stronger Google Play safety posture.
