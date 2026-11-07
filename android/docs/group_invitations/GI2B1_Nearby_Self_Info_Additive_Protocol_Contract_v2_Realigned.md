# GI2B1 — Nearby Self Info Additive Protocol Contract

Status: **ACCEPTED SUBJECT TO IMPLEMENTATION VERIFICATION**  
Milestone: **GI2B1 — Nearby Self Info Additive Protocol**  
Baseline database version: **53**  
Architecture alignment: **GI1R / Product Spec v2**  
Production implementation: **PATCH PREPARED — NOT YET VERIFIED IN REPOSITORY**

---

## 1. Purpose

GI2B1 extends the existing Nearby Self Info wire protocol so a participant may advertise the public
part of its dedicated recipient-encryption identity.

The extension prepares later phases for:

- room-scoped TOFU;
- recipient key lookup;
- recipient-confidential Group Invitation encryption.

GI2B1 is an additive protocol phase. It defines wire fields, validation, capability semantics,
snapshot identity, ACK binding, mixed-version behavior, and payload constraints.

GI2B1 does **not** implement runtime Keystore publication, participant persistence, TOFU, ECDH, HKDF,
AES-GCM, invitation sending, invitation receiving, Group Directory, or profile exchange redesign.

---

## 2. Locked upstream contracts and authority

GI2B1 is governed by the following documents:

- `Group_Invitation_Spec_v2.md`;
- `GI1R_Group_Invitation_Architecture_Realignment.md`;
- `Group_Invitation_Implementation_Roadmap_v2.md`;
- `GI1_Protocol_Review.md`, except for product/domain assumptions superseded by GI1R;
- `GI2A1_Current_Architecture_Contract.md`, except for persistence/materialization assumptions superseded by GI1R;
- `GI2A2_Recipient_Encryption_Identity_Contract.md`;
- `GI2A3_Android_Keystore_Recipient_Identity_Implementation.md`.

Authority order:

```text
Product Spec v2
→ GI1R Architecture Realignment
→ Implementation Roadmap v2
→ this GI2B1 contract
→ historical GI1/GI2 documents
```

GI2B1 does not inherit `Group_Invitation_Roadmap_v1.0.md` as an active implementation authority.

The recipient-encryption identity published by this protocol is the public snapshot defined by GI2A:

```text
algorithm
key ID
P-256 SEC1 uncompressed public key
```

The only supported v1 algorithm identifier is:

```text
p256-hkdf-sha256-a256gcm
```

The public key is exactly:

```text
65-byte SEC1 uncompressed P-256 point
first byte = 0x04
Base64URL without padding = 87 characters
```

The key ID is the deterministic GI2A key ID:

```text
SHA-256(
  "peermesh/recipient-encryption-key-id/v1" || 0x00 ||
  algorithm || 0x00 ||
  publicKeySec1
)
```

encoded as unpadded Base64URL.

---

## 3. Scope

### 3.1 In scope

GI2B1 defines:

- additive fields on `NEARBY_ROOM_SELF_INFO_V1`;
- an additive revision field on `NEARBY_ROOM_SELF_INFO_ACK_V1`;
- a transport-only public identity model;
- a recipient-encryption capability flag;
- strict semantic validation of the optional extension;
- deterministic complete Self Info snapshot revision;
- new-client ACK binding to that revision;
- legacy ACK compatibility behavior;
- relay/deduplication identity requirements;
- mixed old/new client behavior;
- payload and BLE-framing limits;
- required unit and compatibility tests.

### 3.2 Out of scope

GI2B1 must not:

- call `RecipientEncryptionIdentityProvider` from production runtime;
- generate or load a Keystore key;
- persist a claimed recipient key;
- pin a room-scoped TOFU key;
- decide whether a private invitation may be sent;
- perform ECDH, HKDF, AES-GCM, encryption, or decryption;
- add a Group Invitation transport kind;
- add a generic capability registry;
- add a generic extensible Self Info framework;
- change participant database schema;
- change existing profile merge semantics;
- alter GI1 invitation payload shapes.

Runtime identity acquisition and publication belong to GI2B2.

Any durable room-scoped recipient identity or conflict protection belongs to GI2C, but GI1R explicitly
does not require a dedicated trust subsystem or invitation-specific table. GI2C must review the minimal
schema against the existing participant lifecycle before choosing persistence ownership.

---

## 4. Current Self Info baseline

The current Self Info flow uses:

```text
NEARBY_ROOM_SELF_INFO_V1       compact kind: SI1
NEARBY_ROOM_SELF_INFO_ACK_V1   compact kind: SA1
```

The current canonical compact fields are:

```text
k   kind
r   room id
sd  last-hop sender device id
og  logical origin/profile owner device id
sn  sender display name
av  avatar id
pu  profile updated-at timestamp
ct  created-at timestamp
ex  expires-at timestamp
td  target device id
```

