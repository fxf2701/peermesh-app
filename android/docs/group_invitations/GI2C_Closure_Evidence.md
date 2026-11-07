# GI2C — Closure Evidence

**Milestone status:** CLOSED

**Closure date:** 2026-08-04

**Contract:** `GI2C_Room_Scoped_Recipient_Key_Pinning_Contract_LOCKED.md`

## 1. Closure Decision

GI2C is closed for its locked production scope.

The implementation provides room-scoped TOFU continuity for a validated
recipient encryption identity without adding an Invitation subsystem, a new
table, a trust framework or transport behavior.

The final production review confirmed:

- transaction boundary: PASS;
- incoming validation ownership: PASS;
- additive migration `53 → 54`: PASS;
- architecture and product invariants: PASS.

## 2. Production Evidence

### Persistence and atomicity

- `NearbyRoomRepository.upsertParticipantSelfInfo()` owns the complete Room
  transaction.
- The transaction includes participant/profile/presence merge, conditional
  first-pin update, persisted tuple reread and result classification.
- `NearbyRoomParticipantDao.pinRecipientEncryptionIdentityIfAbsent()` updates
  the tuple only when all three stored fields are null.
- Competing first pins cannot overwrite a committed tuple. The persisted tuple
  determines `Pinned`, `Matched`, `Conflict` or `StoredPinInvalid` before the
  transaction returns.
- Ordinary participant updates preserve all three pin fields.
- Existing room purge ownership removes the pin with the participant row.

### Validation ownership

- `NearbySelfInfoProtocol.validateReceived()` is the sole incoming wire
  validation owner.
- `NearbyRoomTransportReceiver` passes only
  `validatedSnapshot?.recipientEncryptionIdentity` to the repository.
- The repository does not receive raw algorithm, key ID or encoded public-key
  fields and does not repeat Base64URL, SEC1, length or algorithm validation for
  incoming wire data.
- Persisted tuple integrity classification is separate and may return
  `StoredPinInvalid` without repairing or overwriting stored data.

### Database migration

- `AppDatabase` version is `54`.
- `DatabaseMigrations.MIGRATION_53_54` is registered in
  `DatabaseMigrations.ALL` and installed by `AppDatabaseProvider`.
- The migration performs exactly three additive nullable-column operations on
  `nearby_room_participants`.
- There is no table rewrite, backfill, new index, primary-key change, foreign-key
  change or destructive fallback.
- Exported schema `54.json` matches the entity and retains primary key
  `(room_id, device_id)`.

## 3. Verification Evidence

### Focused JVM verification

```bash
./gradlew :app:testEmergencyDebugUnitTest \
  --tests 'app.peermesh.nearby.NearbyRecipientKeyPin*' \
  --tests 'app.peermesh.nearby.NearbySelfInfoProtocolTest' \
  --tests 'app.peermesh.nearby.NearbyRoomTransportCodecGI2B1Test' \
  --tests 'app.peermesh.nearby.NearbyRoomTransportCodecTest' \
  --no-parallel
```

Result: PASS.

```bash
./gradlew :security:testDebugUnitTest \
  --tests 'app.peermesh.security.groupinvitation.*' \
  --no-parallel
```

Result: PASS.

### Compilation

```bash
./gradlew \
  :data:compileDebugKotlin \
  :data:compileReleaseKotlin \
  :app:compileEmergencyDebugKotlin \
  :data:compileDebugAndroidTestKotlin \
  --no-parallel
```

Result: PASS.

### Migration instrumentation

```bash
ANDROID_SERIAL='192.168.110.185:38519' \
  ./gradlew :data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.peermesh.data.db.NearbyRecipientKeyPinMigrationTest \
  --no-parallel
```

Result: PASS — one test executed on SM-A107F, Android 11.

The migration test verifies that the existing participant row and all existing
column values survive, the three added fields are null, and the ordered
composite primary key remains `room_id`, `device_id`.

An earlier attempt exposed the same physical device through two ADB endpoints.
The two runners interfered with the same test package and both stopped before
test discovery. Selecting one serial removed that environmental collision and
the migration test passed.

### Diff hygiene

```bash
git diff --check
```

Result: PASS.

## 4. Accepted App Instrumentation Limitation

The app-level `NearbyRecipientKeyPinningTest` repository/DAO instrumentation
suite was not executed during closure.

`./gradlew :app:compileEmergencyDebugAndroidTestKotlin --no-parallel` is blocked
by unrelated pre-existing M3D2 test compilation errors in:

- `GroupMapActivityM3D2Test.kt`;
- `OsmdroidMapRuntimeRendererM3D2Test.kt`.

The compiler failures are constructor/argument mismatches in the M3D2 tests;
no GI2C test compilation error was reported before the source-set build stopped.
The GI2C instrumentation source was reviewed, but source review is not recorded
as a runtime PASS.

This limitation is accepted for GI2C closure because the blocking tests are
outside the locked GI2C production scope, while GI2C production compilation,
focused JVM behavior, transaction/validation source audit and real-device
migration execution passed. Restoring the app `androidTest` baseline and then
running `NearbyRecipientKeyPinningTest` remains follow-up verification debt.
GI2C closure does not authorize changing the M3D2 tests.

## 5. Invariant Closure

Closure confirms:

- no new table;
- no new repository;
- no duplicate incoming validation;
- no transport, ACK, relay, retry or ordering change;
- no pin overwrite path;
- no participant update path that clears a pin;
- no Invitation/Card persistence or business rule;
- migration remains additive;
- TOFU limitations remain explicit and are not represented as authenticated
  participant identity.

The closure advances only GI2C. It does not close or implement GI2D or any later
Group Invitation milestone.
