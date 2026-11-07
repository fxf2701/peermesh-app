# GI2A2 — Recipient Encryption Identity Contract

Status: **IMPLEMENTED — contract/documentation only**  
Milestone: **GI2 — Nearby Room Integration**  
Depends on: **GI1 Protocol Review**, **GI2A1 Current Architecture Contract**  
Production code changes: **none**

## 1. Purpose

GI2A2 freezes the recipient-encryption identity contract before Android Keystore implementation,
self-info distribution, room-scoped TOFU, recipient-confidential encryption, or invitation message
integration begins.

This phase defines:

- the dedicated recipient-encryption identity model;
- key algorithm, public-key encoding, deterministic key ID, and alias/versioning;
- platform capability and API-level behavior;
- key creation, loading, validation, invalidation, reset, and rotation semantics;
- failure behavior and capability-advertisement rules;
- ownership boundaries and required tests.

GI2A2 does not generate production keys, change GI1 payload shapes, modify self-info, add database
entities, or perform ECDH/HKDF/AES-GCM encryption.

## 2. Locked upstream security contract

GI2 inherits these GI1 decisions unchanged:

- PARTICIPANT invitations use a recipient-confidential inner envelope inside normal Nearby Room
  encryption.
- Each installation uses a dedicated recipient-encryption keypair.
- The recipient-encryption keypair must not reuse the DTN manifest signing identity, group key, room
  key, actor ID, participant ID, or device ID.
- Target public keys are distributed through Nearby Self Info.
- A sender generates a fresh ephemeral P-256 keypair for every private invitation.
- The target decrypts using its dedicated recipient-encryption private key.
- The protocol algorithm remains `p256-hkdf-sha256-a256gcm`.
- The wire public-key format remains a 65-byte uncompressed P-256 SEC1 point whose first byte is
  `0x04`.

Identifiers may be used for routing, lookup, AAD, or HKDF context. They are never encryption keys or
entropy inputs for key generation.

## 3. Source findings that constrain this contract

### 3.1 Existing `SecureKeyStore` is not suitable

The current `SecureKeyStore.loadOrCreateDtnManifestIdentity()`:

- generates a normal JCA P-256 keypair;
- serializes the public key as X.509 SubjectPublicKeyInfo;
- serializes the private key as PKCS#8;
- stores both in `EncryptedSharedPreferences`.

GI2 must not reuse or extend that identity for recipient encryption because:

- it is a signing/manifest identity with a different security domain;
- its public-key encoding does not match GI1's SEC1 wire contract;
- its private key is exportable application material;
- coupling rotation or reset would affect DTN manifest verification;
- reuse would violate the explicitly locked Option A architecture.

### 3.2 Platform support boundary

The security module has `minSdk = 21`. Android Keystore's dedicated key-agreement authorization,
`KeyProperties.PURPOSE_AGREE_KEY`, was added in API 31.

Therefore GI2 must not assume that a non-exportable Android Keystore EC private key can perform ECDH
on every supported app API level.

GI2A2 locks the conservative policy:

```text
API 31+ and successful runtime probe:
    recipient-encryption identity may be AVAILABLE

API 21–30:
    recipient-encryption identity is UNSUPPORTED_PLATFORM

API 31+ but generation/load/ECDH probe fails:
    recipient-encryption identity is TEMPORARILY_UNAVAILABLE or INVALIDATED
```

GI2 must not silently fall back to an exportable software private key in v1. Such a fallback would
create a materially different security level, recovery model, and threat surface and requires a
separate reviewed protocol/product decision.

Public ROOM invitations are independent of this recipient-key capability and are not disabled merely
because private recipient encryption is unavailable.

## 4. Ownership and module placement

Recipient identity is owned by the `security` module.

Recommended production package:

```text
security/src/main/java/app/peermesh/security/groupinvitation/
```

The implementation must remain invitation-specific. Do not expand `SecureKeyStore` into a generic
asymmetric identity registry and do not create a generic key-management framework without a second
real use case.

Expected narrow components:

```text
RecipientEncryptionIdentity
RecipientEncryptionIdentityStatus
RecipientEncryptionIdentityProvider
AndroidKeystoreRecipientEncryptionIdentityProvider
RecipientEncryptionKeyId
P256Sec1PublicKeyCodec
```