`NearbyRoomTransportCodec` decodes JSON by field name, ignores unknown JSON members, accepts compact
and legacy long field aliases, and does not depend on member order.

Current targeted Self Info retry and ACK identity is:

```text
(roomId, targetDeviceId, profileUpdatedAtMs)
```

Current Self Info and Self Info ACK relay notice IDs also end in `profileUpdatedAtMs`.

That profile-only identity is insufficient once recipient identity and capability are part of Self
Info, because those values may change without a profile edit.

---

## 5. Compatibility strategy

GI2B1 extends the existing kinds. It does not create `SELF_INFO_V2`.

```text
Self Info:      NEARBY_ROOM_SELF_INFO_V1 / SI1
Self Info ACK:  NEARBY_ROOM_SELF_INFO_ACK_V1 / SA1
```

This is safe because the current codec:

- parses an object by key;
- ignores unknown fields;
- does not enforce an exact member set;
- does not enforce member ordering;
- accepts missing optional fields.

### 5.1 Required compatibility properties

A compliant GI2B implementation must preserve all of the following:

1. Existing required fields remain required and retain their meaning.
2. Existing compact and legacy aliases remain unchanged.
3. Existing fields are not reordered as a semantic requirement.
4. Old Self Info remains valid for a new client.
5. New Self Info remains valid for an old client.
6. Missing recipient-encryption fields do not invalidate Self Info.
7. Partial or malformed recipient-encryption fields do not invalidate profile information.
8. Unknown capability state is never interpreted as supported.
9. An old client must not log a parser error solely because new members are present.
10. An old client must not invalidate or remove a participant solely because new members are present.

### 5.2 No hard dependency

A new client receiving Self Info without the extension must continue normal profile behavior and treat
recipient-encryption support as:

```text
Unknown / not proven
```

It must not treat absence as a protocol error.

---

## 6. Additive wire fields

The following optional fields are added to `NearbyRoomTransportEnvelope`.

| Semantic field | Kotlin envelope field | Compact key | Legacy long alias | Kind scope |
| --- | --- | --- | --- | --- |
| Recipient-encryption capability | `recipient_encryption_supported` | `res` | `recipient_encryption_supported` | Self Info only |
| Algorithm identifier | `recipient_encryption_algorithm` | `rea` | `recipient_encryption_algorithm` | Self Info only |
| Recipient key ID | `recipient_encryption_key_id` | `rek` | `recipient_encryption_key_id` | Self Info only |
| Public key | `recipient_encryption_public_key` | `rep` | `recipient_encryption_public_key` | Self Info only |
| Complete snapshot revision | `self_info_revision` | `sir` | `self_info_revision` | Self Info and Self Info ACK |

The compact keys are locked for GI2B1.

They do not collide with the existing compact key set. In particular, `rp` remains reserved for
`reply_to` and must not be reused.

### 6.1 Kind-scoped encoding

The new fields must not become generic cross-kind payload fields in practice.

Encoder behavior is locked as follows:

- `SELF_INFO_V1` may encode all five new fields;
- `SELF_INFO_ACK_V1` may encode only `self_info_revision`;
- every other transport kind must omit all five fields even if a caller accidentally populates them.

This prevents accidental key advertisement on unrelated transport envelopes and keeps the additive
surface narrowly owned by Self Info.

### 6.2 Boolean capability encoding

The capability is represented by the dedicated optional JSON boolean:

```json
"res": true
```

A compliant sender:

- emits `true` only when it publishes a complete valid recipient identity;
- omits the field when recipient encryption is unavailable;
- does not emit `false`;
- does not use this flag to claim Group Invitation support.

A decoder treats absent, non-boolean, or `false` as recipient-encryption capability not established.

A dedicated boolean is chosen instead of a generic capability list because this field has one narrow
semantic owner: recipient-encryption identity availability.

GI1R establishes that `structured Group Invitation v1 supported` is a separate capability claim.
That future claim must not reuse or reinterpret `recipient_encryption_supported`.

The existence of a second future capability does not require GI2B1 to introduce a generic capability
registry. A later reviewed contract may add another narrowly scoped additive field if needed.

---

## 7. Valid Self Info extension shape

A recipient-encryption advertisement is valid only when the Self Info envelope contains this complete
shape:

```json
{
  "res": true,
  "rea": "p256-hkdf-sha256-a256gcm",
  "rek": "<43-character unpadded Base64URL key ID>",
  "rep": "<87-character unpadded Base64URL SEC1 public key>",
  "sir": "<43-character unpadded Base64URL snapshot revision>"
}
```

The surrounding legacy Self Info fields remain unchanged.

### 7.1 All-or-nothing identity rule

Recipient encryption is advertised only when all of these statements are true:

```text
recipient_encryption_supported == true
algorithm is recognized
key ID is valid
public key is valid
recomputed key ID equals advertised key ID
self-info revision is valid
```

No subset establishes capability.

