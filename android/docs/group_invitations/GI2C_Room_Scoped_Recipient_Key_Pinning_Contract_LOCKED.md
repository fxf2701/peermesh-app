# GI2C — Room-scoped Recipient Key Pinning Contract

**Status:** LOCKED — CLOSED

**Architecture review:** PASS

**Implementation:** COMPLETE

**Closure evidence:** `GI2C_Closure_Evidence.md`

**Closed:** 2026-08-04

## 1. Purpose

GI2C has one responsibility:

```text
Preserve the first valid recipient encryption identity
for one Nearby participant inside one Nearby Room.
```

GI2C provides recipient-key continuity for later encryption use cases.

GI2C is not an Invitation Card milestone and must not introduce an Invitation subsystem.

## 2. Product Architecture Invariants

GI2C must preserve all locked Product Architecture Invariants.

In particular:

- Group Invitation remains a structured Nearby Chat message.
- Nearby Chat retains ownership of transport, relay, retry, ordering, persistence and history.
- Security must not depend on Invitation, Card, Join or UI concepts.
- GI2C must not create Invitation-specific persistence, lifecycle or transport behavior.
- No additional Invitation business rule is introduced by GI2C.

Priority remains:

```text
Stability
>
Correctness
>
Maintainability
>
Performance
```

## 3. Scope and Persistence Ownership

The pin is scoped by:

```text
(roomId, participantId)
```

The locked identity mapping is:

```text
participantId = Nearby participant device_id
```

For a received `SELF_INFO_V1` snapshot:

```text
participantId = origin_device_id ?: sender_device_id
```

The pin must be stored on the existing room-scoped participant row:

```text
nearby_room_participants
PRIMARY KEY (room_id, device_id)
```

The participant row is the only persistence owner approved by GI2C.

The additive persistence shape is:

```text
recipient_encryption_algorithm   TEXT nullable
recipient_encryption_key_id      TEXT nullable
recipient_encryption_public_key  BLOB nullable
```

GI2C must not create:

- a new key-pin table;
- an Invitation table;
- an Invitation Card table;
- an Invitation state or history table;
- a key-conflict history table;
- a generic trust repository or trust framework;
- key rotation, recovery or certificate infrastructure.

No `pinned_at`, trust score or lifecycle state is required.

## 4. Accepted Identity Source

`NearbySelfInfoProtocol.validateReceived()` remains the protocol validation gate.

Only the validated identity may reach persistence:

```text
validatedSnapshot?.recipientEncryptionIdentity
```

Raw transport-envelope identity fields must never be pinned directly.

Profile fields and recipient identity are independent inputs. A valid identity must remain pinnable when display name and avatar are absent or unchanged.

## 5. Key-Pinning Rules

A persisted recipient identity is one indivisible tuple:

```text
(algorithm, keyId, publicKeySec1)
```

All three values participate in equality comparison.

Public-key equality is byte-for-byte equality.

Locked behavior:

```text
No stored pin + valid incoming identity
→ atomically persist incoming tuple
→ Pinned

Stored tuple exactly matches incoming tuple
→ no mutation
→ Matched

Stored tuple differs in algorithm, keyId or publicKeySec1
→ retain original tuple
→ Conflict

Missing or invalid incoming identity
→ no pin mutation
→ NoValidIdentity

Room missing, closed or expired
→ no pin mutation
→ RoomUnavailable

Stored tuple is partial or protocol-invalid
→ no overwrite or repair
→ StoredPinInvalid
```

Silent key replacement is forbidden.

Incoming data must never repair or normalize a corrupt stored pin automatically.

## 6. Atomicity and Transaction Boundary

First-pin behavior must be race-safe.

A Kotlin read-then-write check is insufficient.

The existing Nearby Room repository owns one transactional participant/profile/pin persistence operation. Within that transaction it must:

1. verify that the room exists and is active;
2. ensure or update the participant row while preserving existing pin columns;
3. conditionally write the incoming tuple only when all pin columns are null;
4. read the persisted tuple;
5. derive the key-pin result from persisted state.

