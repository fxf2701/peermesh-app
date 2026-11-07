# GI2B1-P1 Verification Review

## Scope

Applied the prepared GI2B1-P1 files to the uploaded Android baseline and reviewed:

- NearbyRoomTransportEnvelope.kt
- NearbyRoomTransportCodec.kt
- NearbySelfInfoProtocol.kt
- NearbyRoomTransportCodecGI2B1Test.kt
- NearbySelfInfoProtocolTest.kt

## Findings

### Fixed F1 — Optional GI2B JSON type handling

The first patch reused the generic `stringValue()` decoder. That helper accepts any JSON primitive content, so malformed values such as:

```json
"rea": 7
"sir": false
```

were materialized as the strings `"7"` and `"false"`.

This did not establish a usable identity, but it did not meet the GI2B1 contract that wrong JSON types make the affected extension field unavailable.

Correction:

- Added strict string decoding for GI2B fields.
- String fields now require JSON string primitives.
- `res` now requires a non-string JSON boolean.
- Updated malformed-type tests to require null extension fields.

### Fixed F2 — Incomplete standalone patch

The original standalone patch contained only modifications to:

- NearbyRoomTransportEnvelope.kt
- NearbyRoomTransportCodec.kt

It omitted:

- NearbySelfInfoProtocol.kt
- NearbyRoomTransportCodecGI2B1Test.kt
- NearbySelfInfoProtocolTest.kt

Correction:

- Generated a complete patch containing all five production/test files.

## Static Review

### PASS

- No Group Invitation transport kind.
- No invitation event or batch type.
- Extension fields remain kind-scoped to Self Info and Self Info ACK.
- Wrong optional types do not reject base Self Info.
- Recipient identity remains all-or-nothing.
- Key ID is recomputed from algorithm and SEC1 public key.
- Snapshot revision is deterministic and content-derived.
- Public-key byte arrays use defensive copying.
- Non-Self-Info kinds do not serialize extension fields.
- Wire budgets are checked before BLE chunking.

## Verification Attempt

Attempted:

```bash
./gradlew :app:testEmergencyDebugUnitTest   --tests 'app.peermesh.nearby.NearbyRoomTransportCodecTest'   --tests 'app.peermesh.nearby.NearbyRoomTransportCodecGI2B1Test'   --tests 'app.peermesh.nearby.NearbySelfInfoProtocolTest'   --no-parallel
```

The environment could not download Gradle 8.7 because outbound network access is unavailable.

Therefore:

- source review: complete;
- patch completeness: verified;
- compile verification: pending in the real repository;
- focused tests: pending in the real repository.

## Verdict

```text
GI2B1-P1

Architecture: PASS
GI1R alignment: PASS
Patch completeness: PASS AFTER FIX
Static contract review: PASS AFTER FIX
Compile verification: PENDING
Focused tests: PENDING

Milestone status:
NOT READY TO CLOSE

Next:
Apply complete fixed patch in the real repository and run focused tests.
```