### 7.2 Capability meaning

`recipient_encryption_supported = true` means only:

```text
This participant claims a currently usable recipient-encryption public identity
for the advertised algorithm and key ID.
```

It does not mean:

- Group Invitation is supported;
- private invitation UI is available;
- the key is trusted;
- the key is TOFU-pinned;
- the sender may immediately encrypt to it;
- the peer supports every GI2 phase;
- a profile exchange or Group Directory exists.

### 7.3 Unavailable shape

When recipient identity is unavailable, a new sender emits:

```text
recipient_encryption_supported: omitted
recipient_encryption_algorithm: omitted
recipient_encryption_key_id: omitted
recipient_encryption_public_key: omitted
self_info_revision: present
```

The revision remains present because it identifies the complete Self Info snapshot, including the
absence of recipient-encryption capability.

---

## 8. Public identity transport model

GI2B1 should introduce one narrow transport model equivalent to:

```kotlin
data class NearbyRecipientEncryptionPublicIdentity(
    val algorithm: PrivateInvitationEncryptionAlgorithm,
    val keyId: String,
    val publicKeySec1: ByteArray,
)
```

The implementation must defensively copy the byte array or expose it only through a defensive copy.

The model must contain only public protocol material.

It must never contain or expose:

- private key material;
- Android Keystore alias;
- recovery marker;
- reset state;
- reset authorization;
- invalidation reason;
- temporary failure reason;
- diagnostics;
- ECDH probe result;
- provider instance;
- Android `PrivateKey` or `KeyStore` object.

The transport model is not a trust model and is not a persistence model.

---

## 9. Recipient identity validation

Semantic validation belongs in a Self Info protocol helper, not in the generic JSON parser and not in
the Android Keystore provider.

### 9.1 Algorithm

The algorithm must exactly match a known `PrivateInvitationEncryptionAlgorithm.wireValue`.

GI2B1 recognizes only:

```text
p256-hkdf-sha256-a256gcm
```

Unknown algorithms do not invalidate the profile envelope. They suppress the recipient-encryption
advertisement for current behavior.

### 9.2 Public key

The advertised public key must:

- be non-empty;
- contain only unpadded Base64URL characters `[A-Za-z0-9_-]`;
- contain no `=` padding;
- be exactly 87 characters;
- decode successfully;
- decode to exactly 65 bytes;
- start with SEC1 uncompressed point prefix `0x04`;
- pass the existing P-256 SEC1 validation contract.

The implementation should reuse the existing strict P-256 codec where module dependency direction
permits. It must not create a weaker duplicate validator.

### 9.3 Key ID

The key ID must:

- be non-empty;
- contain only unpadded Base64URL characters;
- contain no padding;
- be exactly 43 characters;
- decode to exactly 32 bytes;
- equal `RecipientEncryptionKeyId.derive(algorithm, publicKeySec1)`.

A mismatch suppresses the identity extension.

### 9.4 Failure behavior

Any malformed, incomplete, inconsistent, or unsupported recipient identity produces:

```text
profile Self Info: valid and processable
recipient public identity: absent
recipient-encryption capability: not established
```

It must not produce:

- generic transport decode failure;
- participant invalidation;
- participant deletion;
- profile rejection;
- parser error log at error severity;
- fallback trust behavior;
- automatic key replacement;
- partially usable identity.

A bounded diagnostic may record a reason code without logging the public key or secret-adjacent
material.

---

## 10. Complete Self Info snapshot revision

GI2A1 requires Self Info retry and ACK identity to cover the complete snapshot rather than only
`profile_updated_at_ms`.

GI2B1 therefore defines `self_info_revision`.

### 10.1 Purpose

The revision identifies the logical Self Info content being delivered and acknowledged.

It prevents an ACK for an older profile-only snapshot from silently confirming a newer snapshot whose
recipient key or capability changed without a profile edit.

The revision is:

- deterministic;
- content-derived;
- public;
- independent of send attempt time;
- independent of relay hop;
- independent of JSON member ordering;
- stable across retry of identical content;
- different when any bound field changes.

It is not:

- a trust fingerprint;
- a TOFU pin;
- a replacement for recipient key ID;
- a secret;
- a random nonce;
- a wall-clock timestamp;
- a transport packet ID.

### 10.2 Revision domain

The exact domain-separation label is:

```text
peermesh/nearby-self-info/revision/v1
```

### 10.3 Canonical revision input

The revision input is canonical JSON using the existing `CanonicalJson` implementation.

The exact semantic object is:

```json
{
  "av": "<avatar id or empty string>",
  "od": "<logical profile owner device id>",
  "pu": 0,
  "rea": "<algorithm or empty string>",
  "rek": "<key id or empty string>",
  "rep": "<public key or empty string>",
  "res": false,
  "sn": "<display name or empty string>",
  "v": 1
}
```

Canonical keys and meanings are locked:

