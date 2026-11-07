# GI2D — Private Invitation Cryptography Contract

**Status:** LOCKED — CLOSED

**Milestone:** GI2D — Private Invitation Cryptography

**Contract version:** 1

**Date:** 2026-08-04

**Implementation:** COMPLETE

**Closure evidence:** `GI2D_Closure_Evidence.md`

## 1. Verdict

```text
ARCHITECTURE REVIEW: PASS
INVARIANT CHECK: PASS
CONTRACT: LOCKED
PRODUCTION IMPLEMENTATION: AUTHORIZED WITHIN THIS CONTRACT
```

This contract freezes the minimum production boundary required to encrypt one private Group Invitation exactly once and to decrypt it only on the intended recipient identity under the intended immutable Nearby message context.

GI2D must remain a narrow cryptographic capability. It must not become an Invitation subsystem, transport subsystem, repository, replay database, or message lifecycle owner.

## 2. Authority and precedence

This contract inherits and must remain consistent with:

1. `Product_Architecture_Invariants.md`;
2. `Group_Invitation_Spec_v2.md` and its realigned form where applicable;
3. `GI1R_Group_Invitation_Architecture_Realignment.md`;
4. `Group_Invitation_Implementation_Roadmap_v2.md`;
5. GI2A recipient encryption identity contracts;
6. GI2B Nearby Self Info recipient-key distribution contracts;
7. `GI2C_Room_Scoped_Recipient_Key_Pinning_Contract_LOCKED.md`;
8. `GI2D_Architecture_Review_and_Invariant_Check.md`.

If an implementation choice conflicts with a Product Architecture Invariant, the invariant wins and implementation must stop.

## 3. Locked outcome

GI2D must provide this transformation:

```text
validated private invitation plaintext bytes
+ immutable authenticated context
+ validated pinned target recipient public identity
    -> one encrypted primitive result
```

and:

```text
encrypted primitive fields
+ byte-identical immutable authenticated context
+ local long-lived recipient private identity
+ current time
    -> authenticated plaintext bytes or typed failure
```

The GI2D exit criterion is satisfied only when tests prove:

- the intended recipient can decrypt;
- another recipient cannot decrypt;
- changing any bound context field prevents successful decryption;
- expired content is rejected before plaintext is returned;
- retry or relay can reuse the same inner envelope without regenerating it;
- no new persistence or lifecycle is introduced.

## 4. Scope

### 4.1 GI2D owns

- fresh ephemeral P-256 key generation per encryption operation;
- P-256 ECDH;
- HKDF-SHA256 extraction and expansion;
- AES-256-GCM encryption and authenticated decryption;
- canonical authenticated-context serialization;
- target recipient key-ID binding;
- local private-key resolution by expected target key ID;
- expiry rejection during decryption;
- cryptographic context-transplant resistance;
- typed failures;
- cleanup of mutable secret byte arrays;
- redacted diagnostics;
- focused JVM tests and Android Keystore integration tests.

### 4.2 GI2D does not own

- Group Invitation business rules;
- invitation quota or once-per-recipient enforcement;
- room or participant eligibility;
- TOFU pinning or key-conflict decisions;
- room-expiry calculation;
- Nearby message-ID allocation;
- Nearby Chat send, receive, retry, relay, ordering, persistence, or history;
- outer room-message encryption;
- message deduplication;
- invitation card persistence or materialization;
- timeline parsing or rendering;
- Join behavior;
- recipient identity creation, reset, rotation, or distribution.

These remain in their existing owners or in GI2E and later phases.

## 5. Product Architecture invariant mapping

| Invariant | Locked GI2D interpretation |
|---|---|
| Invitation is a Structured Nearby Chat Message | GI2D returns only inner encrypted fields; the normal message body remains owned by the Group Invitation protocol adapter and Nearby Chat. |
| Nearby Chat owns transport lifecycle | GI2D never sends, retries, relays, persists, orders, or deduplicates. |
| Security must not depend on Invitation business/domain | Security receives opaque bytes and primitive immutable context values only. It must not import Invitation payload/card/policy types. |
| Invitation may depend on Security | A thin adapter may encode/decode protocol models and invoke GI2D. |
| No new subsystem/persistence/lifecycle | GI2D is one narrow stateless production component plus immutable value/result types. |
| No extra business rules | GI2D validates only cryptographic structure, context integrity, target key identity, and expiry. |
| Stability over optimization | Use standard JCA primitives, explicit serialization, bounded APIs, and no provider-specific optimization. |

