# GI2A3 — Android Keystore Recipient Identity Implementation

Status: **IMPLEMENTED — JVM/build verified; Android runtime verification pending device**
Milestone: **GI2 — Nearby Room Integration**
Depends on: **GI2A2 Recipient Encryption Identity Contract**

## 1. Scope

GI2A3 implements the local recipient-encryption identity only. It does not extend Nearby Self Info,
persist participant capabilities, implement room-scoped TOFU, encrypt invitation payloads, or modify
Nearby Room transport.

## 2. Production implementation

New package:

```text
security/src/main/java/app/peermesh/security/groupinvitation/
```

Components:

- `RecipientEncryptionIdentity`: immutable public identity snapshot with defensive public-key copies.
- `RecipientEncryptionIdentityStatus`: typed availability state.
- `RecipientPrivateKeyResult`: key-agreement-only private-key resolution result.
- `RecipientEncryptionIdentityProvider`: narrow provider boundary.
- `P256Sec1PublicKeyCodec`: strict P-256 SEC1 uncompressed encode/decode/validation.
- `RecipientEncryptionKeyId`: GI2A2 deterministic SHA-256/Base64URL key ID.
- `AndroidKeystoreRecipientEncryptionIdentityProvider`: Android Keystore generation, loading,
  validation, bounded ECDH probe, metadata verification, and explicit reset policy.

The public provider boundary is:

```kotlin
fun getOrCreateIdentity(): RecipientEncryptionIdentityStatus
fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult
fun resetIdentity(activeNearbyRoomCount: Int): RecipientIdentityResetResult
```

All three operations preserve unsupported, temporary, invalidated/not-found/mismatch, blocked-reset,
and success distinctions as applicable. Private-key result diagnostics redact the key object.

## 3. Locked behavior implemented

### Platform behavior

- API 21–30 returns `UnsupportedPlatform` without attempting key generation.
- API 31+ uses `AndroidKeyStore`, `secp256r1`, and `PURPOSE_AGREE_KEY`.
- There is no exportable software-key fallback.

### Alias and key material

The alias is exactly:

```text
peermesh.group_invitation.recipient.p256.v1
```

The private key remains inside Android Keystore. No PKCS#8 bytes are persisted or exposed by the
public identity model.

### Public-key validation

The codec:

- emits and accepts exactly 65-byte SEC1 uncompressed points;
- requires prefix `0x04`;
- requires 32-byte unsigned X and Y coordinates;
- checks coordinate range against the P-256 prime;
- checks the P-256 curve equation;
- verifies generated/loaded EC parameters are P-256;
- rejects compressed, hybrid, malformed, wrong-length, and off-curve input.

### Deterministic key ID

The implementation follows GI2A2 exactly:

```text
SHA-256(
  UTF8("peermesh/recipient-encryption-key-id/v1")
  || 0x00
  || UTF8("p256-hkdf-sha256-a256gcm")
  || 0x00
  || SEC1_PUBLIC_KEY_65_BYTES
)
```

The digest is encoded as unpadded Base64URL and is not truncated.

### Runtime probe

Before returning `Available`, the provider:

1. loads or creates the Keystore key;
2. validates the public key and deterministic key ID;
3. creates a temporary in-memory P-256 peer keypair;
4. performs ECDH with the Keystore private key;
5. requires a 32-byte shared secret;
6. clears the temporary secret;
7. caches successful probe state for the current process/key ID.

Probe failure suppresses availability rather than crashing callers.

### Restore and replacement protection

A non-secret SharedPreferences metadata cache records a created marker, contract version, algorithm,
and verified active key ID. The Keystore alias remains the source of truth.

If metadata says an identity existed but the alias is missing, the provider returns `Invalidated`
instead of silently generating a replacement. Explicit reset:

- is refused before deletion while the caller-supplied authoritative active-room count is non-zero;
- deletes the alias and metadata only when no active room remains;
- allows a new identity on the next bounded request.

The provider does not query room state itself. GI2B/application composition must inject an
authoritative active/non-expired room count.