| Key | Meaning |
| --- | --- |
| `v` | Revision schema version, JSON integer `1` |
| `od` | Logical profile owner device ID |
| `sn` | Sender display name, or empty string when absent |
| `av` | Avatar ID, or empty string when absent |
| `pu` | Profile updated-at value, or JSON integer `0` when absent |
| `res` | Effective recipient-encryption capability boolean |
| `rea` | Validated algorithm wire value, or empty string when unavailable |
| `rek` | Validated recipient key ID, or empty string when unavailable |
| `rep` | Validated unpadded Base64URL public key, or empty string when unavailable |

The logical profile owner device ID is:

```text
origin_device_id when present and non-blank
otherwise sender_device_id
```

For a locally constructed Self Info snapshot, it is the local stable device ID.

### 10.4 Effective identity before hashing

The sender must first apply the all-or-nothing validation rule.

If recipient identity is valid and capability is advertised:

```text
res = true
rea = validated algorithm
rek = validated key ID
rep = validated public key
```

Otherwise:

```text
res = false
rea = ""
rek = ""
rep = ""
```

This ensures partial invalid fields do not create multiple semantically equivalent revisions.

### 10.5 Revision derivation

The exact derivation is:

```text
canonical = CanonicalJson.canonicalize(revisionObject).UTF8

digest = SHA-256(
  UTF8("peermesh/nearby-self-info/revision/v1") ||
  0x00 ||
  canonical
)

self_info_revision = Base64URL_without_padding(digest)
```

The resulting revision is exactly 43 characters and decodes to 32 bytes.

### 10.6 Revision validation

An incoming revision is structurally valid only when it:

- is exactly 43 characters;
- uses only unpadded Base64URL characters;
- decodes to exactly 32 bytes.

For a valid new Self Info extension, the receiver must recompute the revision from the effective
received snapshot and require exact equality.

If the revision is absent or invalid, the legacy profile remains processable, but the packet is not a
fully validated GI2B snapshot.

### 10.7 No send-time fields

The revision must not bind:

- `created_at_ms`;
- `expires_at_ms`;
- last-hop `sender_device_id` when `origin_device_id` is available;
- `target_device_id`;
- room ID;
- relay address;
- BLE chunk ID;
- retry attempt number.

Those values describe transport delivery rather than the stable Self Info content. Room and target
remain part of retry/relay keys outside the revision.

---

## 11. Self Info ACK contract

A new receiver acknowledging a structurally and semantically valid new Self Info snapshot echoes:

```text
profile_updated_at_ms
self_info_revision
```

in `SELF_INFO_ACK_V1`.

The ACK keeps all current routing semantics:

- `sender_device_id` identifies the ACK sender/target participant;
- `origin_device_id` identifies the profile owner whose snapshot is acknowledged;
- `target_device_id` routes the ACK back to the profile owner;
- room ID remains unchanged.

### 11.1 New ACK matching

A new-protocol ACK matches a queued snapshot only when all of these match:

```text
roomId
targetDeviceId
selfInfoRevision
```

`profileUpdatedAtMs` remains on wire for compatibility and diagnostics but is no longer the authority
for a new-protocol ACK.

### 11.2 Legacy ACK matching

An old client ignores `self_info_revision` in Self Info and replies with an ACK containing only
`profile_updated_at_ms`.

A new sender may accept that ACK as a **legacy transport-delivery acknowledgement** when:

```text
ACK revision is absent
AND profileUpdatedAtMs matches the queued snapshot profile timestamp
```

This may stop repeated Self Info transport to the old peer.

It must not establish that the old peer:

- parsed recipient identity;
- supports recipient encryption;
- supports Group Invitation;
- has persisted the key;
- can receive a private invitation.

A legacy ACK proves only legacy Self Info delivery behavior.

### 11.3 Invalid revision ACK

An ACK carrying a malformed or non-matching revision must not acknowledge the new snapshot.

It must not silently fall back to profile-only matching merely because `profile_updated_at_ms` matches.
Fallback is allowed only when the revision field is genuinely absent.

This prevents a malformed or stale new ACK from weakening exact matching.

---

## 12. Retry and relay identity contract

GI2B1 locks the protocol identity required by GI2B2 runtime implementation.

### 12.1 Targeted retry key

New targeted Self Info retry authority is:

```text
(roomId, targetDeviceId, selfInfoRevision)
```

The existing coordinator must be extended. GI2B must not create a parallel recipient-identity retry
coordinator.

### 12.2 Snapshot change during retry

If runtime rebuilds Self Info and the resulting revision differs from the queued revision:

- the old retry must not transmit the new content under the old key;
- the old retry must stop or be superseded;
- a new retry may be queued under the new revision.

A send attempt must never claim revision A while carrying snapshot B.

### 12.3 Self Info relay notice ID

For Self Info carrying a valid revision, relay deduplication identity must include:

```text
roomId
logical origin device ID
target device ID or broadcast
selfInfoRevision
```

For legacy Self Info without a valid revision, the existing profile timestamp fallback remains:

```text
roomId
logical origin device ID
target device ID or broadcast
profileUpdatedAtMs
```

### 12.4 Self Info ACK relay notice ID

For ACK carrying a valid revision, relay deduplication identity must include:

```text
roomId
logical profile owner device ID
target device ID
selfInfoRevision
```

For legacy ACK without revision, the current profile timestamp fallback remains.

### 12.5 Relay preservation

A new relay must preserve all GI2B Self Info fields while changing only existing last-hop routing
fields according to current relay behavior.

An old relay decodes into its old envelope model and re-encodes, so it strips unknown GI2B fields. This
is an accepted mixed-version limitation, not a parser failure.

The result through an old relay is:

```text
profile information may still arrive
recipient-encryption capability remains unknown
private invitation must not be enabled from that packet
public invitation remains independently possible
```

GI2B1 does not attempt to repair old relay behavior.

---

## 13. Availability contract for GI2B2

GI2B1 defines the wire outcome that GI2B2 must implement.

| Local identity status | Capability flag | Algorithm/key ID/public key | Revision |
| --- | --- | --- | --- |
| `Available` | `true` | all present and valid | present |
| `UnsupportedPlatform` | omitted | all omitted | present |
| `TemporarilyUnavailable` | omitted | all omitted | present |
| `Invalidated` | omitted | all omitted | present |

The wire packet must not disclose the unavailable reason.

The following are forbidden on wire:

- status enum name;
- reason enum name;
- Keystore alias;
- recovery marker;
- reset state;
- probe result;
- exception text;
- diagnostics;
- private key or derived secret.

An availability transition changes the effective snapshot and therefore changes
`self_info_revision`, even when profile fields remain unchanged.

---

## 14. Mixed-version behavior matrix

| Sender | Receiver | Required result |
| --- | --- | --- |
| Old | Old | Existing behavior unchanged |
| New | Old | Old receiver ignores new fields, processes profile, returns legacy ACK |
| Old | New | New receiver processes profile; recipient-encryption capability remains unknown |
| New | New | Full extension validated; exact revision ACK returned |

### 14.1 New sender to old receiver

Required behavior:

- no crash;
- no Self Info rejection;
- no participant invalidation;
- no parser error caused by unknown members;
- old receiver may drop the new fields on relay;
- new sender may accept absent-revision ACK only as legacy transport ACK;
- no private-invitation capability may be inferred from that ACK.

### 14.2 Old sender to new receiver

Required behavior:

- profile is processed normally;
- missing capability is not an error;
- missing identity is not an error;
- missing revision is treated as legacy Self Info;
- recipient-encryption support remains unknown/not proven.

### 14.3 New sender through old relay to new receiver

Required degraded behavior:

- profile may be processed;
- extension may be absent because the relay stripped unknown fields;
- target must not infer capability;
- no TOFU decision is made;
- no parser failure occurs.

### 14.4 Forward compatibility

A current GI2B1 client must ignore unknown future additive Self Info fields while continuing to validate
the known v1 recipient identity shape.

An unknown future recipient algorithm must not make the entire profile invalid.

---

## 15. Payload and BLE budget

### 15.1 Hard framing baseline

Current BLE chunking uses:

```text
10-byte chunk header
maximum 255 chunks
default GATT chunk bytes = 20
negotiated cap = 180
```

Usable payload per chunk:

```text
default:     10 bytes
negotiated: 170 bytes
```

Maximum reassemblable payload:

```text
default:     2,550 bytes
negotiated: 43,350 bytes
```

### 15.2 Fixed GI2B extension sizes

| Value | Encoded size |
| --- | ---: |
| Capability boolean | 4 bytes for `true`, plus JSON key structure |
| Algorithm | 25 UTF-8 bytes |
| Key ID | 43 UTF-8 bytes |
| Public key | 87 UTF-8 bytes |
| Snapshot revision | 43 UTF-8 bytes |

With compact keys and JSON structure, the recipient-identity extension adds approximately 240–280
bytes to a full Self Info envelope. Exact size depends on existing room/device/profile values and JSON
escaping.

### 15.3 Locked protocol budget

A compliant GI2B1 encoder must enforce:

```text
MAX_SELF_INFO_WIRE_BYTES = 1,024
MAX_SELF_INFO_ACK_WIRE_BYTES = 512
```

These limits apply to final canonical UTF-8 bytes, not characters and not pre-escaped field values.

Rationale:

- 1,024 bytes remains far below the 2,550-byte default hard reassembly limit;
- it bounds malformed or unexpectedly large profile fields;
- it prevents GI2B Self Info from approaching invitation payload risk territory;
- at default chunking it is at most 103 chunks;
- at 180-byte chunks it is at most 7 chunks;
- ACK remains materially smaller than Self Info.