The exact class names may change during implementation review, but their ownership boundaries are
locked.

## 5. Identity model

The public identity snapshot is immutable:

```kotlin
data class RecipientEncryptionIdentity(
    val algorithm: PrivateInvitationEncryptionAlgorithm,
    val keyId: String,
    val publicKeySec1: ByteArray,
)
```

### 5.1 Invariants

A valid identity must satisfy all of the following:

- algorithm is exactly `P256_HKDF_SHA256_A256GCM` for v1;
- public key is exactly 65 bytes;
- byte 0 is exactly `0x04`;
- X and Y coordinates are exactly 32-byte unsigned big-endian values;
- the point is on the NIST P-256/secp256r1 curve;
- the point is not the point at infinity;
- `keyId` is the deterministic v1 fingerprint of algorithm and public key;
- the corresponding private key exists under the expected Android Keystore alias;
- a local ECDH usability probe succeeds before the identity is advertised.

The `ByteArray` must be defensively copied when entering and leaving the model so caller mutation
cannot change identity state after validation.

### 5.2 Private-key access

The public identity model must never expose a `PrivateKey` property.

Private-key use must occur through the provider at the cryptographic operation boundary, for example:

```kotlin
fun loadPrivateKeyForAgreement(keyId: String): RecipientPrivateKeyResult
```

or an equivalent narrow operation that verifies the requested key ID matches the active identity.

The implementation must not:

- return private encoded bytes;
- persist PKCS#8 bytes;
- log a private key object or provider exception containing secret material;
- expose a generic alias lookup callable with arbitrary aliases.

## 6. Algorithm and curve contract

### 6.1 Algorithm

The identity algorithm is the same enum already defined by GI1:

```text
p256-hkdf-sha256-a256gcm
```

The identity provider owns only the long-lived recipient P-256 keypair. HKDF and AES-GCM belong to
later GI2D phases.

### 6.2 Curve

The required curve is:

```text
secp256r1 / NIST P-256 / prime256v1
```

These are names for the same curve, but production code must initialize with a single locked provider
name, recommended `secp256r1`, and validate returned parameters rather than trusting a provider alias.

### 6.3 Public-key wire encoding

GI1 does not use X.509 SubjectPublicKeyInfo. It uses SEC1 uncompressed point encoding:

```text
0x04 || X(32-byte unsigned big-endian) || Y(32-byte unsigned big-endian)
```

Encoding rules:

- strip a single BigInteger sign byte only when present;
- left-pad each coordinate with zero bytes to exactly 32 bytes;
- reject coordinates longer than 32 bytes after sign normalization;
- never emit compressed points (`0x02` or `0x03`);
- never accept hybrid encodings;
- Base64URL conversion is performed only at protocol/self-info boundaries;
- the identity model stores validated raw SEC1 bytes, not the Base64URL string.

Decoding rules:

- require exactly 65 bytes and prefix `0x04` before curve construction;
- parse X/Y as positive integers;
- verify `0 <= x,y < p`;
- verify the P-256 curve equation;
- reject the point at infinity and malformed coordinates;
- use an explicit P-256 `ECParameterSpec` when creating `ECPublicKeySpec`;
- never rely only on `KeyFactory.generatePublic()` to provide all validation.

## 7. Deterministic key ID

### 7.1 Purpose

The key ID allows:

- self-info recipients to identify the advertised recipient key;
- room-scoped TOFU to compare current and pinned identities;
- a private invitation envelope to bind ciphertext to the intended target key;
- the target to reject envelopes for a replaced or missing identity.

The key ID is not secret and is not a key derivation input by itself.

### 7.2 v1 derivation

GI2A2 locks this derivation:

```text
key_id_input = UTF8("peermesh/recipient-encryption-key-id/v1")
               || 0x00
               || UTF8("p256-hkdf-sha256-a256gcm")
               || 0x00
               || SEC1_UNCOMPRESSED_PUBLIC_KEY_65_BYTES

digest = SHA-256(key_id_input)
key_id = Base64URL_NoPadding(digest)
```

The full 32-byte digest is retained. The resulting unpadded Base64URL key ID is 43 characters, below
GI1's 128-byte target-key-ID limit.

No truncation is allowed in v1. Full length avoids introducing a collision policy and remains cheap in
self-info and invitation payloads.

### 7.3 Validation

