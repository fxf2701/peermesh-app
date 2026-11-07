# GI2D — Architecture Review + Invariant Check

**Status:** REVIEW COMPLETE — CONTRACT REQUIRED BEFORE IMPLEMENTATION  
**Milestone:** GI2D — Private Invitation Cryptography  
**Review date:** 2026-08-04

## 1. Verdict

```text
ARCHITECTURE REVIEW: PASS WITH REQUIRED CONTRACT CORRECTIONS
INVARIANT CHECK: PASS CONDITIONALLY
PRODUCTION IMPLEMENTATION: NOT YET AUTHORIZED
```

GI2D is architecturally feasible without creating a new transport, persistence subsystem, invitation lifecycle, or replay store.

Implementation must not begin until a dedicated GI2D contract freezes the cryptographic API, canonical context encoding, HKDF construction, failure model, expiry semantics, and the exact boundary between cryptographic context binding and Nearby Chat duplicate handling.

## 2. Locked scope

GI2D owns only one-time recipient-confidential transformation of a private invitation inner payload:

```text
validated plaintext + immutable message context + pinned recipient public identity
    -> one stable private envelope

stable private envelope + immutable message context + local recipient private identity
    -> authenticated plaintext or typed failure
```

GI2D includes:

- ephemeral P-256 key generation;
- ECDH;
- HKDF-SHA256;
- AES-256-GCM;
- canonical AAD/context serialization;
- target key-ID binding;
- cryptographic transplant/replay resistance;
- expiry validation at decryption;
- recipient-only decryption;
- secret cleanup and redacted diagnostics;
- focused JVM/security tests and Android Keystore integration tests where required.

GI2D does not include:

- Nearby Chat send or receive integration;
- message-row creation;
- message-ID allocation;
- room-expiry resolution;
- invitation send-limit enforcement;
- timeline classification or rendering;
- card materialization persistence;
- transport retry/relay logic;
- a replay-cache table or invitation repository;
- invitation UI or Join behavior.

Those concerns remain owned by existing layers or later GI2E phases.

## 3. Current architecture findings

### 3.1 Existing protocol boundary is suitable

`core.groupinvitation` already owns:

- `PrivateGroupInvitationInnerPayload`;
- `PrivateGroupInvitationEnvelope`;
- strict compact canonical JSON encoding/decoding;
- payload and wire budgets;
- exact v1 algorithm wire value;
- nonce, ephemeral key and ciphertext structural validation.

GI2D should consume these validated models and codecs. It must not create a second JSON shape or duplicate protocol validation inside the security module.

### 3.2 Existing recipient identity boundary is suitable

The security module already owns:

- Android Keystore P-256 recipient keypair;
- SEC1 uncompressed public-key validation and encoding;
- deterministic recipient key ID;
- public identity snapshot;
- private-key resolution by expected key ID;
- API 31+ platform restriction and invalidation taxonomy.

GI2D should build on this provider and must not introduce another recipient keypair, alias, metadata store, rotation flow, or generic asymmetric key registry.

### 3.3 Existing key pinning is the authoritative sender-side input

GI2C stores the first valid recipient identity on the existing room participant row as one indivisible tuple:

```text
(algorithm, keyId, publicKeySec1)
```

GI2D encryption must receive an already validated pinned identity snapshot from the caller. GI2D must not query the participant database itself and must not decide TOFU, conflict, room state, or participant eligibility.

### 3.4 Existing Nearby message identity supports stable encryption

The current architecture allocates `message_id` before outer room encryption and persists the logical message body before transport attempts.

Therefore GI2E can call GI2D exactly once after allocating the message ID and resolving effective expiry, then persist the resulting private envelope as the stable logical body. Existing direct retry and MESSAGE_DATA replay can re-encrypt only the outer room layer.

No new message-ID reservation framework is required.

### 3.5 Existing outer room crypto must remain separate

`NearbyRoomMessageCrypto` binds the normal room-message envelope and may produce fresh outer nonces on retry.

GI2D inner crypto must not be merged into `NearbyRoomMessageCrypto`, because doing so would:

- blur room confidentiality and recipient confidentiality;
- risk re-encrypting the inner payload on retry;
- make normal Nearby Chat crypto depend on Invitation;
- weaken independent testability and ownership.

A separate narrow security component is required.

## 4. Required dependency boundary

The desired dependency direction remains:

```text
Invitation orchestration / GI2E
        |
        v
GI2D security API
        |
        v
JCA + recipient identity provider
```

The GI2D security implementation may know only:

- cryptographic primitives;
- recipient encryption identity;
- opaque plaintext bytes;
- opaque authenticated context bytes or a neutral immutable crypto-context model;
- protocol algorithm/version constants strictly needed for interoperability.

It must not know:

- Invitation Card;
- group title or description semantics;
- Join credential semantics;
- send limits;
- timeline state;
- Nearby Room repository;
- participant DAO;
- transport manager;
- UI.

### 4.1 Existing coupling that must not expand

The current security identity model imports `PrivateInvitationEncryptionAlgorithm` from `core.groupinvitation`, and the package is named `security.groupinvitation`.

This has not yet introduced business/UI coupling, but it is a dependency-direction pressure point. GI2D must not add references from Security to:

- `PrivateGroupInvitationInnerPayload`;
- `PrivateGroupInvitationEnvelope`;
- `InvitationCardInstance`;
- `InvitationAudience`;
- invitation policy or limits unrelated to primitive sizes.

The GI2D contract must choose one narrow resolution:

1. **Preferred:** security operates on opaque bytes plus a neutral crypto algorithm/context type; Invitation performs protocol serialization outside Security.
2. **Acceptable minimal compatibility:** retain the existing shared algorithm enum temporarily, but prohibit every additional Invitation-domain dependency and record this as a bounded compatibility exception.

Do not create a generic cryptography framework merely to remove one import.

## 5. Recommended component boundary

A single narrow production component is sufficient, for example:

```text
PrivateRecipientCrypto
```

Conceptual API:

```kotlin
encrypt(
    plaintext: ByteArray,
    recipientIdentity: RecipientEncryptionIdentity,
    context: RecipientCryptoContext,
): EncryptResult

decrypt(
    encrypted: RecipientEncryptedPayload,
    context: RecipientCryptoContext,
    nowMs: Long,
): DecryptResult
```

The exact names are not locked by this review.

The component should own:

- ephemeral key generation;
- ECDH;
- key derivation;
- AES-GCM;
- target key-ID verification;
- context serialization or consumption;
- local recipient private-key lookup;
- expiry rejection;
- secret cleanup;
- typed cryptographic failures.

Protocol adapters outside Security should map between:

```text
PrivateGroupInvitationInnerPayload <-> plaintext bytes
PrivateGroupInvitationEnvelope     <-> encrypted primitive fields
```

No repository, manager, coordinator, use-case hierarchy, factory graph, or second lifecycle is justified in GI2D.

## 6. Canonical context requirements

The immutable authenticated context must bind exactly the GI1-required values:

```text
protocol version
encryption algorithm
room id
outer Nearby message id
card id
logical sender participant/device id
target participant id
target recipient key id
effective room expiry
```

Requirements:

- sender identity uses `origin_device_id ?: sender_device_id` semantics;
- effective expiry is resolved once by the room/message creation owner before GI2D is called;
- encrypt and decrypt use byte-identical canonical serialization;
- variable-length strings must use versioned canonical structured encoding or explicit length prefixes;
- newline/string concatenation from outer room AAD must not be reused;
- every changed field must produce authentication failure or pre-authentication rejection;
- no locale-sensitive, platform-default charset, map iteration order, or JSON pretty-print behavior is permitted.

The contract must freeze one exact byte encoding and include golden vectors.

## 7. HKDF construction must be explicitly locked

Upstream documents lock only the domain-separation label:

```text
peermesh/group-invitation/private/v1
```

They do not fully freeze:

- HKDF salt bytes;
- whether canonical context participates in `info`;
- exact label/context framing;
- output extraction behavior;
- JCA/provider implementation details.