### Reset partial-failure safety

Reset loads the Keystore, clears metadata, then deletes the alias. If metadata clearing fails, the
alias and probe cache remain untouched and the result is `METADATA_WRITE_FAILED`. After an alias
deletion exception, the provider verifies the actual alias postcondition inside its process-wide
lock: an existing alias returns temporary failure and is deterministically re-adopted; an absent alias
means reset succeeded; an unverifiable alias state returns temporary failure. The narrow internal reset
orchestrator exists solely to verify this ordering and reconciliation on the JVM.

Before identity metadata or the alias is mutated, reset synchronously persists versioned
`RESET_IN_PROGRESS` state using `reset_state_version` and `reset_state`. An unverifiable deletion
outcome is synchronously changed to `RESET_OUTCOME_INDETERMINATE`. This non-secret marker survives
process restart. Subsequent lookup handles it conservatively:

- marker plus existing alias: fully validate/probe the old alias, restore identity metadata, then clear
  the marker;
- marker plus missing alias: return `Invalidated(RESET_RECOVERY_REQUIRED)` without generating a key;
- malformed marker: fail conservatively without generation;
- explicit `resetIdentity(0)` with a missing alias clears the marker and succeeds, allowing only the
  next identity request to create a replacement.

## 4. Tests added

JVM tests cover:

- P-256 SEC1 round trip;
- exact encoding length and prefix;
- wrong-length, compressed, and off-curve rejection;
- public-key defensive copying;
- deterministic 43-character unpadded key ID;
- different public keys producing different IDs.

Android instrumentation tests cover API 31+ behavior:

- stable get-or-create identity;
- non-exportable private key;
- actual ECDH use;
- wrong key-ID rejection;
- missing alias with persisted metadata becoming `Invalidated`;
- explicit no-active-room reset and replacement;
- reset refusal while a room is active.

They also cover provider recreation, concurrent provider instances, missing metadata with an existing
alias, metadata/key mismatch, a wrong-purpose alias, resolution without implicit creation, and the
API 21–30 unsupported/no-alias path (when run on that API range).

Reset tests additionally cover metadata-clear failure preserving alias, metadata, private-key
resolution and probe behavior; delete-before-throw deterministic adoption; delete-then-throw success
reconciliation; alias-state-check failure; successful reset; and active-room blocking before either
reset mutation.

API 21–30 behavior is implemented behind an API guard and must be exercised in the project device/API
matrix before closure.

## 5. Build changes

The `security` module now declares `AndroidJUnitRunner` and AndroidX test dependencies required for
its instrumentation tests. No production dependency was added.

## 6. Verification status

Verified in the implementation environment:

```bash
./gradlew :security:testDebugUnitTest \
  --tests 'app.peermesh.security.groupinvitation.*'

./gradlew :security:testDebugUnitTest

./gradlew :security:assembleDebug :security:assembleDebugAndroidTest
```

The JVM and compile commands above passed. Final instrumentation ran successfully on an API 30 device
for the unsupported-platform path; the 21 API 31+ tests were skipped by their explicit API guard. API
31+ keystore tests remain unverified. The remaining API 31+ runtime command is:

```bash

./gradlew :security:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.peermesh.security.groupinvitation.AndroidKeystoreRecipientEncryptionIdentityProviderTest
```

Run instrumentation on at least:

- one API 31 emulator/device;
- one current API device;
- one API 21–30 emulator/device to verify `UnsupportedPlatform` and no alias-generation attempt.

## 7. Explicit non-goals

GI2A3 does not:

- advertise the identity in self-info;
- add capability fields;
- create TOFU pins;
- implement invitation ECDH/HKDF/AES-GCM;
- add database migrations;
- modify Nearby Room, BLE, DTN, or timeline behavior;
- rotate keys periodically;
- provide trust-reset UI.

## 8. Next phase

The next phase is **GI2B1 — Additive Nearby Self Info Protocol**. It should consume only
`RecipientEncryptionIdentityStatus.Available`; all other states must omit private-recipient capability
and key fields.
