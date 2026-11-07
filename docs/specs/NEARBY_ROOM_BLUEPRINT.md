# PeerMesh Nearby Room Blueprint

## 1. Product Definition

Nearby Room is a BLE-first, temporary, nearby-only public coordination space.

It is not a private group.
It is not a direct message system.
It is not a permanent chat room.

Nearby Room exists to let people who are physically near each other discover, communicate, and optionally transition into a private group through a group invitation.

## 2. Core Positioning

PeerMesh has two social layers:

### Private Group

Private Group is for people who already know each other or have been intentionally invited.

Characteristics:

* Invitation required
* Passphrase or secure join token required
* Persistent
* Private
* Long-term coordination
* Supports shared awareness, maps, markers, areas, trails, and history

### Nearby Room

Nearby Room is for people who are physically nearby but may not know each other yet.

Characteristics:

* BLE-first
* Nearby-only
* No passphrase
* No approval by room owner
* Temporary
* Public within local proximity
* Lightweight coordination
* Can be used as a discovery layer before joining a private group

## 3. Strategic Role

Nearby Room is the discovery layer of PeerMesh.

Private Group is the retention layer.

The intended flow:

1. User opens PeerMesh near other people.
2. User discovers or creates a Nearby Room.
3. Nearby users join without passphrase.
4. People communicate in the temporary room.
5. If trust or shared interest emerges, one user sends a Private Group invitation to another user.
6. Long-term coordination happens in Private Group.

Nearby Room should help convert strangers nearby into trusted group members without adding Direct Messaging, friend lists, or social-network complexity.

## 4. Non-Goals

Nearby Room must not become:

* A replacement for Private Group
* A permanent public chat
* A 1-on-1 private messaging system
* A social feed
* A friend request system
* A cloud-based public community system
* A Telegram/Discord-style room

Do not implement private DM for Nearby Room.

The only private transition action is:

* Send Private Group Invitation

## 5. Room Lifetime

Default TTL:

* 30 minutes

Behavior:

* Room expires automatically after 30 minutes.
* Room owner may close the room early.
* Room history should be deleted or hidden after expiration.
* Expired room should stop being advertised over BLE.
* Nearby users should no longer be able to join after expiration.

Optional later improvement:

* Allow TTL selection: 30 minutes, 1 hour, 2 hours, 4 hours.
* MVP should keep fixed 30-minute TTL to reduce complexity.

## 6. Join Policy

Nearby Room does not require owner approval.

Reason:

* If approval is required, Nearby Room becomes too similar to Private Group.
* Nearby Room should feel like entering a public table in a physical place.
* Physical proximity is the access condition.

Join rules:

* User must be physically nearby and discover the room over BLE.
* No passphrase.
* No invitation link.
* No cloud discovery.
* No account requirement beyond existing PeerMesh local identity model.
* Room owner can mute abusive users within that room.

## 7. Owner Role

Room owner has lightweight moderation only.

Owner can:

* Create room
* Rename room if needed
* Close room early
* Mute participant inside the active room
* Optionally suppress invitations or future messages from a muted participant on the acting device

Owner cannot:

* Approve every join request
* Create complex roles
* Convert room into permanent group automatically
* Read private messages, because no private messages exist

## 8. Participant Actions

Participant can:

* Join room
* Send public room messages
* See nearby participants in the room
* Block another participant locally
* Leave room
* Receive group invitation
* Accept or decline group invitation
* Report or ignore abusive room/user if report system exists

Participant cannot:

* Send private DM
* Add friend
* Follow user
* Join private group without invitation/passphrase/join token
* Access room after expiration

Participant local block should:

* Hide the blocked participant's messages on the blocking device
* Suppress future invitation actions from that participant on the blocking device
* Not require room owner approval
* Not act as a global room removal

Nearby Room moderation rollout priority should be:

1. Block user local
2. Report user
3. Report message
4. Owner mute in room

This keeps Nearby Room aligned with its temporary and public-by-proximity model:

* Self-protection comes before governance
* Reporting comes before room-owner intervention
* Owner moderation stays lightweight

## 9. Group Invitation From Nearby Room

This is the bridge from Nearby Room to Private Group.

Use case:

* A and B meet in a Nearby Room.
* A wants to invite B into an existing Private Group.
* A selects B and taps “Invite to Group”.
* B receives a private invitation card.
* B can Accept or Decline.
* If accepted, B joins the Private Group using a secure group invitation mechanism.

Important UX rule:

The action should be named:

* “Invite to Group”

Not:

* “Message privately”
* “Add friend”
* “Connect”
* “Follow”

This keeps PeerMesh group-centered.

## 10. Invitation Abuse Limits

To prevent spam:

Recommended MVP limits:

* A user can send max 3 group invitations per Nearby Room.
* A user can send max 1 invitation to the same participant per room.
* A participant can decline an invitation.
* After decline, sender cannot invite the same participant again in the same room.
* Muted participants may have invitations suppressed on the acting device where mute is applied.

Optional later limits:

* Require both users to be in the same room for at least 2–5 minutes before invitation.
* Add “Block invitations from this user”.
* Add “Only allow invitations from room owner”.
* Add local abuse score.