If the canonical payload exceeds the limit, encode must return an explicit failure or otherwise prevent
the payload from reaching `Chunking.split()`.

It must not truncate:

- public key;
- key ID;
- algorithm;
- revision;
- display name;
- avatar ID.

### 15.4 Field-specific bounds

GI2B1 additionally locks:

```text
algorithm: exact known wire value
key ID: exactly 43 ASCII characters
public key: exactly 87 ASCII characters
revision: exactly 43 ASCII characters
```

Existing profile validation remains authoritative for local display name and avatar selection. Decoder
must still apply the final wire budget defensively because remote senders are not trusted to have used
local UI validation.

### 15.5 No MTU assumption

GI2B1 does not require successful MTU negotiation. Its 1,024-byte Self Info budget must remain
splittable under the default 20-byte chunk size.

This does not prove delivery quality. GI2B2 and GI2G must still test queue pressure, contact-window
behavior, relay amplification, and retry behavior on devices.

---

## 16. Serialization contract

### 16.1 Canonical encoding

Encoder continues to use `CanonicalJson.canonicalize()` and UTF-8.

No consumer may rely on source declaration order or insertion order for decoding.

### 16.2 Compact and legacy aliases

Encoder emits compact keys only.

Decoder accepts:

- compact key first;
- legacy long alias as fallback.

If both compact and legacy forms are present, the compact field is authoritative, matching current
codec convention.

### 16.3 Unknown members

Unknown JSON members remain ignored.

### 16.4 Wrong JSON types

Wrong types in optional GI2B fields do not reject the base Self Info envelope.

They make the affected extension unavailable.

Examples:

- `res` as string instead of boolean: capability not established;
- `rea` as number: identity unavailable;
- `rek` as object: identity unavailable;
- `rep` as array: identity unavailable;
- `sir` as number: revision unavailable.

### 16.5 Duplicate JSON keys

GI2B1 does not introduce a custom duplicate-key parser. Behavior remains governed by the existing JSON
implementation.

Protocol tests must not construct valid vectors with duplicate keys. Security-sensitive behavior must
come from the decoded effective value and strict recomputation, not from textual key occurrence count.

---

## 17. Logging and redaction

Although the public key is public protocol material, production logs must not print the full value.

Allowed bounded identifiers:

```text
key ID prefix, maximum 8 characters
revision prefix, maximum 8 characters
algorithm identifier
capability present/absent
validation reason code
encoded byte count
```

Forbidden logging:

- full public key;
- full key ID in routine logs;
- private key;
- Keystore alias;
- provider exception stack containing key metadata unless already covered by secure diagnostic policy;
- full raw Self Info envelope when it contains recipient identity.

`toString()` for the transport public identity must redact the public key.

---

## 18. Persistence boundary

GI2B1 performs no database migration.

The new fields are ephemeral wire data in this phase.

GI2B1 does not add recipient key fields to `NearbyRoomParticipantEntity` and does not add a trust
record.

GI2C must preserve the following semantic distinction:

```text
identity claimed by current Self Info
room-scoped first-key/conflict decision
```

This distinction must be enforced by write policy and must never degrade into silent last-write-wins
replacement.

GI1R does not mandate separate tables or a generic trust subsystem. GI2C may use the minimal safe
participant-scoped schema if the architecture review proves that it preserves first-key pinning,
conflict detection, room lifecycle, and restart behavior.

Any later persistence design does not alter this wire contract.

---

## 19. Required implementation ownership

GI2B1 production implementation should remain narrow.

Expected owners:

| Concern | Existing owner to extend |
| --- | --- |
| Optional envelope fields | `NearbyRoomTransportEnvelope` |
| Compact/legacy JSON fields | `NearbyRoomTransportCodec` |
| Strict extension semantics | new narrow Self Info protocol helper near Nearby transport |
| Algorithm enum | existing `PrivateInvitationEncryptionAlgorithm` |
| Base64URL | existing `Base64Url` |
| SEC1 validation | existing strict P-256 codec, subject to dependency direction |
| Key ID recomputation | existing `RecipientEncryptionKeyId`, subject to dependency direction |
| Canonical revision | existing `CanonicalJson` + SHA-256 |
| Retry/ACK runtime use | deferred to GI2B2 |
| Minimal room-scoped identity/conflict persistence | deferred to GI2C architecture review |

No new interface is required for GI2B1 unless a concrete module dependency prevents reuse of the
existing strict security primitives. Even then, the preferred solution is to place pure public-key
validation/derivation at the lowest valid shared module rather than duplicate logic.

---

## 20. Required tests

### 20.1 Existing codec regression

All existing `NearbyRoomTransportCodecTest` behavior must remain green.

No existing kind may gain new encoded fields unless explicitly populated and kind-allowed.

### 20.2 Additive codec tests

Required tests:

1. Old compact Self Info decodes unchanged.
2. Old legacy-long Self Info decodes unchanged.
3. Full new compact Self Info round-trips.
4. Full new legacy aliases decode.
5. Compact field wins over legacy alias when both exist.
6. Old decoder compatibility fixture ignores all new members.
7. New decoder ignores unrelated unknown future members.
8. Other transport kinds omit GI2B fields even if populated.
9. Self Info ACK encodes revision but omits identity fields.
10. Canonical byte output is deterministic.

### 20.3 Identity validation tests

Required tests:

1. Valid algorithm/key ID/public key is accepted.
2. Capability absent suppresses otherwise valid identity fields.
3. Capability `false` suppresses identity.
4. Capability wrong type suppresses identity.
5. Missing algorithm suppresses identity.
6. Missing key ID suppresses identity.
7. Missing public key suppresses identity.
8. Unknown algorithm suppresses identity.
9. Padded Base64URL is rejected.
10. Invalid Base64URL character is rejected.
11. Public key encoded length other than 87 is rejected.
12. Public key decoded length other than 65 is rejected.
13. SEC1 prefix other than `0x04` is rejected.
14. Invalid P-256 point is rejected.
15. Key ID encoded length other than 43 is rejected.
16. Key ID that does not match the public key is rejected.
17. Partial/malformed extension leaves profile fields usable.
18. No private or Keystore metadata field is encoded.

### 20.4 Revision vector tests

Required fixed vectors:

1. Same semantic snapshot always yields the same revision.
2. Different JSON construction order yields the same revision.
3. Display-name change changes revision.
4. Avatar change changes revision.
5. Profile timestamp change changes revision.
6. Logical owner device ID change changes revision.
7. Recipient key change changes revision.
8. Key ID change changes revision.
9. Algorithm change changes revision.
10. Available to unavailable changes revision.
11. Temporarily unavailable and unsupported produce the same effective no-capability revision when all
    public profile fields are equal; private local status reason is not bound or leaked.
12. Transport created-at change does not change revision.
13. Transport expires-at change does not change revision.
14. Target device change does not change revision.
15. Relay last-hop sender change does not change revision when logical origin is preserved.
16. Revision is 43-character unpadded Base64URL and decodes to 32 bytes.
17. Incoming valid snapshot with mismatched revision is not accepted as a full GI2B snapshot.

### 20.5 ACK compatibility tests

Protocol/helper tests must prove:

1. Exact revision ACK matches a new queued snapshot.
2. Different revision does not match.
3. Malformed present revision does not fall back to profile-only match.
4. Absent revision may use legacy profile timestamp match.
5. Legacy match does not produce recipient-capability evidence.
6. ACK identity fields are never encoded.

Full coordinator/runtime tests belong to GI2B2.

### 20.6 Payload tests

Required tests:

1. Representative full Self Info remains within 1,024 bytes.
2. Final canonical byte count, not character count, enforces the budget.
3. JSON escaping is included in byte-budget enforcement.
4. Over-budget Self Info is rejected before BLE chunking.
5. Maximum accepted Self Info splits under default 20-byte chunks without exceeding 255 chunks.
6. Maximum accepted ACK remains within 512 bytes.
7. No field is truncated to satisfy the budget.

---

## 21. Security invariants

The following are non-negotiable:

1. Device, actor, participant, or room identifiers are never encryption keys.
2. Only public recipient identity material is advertised.
3. Capability unknown is never interpreted as supported.
4. Capability is all-or-nothing with a strictly validated identity.
5. Advertised key ID must be recomputed from algorithm and public key.
6. Self Info revision is not a trust decision.
7. Receiving a key does not pin or trust it in GI2B1.
8. A malformed extension cannot poison or invalidate the legacy participant profile.
9. A legacy ACK cannot prove recipient-encryption support.
10. An old relay stripping the extension must degrade to unknown capability, never false support.
11. Private/local identity status reasons are not transmitted.
12. No secret material enters the transport envelope or logs.
13. Existing GI1 private invitation algorithm and key format remain unchanged.

---

## 22. Prohibited implementation shortcuts

GI2B1 and GI2B2 must not:

- infer recipient capability merely from a public-key field;
- infer recipient capability from a legacy ACK;
- accept a key ID without recomputation;
- accept padded or non-canonical Base64URL;
- reuse `profile_updated_at_ms` as complete snapshot authority;
- use `created_at_ms` as snapshot revision;
- generate a random revision per retry;
- include target or last-hop transport state in revision;
- persist the claimed key into a future TOFU pin field;
- silently replace a pinned key;
- reject the entire Self Info packet because the optional extension is bad;
- create `SELF_INFO_V2` for these additive fields;
- create a generic capability framework;
- put identity JSON inside `ciphertext` or another nested opaque field;
- log full key material;
- allow `Chunking.split()` to be the first size guard.

---

## 23. GI2B1 implementation phases

After this contract is accepted, GI2B1 implementation should proceed in small reviewable phases.