The first-pin condition must be enforced by SQL equivalent to:

```sql
UPDATE nearby_room_participants
SET recipient_encryption_algorithm = :algorithm,
    recipient_encryption_key_id = :keyId,
    recipient_encryption_public_key = :publicKey
WHERE room_id = :roomId
  AND device_id = :deviceId
  AND recipient_encryption_algorithm IS NULL
  AND recipient_encryption_key_id IS NULL
  AND recipient_encryption_public_key IS NULL
```

Concurrent competing first pins must result in exactly one persisted tuple. Every loser must observe the persisted tuple and return `Conflict` unless it exactly matches and therefore returns `Matched`.

## 7. Participant Upsert Preservation

Every existing participant presence, profile or Self Info upsert must preserve the three pin columns.

An ordinary participant update must never:

- clear a pin;
- replace a pin;
- create a partial pin;
- bypass the atomic pin operation.

If an existing `REPLACE`-style DAO path remains, its entity merge must carry forward the complete persisted pin tuple. A safer targeted update may be used where it reduces overwrite risk without introducing a new abstraction.

## 8. Stored Data Invariant

The database invariant is:

```text
all three pin fields are NULL
OR
all three pin fields are non-NULL and protocol-valid
```

Any partial tuple is treated as stored-data corruption.

GI2C must fail closed and retain the stored values for diagnosis. It must not silently clear or overwrite them.

## 9. Runtime Result Model

GI2C may expose only this narrow key-continuity result:

```text
NoValidIdentity
Pinned
Matched
Conflict
RoomUnavailable
StoredPinInvalid
```

The result must not encode:

- Invitation Card state;
- public/private invitation type;
- invitation send eligibility;
- group selection;
- Join behavior;
- UI enablement;
- transport ACK or relay status.

Participant/profile insertion or update outcomes remain separate from the key-pin result if the caller needs them.

## 10. Receive-Path Behavior

`NearbyRoomTransportReceiver` remains the receive-path orchestrator.

The existing Nearby Room repository owns persistence and atomicity.

A key conflict or unavailable pin result must not:

- reject an otherwise valid Self Info envelope;
- stop or alter ACK behavior;
- stop or alter relay behavior;
- change transport retry behavior;
- block valid participant presence/profile updates;
- enable or disable Invitation UI;
- emit raw public-key bytes in logs.

A conflict may produce a narrow local security diagnostic only.

Targeted Self Info that is not applicable to the local participant-processing path must retain existing relay behavior and must not be locally pinned outside current Self Info ownership rules.

## 11. Persistence Lifecycle

The pin lifetime is exactly the participant-row lifetime inside the room.

The authoritative cleanup path remains:

```text
NearbyRoomRepository.purgeRoomLocalData(roomId)
→ nearbyRoomParticipantDao.deleteByRoom(roomId)
```

Room purge must remove the pin through deletion of the participant row. No separate pin cleanup path is allowed.

Room close or expiry does not itself require a second pin lifecycle. Existing persisted data may remain until terminal room cleanup, but a closed or expired room must not accept a new pin or mutate an existing pin.

## 12. Security Boundary and Limitation

GI2C provides room-scoped:

```text
TOFU — Trust On First Use
```

GI2C guarantees continuity after the first successfully persisted valid identity:

- the first valid tuple is retained;
- the same tuple is recognized;
- a later different tuple cannot silently overwrite it.

GI2C does not provide:

- proof that the first observed key belongs to the claimed participant;
- stable participant signatures;
- certificate validation;
- global identity trust;
- compromise detection or recovery;
- key rotation.

The current Self Info protocol is revision-bound but does not authenticate the claimed origin through a stable participant signing identity. This limitation is accepted for GI2C and must not be overstated by implementation or documentation.

## 13. Database Migration

GI2C requires one additive Room migration:

```text
AppDatabase 53 → 54
```