## 11. BLE-First Behavior

Nearby Room discovery should happen through BLE.

Room advertisement should include minimal metadata:

* room_id
* room_name
* owner_device_id or owner_public_id
* created_at
* expires_at
* participant_count approximate if available
* protocol_version
* room_capabilities

Room messages should be propagated through existing BLE mesh/DTN mechanisms where possible.

Room should not require internet.

Cloud sync is not required for Nearby Room MVP.

## 12. Connectivity Rules

Nearby Room should work when:

* Internet is unavailable
* Devices are nearby over BLE
* App has required Bluetooth/location permissions
* PeerMesh mesh service is running

Nearby Room may optionally use relay/cloud later, but MVP should avoid cloud-based room discovery to preserve uniqueness and reduce complexity.

## 13. Data Model Draft

### NearbyRoom

Fields:

* roomId: String
* name: String
* ownerDeviceId: String
* createdAt: Long
* expiresAt: Long
* closedAt: Long?
* state: ACTIVE | EXPIRED | CLOSED
* participantCount: Int?
* isLocalOwner: Boolean
* lastSeenAt: Long
* source: BLE

### NearbyRoomParticipant

Fields:

* roomId: String
* deviceId: String
* displayName: String?
* joinedAt: Long
* lastSeenAt: Long
* role: OWNER | PARTICIPANT
* status: ACTIVE | LEFT
* invitationBlocked: Boolean
* isMutedLocally: Boolean

### NearbyRoomMessage

Fields:

* messageId: String
* roomId: String
* senderDeviceId: String
* body: String
* createdAt: Long
* receivedAt: Long
* ttlExpiresAt: Long
* deliveryState: LOCAL | SENT | RECEIVED

### NearbyRoomGroupInvitation

Fields:

* invitationId: String
* roomId: String
* fromDeviceId: String
* toDeviceId: String
* groupId: String
* groupName: String
* inviteToken or encryptedPayload
* createdAt: Long
* expiresAt: Long
* status: PENDING | ACCEPTED | DECLINED | EXPIRED

## 14. Security Model

Nearby Room is public to nearby users.

Do not promise strong privacy inside Nearby Room.

Recommended copy:

* “Nearby Rooms are visible to people nearby.”
* “Do not share sensitive information in a Nearby Room.”
* “Use Private Groups for trusted coordination.”

Private Group invitation must remain secure.

The group passphrase or join credential should not be publicly broadcast to the whole room.

Invitation payload should be targeted to the selected recipient where possible.

If true recipient-only encryption is not ready in MVP, use a safer fallback:

* Show invitation request in room
* Recipient accepts
* Then use existing secure group invitation/passphrase flow manually

Do not leak private group credentials into public room messages.

## 15. UX Flow

### Create Nearby Room

1. User opens Nearby Rooms screen.
2. User taps “Create Nearby Room”.
3. User enters room name.
4. App shows: “Visible to nearby people for 30 minutes.”
5. User confirms.
6. Room starts advertising over BLE.

### Discover Nearby Room

1. User opens Nearby Rooms screen.
2. App scans BLE.
3. Nearby rooms appear in list.
4. User taps room.
5. App shows room details.
6. User taps “Join”.
7. User enters public room chat.

### Send Group Invitation

1. User opens participant list inside Nearby Room.
2. User taps participant.
3. User taps “Invite to Group”.
4. User selects one of their Private Groups.
5. App sends invitation.
6. Recipient sees invitation card.
7. Recipient taps Accept or Decline.
8. If Accept, recipient joins Private Group through existing secure group join flow.

## 16. UI Copy Suggestions

### Nearby Room intro

“Nearby Rooms are temporary spaces for people around you. They are visible over local mesh and disappear after 30 minutes.”

### Create room warning

“People nearby can join this room without approval. Use Private Groups for trusted coordination.”

### Expiration notice

“This room will disappear in 30 minutes.”

### Group invitation card

“Truong invited you to join the private group: Weekend Hiking.”

Actions:

* Accept
* Decline

### Room expired

“This Nearby Room has expired.”

## 17. Pricing Strategy

Nearby Room should be available to Free users.

Reason:

* It creates discovery and activation.
* It demonstrates PeerMesh’s unique BLE-first value.
* It can bring new users into Private Groups.

Recommended Free limits:

* Create Nearby Rooms
* Join Nearby Rooms
* Create limited Private Groups
* Limited markers/areas
* Cannot create trails

Recommended Plus value:

* More/unlimited Private Groups
* More members per group
* Trail creator
* Awareness history
* Advanced replay
* More markers/areas
* Advanced search/filter

Nearby Room should not be the main paid feature.

Nearby Room should drive growth.

Private Group and advanced awareness tools should drive retention and monetization.

## 18. MVP Scope

Implement first:

* Create Nearby Room
* BLE advertise room
* Discover nearby rooms
* Join room
* Public room messages
* TTL 30 minutes
* Owner close room
* Owner mute participant
* Send Private Group invitation to selected participant
* Accept/decline invitation
* Basic spam limits

Nearby Room moderation MVP should be introduced in this order:

1. Local block user
2. Report user
3. Report message
4. Owner mute participant

