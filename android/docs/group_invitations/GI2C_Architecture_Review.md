# GI2C — Room-scoped Recipient Key Pinning Architecture Review

**Review status:** PASS WITH REQUIRED CONTRACT AMENDMENTS  
**Implementation status:** NOT AUTHORIZED until the GI2C contract is updated and marked `LOCKED`.

## 1. Reviewed authority and source

Reviewed in precedence order:

1. `Product_Architecture_Invariants.md`
2. `Group_Invitation_Spec_v2.md`
3. `GI1R_Group_Invitation_Architecture_Realignment.md`
4. `Group_Invitation_Implementation_Roadmap_v2.md`
5. `GI2C_Room_Scoped_Recipient_Key_Pinning_Scope_Contract.md`
6. Current Nearby Room participant entity/DAO/repository
7. `SELF_INFO_V1` validation and receive path
8. Room close, expiry and purge paths
9. Current Room database and migrations

## 2. Architecture verdict

GI2C does not require a new subsystem or a new table.

The smallest safe ownership model is:

```text
nearby_room_participants
primary key: (room_id, device_id)
```

The existing participant row is already room-scoped, persistent across restart, and deleted by the authoritative room purge path. It is therefore the natural owner of a recipient public-key pin.

Approved persistence shape:

```text
recipient_encryption_algorithm   TEXT nullable
recipient_encryption_key_id      TEXT nullable
recipient_encryption_public_key  BLOB nullable
```

No `pinned_at`, trust score, history, rotation state, conflict table, repository, or generic trust framework is justified by GI2C.

## 3. Identity key

The GI2C `participantId` is the existing Nearby participant `device_id`.

For received `SELF_INFO_V1`:

```text
participantId = origin_device_id ?: sender_device_id
```

This matches current participant ownership and relay behavior. No new participant identity abstraction is needed.

## 4. Security semantics

GI2C provides room-scoped TOFU continuity:

```text
Trust On First Use
```

It guarantees:

- the first valid key is retained;
- the same key is accepted;
- a later different key cannot silently overwrite the pin.

It does not prove that the first observed key belongs to the claimed participant. The current Self Info payload is revision-bound but not signed by a stable participant identity. A malicious room member capable of forging an origin identifier may win the first-pin race.

This limitation must be explicit. GI2C must not claim authenticated identity, certificate validation, or compromise recovery.

## 5. Receive-path ownership

`NearbySelfInfoProtocol.validateReceived()` remains the only protocol validation gate.

Only this value may reach pinning:

```text
validatedSnapshot?.recipientEncryptionIdentity
```

Raw envelope identity fields must never be persisted directly.

The existing `NearbyRoomTransportReceiver` remains responsible for orchestration. The repository owns the atomic participant/profile/pin persistence operation.

A pin conflict must not:

- reject the Self Info envelope;
- stop ACK;
- stop relay;
- alter participant presence;
- mutate the existing pin;
- enable or disable Invitation UI.

It should produce a local diagnostic result only. Nearby transport ownership remains unchanged.

## 6. Required atomicity

A read-then-`REPLACE` implementation is not sufficient for first-pin correctness.

The DAO/repository path must guarantee that two competing first valid keys cannot both observe an empty pin and overwrite one another.

Required behavior inside one database transaction:

1. Verify that the room exists and is active.
2. Ensure/update the participant row while preserving any existing pin fields.
3. Conditionally write the incoming key only when all three pin columns are null.
4. Read the persisted pin.
5. Return `Pinned`, `Matched`, or `Conflict` from persisted state.

The conditional write must be enforced by SQL, not only by Kotlin branching.

Recommended DAO operation:

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

Existing participant upserts must copy and preserve all pin fields.

## 7. Missing profile data

The current `upsertParticipantSelfInfo()` returns early when both display name and avatar are absent.

GI2C cannot depend on profile fields being present. A valid recipient identity must still be pinnable even when profile fields are absent or unchanged.

The Self Info repository operation should therefore accept profile data and validated identity independently. It should not create two unrelated repository pipelines.