Whenever an existing identity is loaded:

1. export and validate its public SEC1 point;
2. recompute the key ID;
3. require an exact constant-time byte/string equality with any persisted or requested key ID;
4. treat mismatch as invalid identity state, never silently repair metadata while a room is active.

The key ID must not contain:

- device ID;
- actor ID;
- participant ID;
- room ID;
- app-install timestamp;
- random metadata.

The same public key and algorithm must always produce the same key ID across devices and processes.

## 8. Android Keystore alias contract

The active v1 alias is fixed and installation-scoped:

```text
peermesh.group_invitation.recipient.p256.v1
```

Properties:

- no room ID or participant/device ID appears in the alias;
- one active recipient identity exists per app installation;
- the alias is versioned by purpose, curve, and contract generation;
- a future incompatible algorithm uses a new alias and explicit migration/review;
- application data removal or app uninstall naturally removes the identity;
- backup/restore must not assume the Keystore entry transfers with application data.

GI2 v1 does not maintain multiple active recipient aliases or historical private keys.

## 9. Key generation authorization

On API 31+, the implementation is expected to generate:

```text
provider: AndroidKeyStore
algorithm: EC
purposes: PURPOSE_AGREE_KEY
curve: secp256r1
user authentication required: false
validity window: unrestricted
attestation: not requested
StrongBox: not required
```

### 9.1 Why authentication is not required

Nearby Room must continue to receive/decrypt private invitations while the app is operating without a
biometric prompt per message. Requiring user authentication would introduce device-specific timeout,
lock-screen, and background failures into BLE convergence.

This does not mean device lock protection is disabled; it means GI2 does not add per-use user-auth
requirements.

### 9.2 Why StrongBox is not required

StrongBox availability is inconsistent and would unnecessarily suppress private invitations on many
otherwise capable devices. The contract requires Android Keystore non-exportability and successful
ECDH use, not a specific hardware security level.

Security level may be recorded in redacted diagnostics, but it must not change the v1 key ID or wire
algorithm.

## 10. Runtime usability probe

API level alone is insufficient because OEM providers may fail generation or key agreement.

Before advertising recipient-encryption capability, the provider must complete a local probe:

1. load or create the active Android Keystore keypair;
2. validate the exported public key and key ID;
3. generate a temporary in-memory P-256 peer keypair;
4. perform ECDH using the Keystore private key and temporary peer public key;
5. require a 32-byte shared secret;
6. clear temporary shared-secret bytes after validation;
7. do not persist the temporary peer private key.

The probe must be bounded and must not run on every self-info encode. Cache success for the current
process and active alias, invalidating the cache when key load/use fails.

A probe failure must suppress private recipient capability; it must not crash Nearby Room startup.

## 11. Provider result and failure model

Callers must receive explicit typed state rather than exceptions or nullable identity ambiguity.

Recommended semantic result:

```kotlin
sealed interface RecipientEncryptionIdentityState {
    data class Available(val identity: RecipientEncryptionIdentity) : RecipientEncryptionIdentityState
    data object UnsupportedPlatform : RecipientEncryptionIdentityState
    data class TemporarilyUnavailable(val reason: SafeReason) : RecipientEncryptionIdentityState
    data class Invalidated(val reason: SafeReason) : RecipientEncryptionIdentityState
}
```

Exact Kotlin naming may vary, but these semantic states are locked.

### 11.1 `Available`

Returned only when:

- platform/API is supported;
- alias exists or generation succeeds;
- public-key/curve/key-ID validation succeeds;
- runtime ECDH probe succeeds.

Only this state may cause recipient-encryption capability and key fields to be placed in self-info.

### 11.2 `UnsupportedPlatform`

Used for API 21–30 in GI2 v1.

Behavior:

- public invitations remain possible subject to normal mixed-version capability checks;
- private invitation target capability is absent;
- no generation attempt is made;
- no error-level logging or user alarm is required.

### 11.3 `TemporarilyUnavailable`

Examples:

- transient provider exception;
- Keystore temporarily unavailable;
- ECDH probe fails without proof of permanent invalidation;
- certificate/public entry cannot currently be loaded.

Behavior:

- do not advertise recipient key/capability;
- retain existing alias unless corruption is proven;
- retry only at bounded lifecycle points, not a tight loop;
- do not silently generate a replacement while an active alias may still recover.