Do not implement in MVP:

* Direct messages
* Friend list
* Cloud room discovery
* Public internet rooms
* Room categories
* Room search
* Long-term room history
* Advanced moderation
* Persistent public communities

## 19. Implementation Phases

### Phase 1: Local Data + UI Skeleton

* Add NearbyRoom models
* Add local persistence
* Add Nearby Rooms screen
* Add Create Room flow
* Add Room detail/chat screen
* Add expiration timer

### Phase 2: BLE Discovery

* Advertise active room metadata over BLE
* Scan nearby room advertisements
* Show discovered rooms
* Handle room expiration and disappearance

### Phase 3: Room Messaging

* Add room-scoped message type
* Propagate messages through BLE mesh/DTN
* Deduplicate messages
* Respect TTL
* Prevent expired room messages from appearing

### Phase 4: Participant Management

* Track participants
* Show participant list
* Add leave room
* Add owner close room
* Add owner mute participant

### Phase 5: Group Invitation Bridge

* Add “Invite to Group” action
* Select existing Private Group
* Send invitation to selected participant
* Recipient accepts/declines
* Integrate with existing secure group join flow
* Add invitation rate limits

### Phase 6: Polish + Abuse Controls

Implement moderation in this order:

1. Add local block/ignore
2. Add report user
3. Add report message
4. Add owner mute participant

* Add clear safety copy
* Add spam prevention
* Add local block/ignore
* Add expired room cleanup
* Improve empty states and onboarding

## 20. Key Product Principles

1. Nearby Room is for discovery.
2. Private Group is for trust.
3. No Direct Message.
4. No friend graph.
5. No passphrase for Nearby Room.
6. No approval to join Nearby Room.
7. Physical proximity is the access layer.
8. Group invitation is the only bridge from stranger interaction to long-term coordination.
9. Nearby Room should be simple enough to understand in 10 seconds.
10. Do not let Nearby Room become another generic chat app.

## 21. Final Product Summary

Nearby Room is a temporary BLE-first public space for people who are physically near each other.

It lets strangers nearby communicate briefly without passphrase or approval.

It disappears after 30 minutes.

It does not support private chat.

If two people want to continue coordination, one can invite the other into a Private Group.

This makes Nearby Room the discovery layer and Private Group the long-term coordination layer of PeerMesh.

# Revision Notes (Version 1.1)

This section supersedes any conflicting statements in Version 1.0.

The Nearby Room rollout strategy has been simplified.

Version 1 focuses on creating the first user Aha Moments through nearby discovery and presence awareness.

Advanced Shared Awareness features inside Nearby Rooms are postponed to a later phase.

---

# Nearby Room V1 Goal

The primary purpose of Nearby Room V1 is:

```text
Discover people nearby.
```

and

```text
Feel the presence of nearby PeerMesh users.
```

Nearby Room V1 is intentionally lightweight.

It is not intended to demonstrate the full Shared Awareness model.

---

# Core Aha Moments

Nearby Room V1 should focus on two experiences.

## Aha Moment #1

```text
There are people around me using PeerMesh right now.
```

Example:

User opens PeerMesh.

The app shows:

```text
Nearby Right Now

3 Nearby Rooms

☕ Coffee Corner
🚴 Cycling Meetup
🥾 Weekend Hike
```

The user immediately understands:

```text
PeerMesh connects people who are physically nearby.
```

---

## Aha Moment #2

```text
Someone nearby just appeared.
```

Example:

User joins a Nearby Room.

A new participant joins.

The room activity feed shows:

```text
Someone nearby joined.
```

or

```text
A new participant joined.
```

The goal is to reinforce:

```text
These are real people nearby.
```

without revealing identity or physical location.

---

# Privacy Principle

Nearby Room is designed around:

```text
Physical Proximity
+
Controlled Disclosure
```

Being nearby does not automatically reveal identity.

Participants should know:

```text
Someone is nearby.
```

but not:

```text
Who they are.
Where exactly they are.
```

unless that participant chooses to reveal it.

---

# Supported Interaction Styles

Nearby Room should support both interaction patterns.

## Pattern 1

Real-world discovery.

Example:

Participant voluntarily describes themselves.

```text
I'm the person sitting near the cherry blossom plant.
```

Another participant approaches and starts a conversation in person.

---

## Pattern 2

Private relationship formation.

Example:

Participants interact only through the room.

One participant sends a Group Invitation.

The other accepts.

A relationship forms without revealing real-world identity.

---

# Nearby Room Is Not A Dating Feature

Nearby Room should not encourage users to identify people without consent.

The system should never expose:

* precise position
* distance
* device identity
* table number
* real-world description

All disclosure must be voluntary.

---

# Scope Reduction For V1

The following ideas are intentionally postponed.

## Deferred Features

* Shared Awareness Preview
* Marker sharing inside Nearby Rooms
* Area sharing inside Nearby Rooms
* Route sharing inside Nearby Rooms
* Awareness notifications
* Awareness activity feeds
* Nearby Room map overlays

These features may be explored in a future version.

They are not required for the first release.

---

# Room Feed Requirements

Nearby Room V1 only requires:

## Messages

Example:

```text
Anna:
Anyone here working remotely today?
```

---

## Join Events

Example:

```text
Someone nearby joined.
```

or

```text
A new participant joined.
```

---

## Leave Events

Example:

```text
Someone left the room.
```

No additional event types are required.

---

# Discovery Metadata Contract

Nearby Room discovery uses two metadata layers:

1. BLE advertisement discovery metadata
2. Full metadata fetched after discovery

The design goal is:

- advertisement stays tiny and reliable enough for BLE manufacturer data
- full room details are pulled separately after a room has been discovered

## 1. BLE Advertisement Discovery Metadata

Advertisement metadata is intentionally minimal and is only used to discover candidate rooms.

Current payload structure:

- `protocolVersion`: `1 byte`
- `roomId`: `6 bytes`
- `participantCount`: `1 byte`
- `ttlRemainingMinutes`: `1 byte`
- `nameLength`: `1 byte`

Current behavior:

- `roomName` bytes are omitted from advertisement
- `nameLength` is therefore currently `0`
- total nearby-room advertisement payload is `10 bytes`

Meaning:

- `roomId` identifies the room
- `participantCount` gives a coarse participant count
- `ttlRemainingMinutes` gives a coarse remaining lifetime hint for discovery
- advertisement is not authoritative for the final room name or final countdown

## 2. Full Metadata Payload

After discovery, a device must fetch full metadata through Nearby Room transport.

Current full metadata contract for `NEARBY_ROOM_METADATA_RESPONSE_V1`:

- `roomName`
- `createdAtMs`
- derived `expiresAtMs`
- `participantCount`
- optional `ownerDisplayName`

Authoritative rule:

- `createdAtMs` from owner-originated nearby-room transport is the authoritative room-time anchor
- `expiresAtMs` is derived locally as `createdAtMs + ROOM_TTL_MS`
- advertisement `ttlRemainingMinutes` is only a coarse discovery hint

## 3. Nearby Room Transport Wire Format

Nearby Room transport now uses a compact JSON wire format on encode, while decode remains backward compatible with the older long-field JSON format.

Wire-format rules:

- new senders should emit short field names and short `kind` values
- `JOIN_READY` must not introduce a verbose-only exception; it follows the same compact-on-encode rule
- receivers must accept both:
  - short wire format
  - legacy long-field wire format
- internal Kotlin/domain model may continue to use long semantic names

### Short `kind` mapping

| Short `k` | Legacy `kind` | Meaning |
|---|---|---|
| `M2` | `NEARBY_ROOM_MESSAGE_V2` | encrypted nearby-room message |
| `KB1` | `NEARBY_ROOM_KEY_BOOTSTRAP_V1` | room-key bootstrap |
| `KR1` | `NEARBY_ROOM_KEY_REQUEST_V1` | room-key request |
| `MR1` | `NEARBY_ROOM_METADATA_REQUEST_V1` | full metadata request |
| `MS1` | `NEARBY_ROOM_METADATA_RESPONSE_V1` | full metadata response |
| `RC1` | `NEARBY_ROOM_CLOSED_V1` | room-closed event |
| `JR1` | `NEARBY_ROOM_JOIN_READY_V1` | participant join-success activity event |

### Short field mapping

| Short field | Legacy field | Meaning |
|---|---|---|
| `k` | `kind` | packet kind |
| `r` | `room_id` | nearby room id |
| `sd` | `sender_device_id` | immediate sending device |
| `od` | `owner_device_id` | authoritative owner device |
| `sn` | `sender_display_name` | optional sender display name |
| `og` | `origin_device_id` | canonical signal origin across relay hops |
| `ct` | `created_at_ms` | owner-authoritative room/message creation time |
| `ex` | `expires_at_ms` | derived expiry / message ttl expiry |
| `mid` | `message_id` | message id |
| `ctxt` | `ciphertext` | encrypted message body |
| `n` | `nonce` | AES-GCM nonce |
| `kv` | `key_version` | room key version |
| `rk` | `room_key` | bootstrap room key |
| `td` | `target_device_id` | directed transport target |
| `rn` | `room_name` | full room name |
| `pc` | `participant_count` | participant count |
| `cl` | `closed_at_ms` | room close timestamp |

Origin/sender rule:

- `origin_device_id` identifies the device that originally created the signal
- `sender_device_id` identifies the device that transmitted the current hop
- relays must preserve `origin_device_id`
- relays may rewrite `sender_device_id`
- this rule applies consistently to:
  - messages
  - join-success events
  - other relayable nearby-room signals added later

Backward-compatibility rule:

- receivers may continue to accept legacy signal-specific origin fields during decode
- new senders should normalize on `origin_device_id` for origin semantics

## 4. Nearby Room BLE Transport Packet Lifecycle

This section describes the current app-layer BLE transport flow for Nearby Room packets:

- who creates each packet
- how it is sent
- who is expected to receive it
- what local side effects happen on receive

All Nearby Room transport packets are encoded from `NearbyRoomTransportEnvelope` into compact canonical JSON before being handed to the shared BLE mesh transport pipeline.

### Transport-wide rules