## 8. Persistence lifecycle

The pin lifetime is exactly the participant row lifetime within the room.

Current authoritative purge path:

```text
NearbyRoomRepository.purgeRoomLocalData(roomId)
  -> nearbyRoomParticipantDao.deleteByRoom(roomId)
```

Therefore room purge automatically removes pin data without extra cleanup code.

Room close or expiry does not immediately delete participant rows. The pin remains until terminal-room cleanup/purge. This is consistent with the Product Spec model in which room-scoped persisted state survives until room data lifecycle removal.

A closed/expired room must not accept a new pin or replace an existing pin.

## 9. Locked pin comparison

A persisted pin is one tuple:

```text
(algorithm, keyId, publicKeySec1)
```

Comparison must include all three values. Public-key comparison is byte-for-byte.

Database invariants:

```text
all three fields null
OR
all three fields non-null and protocol-valid
```

A partial stored tuple is fail-closed data corruption. It must never be repaired by silently accepting an incoming key. Return an unavailable/corrupt diagnostic result and retain stored data for investigation.

## 10. Result model

The existing proposed result list is incomplete because it does not distinguish same-key acceptance from absence of input.

The narrow runtime result should be:

```text
NoValidIdentity
Pinned
Matched
Conflict
RoomUnavailable
StoredPinInvalid
```

These are security-continuity outcomes only. They must not contain Invitation semantics.

`ParticipantInserted` remains a separate participant/profile outcome if the caller needs it; it should not be folded into the key-pin result.

## 11. Migration

GI2C requires one additive Room migration:

```text
AppDatabase 53 -> 54
```

The migration adds the three nullable columns to `nearby_room_participants`.

No backfill is required. Existing participants begin unpinned and use TOFU when the next valid Self Info identity is received.

The exported Room schema and migration tests must be updated.

## 12. Invariant check

- New subsystem: **No**
- New table: **No**
- Invitation-specific persistence: **No**
- Nearby Chat transport duplication: **No**
- Security depends on Invitation: **No**
- New Invitation business rule: **No**
- Backward-compatible wire change: **No wire change**
- Existing architecture reused: **Yes**

## 13. Required focused tests

### DAO/repository

- Empty pin + valid identity -> `Pinned` and survives database reopen.
- Same tuple -> `Matched`, no row mutation.
- Different key ID/public key/algorithm -> `Conflict`, original tuple unchanged.
- Missing identity -> `NoValidIdentity`, existing pin unchanged.
- Identity-only Self Info with no display/avatar -> pin succeeds.
- Stale profile update cannot erase pin.
- Presence upsert cannot erase pin.
- Concurrent competing first pins -> exactly one persists; loser reports `Conflict`.
- Partial stored tuple -> fail closed; no overwrite.
- Closed/expired/missing room -> no pin mutation.
- `purgeRoomLocalData()` removes pin with participant row.
- Migration 53 -> 54 preserves existing participant data and initializes pin fields to null.

### Receiver

- Only validated snapshot identity is passed to repository.
- Invalid revision/key tuple is never pinned.
- Conflict still ACKs and relays according to existing Self Info behavior.
- Targeted Self Info for another device is relayed but not locally pinned.
- Conflict logging never includes raw public-key bytes.

## 14. Files expected in implementation

Expected production scope only:

```text
data/.../NearbyRoomParticipantEntity.kt
data/.../NearbyRoomParticipantDao.kt
data/.../AppDatabase.kt
data/.../DatabaseMigrations.kt
app/.../NearbyRoomRepository.kt
app/.../NearbyRoomTransportReceiver.kt
```

A small result type may be added under the existing Nearby package. Tests and exported Room schema will also change.

Any new Invitation repository/table, trust framework, key-history model, rotation workflow, or transport kind is out of scope and must be rejected.

## 15. Final decision

```text
Architecture direction: APPROVED
Current proposed contract: INCOMPLETE
Production implementation: BLOCKED
Next step: amend and lock GI2C contract using this review
```