### 11.4 `Invalidated`

Examples:

- alias exists but private entry is permanently unusable;
- public/private entry mismatch;
- wrong curve or wrong algorithm under the locked alias;
- recomputed key ID is inconsistent with current identity metadata;
- explicit security reset is requested.

Behavior:

- do not advertise recipient capability;
- do not silently replace the key while one or more Nearby Rooms are active;
- surface a stable internal state so GI2B/C can produce TOFU conflict-safe behavior;
- replacement requires the lifecycle policy below.

## 12. Lifecycle and rotation policy

### 12.1 First use

The identity is lazily created when Nearby Self Info first needs recipient capability, or at an
explicit bounded startup bootstrap point chosen in GI2A3.

Generation must be process-safe:

- synchronize creation within the process;
- recheck the alias after acquiring the lock;
- if concurrent generation races at provider level, load and validate the final alias;
- never overwrite an existing valid alias.

### 12.2 Normal lifetime

A valid identity persists for the app installation lifetime.

It is not room-scoped and is not regenerated when:

- a Nearby Room is created, joined, closed, expired, or purged;
- profile name or avatar changes;
- device/participant self-info timestamp changes;
- app process restarts;
- a private invitation is sent or received.

### 12.3 Periodic rotation

GI2 v1 has **no automatic periodic rotation**.

Periodic rotation would require historical private-key retention, delayed-message key selection,
self-info transition rules, TOFU replacement UX, and offline relay compatibility. Those are outside
GI2 and would reduce stability without a demonstrated requirement.

### 12.4 Explicit reset or permanent invalidation

Replacement policy:

```text
No active Nearby Rooms:
    delete invalid alias
    generate a new identity on next bounded request

One or more active Nearby Rooms:
    do not silently replace
    suppress recipient-encryption capability
    mark identity unavailable/invalidated
    require rooms to end or an explicit reviewed recovery action
```

Rationale: generating a new key during an active room would appear to peers as a TOFU replacement.
Silently doing so would either break decryptability or encourage silent pin replacement, both of which
are forbidden.

“Active Nearby Room” must use the repository's authoritative ACTIVE/non-expired state, not Activity or
BLE connection state.

### 12.5 App reinstall, data clear, or restore

A reinstalled/cleared app receives a new identity.

If application data is restored without the old Android Keystore entry:

- the old identity must be treated as missing/invalidated;
- persisted key ID metadata must not recreate trust;
- a new identity may be generated only under the active-room replacement policy;
- peers in an existing restored room must detect TOFU conflict rather than silently repin.

## 13. Persistence contract

The Android Keystore alias is the source of truth for the private key.

GI2A2 does not require a Room table for the local active identity.

Allowed non-secret metadata cache, if implementation proves it useful:

```text
algorithm
key_id
public_key_sec1
creation/version marker
last successful probe marker (process memory preferred)
```

Any persisted metadata is a cache only. On load it must be recomputed and verified against the
Keystore public entry.

Forbidden persistence:

- PKCS#8 private key bytes;
- raw ECDH shared secrets;
- HKDF output;
- derived invitation AES keys;
- ephemeral sender private keys;
- private key in SharedPreferences, EncryptedSharedPreferences, Room, files, logs, or backups.

## 14. Capability-advertisement contract for GI2B

GI2A2 does not modify self-info, but locks the future input:

```text
RecipientEncryptionIdentityState.Available
    -> advertise algorithm + key ID + public SEC1 key
    -> advertise recipient-encryption capability

UnsupportedPlatform / TemporarilyUnavailable / Invalidated
    -> omit recipient key fields
    -> omit recipient-encryption capability
```

Do not advertise an error field to peers. Absence means the participant is not currently eligible as
a private invitation target.

A profile capability must never be inferred only from API level or from a cached key ID. It requires a
currently validated `Available` identity.

## 15. Logging and diagnostics

Allowed:

- identity state enum;
- API level;
- alias contract version;
- shortened key-ID fingerprint for local diagnostics, for example first 8 characters;
- provider/security-level category;
- safe exception class or normalized reason;
- whether generation/load/probe succeeded.

Forbidden:

- private key bytes or encoded private key;
- ECDH shared secret;
- full public key in routine logs;
- full invitation credential or plaintext;
- raw provider dumps containing key material;
- repeated warning spam for an expected unsupported API level.