- `broadcastEnvelope(...)` is used for broadcast-style packets
- `broadcastEnvelopeToDeviceIds(...)` is used for directed packets
- `KR1`, `KB1`, `MR1`, and `MS1` may be processed even when the receiver is not yet in an active room session
- all other Nearby Room packets are processed only when the receiver is currently in the matching active room
- relayed packets preserve `origin_device_id` and may rewrite `sender_device_id` for the current hop

### `KR1` room-key request

Purpose:

- a joining peer asks for the room key so it can gain chat access

Creation:

- the joining peer emits `KR1` when it needs a bootstrap key for a room

Send path:

- sent as a BLE broadcast

Receive path:

- a receiver that already has the room key evaluates local reply guards
- if the receiver is not the requester, has the room key, and is not inside reply cooldown, it returns `KB1`

Side effects on receive:

- no room DB mutation by itself
- may trigger an immediate `KB1` reply

### `KB1` room-key bootstrap

Purpose:

- deliver the room key to a joining peer

Creation:

- owner or another eligible peer with the room key emits `KB1` in response to `KR1`

Send path:

- directed to the requester device id

Receive path:

- receiver validates and stores the room key locally
- receiver consumes owner-authoritative `created_at_ms`
- receiver schedules a post-join metadata refresh so full room metadata is resolved after key bootstrap

Side effects on receive:

- local key storage updated
- local room timing may be corrected from owner-authoritative `created_at_ms`

### `MR1` full metadata request

Purpose:

- request full room metadata after discovery advertisement has identified a room

Creation:

- discovery flow or post-join refresh flow emits `MR1`

Send path:

- directed to one chosen source device for that room

Receive path:

- receiver checks that the room is still active, metadata is serveable, and reply cooldown is clear
- if allowed, it returns `MS1`

Side effects on receive:

- no room DB mutation by itself
- may trigger an immediate `MS1` reply

### `MS1` full metadata response

Purpose:

- return authoritative room metadata required to make a discovered room usable

Creation:

- emitted as the response to `MR1`

Send path:

- directed back to the requester

Receive path:

- receiver resolves room metadata locally
- room name, participant count, owner identity, and authoritative `created_at_ms` are merged into the local room record
- once the peer has both metadata and room key, it may emit `JR1`

Side effects on receive:

- local room metadata becomes `RESOLVED`
- room may become visible in Discovery UI after this step

### `JR1` join-ready event

Purpose:

- announce that a participant has actually completed room entry and is ready to chat

Creation:

- emitted only after the joining peer has both:
  - local room key
  - resolved room metadata

Send path:

- sent to nearby active peers in the same room

Receive path:

- receiver records participant join success in local room state
- receiver may relay `JR1` onward once, preserving `origin_device_id`

Side effects on receive:

- participant presence can be refreshed locally
- optional room-activity UI may render from successful join-ready processing

### `RC1` room-closed event

Purpose:

- announce that the owner has closed the room

Creation:

- emitted by the local owner when the owner closes the room

Send path:

- sent as BLE broadcast

Receive path:

- active-room receiver marks the room closed
- receiver relays the close event once, with dedupe
- receiver purges local room data immediately
- receiver records a UI notice so the room screen can show the blocking owner-closed dialog

Side effects on receive:

- room state is terminal locally
- room data is removed from local DB
- UI navigation should wait for explicit user acknowledgement of the dialog

### `M2` encrypted room message

Purpose:

- carry a normal nearby-room chat message

Creation:

- sender creates a local room message
- message body is encrypted with the room key using AAD bound to:
  - `room_id`
  - `message_id`
  - canonical message origin device id
  - `created_at_ms`
  - `expires_at_ms`
  - `key_version`

Send path:

- owner may target a chosen set of nearby active peers
- otherwise the message is broadcast through the BLE mesh pipeline

Receive path:

- if receiver already has the room key, it decrypts and ingests the message
- if receiver lacks the room key, it emits `KR1`
- after successful ingest, the message becomes eligible for relay recovery fan-out

Side effects on receive:

- message stored locally
- room participant and last-seen state updated
- relay recovery offer flow may start

### `MO1` message offer

Purpose:

- tell a nearby active peer that this device has a specific message available for pull

Creation:

- emitted by the relay-offer worker after a message has been locally rendered/ingested

Send path:

- directed to one nearby target device at a time

Receive path:

- receiver checks whether `(room_id, message_compact_id)` is already resolved or already wanted
- if not, receiver inserts or refreshes a want-list item

Side effects on receive:

- local relay want-list may gain a new pending item

### `MF1` message fetch request

Purpose:

- request the full encrypted message payload for one offered compact message id

Creation:

- emitted by the relay-want worker after it chooses one candidate source device

Send path:

- directed to the chosen source peer

Receive path:

- source peer looks up the matching message by compact id
- if available, it responds with `MD1`

Side effects on receive:

- no direct DB mutation by itself
- may trigger an immediate `MD1` response

### `MD1` message data response

Purpose:

- return the full encrypted payload for relay recovery

Creation:

- emitted by a source peer in response to `MF1`

Send path:

- directed back to the requesting peer

Receive path:

- receiver decrypts the payload using the room key
- receiver ingests the message into canonical local storage
- receiver marks the want-list item resolved
- receiver then treats the message as locally available and may fan out `MO1` to other nearby peers

Side effects on receive:

- recovered message stored locally
- relay want state resolved
- message can continue propagating outward through the nearby cluster

### End-to-end summary

The intended packet sequence is:

1. Discovery advertisement identifies a room.
2. Device sends `MR1`.
3. Source replies with `MS1`.
4. Joining peer sends `KR1` if it still lacks chat access.
5. Owner or another eligible peer replies with `KB1`.
6. Joining peer resolves metadata/key and may emit `JR1`.
7. Chat sender emits `M2`.
8. Peers that ingest `M2` may later emit `MO1`.
9. Missing peers request recovery with `MF1`.
10. Source responds with `MD1`.
11. Owner close emits `RC1`, and active peers relay and clean up terminal room state.

### `JR1` join-success contract

`JOIN_READY` exists to represent:

- a participant has successfully joined the room
- the participant is now actually ready to chat

It must not be emitted for:

- discovery
- key-request attempts
- metadata-request attempts
- partial join progress
- failed bootstrap or failed metadata resolution

Join-success readiness rule:

- emit `JR1` only after the joining peer has both:
  - room bootstrap key available locally
  - room metadata resolved locally

Transport rules:

- joiner originates `JR1`
- origin packet should use compact wire fields and short `k = JR1`
- packet should carry:
  - `r`
  - `sd`
  - `og`
  - `od` if available
  - `ct`
  - `ex`
- avoid unnecessary verbose payload fields for `JR1`

Relay rules:

- `JR1` should be broadcast to active nearby peers in the same room
- receiving peers in the same active room may relay `JR1` onward to other nearby peers
- relay must preserve `origin_device_id`
- relay must update only `sender_device_id` for the current hop
- relay must not echo back to:
  - immediate sender
  - original joiner / `origin_device_id`
  - owner device if the local relay policy excludes owner echo

Dedupe rules:

- `JR1` relay must be deduplicated so the same join-success notice does not fan out indefinitely
- recommended dedupe key:
  - `(room_id, origin_device_id, created_at_ms)`

UI rule:

- room activity line `Someone nearby joined.` should only be rendered from a successful `JR1` outcome or an equivalent post-join-ready fallback, never from an early join attempt

## Discovery And Fetch Flow

The intended flow is:

1. User opens Nearby Rooms screen.
2. Device runs a BLE discovery burst.
3. Device ingests advertisement discovery metadata for nearby rooms.
4. Device inserts discovered rooms into local DB as hidden unresolved entries.
5. Each unresolved room gets:
   - `metadata_status = PENDING`
   - `metadata_source_device_id = best candidate advertiser`
6. Discovery UI does not show that room yet.
7. A metadata fetch queue requests full metadata for unresolved rooms, with limited concurrency.
8. Owner or another eligible advertiser responds with `NEARBY_ROOM_METADATA_RESPONSE_V1`.
9. Device stores `roomName` and authoritative `expiresAtMs`.
10. Room becomes visible in discovery UI only after `metadata_status = RESOLVED`.

Implementation constraints:

- do not fetch every room at once
- use a queue with small concurrency, for example 1 to 2 rooms in flight
- if multiple advertisers expose the same `roomId`, choose one best candidate source device for metadata fetch

Discovery-burst ingest rules:

- within one discovery burst, each `roomId` should be inserted or refreshed in DB at most once
- repeated advertisements for the same `roomId` inside the same burst must be accumulated first, not written directly to DB each time
- if multiple devices advertise the same `roomId`, choose one best candidate source for that burst
- best candidate selection should prefer stronger signal first, with recency as a tie-breaker
- discovery UI should keep a bounded room budget, currently targeting at most `10` rooms per burst
- only the chosen best candidate for each accepted room should be used to:
  - perform the single DB upsert for that burst
  - seed the source device for full metadata fetch
- the best candidate decision must be finalized before any discovery-row DB write for that `roomId` in the burst
- non-winning sightings in the same burst are diagnostic input only and must not cause repeated DB churn

Consequence for UI:

- a newly discovered room may exist in DB before it is visible in the discovery list
- visibility is gated on successful full-metadata resolution

Room close propagation rules:

- when the owner closes a room, the owner device must broadcast an explicit room-closed transport event
- devices that are currently active in that room and receive the close event must:
  - forward the same close event once to other active-room peers reachable through their local BLE connectivity
  - after that relay attempt finishes, delete the room and its related local room data
  - exit the room UI and return to Nearby Rooms discovery
  - show a toast that the room was closed by the owner
- only devices currently active in that `roomId` should process or relay the close event
- close-event relay should be deduplicated so the same close notice does not fan out indefinitely

Authoritative room-time rules:

- authoritative room lifetime should be anchored on `created_at_ms`
- `created_at_ms` is the owner room-creation timestamp in UTC epoch milliseconds
- `expires_at_ms` should be derived as `created_at_ms + room_ttl_ms`
- when a peer resolves full metadata for a nearby room, it should consume owner-authoritative `created_at_ms`
- when a peer receives owner bootstrap transport for a joined room, it should also consume owner-authoritative `created_at_ms`
- remote peers should not keep a locally larger `expires_at_ms` if owner-provided `created_at_ms` implies a smaller but authoritative expiry

