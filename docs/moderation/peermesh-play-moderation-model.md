# PeerMesh Minimal Moderation Model for Google Play

This document defines the minimum viable moderation and user-safety model for PeerMesh in a way that fits:

- local-first communication
- end-to-end or group-confidential message access
- peer-to-peer BLE and delayed network synchronization
- Google Play expectations for user-generated content and direct user interaction

It is not a full trust-and-safety program. It is the smallest defensible model that gives users direct safety controls without requiring PeerMesh operators to read all chat content by default.

## 1. Goals

PeerMesh should support all of the following at the same time:

- users can protect themselves inside the app without waiting for an admin
- group owners can manage membership and abusive participants
- reports can be created even while fully offline
- default app operation does not require centralized content inspection
- the design remains compatible with encrypted and peer-to-peer delivery

## 2. Policy Posture

PeerMesh should assume Google Play will treat the app as:

- a UGC app
- an app with direct user-to-user interaction

Because of that, the app should provide:

- in-app user blocking
- in-app reporting
- clear terms / acceptable use rules
- a practical action model after report or block events

PeerMesh should not rely on the argument that encryption removes the need for moderation controls. Encryption may explain why moderation is local-first and user-initiated, but it does not remove the need for user safety features.

## 3. Core Principles

The moderation model should be built on these principles:

- personal safety controls are local and immediate
- administrative controls are group-scoped
- developer visibility is opt-in and report-driven
- offline actions are first-class, not fallback behavior
- evidence collection is minimal by default

## 4. Required Roles

### 4.1 Every Member

Every group member should be able to:

- block another peer locally
- report a peer
- report a specific message
- hide another peer's live location and trail locally
- leave a group at any time

These controls protect the individual user directly and do not require owner approval.

### 4.2 Group Owner

The group owner should be able to:

- remove a member from the group
- revoke future group access for that member
- rotate group invite / join material when appropriate
- review member reports that are intended for group governance

Owner actions are group governance controls. They do not replace local block controls for each member.

### 4.3 Nearby Room Owner

Nearby Room should use a different moderation model than Private Group.

Because Nearby Room is:

- public to nearby participants
- BLE-first
- temporary by default
- not based on durable membership

the room owner should have lightweight room-scoped controls only.

For Nearby Room, the owner should be able to:

- mute a participant for that room
- hide a participant's messages locally on the owner's device
- stop surfacing invitations from that participant inside that room
- close the room early

For Nearby Room, the owner should not have:

- authoritative kick / remove semantics comparable to Private Group membership removal
- durable ban state that survives beyond the room lifetime by default
- complex moderator roles in MVP

## 5. Minimum User Actions

### 5.1 Block Peer Locally

This is the most important safety control for Play compliance.

When user A blocks peer B:

- messages from B are not rendered in A's UI
- new messages from B should not materialize into A's visible chat tables where the client has enough identity context to apply the block
- new live trail or location updates from B are hidden from A
- A's device may still receive and relay mesh envelopes from B when required for peer-to-peer transport continuity
- existing local content from B may be hidden behind an "unblock to view" state

This action is:

- immediate
- local to the blocking user's device/account state
- available offline
- reversible by the user

Local block does not need to remove B from the group for everyone else.
Local block also does not need to deny room join, deny group membership, or provide bilateral enforcement to the blocked peer.

### 5.2 Report Peer

The app should allow a user to report a peer from:

- member list
- chat thread header
- message overflow menu
- trail / live location UI where identity is visible

Suggested report reasons:

- harassment or abuse
- threatening behavior
- spam
- non-consensual location sharing concern
- inappropriate content
- impersonation or deceptive identity
- other

### 5.3 Report Message

The app should allow reporting a specific message. This is preferable to user-only reporting because it narrows the evidence scope and reduces unnecessary disclosure.

The UI should support:

- `Report message`
- optional `Block peer`
- optional `Hide future messages from this peer`

### 5.4 Hide Location / Trail

Because PeerMesh handles sensitive movement data, a separate control is useful:

- `Hide this peer's location`
- `Hide this peer's live trail`

This can be implemented as part of local block or as a separate lighter-weight control.

## 6. Offline-First Reporting Model

PeerMesh should treat reporting as a local event that may synchronize later.

### 6.1 Local Queue

When a user creates a report while offline:

- the report is written to a local queue
- the report gets a stable local id
- the user sees confirmation that the report was saved
- the queue retries delivery later when internet / relay registry access becomes available

### 6.2 Delivery Targets

PeerMesh can support one or both of these report destinations:

- developer / platform moderation endpoint
- group-owner governance inbox or signed group event

If only one can be built first, developer moderation endpoint is more useful for Play review, while owner-directed governance is more useful for in-group control.

### 6.3 No Always-On Central Inspection

Reports should be user-triggered. PeerMesh does not need to upload all messages for inspection by default.

Recommended posture:

- no blanket upload of all chat content
- no background content scanning requirement
- only user-reported evidence leaves the local device

## 7. Evidence Model

PeerMesh should minimize what gets disclosed during a report.

### 7.1 Minimal Evidence by Default

For a user report, capture only:

- report id
- reporter local peer id
- reported peer id
- group id
- report reason
- timestamp
- local app version / protocol version
- message id or trail event id if applicable

### 7.2 Optional Attached Evidence

If the report targets a specific message, the app may also include:

- ciphertext payload as received
- message metadata needed to identify the event
- optional plaintext excerpt only if the reporter explicitly confirms sending it

For strong privacy posture, PeerMesh should prefer:

- metadata-only reporting by default
- explicit consent before attaching readable plaintext

### 7.3 User-Controlled Disclosure