## 6. Dependency boundary

The required direction is:

```text
Group Invitation protocol/orchestration adapter
        |
        v
GI2D recipient cryptography component
        |
        +--> recipient identity provider
        |
        v
JCA / Android Keystore
```

### 6.1 Permitted Security inputs

The Security implementation may receive only:

- plaintext `ByteArray`;
- immutable primitive context fields;
- recipient algorithm;
- recipient key ID;
- recipient SEC1 public-key bytes;
- encrypted primitive byte fields;
- current time;
- recipient private-key provider result.

### 6.2 Forbidden Security dependencies

Production code in Security must not import or accept:

- `PrivateGroupInvitationInnerPayload`;
- `PrivateGroupInvitationEnvelope`;
- `InvitationCardInstance`;
- `InvitationCredential`;
- `InvitationAudience`;
- invitation quota/policy types;
- Nearby room or participant entities/DAOs/repositories;
- Nearby transport/message managers;
- Activity, Fragment, ViewModel, Compose, or Android UI types.

### 6.3 Bounded compatibility exception

The existing `RecipientEncryptionIdentity` currently uses `PrivateInvitationEncryptionAlgorithm` from `core.groupinvitation`.

GI2D may retain this single existing enum dependency in v1 to avoid destabilizing GI2A–GI2C. It must not expand the exception to any other Invitation-domain model. Removing this exception is not part of GI2D unless implementation proves it is required for correctness.

## 7. Minimal production component

One component is sufficient. The recommended locked semantic boundary is:

```kotlin
interface PrivateRecipientCrypto {
    fun encrypt(
        plaintext: ByteArray,
        recipientIdentity: RecipientEncryptionIdentity,
        context: PrivateRecipientCryptoContext,
    ): PrivateRecipientEncryptResult

    fun decrypt(
        encrypted: PrivateRecipientEncryptedPayload,
        context: PrivateRecipientCryptoContext,
        nowEpochMs: Long,
    ): PrivateRecipientDecryptResult
}
```

Exact class names may differ only if repository naming conventions strongly require it. Semantics and ownership must not differ.

The production implementation should be a single concrete JCA-backed component. Do not add a manager hierarchy, repository, coordinator, use-case framework, registry, plugin system, or generic crypto framework.

## 8. Immutable input models

### 8.1 Authenticated context

The neutral immutable context contains exactly:

```kotlin
data class PrivateRecipientCryptoContext(
    val protocolVersion: Int,
    val algorithmWireValue: String,
    val roomId: String,
    val messageId: String,
    val cardId: String,
    val logicalSenderId: String,
    val targetParticipantId: String,
    val targetKeyId: String,
    val effectiveRoomExpiryEpochMs: Long,
)
```

All properties must be immutable.

The caller must resolve `logicalSenderId` using the locked Nearby semantic:

```text
origin_device_id ?: sender_device_id
```

The last-hop relay sender must never be used.

The caller must resolve effective room expiry exactly once before encryption. GI2D must not query or recalculate room expiry.

### 8.2 Encrypted primitive payload

The neutral encrypted input/output contains exactly:

```kotlin
data class PrivateRecipientEncryptedPayload(
    val algorithmWireValue: String,
    val targetKeyId: String,
    val ephemeralPublicKeySec1: ByteArray,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
)
```

The production type must protect against accidental mutable-array aliasing by defensive copying on construction and access, or by an equivalent repository-standard immutable-byte wrapper.

It must not contain:

- card title;
- description;
- credential semantics;
- room entity;
- message entity;
- ephemeral private key;
- shared secret;
- derived AES key.

## 9. Input validation

### 9.1 Caller/protocol adapter responsibilities