GI2D must not infer these values in production code. The contract must specify the complete HKDF construction and test vectors first.

Recommended architecture principle:

- ECDH shared secret is input keying material;
- the dedicated versioned label provides domain separation;
- canonical immutable context participates unambiguously in derivation and is also passed as AES-GCM AAD;
- output is exactly 32 bytes for AES-256;
- temporary shared-secret and derived-key byte arrays are cleared in `finally` blocks.

The exact bytes remain a contract decision, not an implementation assumption.

## 8. Replay and duplicate semantics

The phrase “replay protection” must be split into two concerns.

### 8.1 GI2D-owned cryptographic replay/context resistance

GI2D must prevent a captured ciphertext from being transplanted into a different:

- room;
- message ID;
- sender;
- target;
- card;
- target key;
- expiry;
- algorithm/version context.

This is achieved through canonical context binding in HKDF/AAD.

### 8.2 Nearby Chat-owned duplicate delivery handling

A byte-identical envelope replayed with the same authenticated message ID and context may still authenticate cryptographically. Suppressing duplicate logical messages belongs to existing Nearby Chat persistence/reducer identity `(roomId, messageId)` and later GI2E ingestion.

GI2D must not create:

- a nonce ledger;
- a decrypted-card replay table;
- an invitation-specific seen-message cache;
- a new cleanup lifecycle.

This distinction is required to preserve Nearby Chat ownership and Minimal Persistence.

## 9. Expiry semantics

GI2D decryption must fail closed when:

```text
nowMs >= effectiveRoomExpiryMs
```

The same immutable expiry must be authenticated in the crypto context.

GI2D must not:

- load the room row;
- independently recompute room expiry;
- extend grace periods;
- decide whether history remains visible;
- decide Join-button state.

The caller supplies the authoritative effective expiry and current time. Tests must cover the exact boundary immediately before and at expiry.

Encryption may validate structural sanity such as non-negative timestamps and expiry after creation if creation time is part of the final context model, but it must not invent room business rules.

## 10. Recipient-only decryption

Recipient-only behavior requires all of the following:

- encryption uses the pinned target public key;
- envelope binds the target key ID;
- decryption resolves only the local active private key matching that key ID;
- a different installation/key returns typed mismatch/unavailable failure;
- ephemeral private key is never returned or persisted;
- sender retains local plaintext through later message materialization and does not decrypt its own wire envelope;
- non-target participants can store/relay only opaque envelope bytes.

The encrypt result must expose only:

- ephemeral public key;
- nonce;
- ciphertext;
- algorithm/key identifiers required by the wire protocol.

It must never expose the ECDH shared secret, AES key, recipient private key, or ephemeral private key.

## 11. Failure model requirements

The contract should define typed, non-secret failure categories at least for:

### Encryption

- unsupported algorithm;
- invalid recipient identity/public key;
- ephemeral key generation failure;
- ECDH failure;
- key derivation failure;
- random/nonce generation failure;
- AEAD failure;
- payload/wire budget rejection.

### Decryption

- not target participant;
- target key-ID mismatch;
- unsupported platform;
- recipient key unavailable/not found/invalidated;
- invalid ephemeral public key;
- malformed nonce/ciphertext;
- expired context;
- authentication failure;
- invalid decrypted payload handled by the protocol adapter.

Provider exceptions and secret bytes must not escape to logs or UI. Authentication failure must not reveal which bound field differed.

## 12. Secret handling

GI2D must:

- use `SecureRandom` or provider-generated GCM nonce;
- enforce a 12-byte nonce;
- never reuse an ephemeral private key across invitation messages;
- generate one ephemeral keypair per private card encryption;
- clear ECDH shared secret and derived AES key arrays;
- avoid storing plaintext, keys, or ciphertext in diagnostic exception messages;
- redact public keys and full key IDs in logs consistently with GI2A/GI2C;
- avoid copying secrets more than required by JCA APIs.

Java `PrivateKey` objects cannot generally be zeroized; therefore lifecycle must be kept inside the narrow operation and references released promptly.

## 13. Payload stability and budgets