Before sending a report, the app should clearly state:

- what data will be sent
- whether message text is included
- whether location / trail data is included
- that reporting is voluntary

## 8. Separation of Powers

PeerMesh should separate three different actions:

- `Block locally`
- `Report to PeerMesh`
- `Remove from group`

These are not the same thing.

### 8.1 Local Block

- protects one user immediately
- no owner approval needed
- works offline

### 8.2 Report

- creates an abuse / safety signal
- may reach developer moderation later
- may optionally notify the group owner

### 8.3 Owner Remove

- changes group membership
- affects the whole group
- should be limited to owner or delegated moderator roles if they exist later

This separation is important because owner-only enforcement is not enough for direct user safety.

## 9. Group Owner Governance

Group owner moderation should be intentionally limited and practical.

Owner actions should include:

- remove member from group
- rotate invite / join credentials after abusive removal
- optionally mark member as not trusted for live trail sharing defaults

Owner reporting view should show:

- who was reported
- report reason
- optional message reference
- whether the reporter also blocked locally

Owner should not automatically receive readable plaintext of unrelated private history.

## 9.1 Nearby Room Governance

Nearby Room governance should be explicitly lighter than Private Group governance.

Recommended Nearby Room moderation rules:

- every participant can still `Block locally` and `Report`
- the room owner can `Mute in this room`
- mute should be room-scoped and expire with the room by default
- Nearby Room mute should not be modeled as durable membership revocation
- Nearby Room should not support authoritative `Kick user out of room` semantics in MVP

For Nearby Room, `Block locally` should be exposed directly to ordinary participants from participant list and message actions.
This control should not depend on room owner action and should remain purely local to the blocking device.

Rationale:

- Nearby Room lifetime is short, currently 30 minutes by default
- transport is BLE-first and propagation is snapshot-oriented rather than a durable server-backed authority model
- adding hard removal semantics creates mismatch with the current temporary room architecture
- local and room-scoped controls are sufficient for the first safety baseline

Operationally, mute in Nearby Room should mean:

- muted participant messages are hidden on the acting device
- invitation actions from the muted participant in that room may be suppressed
- owner UI can label the participant as muted for that room
- mute state is deleted or ignored once the room expires

If stronger enforcement is needed later, it should be introduced as a separate design with explicit transport, authority, and reconciliation rules rather than by reusing Private Group removal semantics mechanically.

## 10. Local Data Model

Minimum local entities:

- `BlockedPeer`
- `PendingAbuseReport`
- `ModerationActionAudit`

Suggested fields:

### 10.1 BlockedPeer

- `blocked_peer_id`
- `scope`: `global_local` or `group_local`
- `group_id` nullable
- `reason` optional
- `created_at`
- `expires_at` nullable

### 10.2 PendingAbuseReport

- `report_id`
- `reporter_peer_id`
- `reported_peer_id`
- `group_id`
- `message_id` nullable
- `event_type`
- `reason_code`
- `user_note` optional
- `attached_plaintext` boolean
- `delivery_state`
- `created_at`
- `last_attempt_at` nullable

### 10.3 ModerationActionAudit

- `action_id`
- `action_type`
- `actor_peer_id`
- `target_peer_id`
- `group_id` nullable
- `created_at`

## 11. UI Minimum

PeerMesh should expose moderation controls in obvious places.

### 11.1 Member List

- `Block locally`
- `Report peer`
- `Hide location`

### 11.2 Message Overflow Menu

- `Report message`
- `Block peer`
- `Hide messages from this peer`

### 11.3 Group Owner Member Actions

- `Remove from group`
- `Rotate invite after removal` if implemented

### 11.4 Safety Copy

The app should state clearly:

- PeerMesh is encrypted / local-first by design
- PeerMesh cannot proactively review all content
- users can block and report inside the app
- reports may send limited evidence only when the user chooses to submit one

## 12. What PeerMesh Can Honestly Claim

PeerMesh can safely claim:

- local-first communication
- encrypted or group-confidential messaging
- user-controlled reporting
- local user blocking
- owner-controlled group membership actions

PeerMesh should avoid claiming:

- fully unmoderated by design
- impossible for abuse to occur because of encryption
- complete inability to act on reports

Those claims make Play review harder and weaken the safety posture.

## 13. Recommended First Implementation Scope

If engineering capacity is tight, build in this order:

1. local block peer
2. report peer
3. report message
4. owner remove member
5. queued report delivery when online
6. explicit evidence disclosure screen

That is the smallest version that is both technically realistic for PeerMesh and easier to defend in Google Play review.

For Nearby Room specifically, build in this order:

1. local block peer
2. report peer
3. report message
4. owner mute in room

This ordering matches Nearby Room's lighter moderation model:

- self-protection first
- reporting second
- room-owner soft control last

## 14. Non-Goals for V1

This minimum model does not require:

- centralized live chat scanning
- mandatory plaintext server copies
- global username directory moderation
- automated content classification
- full trust-and-safety backoffice before first release

Those can come later if the product evolves beyond private invited groups.

## 15. Bottom Line

For PeerMesh, the minimum defensible Play-friendly moderation model is:

- every member can block another peer locally
- every member can report a peer or message in-app
- reports can be stored offline and delivered later
- the group owner can remove members from the group
- developer visibility is user-initiated rather than always-on

If PeerMesh ships with owner-only blocking and no member-level local block, the safety model is weak for direct user interaction and is harder to defend during Google Play review.

For Nearby Room specifically, the minimum defensible model should be:

- member-level local block
- peer and message reporting
- room-owner mute for the active room only
- no authoritative kick / ban semantics in MVP