Before calling encrypt, the Group Invitation adapter must:

- construct a valid `PrivateGroupInvitationInnerPayload`;
- encode it only with `GroupInvitationProtocol.encodePrivateInnerPayload`;
- convert the canonical string to UTF-8 explicitly;
- provide a GI2C-validated pinned `RecipientEncryptionIdentity` snapshot;
- allocate the outer Nearby `messageId`;
- resolve immutable effective room expiry;
- provide all exact context values.

After encrypt, the adapter must:

- Base64URL-no-padding encode the primitive fields;
- construct `PrivateGroupInvitationEnvelope`;
- serialize only with `GroupInvitationProtocol.encodePrivateEnvelope`;
- perform the existing final wire-budget validation through that constructor/encoder path.

Before calling decrypt, the adapter must:

- parse only with `GroupInvitationProtocol.decode`;
- accept only `PrivateEncryptedInvitation`;
- Base64URL-no-padding decode the three encrypted fields;
- reconstruct the same immutable context from authoritative message metadata and envelope fields.

After successful decrypt, the adapter must:

- decode UTF-8 strictly;
- parse only with `GroupInvitationProtocol.decodePrivateInnerPayload`;
- reject malformed or non-canonical/unsupported inner payload through the existing protocol result path;
- never render raw plaintext, JSON, key material, or ciphertext.

### 9.2 GI2D cryptographic validation

GI2D must fail without performing encryption/decryption when any of these conditions is present:

- unsupported protocol version;
- unsupported algorithm wire value;
- blank context identity field;
- negative `effectiveRoomExpiryEpochMs`;
- context `algorithmWireValue` differs from encrypted/recipient algorithm;
- context `targetKeyId` differs from encrypted/recipient target key ID;
- invalid target key-ID form according to the existing recipient identity contract;
- recipient public key is not exactly 65-byte SEC1 uncompressed P-256 form beginning with `0x04`;
- ephemeral public key is not exactly 65-byte SEC1 uncompressed P-256 form beginning with `0x04`;
- nonce is not exactly 12 bytes;
- ciphertext is shorter than the 16-byte GCM tag;
- any canonical context string exceeds unsigned 32-bit UTF-8 length representation.

GI2D must not duplicate title, description, credential, card-ID syntax, participant-ID syntax, or final JSON wire-budget policy already owned by the Group Invitation protocol.

## 10. Canonical authenticated context v1

### 10.1 Purpose

The canonical context binds ciphertext to one exact logical invitation message. It is used:

1. as HKDF `info` input through a domain-separated framing;
2. directly as AES-GCM Additional Authenticated Data.

Encrypt and decrypt must generate byte-identical context bytes.

### 10.2 Encoding primitives

All integer values are unsigned big-endian except expiry, which is a non-negative signed 64-bit big-endian epoch-millisecond value.

Definitions:

```text
U8(x)      = one byte
U16BE(x)   = two bytes, unsigned big-endian
U32BE(x)   = four bytes, unsigned big-endian
I64BE(x)   = eight bytes, signed big-endian; value must be >= 0
UTF8(s)    = strict UTF-8 bytes of s, with no Unicode normalization
TEXT(s)    = U32BE(length(UTF8(s))) || UTF8(s)
```

No platform-default charset, locale transform, case folding, trimming, Unicode normalization, JSON map iteration, delimiter concatenation, or newline framing is permitted.

### 10.3 Exact context bytes

```text
context_v1 =
    ASCII("PMGI")                                      // 4 bytes
 || U8(0x01)                                           // context format version
 || U16BE(protocolVersion)                             // v1 = 0x0001
 || TEXT(algorithmWireValue)
 || TEXT(roomId)
 || TEXT(messageId)
 || TEXT(cardId)
 || TEXT(logicalSenderId)
 || TEXT(targetParticipantId)
 || TEXT(targetKeyId)
 || I64BE(effectiveRoomExpiryEpochMs)
```

For GI2D v1:

```text
protocolVersion       = 1
algorithmWireValue    = "p256-hkdf-sha256-a256gcm"
context format version = 1
```

