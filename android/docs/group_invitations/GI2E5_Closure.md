# GI2E5 — Private Invitation Receive / Viewer Pipeline Closure

**Status:** CLOSED — LOCKED

**Milestone:** GI2E5 — Private Invitation Receive / Viewer Pipeline

**Date:** 2026-08-06

---

# 1. Verdict

```text
ARCHITECTURE REVIEW: PASS

IMPLEMENTATION: PASS

SECURITY REVIEW: PASS

PERSISTENCE REVIEW: PASS

UX BOUNDARY REVIEW: PASS

INDEPENDENT REVIEW: PASS

MANUAL REVIEW: PASS

UNIT VERIFICATION: PASS

GI2E5: CLOSED
```

GI2E5 establishes the repository-owned receive capability for private Group Invitations.

The implementation preserves all locked contracts from GI1 through GI2E4.

No protocol changes were introduced.

No persistence changes were introduced.

No cryptographic contract changes were introduced.

---

# 2. Scope Completed

GI2E5 implements:

- persisted private invitation inspection
- canonical private envelope detection
- canonical protocol decoding
- authenticated decrypt context reconstruction
- GI2D decrypt integration
- private-inner payload reconstruction
- domain validation
- immutable read-only invitation model
- repository-owned receive API

GI2E5 intentionally does **not** implement:

- invitation UI
- renderer
- accept/reject
- join flow
- hide/unhide presentation
- notification rendering
- timeline rendering

These remain owned by GI2E6.

---

# 3. Final Receive Pipeline

```text
Persisted NearbyRoomMessageEntity
        │
        ▼
Repository
        │
        ▼
Load authoritative persisted row
        │
        ▼
Exact Group Invitation candidate detection
        │
        ▼
Canonical private envelope decode
        │
        ▼
Canonical outer-envelope verification
        │
        ▼
Identity validation
        │
        ▼
Authenticated crypto context construction
        │
        ▼
GI2D decrypt (exactly once)
        │
        ▼
Strict UTF-8 decode
        │
        ▼
Canonical private-inner verification
        │
        ▼
Domain validation
        │
        ▼
Immutable read-only invitation view
```

Repository remains the single authority throughout the receive flow.

---

# 4. Repository Authority

Repository is the only public entry point.

Presentation cannot:

- parse invitation protocol
- reconstruct crypto context
- decrypt payload
- resolve recipient identity
- validate authenticated context
- rebuild invitation identity

Repository owns:

- persisted row loading
- protocol orchestration
- context construction
- GI2D invocation
- receive result mapping

---

# 5. Cryptographic Boundary

GI2D remains a pure cryptographic component.

GI2E5 does not modify:

- HKDF construction
- AES-GCM usage
- authenticated context
- recipient identity provider
- key lookup
- key pinning
- cryptographic validation

GI2D is invoked exactly once for each receive attempt.

No fallback decrypt exists.

No alternative key lookup exists.

---

# 6. Identity Guarantees

The following invariants remain enforced:

- messageId == cardId
- logical sender uses:
  origin_device_id ?: sender_device_id
- exact target participant validation
- exact target key-ID validation
- authenticated context binding
- canonical protocol verification

No new invitation identity is created during receive.

---

# 7. Persistence Review

No persistence regression was introduced.

Persisted message remains:

```text
body
=
canonical encrypted private envelope
```

Receive does not:

- persist plaintext
- mutate body
- rewrite envelope
- create invitation tables
- introduce schema migration
- populate local render source

All decrypted content remains memory-only.

---

# 8. Retry Compatibility

Receive remains read-only.

Receive does not:

- re-encrypt
- normalize persisted JSON
- update delivery state
- interfere with retry
- interfere with relay

Retry continues using the original persisted encrypted envelope.

---

# 9. Canonical Protocol Enforcement

GI2E5 verifies canonical protocol representation at two boundaries:

Outer envelope:

- decode
- canonical re-encode
- byte-for-byte equality

Private-inner payload:

- decode
- canonical re-encode
- byte-for-byte equality

Non-canonical payloads are rejected before domain materialization.

---

# 10. Security Review

Security review confirms:

PASS

Verified:

- wrong recipient rejection
- wrong key rejection
- wrong participant rejection
- authenticated context binding
- canonical protocol enforcement
- malformed envelope rejection
- malformed inner rejection
- invalid ciphertext rejection
- invalid nonce rejection
- decrypt exactly once
- no partial plaintext exposure
- no plaintext persistence
- no authority inversion

---

# 11. UX Boundary Review

Presentation receives only immutable validated domain models.

Presentation cannot:

- decrypt
- decode protocol
- rebuild invitation identity
- bypass repository validation

GI2E5 introduces no presentation behavior.

Presentation responsibilities remain deferred to GI2E6.

---

# 12. Files Added / Modified

Production:

- NearbyPrivateGroupInvitationReceive.kt
- NearbyRoomRepository.kt

Tests:

- NearbyPrivateGroupInvitationReceiveGI2E5Test.kt

No protocol modifications.

No GI2D modifications.

No Room schema changes.

No transport changes.

---

# 13. Verification

## Production Compile

PASS

## Focused Unit Tests

PASS

Focused receive pipeline tests verify:

- valid decrypt
- canonical outer verification
- canonical inner verification
- wrong participant
- wrong key
- wrong recipient
- malformed envelope
- malformed inner
- invalid ciphertext
- invalid nonce
- authentication failure
- context mapping
- decrypt exactly once
- repository authority
- immutable receive model
- no persistence mutation

## Full Unit Suite

The remaining failing tests belong to unrelated Map presentation modules and are outside the scope of GI2E5.

No evidence indicates regression introduced by GI2E5.

## Instrumentation Assessment

Android instrumentation execution remains blocked by unrelated pre-existing Map androidTest compilation failures.

GI2E5 introduces no new instrumentation regression.

The existing limitation remains documented separately.

---

# 14. Architecture Preservation

Confirmed:

- Repository authority preserved
- GI2D purity preserved
- Security boundary preserved
- Persistence boundary preserved
- Retry boundary preserved
- Protocol boundary preserved
- No protocol drift
- No persistence drift
- No architecture inversion

---

# 15. Decision

GI2E5 is complete.

The milestone establishes the repository-owned private invitation receive capability while preserving all previously locked contracts.

No further changes are required within GI2E5.

Presentation integration continues in GI2E6.

---

# 16. Final Status

```text
GI2E5

Architecture Review
PASS

Implementation
PASS

Security Review
PASS

Persistence Review
PASS

UX Boundary Review
PASS

Independent Review
PASS

Manual Review
PASS

Focused Unit Verification
PASS

Production Compile
PASS

Instrumentation Assessment
PASS WITH VERIFIED LIMITATION

GI2E5

CLOSED

LOCKED
```