Expected API 21–30 behavior should be debug/info at most, not warning/error.

## 16. Required GI2A3 implementation tests

### 16.1 Pure JVM tests

`RecipientEncryptionKeyIdTest`:

- deterministic output;
- exact domain-separation label and `0x00` delimiters;
- algorithm change changes ID;
- one public-key byte change changes ID;
- full SHA-256 digest produces 43 unpadded Base64URL characters;
- no identifiers or room data influence ID;
- caller mutation cannot alter a constructed identity.

`P256Sec1PublicKeyCodecTest`:

- known valid P-256 point round-trip;
- X or Y with leading zero padding;
- reject 64/66-byte lengths;
- reject prefixes other than `0x04`;
- reject coordinate equal to or above field prime;
- reject off-curve point;
- reject point at infinity representation;
- reject compressed/hybrid encoding;
- deterministic raw bytes and Base64URL form.

### 16.2 Android instrumentation tests, API 31+

`AndroidKeystoreRecipientEncryptionIdentityProviderTest`:

- first call generates one alias;
- repeated calls return the same key ID/public key;
- process/provider re-instantiation returns the same identity;
- alias private key has no exportable encoded bytes;
- curve is P-256;
- SEC1 output is 65 bytes with `0x04` prefix;
- recomputed key ID matches;
- ECDH with an in-memory ephemeral peer succeeds;
- shared secret length is 32 bytes;
- concurrent calls converge to one identity;
- deleting alias produces a new identity only when lifecycle policy allows;
- malformed/wrong-purpose alias is rejected;
- provider failure maps to typed state without crash.

### 16.3 Android tests, API 21–30

- returns `UnsupportedPlatform`;
- does not attempt alias generation;
- does not advertise recipient capability;
- public invitation support remains unaffected;
- no warning/error log spam.

### 16.4 Lifecycle integration tests

- invalid alias with no active room can be replaced by explicit recovery flow;
- invalid alias with active room is not silently replaced;
- profile update does not rotate identity;
- room create/join/close does not rotate identity;
- app component recreation does not rotate identity;
- restored metadata without Keystore entry does not recreate the old identity.

## 17. Explicit non-goals

GI2A2 does not:

- implement Android Keystore code;
- implement fallback software private keys for API 21–30;
- implement self-info wire fields or ACK revision;
- implement TOFU persistence;
- implement sender ephemeral keys;
- implement ECDH/HKDF/AES-GCM invitation crypto;
- implement key history or periodic rotation;
- implement trust-reset UI;
- alter GI1 protocol limits or payload shapes;
- reuse the DTN manifest identity;
- add a generic identity framework.

## 18. Locked decisions

1. Recipient encryption uses a dedicated installation-scoped P-256 keypair.
2. The private key is Android Keystore-backed and non-exportable.
3. GI2 v1 recipient encryption is available only on API 31+ after a successful runtime ECDH probe.
4. API 21–30 receives no silent exportable-key fallback.
5. Public invitation capability remains independent of recipient-encryption capability.
6. Public keys use exact 65-byte SEC1 uncompressed encoding.
7. Key IDs use full SHA-256 over a versioned domain, algorithm, and SEC1 public key.
8. The v1 key ID is 43-character unpadded Base64URL; no truncation.
9. The alias is `peermesh.group_invitation.recipient.p256.v1`.
10. One active recipient identity exists per app installation.
11. No periodic rotation exists in GI2 v1.
12. A key is advertised only after load, validation, key-ID verification, and ECDH usability probe.
13. Invalidated keys are not silently replaced while any Nearby Room remains active.
14. The Keystore alias is private-key source of truth; persisted metadata is only a verified cache.
15. No private, shared-secret, derived-key, or ephemeral-private material may be persisted or logged.

## 19. Exit criteria

GI2A2 is complete when this contract is reviewed and accepted and there is no unresolved disagreement
about:

- API 31+ private-invitation support boundary;
- no software-key fallback in v1;
- SEC1 encoding and curve validation;
- deterministic full-length key ID;
- alias and installation-scoped lifecycle;
- invalidation/replacement behavior during active rooms;
- typed identity availability states;
- test obligations for GI2A3.

After acceptance, the next phase is:

```text
GI2A3 — Android Keystore Recipient Identity Implementation
```