No optional fields exist. Field order is fixed. A future context format must use a new context-format version and must not reinterpret v1 bytes.

### 10.4 Binding requirements

Changing any one of these values must prevent successful authenticated decryption:

- protocol version;
- algorithm;
- room ID;
- message ID;
- card ID;
- logical sender ID;
- target participant ID;
- target key ID;
- effective room expiry.

The envelope's `targetParticipantId`, `cardId`, `algorithm`, and `targetKeyId` must be used to reconstruct the context and must be consistent with authoritative message metadata. A mismatch must fail closed.

## 11. Cryptographic construction v1

### 11.1 Algorithms and sizes

```text
Recipient curve                 P-256 / secp256r1 / prime256v1
Ephemeral curve                 P-256 / secp256r1 / prime256v1
Key agreement                   ECDH
KDF                             HKDF-SHA256
Content encryption              AES-256-GCM
AES key size                    32 bytes
Nonce size                      12 bytes
GCM authentication tag          16 bytes / 128 bits
Ephemeral public-key wire form  SEC1 uncompressed, 65 bytes, prefix 0x04
```

No algorithm fallback is allowed.

### 11.2 Fresh randomness

Every encryption call must generate:

- one fresh ephemeral P-256 keypair;
- one fresh 12-byte nonce from `SecureRandom`.

The implementation must not accept caller-provided ephemeral private keys or production nonces.

Test-only deterministic seams may be injected at package-private/internal scope. They must not be exposed through the production interface or used by production wiring.

### 11.3 ECDH

Sender encryption:

```text
sharedSecret = ECDH(ephemeralPrivateKey, recipientPinnedPublicKey)
```

Recipient decryption:

```text
sharedSecret = ECDH(localRecipientPrivateKey, envelopeEphemeralPublicKey)
```

The raw P-256 ECDH secret must be exactly 32 bytes. Any provider result of another length is a cryptographic failure; it must not be silently padded, truncated, or transformed.

Public points must be decoded and validated as P-256 points through the existing `P256Sec1PublicKeyCodec` or one shared equivalent implementation. Do not create a second inconsistent SEC1 codec.

### 11.4 HKDF-SHA256

Domain-separation label:

```text
ASCII("peermesh/group-invitation/private/v1")
```

The exact derivation is:

```text
salt = 32 zero bytes
PRK  = HMAC-SHA256(key = salt, data = sharedSecret)

info =
    ASCII("peermesh/group-invitation/private/v1")
 || U8(0x00)
 || U32BE(length(context_v1))
 || context_v1

OKM = HKDF-Expand-SHA256(PRK, info, 32)
aesKey = OKM
```

Because only 32 bytes are requested:

```text
T1 = HMAC-SHA256(key = PRK, data = info || U8(0x01))
OKM = first 32 bytes of T1
```

The zero salt is the RFC 5869 default semantic made explicit as 32 bytes for deterministic cross-provider behavior.

The context appears in HKDF `info` and again as AES-GCM AAD intentionally. HKDF provides key separation between contexts; AAD provides direct authenticated binding.

Do not use:

- the room ID, participant ID, device ID, card ID, message ID, or key ID as a key;
- raw SHA-256 of the shared secret as the AES key;
- PBKDF2;
- platform-specific HKDF APIs with ambiguous defaults;
- a random or persisted HKDF salt;
- truncated context;
- context JSON.

### 11.5 AES-256-GCM

Encryption is exactly:

```text
cipher = AES/GCM/NoPadding
key    = aesKey
nonce  = fresh 12-byte nonce
tag    = 128 bits
AAD    = context_v1
output = ciphertext || 16-byte tag
```

The protocol field `ciphertext` contains the JCA `doFinal` output including the GCM tag. The tag is not stored as a separate wire field.

Decryption must call `updateAAD(context_v1)` before `doFinal(ciphertextAndTag)`.

Any tag failure, modified ciphertext, wrong nonce, wrong ephemeral key, wrong recipient key, or changed context must not return plaintext.

## 12. Encrypt operation contract

The encrypt operation must execute in this order:

1. validate context and recipient identity consistency;
2. decode/validate recipient SEC1 P-256 public key;
3. create context bytes;
4. generate fresh ephemeral P-256 keypair;
5. generate fresh 12-byte nonce;
6. perform ECDH;
7. derive the 32-byte AES key by the exact HKDF contract;
8. encrypt plaintext with AES-256-GCM and context bytes as AAD;
9. encode ephemeral public key to 65-byte SEC1 form;
10. return immutable encrypted primitive fields;
11. clear mutable shared-secret, PRK, AES-key/OKM, and temporary plaintext copies created internally in `finally`.

The caller-owned plaintext array must not be modified. GI2D must not retain it after return.

One successful call produces one stable inner encrypted payload. The caller must persist/reuse that exact result for retry and relay. It must not call encrypt again for the same logical message.

## 13. Decrypt operation contract

The decrypt operation must execute in this order:

1. validate context and encrypted primitive structure;
2. reject when `nowEpochMs >= effectiveRoomExpiryEpochMs`;
3. verify exact algorithm consistency;
4. verify exact target-key-ID consistency;
5. ask the existing identity provider for the local private key using `targetKeyId`;
6. map provider status to the locked typed failure taxonomy;
7. decode/validate ephemeral SEC1 P-256 public key;
8. create context bytes;
9. perform ECDH with the non-exportable local private key;
10. derive the same 32-byte AES key;
11. authenticate and decrypt with context bytes as AAD;
12. return plaintext only after successful GCM authentication;
13. clear mutable shared-secret, PRK, AES-key/OKM, and unauthenticated temporary output in `finally`.

Expiry must be checked before private-key lookup and before ECDH to avoid unnecessary Keystore work. Authentication must still bind expiry because the expiry value is part of `context_v1`.

No plaintext may be returned on partial decryption, provider exception, tag failure, or any validation failure.

## 14. Expiry semantics

The exact boundary is:

```text
valid   when nowEpochMs < effectiveRoomExpiryEpochMs
expired when nowEpochMs >= effectiveRoomExpiryEpochMs
```

No grace period, clock-skew allowance, fallback expiry, or implicit extension is permitted in GI2D.

GI2D does not decide whether an expired opaque message remains visible in history. It only refuses to return decrypted plaintext after the boundary.

Tests must inject `nowEpochMs`; production code must not hide a global wall-clock call inside the cryptographic primitive.

## 15. Replay and duplicate ownership

### 15.1 GI2D cryptographic replay/transplant resistance

GI2D prevents an encrypted payload from being successfully transplanted into a different authenticated context. It must fail when any bound field changes.

### 15.2 Nearby Chat logical duplicate ownership

An exact replay with the exact same:

```text
roomId + messageId + envelope + context
```

is cryptographically valid by design.

Logical duplicate suppression remains owned by Nearby Chat's existing message identity and persistence semantics, including the existing `(roomId, messageId)` behavior.

GI2D must not introduce:

- replay database/table;
- nonce ledger;
- seen-envelope cache;
- invitation replay repository;
- cleanup worker/timer;
- new retention policy.

## 16. Recipient-only decryption

Encryption must use only the GI2C-pinned target public identity tuple:

```text
(algorithm, keyId, publicKeySec1)
```

Decryption must resolve only the local long-lived recipient private key matching the envelope/context `targetKeyId`.

The following must fail closed:

- local identity belongs to another key ID;
- local identity was reset or invalidated;
- local private key is unavailable;
- recipient public key and key ID are inconsistent;
- target participant or key ID was altered;
- another participant copies the envelope into its own room/message context.

The sender must not decrypt its own wire envelope to render its local sent card. GI2E must retain/use the sender's local structured source data through existing local message representation rules, without introducing a new Invitation persistence subsystem in GI2D.

Relay nodes treat the envelope as opaque message content.

## 17. Result and failure taxonomy

Failures must be typed and must not rely on exception-message parsing.

### 17.1 Encrypt results