The migration adds the three nullable columns to `nearby_room_participants`.

No data backfill is required. Existing participant rows begin unpinned and apply TOFU when the next valid recipient identity is processed.

The exported Room schema and migration verification must be updated.

## 14. Invitation Independence

GI2C must not know about:

- public invitation;
- private invitation;
- Invitation Card;
- group selection;
- group name;
- description;
- Join;
- invitation send limits;
- invitation history.

The only Invitation management rules remain those defined by Product Spec v2:

```text
1 public invitation per sender per room
1 private invitation per sender-recipient pair per room
```

Those rules belong to later structured Nearby Chat integration and are outside GI2C.

## 15. Required Focused Verification

### DAO and repository

- Empty pin plus valid identity returns `Pinned` and survives database reopen.
- Same tuple returns `Matched` without row mutation.
- Different algorithm returns `Conflict`; original tuple remains unchanged.
- Different key ID returns `Conflict`; original tuple remains unchanged.
- Different public key returns `Conflict`; original tuple remains unchanged.
- Missing identity returns `NoValidIdentity`; existing pin remains unchanged.
- Identity-only Self Info with no display name or avatar can pin successfully.
- Presence update cannot clear or replace a pin.
- Profile update cannot clear or replace a pin.
- Concurrent competing first pins persist exactly one tuple.
- Partial stored tuple returns `StoredPinInvalid` and is not overwritten.
- Protocol-invalid stored tuple returns `StoredPinInvalid` and is not overwritten.
- Missing, closed or expired room returns `RoomUnavailable` without pin mutation.
- `purgeRoomLocalData()` removes the pin with the participant row.
- Migration `53 → 54` preserves participant data and initializes all pin columns to null.

### Receiver

- Only validated snapshot identity reaches the repository.
- Invalid revision or invalid identity tuple is never pinned.
- Conflict does not prevent existing ACK behavior.
- Conflict does not prevent existing relay behavior.
- Conflict does not prevent valid presence/profile processing.
- Targeted Self Info outside the local processing scope is not pinned locally.
- Diagnostics do not contain raw public-key bytes.

## 16. Exit Criteria

GI2C may close only when all of the following are true:

- First valid room-participant identity is atomically pinned.
- The pin survives process restart and database reopen.
- Same identity is accepted without mutation.
- Any different tuple is reported as conflict and never overwrites the original.
- Missing or invalid identity never removes or changes a pin.
- Participant presence/profile updates preserve the pin.
- Concurrent first-pin attempts cannot overwrite one another.
- Corrupt stored tuples fail closed.
- Closed, expired or missing rooms reject pin mutation.
- Room purge removes pin data through the existing participant cleanup path.
- Existing Self Info ACK, relay and transport ownership remain unchanged.
- No Invitation/Card subsystem, new table, trust framework or extra Invitation business rule is introduced.
- Migration and focused verification pass, subject only to the explicitly
  accepted app instrumentation limitation below.

Closure was accepted on 2026-08-04 with the evidence recorded in
`GI2C_Closure_Evidence.md`.

One verification limitation remains explicit: the app-level
`NearbyRecipientKeyPinningTest` instrumentation suite could not be executed
because unrelated existing M3D2 tests prevent the app `androidTest` source set
from compiling. GI2C production compilation, focused JVM tests, test-source
review and the `53 → 54` migration instrumentation test passed. The M3D2
failures are not part of GI2C and are not waived as product defects or repaired
by this milestone.

## 17. Implementation Boundary

Expected production changes are limited to the existing Nearby Room participant persistence and Self Info receive path, including:

```text
NearbyRoomParticipantEntity
NearbyRoomParticipantDao
AppDatabase
DatabaseMigrations
NearbyRoomRepository
NearbyRoomTransportReceiver
```

A small result type may be added under the existing Nearby package.

Any proposal to add an Invitation repository/table, key history, trust subsystem, rotation workflow, new transport kind or separate retry pipeline is outside the locked contract and requires a new architecture decision before implementation.