### GI2B1-P1 — Pure protocol model and revision

Deliver:

- transport-only public identity model;
- strict identity validator;
- deterministic revision implementation;
- fixed revision vectors;
- no envelope or runtime changes yet.

### GI2B1-P2 — Envelope and codec extension

Deliver:

- five optional envelope fields;
- compact and legacy aliases;
- kind-scoped encoding;
- optional decoding;
- final canonical byte budgets;
- codec compatibility tests.

### GI2B1-P3 — Protocol compatibility review

Verify:

- old/new decode matrix;
- malformed optional fields preserve profile behavior;
- old relay stripping limitation is documented;
- no private material can be serialized;
- ACK extension is revision-only;
- no runtime provider integration entered GI2B1.

### GI2B1 closure

GI2B1 may close only when:

```text
wire fields are locked
identity validation is strict
revision vectors are locked
old/new parser compatibility passes
wire budgets pass
no runtime Keystore publication exists
no persistence exists
no TOFU exists
```

---

## 24. GI2B2 handoff contract

GI2B2 must implement the runtime behavior already defined here:

- call the recipient identity provider at a bounded Self Info snapshot-construction point;
- publish identity only for `RecipientEncryptionIdentityStatus.Available`;
- suppress all capability/identity fields for every other status;
- always produce a complete snapshot revision;
- bind targeted retry to `(roomId, targetDeviceId, selfInfoRevision)`;
- echo and match exact revision in new ACKs;
- retain absent-revision legacy ACK fallback without capability inference;
- update relay notice IDs to prefer revision;
- preserve old client compatibility;
- avoid a Keystore call inside codec, relay, parser, or tight retry loops;
- add runtime and integration tests.

GI2B2 must not reopen the wire decisions in this document without a protocol review.

---

## 25. Architecture review

### 25.1 Backward compatibility review

**PASS**

Reasoning:

- the existing decoder ignores unknown members;
- no existing required field changes;
- no kind changes;
- old clients continue to process profile fields;
- legacy ACK fallback prevents endless retry solely because a peer is old;
- recipient capability remains unknown rather than falsely inferred.

Accepted limitation:

- old relays re-encode through an envelope type that does not know GI2B fields and therefore strip the
  extension.

This limitation is safe because private capability remains unavailable rather than falsely available.

### 25.2 Forward compatibility review

**PASS**

Reasoning:

- unknown future fields remain ignored;
- unknown algorithms suppress only the optional identity extension;
- no exact field-set validation is introduced;
- dedicated recipient-encryption boolean cannot be interpreted as structured Group Invitation support;
- future Group Invitation compatibility remains a separate additive capability decision.

### 25.3 Security review

**PASS**

Reasoning:

- only public material is serialized;
- key ID is recomputed;
- strict SEC1 and Base64URL requirements are inherited;
- capability is all-or-nothing;
- revision is domain-separated and deterministic;
- revision is explicitly not trust;
- no TOFU or private-key behavior enters GI2B1;
- malformed extension cannot poison legacy profile ingestion.

### 25.4 ACK/retry correctness review

**PASS — REQUIRED CHANGE IDENTIFIED**

The current profile-only ACK identity is insufficient. The complete deterministic Self Info revision
is mandatory, not optional optimization.

Legacy profile-only ACK remains a compatibility fallback only when revision is absent.

### 25.5 BLE payload review

**PASS WITH REQUIRED BUDGET ENFORCEMENT**

The extension is materially larger than current profile-only Self Info but remains safely below the
default 255-chunk hard limit when final Self Info is capped at 1,024 bytes.

Runtime device performance remains a GI2B2/GI2G concern.

### 25.6 Maintainability review

**PASS**

The design extends existing envelope, codec, retry, ACK, and relay seams. It does not create a second
protocol family, generic framework, parallel retry mechanism, or trust abstraction.

### 25.7 Scope review

**PASS**

The contract does not implement:

- runtime identity acquisition;
- persistence;
- TOFU;
- encryption;
- invitation sending;
- directory/profile redesign.

---

## 26. Final verdict

```text
GI2B1 — Nearby Self Info Additive Protocol Contract

Architecture verdict:
PASS

Compatibility verdict:
PASS WITH DOCUMENTED OLD-RELAY LIMITATION

Security verdict:
PASS

BLE budget verdict:
PASS WITH MANDATORY 1,024-BYTE SELF INFO PREFLIGHT

Implementation authorization:
READY FOR GI2B1 PATCH APPLICATION AND FOCUSED VERIFICATION
```

No unresolved product-architecture blocker remains inside GI2B1.

Repository compile and focused test verification are still required before GI2B1 can close.

The mandatory implementation constraint is that `self_info_revision` must become the authority for new
Self Info retry, ACK, and relay deduplication. `profile_updated_at_ms` remains only a legacy profile
field and absent-revision compatibility fallback.