## Nearby Room Relay Recovery V1

Nearby Room chat messages are currently best-effort over BLE mesh.

Relay Recovery V1 adds a lightweight, nearby-only recovery layer so a peer that misses a nearby-room message can pull it from another nearby active peer without turning Nearby Room into full DTN.

### Scope

Relay Recovery V1 applies only to:

- public nearby-room chat messages

It does not apply to:

- room advertisement discovery metadata
- room full metadata fetch
- key bootstrap/key request
- private-group DTN transport

### Design goals

- improve delivery probability inside one physical nearby cluster
- keep control packets small
- avoid sender-side waiting/ack dependency
- avoid broadcast re-sending full payload to peers that already have the message
- avoid relay loops and echo-back to the message origin path

### Canonical message identity

Nearby Room DB remains authoritative on full `message_id`.

Relay Recovery V1 uses:

- full DB identity:
  - `message_id`
- compact relay identity:
  - `message_compact_id`

Rules:

- DB stores full `message_id` unchanged
- relay layer adds `message_compact_id`
- offer/fetch control packets use only:
  - `room_id`
  - `message_compact_id`
- data response must carry enough metadata to map compact id back to the full stored message

### Compact identity rules

- `room_id` stays compact at `6 bytes`
- `message_compact_id` is `12 bytes`
- current agreed derivation:
  - take the first `6 bytes` and the last `6 bytes` from the canonical full message id representation

### Receiver-side dedupe key

Receiver-side relay dedupe key is:

- `(room_id, message_compact_id)`

This key is used only for relay-recovery gating at the receiver:

- decide whether to accept an offer into want-list processing
- decide whether the message is already resolved locally

It is not treated as the global canonical message identity for DB storage.

### Receiver relay states

For each `(room_id, message_compact_id)`, receiver state is:

- `missing`
- `wanted`
- `resolved`

Rules:

- `missing`:
  - accept offer
  - upsert want-list item
- `wanted`:
  - reject duplicate offer
- `resolved`:
  - reject duplicate offer

### Packet types

Relay Recovery V1 introduces three compact nearby-room control/data packet kinds:

- `MO1`
  - message offer
- `MF1`
  - message fetch request
- `MD1`
  - message data response

These packet kinds are app-layer nearby-room transport packets, not BLE advertisement payloads.

### `MO1` message offer

Purpose:

- tell nearby peers that this device has a specific nearby-room message available for pull

Wire payload should be kept minimal.

Minimum fields:

- `k`
  - `MO1`
- `r`
  - compact `room_id`
- `m`
  - `message_compact_id`

Current direction:

- sender should proactively offer to all nearby peers visible in the current snapshot
- sender should not wait for target `ok/reject`

### `MF1` message fetch request

Purpose:

- request the full nearby-room message data for one compact message id

Minimum fields:

- `k`
  - `MF1`
- `r`
  - compact `room_id`
- `m`
  - `message_compact_id`

### `MD1` message data response

Purpose:

- provide the full nearby-room message payload needed to decrypt, ingest, and map the compact relay id back to canonical storage

Required fields:

- `k`
  - `MD1`
- `r`
  - compact `room_id`
- `m`
  - `message_compact_id`
- full `message_id`
- `origin_device_id`
- `sender_device_id`
- `created_at_ms`
- `expires_at_ms`
- `ciphertext`
- `nonce`
- `key_version`
- optional `sender_display_name`

Message identity rule:

- for nearby-room message transport, `origin_device_id` is the canonical message origin / creator
- `sender_device_id` is only the current transport hop sender
- decryption AAD and canonical DB ownership should bind to `origin_device_id`, not relay-hop `sender_device_id`

### Sender-side offer table

Each sender/relay peer should keep a local offer ledger so it does not repeatedly offer the same message to the same target.

Suggested key:

- `(room_id, message_compact_id, target_device_id)`

Suggested columns/state:

- `offered_at`
- `last_seen_target_at`
- `expires_at`

Behavior:

- sender offers once per target peer per message within the active relay window
- sender does not need to know whether target internally accepted or rejected
- sender may offer to newly discovered nearby peers that were not previously in the ledger

### Offer fan-out rule

When a peer successfully receives and ingests a nearby-room message, it may relay-offer that message further.

Offer behavior:

- scan nearby snapshot
- fan out `MO1` to all nearby active peers currently visible in that snapshot
- cap the snapshot to at most `10` peers
- skip peers already present in the sender-side offer ledger for that message

### Anti-echo rule

Relay recovery must not send back toward:

- `message owner`
  - the peer that originally created the message
- `message sender`
  - the peer that sent the current relay/control packet being processed

Meaning:

- do not offer back to either of those two peers
- do not fetch from either of those two peers when the current decision path would bounce the request back to them

This prevents immediate echo loops and trivial back-and-forth relay churn.

### Target-side want list

Receiver keeps a local want list keyed by:

- `(room_id, message_compact_id)`

Suggested per-item fields:

- `room_id`
- `message_compact_id`
- `state`
  - `pending`
  - `requesting`
  - `resolved`
  - `failed`
- `candidate_sources`
- `attempt_count`
- `last_requested_at`
- `expires_at`

Behavior:

- on accepted `MO1`, receiver inserts or refreshes a want-list item
- if the key is already `wanted` or `resolved`, receiver rejects locally and does not upsert a duplicate item

### Candidate-source fairness rule

Want-list pull resolution should avoid overloading a single strongest peer.

Therefore:

- do not permanently bias toward one “best” source peer
- maintain multiple candidate sources when multiple offers are observed
- fetch selection should be fair across candidates, for example:
  - round-robin
  - randomized with short cooldown avoidance

The goal is to spread recovery work across nearby peers instead of always pulling from one high-RSSI peer.

### Pull scheduling

Receiver-side recovery runs on a dedicated worker/queue separate from normal message render flow.

Suggested behavior:

- when want list transitions from empty to non-empty, wake the worker immediately
- after each fetch attempt, wait a short cooldown, around `250-500 ms`
- allow only low concurrency, for example `1` in-flight fetch per room

### Pull flow

Recommended v1 flow:

1. Peer receives and successfully ingests a nearby-room message.
2. Peer adds the message to relay-offer processing.
3. Offer worker scans nearby peers and sends `MO1` to nearby targets, up to `10`.
4. Target receives `MO1`.
5. Target locally checks `(room_id, message_compact_id)`:
   - if missing -> accept locally and upsert want item
   - if wanted/resolved -> reject locally and do nothing further
6. After a short cooldown, target want-list worker scans nearby and chooses one fair candidate source.
7. Target sends `MF1`.
8. Source returns `MD1`.
9. Target maps compact id to full `message_id`, decrypts, ingests, and marks the want item `resolved`.
10. Newly resolved target may now offer the same message onward to other nearby peers, subject to the same anti-echo and offer-ledger rules.

### Timeouts and retries

Relay Recovery V1 should remain lightweight.

Recommended limits:

- short fetch timeout per source, around `2-3 seconds`
- bounded retry count per want item
- if one source times out, try another candidate source fairly
- if all candidates fail, mark the want item `failed`
- a later new offer may revive a failed item back to `pending`

### Active-room gate

Relay recovery is only valid for the currently active nearby room.

Receiver must ignore `MO1`, `MF1`, and `MD1` if:

- the device is not active in that `room_id`
- or the room is closed/expired locally

### Persistence boundary

V1 may keep offer-ledger and want-list state in local persistence or durable app-local storage if needed for process resilience.

Minimum requirement:

- state must survive long enough to prevent immediate duplicate relay churn during the active room session

### Non-goals of V1

Relay Recovery V1 does not attempt to provide:

- full DTN guarantees
- cloud-backed recovery
- global exactly-once delivery
- cross-room message backfill
- indefinite message store-carry-forward

---

# Transport Scheduling Note

Nearby Room transport currently shares the same BLE mesh transport pipeline as normal PeerMesh BLE envelopes.

This means Nearby Room traffic can contend with standard BLE mesh transport if left unmanaged.

The selected direction for a later implementation phase is:

* rate-limit and deprioritize Nearby Room transport envelopes within the existing scheduler

This is preferred over introducing a fully separate BLE transport lane at this stage because it has lower implementation risk and lower regression risk for the current BLE mesh stack.

However, this work is explicitly deferred.

Priority order:

1. Fix Nearby Room discovery and fresh room fetch behavior first.
2. Verify that Rooms Listing can reliably collect room metadata from nearby active peers.
3. Only after discovery/fetch is stable, implement Nearby Room transport rate-limiting and deprioritization.

Until that later phase is implemented, transport contention should be treated as a known limitation of the current Nearby Room prototype.

---

# Recommended Empty State

When no room exists:

```text
No Nearby Rooms Found

Nearby Rooms are temporary spaces
for people around you.

Create one and become discoverable
to nearby PeerMesh users.

[ Create Nearby Room ]
```

---

# Recommended Discovery Screen

Suggested heading:

```text
Nearby Right Now
```

Suggested subtitle:

```text
Temporary rooms created by people nearby.
```

Example:

```text
☕ Coffee Corner
12 participants

🚴 Cycling Meetup
4 participants

🥾 Weekend Hike
7 participants
```

---

# Success Criteria For V1

A first-time user should understand:

1. Nearby Rooms are temporary.
2. Nearby Rooms are based on physical proximity.
3. Nearby Rooms do not reveal identity automatically.
4. There are real people nearby using PeerMesh.
5. Users can interact while maintaining privacy.
6. Users can later transition into private groups if they choose.

---

# Future Vision

Nearby Room V1 focuses on presence.

Future versions may expand into Shared Awareness features such as:

* shared markers
* shared areas
* shared routes
* awareness notifications
* temporary local awareness layers

These features are intentionally outside the scope of Version 1.

---

# Final Principle

Nearby Room is not primarily about chat.

Nearby Room is about discovering that other people nearby exist and are available for interaction.

The room provides a temporary shared space.

Identity disclosure remains entirely under the participant's control.