GI2D encryption is executed once per logical private invitation message.

The resulting envelope must remain byte-stable across:

- direct retransmission;
- MESSAGE_OFFER/FETCH/DATA recovery;
- process restart when reloaded from the persisted message body.

GI2D must not regenerate nonce, ephemeral key, or ciphertext during retry.

Protocol encoding remains responsible for enforcing:

- private inner plaintext maximum: 1,280 bytes;
- private envelope maximum: 2,400 bytes;
- strict Base64URL and structural bounds.

GI2D should return raw encrypted bytes/fields to avoid unnecessary Base64 work inside primitive crypto. The protocol adapter should perform wire Base64URL encoding exactly once.

## 14. Invariant check

### Invariant 1 — Structured Nearby Chat Message

**PASS.** GI2D produces only an inner encrypted envelope for later storage as an existing chat message body. No new transport kind is required.

### Invariant 2 — Nearby Chat Ownership

**PASS CONDITIONALLY.** GI2D must not send, persist, retry, relay, order, classify, or deduplicate messages. Existing Nearby Chat remains authoritative.

### Invariant 3 — No Invitation Subsystem

**PASS.** One stateless/narrow crypto component is sufficient. No repository or manager lifecycle is justified.

### Invariant 4 — No New Business Rules

**PASS.** GI2D adds no quota, eligibility, card management, or UI rule. Expiry validation executes an already locked room-bound security condition.

### Invariant 5 — Exactly Two Invitation Rules

**PASS.** GI2D does not inspect or enforce public/private send counts.

### Invariant 6 — Security Is Independent

**PASS CONDITIONALLY.** Security must operate on primitive/neutral models and must not import Invitation payload/card/business models. Existing algorithm-enum coupling must not expand.

### Invariant 7 — Invitation Depends on Security

**PASS CONDITIONALLY.** Protocol/orchestration adapters may call Security. Security must not call Invitation codecs or domain policies.

### Invariant 8 — Minimal Persistence

**PASS.** No GI2D persistence is required. Runtime duplicate suppression remains on existing Nearby message identity.

### Stability priority

**PASS.** The proposal reuses current identity, pinning, message identity, room expiry, transport and persistence ownership. It avoids redesigning Nearby Chat or introducing generic frameworks.

## 15. Rejected architecture options

The following designs must be rejected:

1. Adding private invitation encryption directly to `NearbyRoomMessageCrypto`.
2. Letting Security serialize/deserialize `PrivateGroupInvitationInnerPayload` or `PrivateGroupInvitationEnvelope`.
3. Having GI2D query `NearbyRoomRepository` or participant DAO.
4. Re-encrypting inner payload during every send/retry.
5. Creating an invitation nonce/replay database.
6. Creating a generic `EncryptedStructuredMessageFramework` without a second use case.
7. Reusing room keys or DTN manifest keys as recipient keys.
8. Using participant/device IDs as key material.
9. Treating timeline filtering as confidentiality.
10. Returning raw JCA exceptions or secret-bearing objects through public result models.

## 16. Required GI2D contract decisions

Before implementation, the GI2D contract must lock:

1. exact component/API boundary;
2. neutral context model and ownership;
3. canonical context byte serialization;
4. complete HKDF salt/info/output construction;
5. AES-GCM provider parameters and ciphertext/tag representation;
6. ephemeral key generation/validation rules;
7. encryption and decryption result taxonomy;
8. expiry boundary behavior;
9. replay/context-binding versus duplicate-delivery ownership;
10. payload-budget preflight ownership;
11. secret cleanup/logging rules;
12. JVM golden vectors, negative vectors, and Android Keystore tests;
13. API 31+ behavior and accepted lower-platform limitation;
14. explicit prohibition on GI2E send/receive/persistence integration in this phase.

## 17. Final recommendation

Proceed to:

```text
GI2D Private Invitation Cryptography Contract
```

Do not implement production code yet.

The contract should be deliberately narrow: one security primitive boundary, one canonical context encoding, one exact v1 crypto suite, typed failures, and no persistence or transport ownership.