```kotlin
sealed interface PrivateRecipientEncryptResult {
    data class Success(val payload: PrivateRecipientEncryptedPayload) : PrivateRecipientEncryptResult

    data object UnsupportedProtocolVersion : PrivateRecipientEncryptResult
    data object UnsupportedAlgorithm : PrivateRecipientEncryptResult
    data object InvalidContext : PrivateRecipientEncryptResult
    data object InvalidRecipientIdentity : PrivateRecipientEncryptResult
    data object KeyAgreementFailed : PrivateRecipientEncryptResult
    data object CryptoUnavailable : PrivateRecipientEncryptResult
}
```

### 17.2 Decrypt results

```kotlin
sealed interface PrivateRecipientDecryptResult {
    data class Success(val plaintext: ByteArray) : PrivateRecipientDecryptResult

    data object Expired : PrivateRecipientDecryptResult
    data object UnsupportedProtocolVersion : PrivateRecipientDecryptResult
    data object UnsupportedAlgorithm : PrivateRecipientDecryptResult
    data object InvalidContext : PrivateRecipientDecryptResult
    data object InvalidEncryptedPayload : PrivateRecipientDecryptResult
    data object NotForLocalRecipientKey : PrivateRecipientDecryptResult
    data class RecipientIdentityUnavailable(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : PrivateRecipientDecryptResult
    data class RecipientIdentityInvalidated(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : PrivateRecipientDecryptResult
    data object UnsupportedPlatform : PrivateRecipientDecryptResult
    data object AuthenticationFailed : PrivateRecipientDecryptResult
    data object KeyAgreementFailed : PrivateRecipientDecryptResult
    data object CryptoUnavailable : PrivateRecipientDecryptResult
}
```

Exact Kotlin nesting may adapt to existing provider types, but externally observable distinctions must remain.

### 17.3 Mapping rules

- key-ID mismatch from the identity provider → `NotForLocalRecipientKey`;
- provider unsupported API/platform → `UnsupportedPlatform`;
- provider temporary/unavailable result → `RecipientIdentityUnavailable(reason)`;
- provider invalidated result → `RecipientIdentityInvalidated(reason)`;
- GCM tag failure → `AuthenticationFailed`;
- malformed key/nonce/ciphertext/context → validation result, not `AuthenticationFailed`;
- JCA algorithm/provider unavailable → `CryptoUnavailable`;
- ECDH execution/provider failure after valid inputs → `KeyAgreementFailed` unless provider identity status gives a more specific result.

Do not expose raw provider exceptions through public result objects.

## 18. Error handling and exception policy

The production API must not throw for untrusted wire input or expected identity states. Those conditions return typed results.

Programmer-contract violations may use constructor `require` only for internal immutable values that cannot be built from untrusted data without a prior result-based parser. Prefer result-based factories for byte payloads originating from the wire.

Catch only the required JCA/Keystore exception families and map them deliberately. Do not use broad exception handling to convert programming defects into authentication failures.

No fallback algorithm or software-exportable recipient private key is allowed.

## 19. Secret handling

The implementation must:

- keep the long-lived recipient private key non-exportable in Android Keystore;
- never serialize or persist an ephemeral private key;
- never return an ephemeral private key;
- never return the ECDH shared secret, PRK, or AES key;
- clear mutable arrays holding shared secret, PRK, OKM/AES key, and internal temporary plaintext/ciphertext copies in `finally`;
- avoid unnecessary copies of secrets;
- not claim that JVM object zeroization is absolute;
- ensure no secret appears in `toString`, logs, test failure messages, analytics, crash breadcrumbs, or metrics.

The ephemeral `PrivateKey` object cannot be reliably zeroized by application code; it must remain local to the encrypt call and become unreachable immediately after return.

## 20. Logging and observability

Allowed diagnostic fields:

- operation: encrypt/decrypt;
- coarse result category;
- protocol/context version;
- algorithm enum/wire value;
- non-secret byte lengths;
- recipient identity status reason;
- API/platform support state.

Must not log:

- plaintext;
- title, description, credential;
- ciphertext;
- nonce;
- ephemeral public key;
- recipient public key;
- target key ID in full;
- shared secret;
- PRK or AES key;
- private keys;
- full canonical context bytes;
- raw JCA exception messages where provider text may expose material.

