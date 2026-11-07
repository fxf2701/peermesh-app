# GI2A1 — Current Architecture Contract

Status: **IMPLEMENTED — architecture/documentation only**  
Milestone: **GI2 — Nearby Room Integration**  
Baseline database version: **53**  
Production code changes: **none**

## 1. Purpose

GI2A1 freezes the current Nearby Room architecture before recipient identity, self-info capability,
TOFU, recipient-confidential crypto, invitation persistence, or timeline integration are added.

This contract is source-anchored. It records:

- exact outbound and inbound message flows;
- message, participant, room-expiry, replay, and relay ownership;
- current persistence and migration boundaries;
- current BLE framing limits;
- the integration seams and blockers that later GI2 phases must respect.

GI2A1 does not change the GI1 protocol, create a new transport kind, add production crypto, or modify
Room persistence.

## 2. Locked upstream contract

GI2 inherits `GI1_Protocol_Review.md` unchanged:

- ROOM invitations use normal Nearby Room encryption only.
- PARTICIPANT invitations use a recipient-confidential inner envelope inside normal room encryption.
- A non-target participant may relay the private envelope but must never materialize plaintext.
- The sender keeps local plaintext and must not decrypt its own wire ciphertext.
- Actor, participant, and device identifiers are routing/AAD/key-resolution inputs only; they are never
  encryption keys.
- Recognized malformed or future invitation payloads must never fall back to normal text rendering.
- The private HKDF label is exactly `peermesh/group-invitation/private/v1`.

## 3. Current Nearby Room ownership map

| Concern | Current owner | Persistent source of truth |
| --- | --- | --- |
| Room identity and lifetime | `NearbyRoomRepository` / `NearbyRoomEntity` | `nearby_rooms.room_id`, `created_at_ms`, `expires_at_ms`, `state` |
| Participant identity | `NearbyRoomRepository` / `NearbyRoomParticipantEntity` | `(room_id, device_id)` |
| Logical message identity | Repository caller before transport | `(room_id, message_id)` in `NearbyRoomMessageEntity` |
| Message body | `NearbyRoomMessageEntity.body` | Plaintext logical body at rest |
| Outer room encryption | `NearbyRoomTransportManager` + `NearbyRoomMessageCrypto` | Recomputed for every send attempt |
| Room key | `NearbyRoomKeyManager` | Existing room-key storage |
| Wire envelope | `NearbyRoomTransportCodec` | Ephemeral encoded bytes |
| BLE delivery and fragmentation | `BleMeshRuntime` / `BleMeshTransport` | Runtime queues and chunk state |
| Direct receive | `NearbyRoomTransportReceiver` | Decrypt then repository ingest |
| Replay/recovery | `NearbyRoomRelayRecoveryManager` | message rows + offer/want rows |
| Timeline source | `NearbyRoomRepository.listMessages()` | `nearby_room_messages` |
| Self info | transport manager/receiver + participant row | participant profile fields |
| Self-info retry identity | `NearbyRoomSelfInfoRetryCoordinator` | in-memory key `(roomId,targetDeviceId,profileUpdatedAtMs)` |

## 4. Outbound message flow

The current logical message is created and persisted before transport encryption.

```text
UI/repository caller
  -> create NearbyRoomMessageEntity
     - room_id
     - message_id
     - sender/origin identity
     - body plaintext
     - created_at_ms
     - ttl_expires_at_ms
  -> persist message row
  -> NearbyRoomTransportManager.sendEncryptedMessage(message)
  -> load room and room key
  -> NearbyRoomMessageCrypto.encrypt(message.body, outer AAD)
  -> build KIND_MESSAGE_V2 transport envelope
  -> NearbyRoomTransportCodec.encode()
  -> BleMeshRuntime broadcast with NEARBY_ROOM_DATA
```

### 4.1 Message identity contract

`message_id` already exists before outer room encryption. `sendEncryptedMessage()` binds it into the
outer AAD and copies it into the wire envelope.

GI2 consequence:

- private invitation inner AAD can bind the existing `message_id`;
- GI2 does not need a new generic message-ID reservation framework;
- the repository operation that creates a private invitation must allocate the immutable message ID
  before recipient encryption;
- retry must reuse the persisted logical body/envelope and message ID.

### 4.2 Outer room encryption contract

`NearbyRoomMessageCrypto.messageAad()` currently binds:

```text
KIND_MESSAGE_V2
room id
message id
sender/origin device id
created-at
expires-at
room-key version
```

It uses newline concatenation. This existing encoding remains the outer-room contract for GI2.
It must not be reused for the GI1 inner recipient-confidential AAD, which requires canonical structured
or length-prefixed encoding.

### 4.3 Transport kind contract

Normal room messages use `KIND_MESSAGE_V2` and `MeshTrafficHint.NEARBY_ROOM_DATA`.

GI2 must keep Group Invitation inside this message kind. No invitation-specific BLE transport kind is
needed or permitted by this architecture contract.

## 5. Inbound direct-delivery flow

```text
BLE/runtime
  -> NearbyRoomTransportCodec.decode()
  -> NearbyRoomTransportReceiver
  -> KIND_MESSAGE_V2 branch
  -> load room key
  -> outer AES-GCM decrypt
  -> repository.ingestRemoteTransportMessage(envelope, body)
  -> NearbyRoomMessageEntity upsert/reducer
  -> relay recovery onMessageAvailable()
  -> timeline reads message rows
```

Current receiver passes the decrypted body directly to the repository. Current repository validation
assumes the body is a normal text body.

### 5.1 Required GI2 classification seam

GI2 message classification must occur after successful outer room decryption and before normal-text
validation/materialization.

The decision must distinguish at least:

```text
NOT_INVITATION
VALID_PUBLIC_INVITATION
VALID_PRIVATE_INVITATION
RECOGNIZED_UNSUPPORTED_OR_MALFORMED_INVITATION
```

`NOT_INVITATION` may continue to the current text path. The final state must be persisted/suppressed
without text fallback. A nullable decoder result must not collapse these states.

### 5.2 Sender/origin identity

Transport forwarding may change `sender_device_id` to the last-hop sender. The logical origin is
resolved as:

```text
origin_device_id ?: sender_device_id
```

Outer message decryption and repository ingestion use that origin identity. GI2 inner AAD sender
binding must use the same stable logical-origin semantic, not the last-hop relay identity.

## 6. Replay and relay recovery flow

Nearby Room recovery uses three existing control kinds:

```text
MESSAGE_OFFER_V1
MESSAGE_FETCH_V1
MESSAGE_DATA_V1
```

Recovery identity is the compact form of the stable full message ID. `MESSAGE_DATA_V1` contains the
same logical message metadata and a newly room-encrypted copy of the persisted `message.body`.

```text
message available
  -> compact(message_id)
  -> offer missing message
  -> receiver stores WANT
  -> fetch
  -> sender finds message row by compact ID
  -> sendMessageData(message)
  -> receiver outer-decrypts
  -> ingestRemoteTransportMessage()
```

GI2 consequence:

- an invitation wire body must be persisted as the stable logical message body;
- private recipient encryption must happen once at message creation, not on every direct send or
  MESSAGE_DATA retry;
- outer room encryption may continue to use a fresh nonce per send attempt;
- non-target devices can retain and recover the opaque private GI1 envelope without inner decryption;
- no new relay protocol is required.

## 7. Current persistence contract

### 7.1 Database baseline

`AppDatabase` is version **53**, with migrations registered through `MIGRATION_52_53`.
Any GI2 schema change starts at migration **53 -> 54** unless another milestone changes the baseline
first.

### 7.2 Room row

`NearbyRoomEntity` owns:

- authoritative `room_id`;
- `created_at_ms` and `expires_at_ms`;
- terminal state: ACTIVE, EXPIRED, CLOSED;
- owner device identity;
- metadata state.

### 7.3 Participant row

`NearbyRoomParticipantEntity` primary key is:

```text
(room_id, device_id)
```

No separate Nearby participant ID exists in the current architecture. Therefore GI2 room-scoped TOFU
may identify its principal by `(roomId, deviceId)` without introducing a new participant abstraction.
The device ID remains identity/routing data only and must never become key material.

Current participant crypto/capability fields: **none**.

### 7.4 Message row

`NearbyRoomMessageEntity` primary key is:

```text
(room_id, message_id)
```

It stores a single `body: String` and has no semantic type, target, materialization state, or structured
invitation card persistence.

This is insufficient by itself for private invitation semantics because:

- the sender needs local plaintext;
- the target needs decrypted plaintext;
- a relay/non-target needs only the opaque outer private envelope;
- malformed/future invitations need non-text suppression;
- timeline materialization must be target-only.

GI2 later needs a dedicated invitation persistence model. GI2A1 does not freeze its final schema, but
it freezes the separation:

```text
logical/wire message persistence != invitation card materialization
```

### 7.5 Cleanup contract

Room cleanup and expiry are owned by `NearbyRoomRepository`:

- `closeRoom()` / `closeRoomFromTransport()` transition terminal state;
- `expireRooms()` marks elapsed rooms expired;
- `purgeRoomLocalData()` deletes room-local data.

Future invitation materialization and TOFU rows must join these cleanup paths. Card history must not be
deleted merely because the room expired if normal room history remains visible.

## 8. Room expiry contract

Room lifetime is currently fixed by `NearbyRoomPolicy.ROOM_TTL_MS = 30 minutes`.

For received messages, repository ingestion computes effective message expiry from the room and wire
envelope, bounded by the room lifetime. GI2 inner AAD must bind the same effective room-expiry value
used by sender and recipient.

The following values must not be independently recomputed in multiple layers:

- invitation card expiry;
- inner AAD expiry;
- message `ttl_expires_at_ms`;
- target Join-enabled state.

A future repository creation operation must resolve one effective expiry and pass that immutable value
through encoding, encryption, persistence, transport, and rendering.

## 9. Nearby Self Info contract

Current self info carries:

- display name;
- avatar ID;
- `profile_updated_at_ms`;
- optional target device ID.

It is encoded as additive canonical JSON in `SELF_INFO_V1`. The codec accepts only known transport
kinds but ignores unknown JSON fields, so adding optional recipient-encryption and capability fields to
SELF_INFO_V1 is structurally possible without a new transport kind.

### 9.1 Current ACK/retry identity

Current retry key:

```text
(roomId, targetDeviceId, profileUpdatedAtMs)
```

Current ACK also confirms `profile_updated_at_ms`.

This cannot safely represent GI2 crypto identity. A key/capability snapshot can change while the profile
timestamp remains unchanged, and an old ACK could stop retry for the new snapshot.

GI2B must introduce a self-info snapshot/revision identity that covers profile + recipient key +
capabilities. The existing coordinator should be extended rather than duplicated, but its key and ACK
binding must change.

## 10. BLE framing and payload baseline

### 10.1 Hard framing limits

`Chunking` uses:

- 10-byte header per chunk;
- maximum 255 chunks;
- default GATT chunk bytes: 20;
- desired MTU: 185;
- negotiated chunk cap: 180 bytes.

Usable payload per chunk:

```text
20-byte default chunk  -> 10 bytes payload
180-byte negotiated    -> 170 bytes payload
```

Maximum reassemblable envelope bytes at those two sizes:

```text
Default/unnegotiated: 255 * 10  = 2,550 bytes
Negotiated cap:       255 * 170 = 43,350 bytes
```

### 10.2 Architecture estimate for invitation envelopes

The following is a deterministic architecture estimate using the current compact transport fields,
representative 36-byte room/device/message IDs, a 20-byte display name, AES-GCM's 16-byte tag, and
unpadded Base64URL. It is not a replacement for device instrumentation.

| Logical body bytes | Estimated final transport envelope | Chunks at 20 bytes | Chunks at 180 bytes |
| ---: | ---: | ---: | ---: |
| 500 (current max text characters approximation) | ~1,009 bytes | ~101 | ~6 |
| 1,536 (GI1 max public wire payload) | ~2,391 bytes | ~240 | ~15 |
| 2,400 (GI1 max private outer payload) | ~3,543 bytes | ~355 — rejected | ~21 |

Important findings:

1. A maximum GI1 public invitation only narrowly fits the hard 255-chunk limit at default chunk size.
2. A maximum GI1 private outer payload cannot be split at default chunk size; `Chunking.split()` will
   throw `IllegalArgumentException("payload too large")`.
3. With successfully negotiated 180-byte chunks, both budgets are technically well below the hard
   chunk-count limit.
4. Runtime queue, timeout, contact-window, and fairness behavior remain unproven even when the payload
   fits.

### 10.3 Locked GI2 runtime requirement

GI2 must not assume successful MTU negotiation.

Before a large invitation is handed to BLE, later phases must provide one of these safe outcomes:

- prove the final encoded envelope fits the current target path's chunk capability; or
- suppress/defer the invitation with an explicit non-crashing result.

No production caller may allow `Chunking.split()` to become the first payload-size guard.

GI2G must collect actual metrics for:

- canonical GI1 body bytes;
- outer room ciphertext Base64URL bytes;
- final transport envelope bytes;
- negotiated chunk bytes;
- chunk count;
- queue and completion latency;
- reconnect/recovery behavior.

## 11. Integration seams frozen by GI2A1

| Later phase | Required seam | Existing owner to extend |
| --- | --- | --- |
| GI2A recipient identity | dedicated P-256 keypair lifecycle | `security` module; do not reuse DTN key |
| GI2B self info | additive fields + snapshot revision | transport envelope/codec/manager/receiver and retry coordinator |
| GI2C capability | durable claimed capability | participant persistence or dedicated capability row |
| GI2C TOFU | durable room-scoped pinned key | new dedicated persistence; not the claimed participant fields alone |
| GI2D crypto | one-time private logical-body encryption | repository creation flow before normal room transport |
| GI2E receive | invitation classifier before text ingest | receiver/repository ingestion boundary |
| GI2E materialization | sender/target-only card row | dedicated invitation persistence |
| GI2F mixed version | pre-send gating | repository/use-case layer before message creation |
| GI2G BLE | final-envelope preflight and metrics | transport/runtime test seam; no new message kind |

## 12. Prohibited architecture changes

GI2 phases must not:

- alter GI1 public/private payload shapes without a separate protocol review;
- create a Group Invitation BLE transport kind;
- create a DM channel or conversation;
- route private invitation only to the target and thereby lose opaque room relay semantics;
- use timeline filtering as confidentiality protection;
- reuse DTN manifest signing identity;
- use actor/device/participant IDs as encryption keys;
- re-encrypt the private inner payload for every retry;
- treat malformed/future GI payloads as normal text;
- add a generic Rich Message Framework;
- silently replace a room-scoped TOFU key.

## 13. Confirmed blockers for subsequent phases

### Critical

1. **Self-info ACK identity is profile-only.** GI2B must bind the complete self-info snapshot.
2. **Message repository is text-only.** GI2E must separate wire record and card materialization.
3. **Private max payload exceeds default BLE framing capacity.** GI2 must preflight/suppress safely.
4. **Invitation classifier is absent.** Raw or malformed GI JSON currently reaches the text path.

### High

5. Sender/origin identity must remain logical origin across MESSAGE_DATA relay.
6. Inner encrypted body must be stable across retries and replay.
7. Effective room expiry must be resolved once and reused in inner AAD and persistence.
8. Capability unknown must not be interpreted as supported.
9. TOFU claimed key and pinned key must not share a last-write-wins field.

## 14. Verification performed

Static source review completed for:

- GI1 protocol review, product specification, and roadmap;
- Nearby Room transport envelope and codec;
- transport manager and receiver;
- repository, room/message/participant entities and DAOs;
- self-info retry coordinator;
- message offer/fetch/data relay recovery;
- room message crypto;
- BLE chunking/configuration and MTU selection;
- AppDatabase version and migration registration.

Focused Gradle tests were requested but could not run in this isolated environment because the Gradle
8.7 distribution was not cached and network access to `services.gradle.org` is unavailable. No test
pass claim is made by GI2A1.

## 15. GI2A1 closure criteria

GI2A1 is closed when review agrees on all of the following:

- message ID is allocated before recipient encryption;
- Group Invitation stays inside KIND_MESSAGE_V2;
- logical origin, not last-hop sender, binds sender context;
- invitation wire body is stable across retry/replay;
- classification occurs before normal-text ingestion;
- wire persistence and card materialization are separate concerns;
- Nearby participant identity is `(roomId, deviceId)` for the current architecture;
- self-info requires a complete snapshot revision;
- database baseline is 53;
- maximum private payload requires MTU-aware preflight/suppression;
- no production implementation begins until this contract is accepted.

## 16. Recommended next phase

After GI2A1 review and closure:

```text
GI2A2 — Recipient Encryption Identity Contract
```

GI2A2 should freeze algorithm constants, Android Keystore alias/lifecycle, SEC1 public-key encoding,
deterministic key-ID derivation, invalidation behavior, and rotation policy before implementing the
keystore adapter.