A stable opaque operation identifier is unnecessary in GI2D. Existing Nearby message ID logging policy remains with Nearby Chat.

## 21. Wire/protocol mapping

The adapter mapping is exactly:

```text
PrivateGroupInvitationInnerPayload
    -> GroupInvitationProtocol.encodePrivateInnerPayload(...)
    -> UTF-8 bytes
    -> PrivateRecipientCrypto.encrypt(...)
    -> Base64URL-no-padding primitive fields
    -> PrivateGroupInvitationEnvelope
    -> GroupInvitationProtocol.encodePrivateEnvelope(...)
```

and:

```text
Nearby message body
    -> GroupInvitationProtocol.decode(...)
    -> PrivateGroupInvitationEnvelope
    -> Base64URL-no-padding primitive bytes
    -> PrivateRecipientCrypto.decrypt(...)
    -> strict UTF-8
    -> GroupInvitationProtocol.decodePrivateInnerPayload(...)
```

GI2D must not alter GI1 wire field names, version, algorithm value, Base64URL rules, nonce size, SEC1 representation, ciphertext representation, or budgets.

## 22. Backward compatibility

GI2D implements only protocol version 1 and algorithm:

```text
p256-hkdf-sha256-a256gcm
```

Unsupported versions/algorithms must fail closed with typed results. They must not be interpreted as v1 and must not trigger fallback.

GI2D must not change public invitation encoding/decoding or normal Nearby Chat message handling.

## 23. Test contract

Implementation is not complete without all applicable focused tests below.

### 23.1 Canonical context JVM tests

Must prove exact bytes for at least one golden vector and boundaries for:

- ASCII values;
- multibyte UTF-8 values;
- empty/blank rejection where required;
- fixed field order;
- exact U32 length prefixes;
- exact big-endian protocol version and expiry;
- no locale dependence;
- no platform-default charset dependence;
- each single-field mutation changes context bytes.

The test must assert the full context hex/Base64 value, not only round-trip equality.

### 23.2 HKDF JVM tests

Must include independent RFC-style or externally computed golden vectors for:

- exact zero salt;
- exact label framing;
- exact context-length framing;
- exact 32-byte output;
- context mutation changing output;
- no accidental use of context as salt;
- no accidental raw-SHA256 derivation.

At least one vector must use a fixed 32-byte shared secret and fixed context with an exact expected AES key.

### 23.3 AES-GCM deterministic JVM tests

With test-only fixed private/public keys and nonce, prove exact:

- ephemeral SEC1 bytes;
- derived AES key through the HKDF unit seam;
- ciphertext-and-tag bytes;
- successful decrypt;
- tag length and nonce length.

Production encryption tests must separately prove fresh ephemeral keys/nonces across repeated calls; they must not expect equal ciphertext.

### 23.4 Context binding negative matrix

Starting from one valid encrypted payload, mutate each field separately and require failure:

- protocol version;
- algorithm;
- room ID;
- message ID;
- card ID;
- logical sender ID;
- target participant ID;
- target key ID;
- expiry.

Also mutate separately:

- nonce;
- ciphertext byte;
- tag byte;
- ephemeral public-key byte;
- recipient public key/key ID at encryption input.

No mutation may return plaintext.

### 23.5 Expiry tests

Must prove:

```text
now = expiry - 1 -> may decrypt
now = expiry     -> Expired
now = expiry + 1 -> Expired
```

Expired paths must not call private-key lookup or ECDH. Use a seam/fake to assert this.

### 23.6 Recipient-only tests

Must prove:

- intended software P-256 recipient can decrypt in JVM tests;
- second P-256 recipient cannot decrypt;
- wrong target key ID returns `NotForLocalRecipientKey` without decryption;
- invalidated/unavailable/unsupported identity provider states map exactly;
- sender-side ephemeral private key is not exposed or persisted.

### 23.7 Structural validation tests

Must reject:

- compressed P-256 point;
- wrong SEC1 prefix;
- off-curve point;
- wrong key length;
- nonce lengths 0, 11, 13;
- ciphertext shorter than 16 bytes;
- unsupported version/algorithm;
- mismatched context/envelope algorithm;
- mismatched context/envelope target key ID.

### 23.8 Android instrumentation tests

On a real or instrumentation-supported Android API 31+ environment, must prove:

- existing Android Keystore recipient private key performs ECDH with an ephemeral software P-256 public key;
- full GI2D decrypt succeeds using the non-exportable Keystore private key;
- another local identity/key ID cannot decrypt;
- key deletion/invalidation maps to the locked failure result;
- no private encoded bytes are exposed.

The pre-existing unrelated M3D2 AndroidTest compile failures may remain a separately documented verified limitation only if they still block the app-level suite. GI2D must still add focused module-level tests that compile independently where the build structure permits.

### 23.9 Protocol adapter tests

Must prove:

- canonical inner payload encoding is the plaintext encrypted;
- encrypted primitive fields map exactly to `PrivateGroupInvitationEnvelope`;
- final private envelope remains within existing wire budget;
- successful decrypt parses through `decodePrivateInnerPayload`;
- malformed authenticated plaintext is rejected by protocol parsing;
- no duplicate JSON codec or alternate wire shape is introduced.

### 23.10 Architecture tests/review checks

Must verify by source review or architecture test:

- Security does not import forbidden Invitation models;
- GI2D does not import Nearby DAOs/repositories/transport/UI;
- no new database entity/table/migration is added;
- no replay cache/store/worker is added;
- `NearbyRoomMessageCrypto` remains separate;
- GI2D has no send/retry/relay calls.

## 24. Production implementation file boundary

Expected minimal additions are limited to the Security module and focused tests, plus at most a thin adapter in the existing Group Invitation integration owner when required by the repository layout.

A reasonable implementation set is:

```text
security/.../groupinvitation/PrivateRecipientCrypto.kt
security/.../groupinvitation/JcaPrivateRecipientCrypto.kt
security/.../groupinvitation/PrivateRecipientCryptoContext.kt
security/.../groupinvitation/PrivateRecipientCryptoResult.kt
security/.../groupinvitation/HkdfSha256.kt
```

These names are illustrative, not a requirement to split every type into a separate file. Prefer fewer cohesive files.

Do not add production files for:

- repository;
- DAO/entity/migration;
- lifecycle manager;
- replay manager;
- transport adapter;
- background worker;
- invitation state machine;
- generic crypto registry.

## 25. GI2E boundary

The following are explicitly deferred to GI2E:

- choosing the private invitation send action;
- enforcing once-per-recipient rule;
- resolving target participant and pinned identity from current room state;
- allocating `messageId`;
- resolving effective room expiry;
- invoking encrypt exactly once;
- constructing/persisting the structured Nearby message;
- keeping sender-local renderable representation through existing message ownership;
- receiving, identifying, and invoking decrypt;
- timeline materialization;
- retry and relay verification.

GI2D tests may use test adapters to prove cryptographic interoperability, but production GI2D must not implement these orchestration responsibilities.

## 26. Stop conditions

Implementation must stop and return to architecture review if it appears to require any of the following:

- a new database table/entity/migration;
- a replay cache or cleanup lifecycle;
- changes to Nearby Chat retry/relay/ordering ownership;
- changes to `NearbyRoomMessageCrypto` to understand Invitation;
- Security importing Invitation payload/card/policy models;
- a second recipient identity or key store;
- exportable software fallback for the long-lived recipient private key;
- algorithm fallback;
- re-encryption of the private inner payload on retry;
- business rules not present in Product Specification;
- a generic cryptography framework without a second proven use case.

## 27. Implementation authorization

Production implementation is authorized only within this locked boundary.

Required workflow from here:

```text
Production Implementation
        -> Independent Review
        -> Required Fixes
        -> Focused Tests
        -> GI2D Closure Evidence
```

Any deviation from the canonical context bytes, HKDF construction, AES-GCM representation, expiry boundary, dependency direction, or ownership rules is a contract change and requires explicit architecture review before code proceeds.
