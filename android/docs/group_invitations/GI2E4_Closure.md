# GI2E4 — Private Invitation Send Pipeline

**Status**

```text
CLOSED — LOCKED
```

---

# 1. Executive Summary

GI2E4 completes the production implementation of the **Private Invitation Send Pipeline**.

The milestone integrates private Group Invitation creation into the existing Nearby Room architecture without introducing any parallel persistence, transport, retry or cryptographic subsystem.

Private Invitation is now produced through the repository-authoritative preparation capability, encoded as a canonical private-inner payload, encrypted exactly once through the locked GI2D recipient-encryption component, wrapped in the canonical private envelope, atomically persisted through the prepared insertion boundary, and transmitted through the existing Nearby Room transport path.

No protocol changes, database schema changes or architectural redesign were introduced.

---

# 2. Scope Completed

GI2E4 completed:

* Repository-owned private invitation preparation integration.
* Canonical private-inner payload construction.
* GI2D recipient encryption integration.
* Canonical private envelope construction.
* Prepared insertion integration.
* Existing Nearby transport integration.
* Existing owner retry compatibility.
* Existing relay compatibility.
* Focused JVM verification.
* Security and authority hardening.
* Independent Review.
* Manual source review.

GI2E4 intentionally did **not** implement:

* Invitation UI.
* Invitation renderer.
* Invitation parser UI.
* Hide / Unhide UI.
* Join flow.
* Invitation viewer.
* Invitation actions.
* GI2E5.

---

# 3. Final Architecture

The final production pipeline is:

```text
Semantic Private Request
        │
        ▼
prepareGroupInvitationContext(Private)
        │
        ▼
Repository-issued frozen preparation
        │
        ▼
Canonical PrivateGroupInvitationInnerPayload
        │
        ▼
GI2D recipient encryption — exactly once
        │
        ▼
Canonical PrivateGroupInvitationEnvelope
        │
        ▼
insertPreparedGroupInvitation()
        │
        ▼
Persisted NearbyRoomMessageEntity
        │
        ▼
Existing Nearby Room Transport
        │
        ▼
Existing Delivery / Retry / Relay
```

Repository remains the only authority responsible for:

* message identity;
* sender identity;
* effective expiry;
* private audience binding;
* recipient encryption identity selection;
* preparation capability;
* authoritative insertion;
* send-limit enforcement;
* persistence and transaction ownership.

GI2D remains a pure cryptographic component and does not know about:

* DAO;
* repository;
* transport;
* retry;
* UI.

---

# 4. Authority Preservation

Caller-owned semantic input is limited to:

* roomId;
* targetParticipantId;
* title;
* shortDescription;
* invitationCredential.

Caller cannot supply:

* messageId;
* cardId;
* sender identity;
* created time;
* expiry;
* recipient key ID;
* recipient public key;
* encryption algorithm;
* nonce;
* ephemeral key;
* ciphertext;
* encoded private-inner body;
* encoded private envelope;
* persisted entity.

The private invitation identity is bound as:

```text
cardId = preparation.messageId
```

Recipient encryption identity is obtained only from the repository-issued frozen preparation.

No second recipient lookup path exists after preparation.

---

# 5. Canonical Private-Inner Payload

The private-inner payload contains semantic invitation content only and is encoded exclusively through the locked Group Invitation protocol implementation.

The canonical private-inner body is used for:

```text
GI2D plaintext input
+
local_render_source_body
```

No ad-hoc JSON builder, duplicate protocol encoder or caller-owned encoded payload is permitted.

The temporary UTF-8 plaintext byte buffer is zeroed after the GI2D encryption call, including failure paths protected by the encryption boundary.

---

# 6. GI2D Integration

GI2D is invoked exactly once for each logical private send attempt.

The authenticated cryptographic context is frozen from preparation and binds:

* protocol version;
* algorithm wire value;
* room ID;
* message ID;
* card ID;
* logical sender ID;
* target participant ID;
* target recipient key ID;
* effective room expiry.

Production callers cannot inject an arbitrary PrivateRecipientCrypto implementation.

Production wiring always uses the approved GI2D implementation backed by the Android Keystore recipient identity provider.

Test-only injection remains internal to the module and is not part of the production API.

---

# 7. GI2D Output Validation

A GI2D success result is not accepted blindly.

Before outer-envelope construction, the pipeline verifies:

```text
encrypted.algorithmWireValue
    == preparation recipient algorithm

encrypted.targetKeyId
    == preparation recipient key ID
```

Any mismatch returns:

```text
InvalidCryptoContext
```

and stops before persistence and transport.

This prevents cryptographic metadata drift between repository-frozen preparation and the encrypted payload returned by GI2D.

---

# 8. Canonical Private Envelope

The canonical private envelope is constructed from:

* `cardId = preparation.messageId`;
* frozen target participant ID;
* frozen encryption algorithm;
* validated target key ID;
* GI2D-generated ephemeral public key;
* GI2D-generated nonce;
* GI2D-generated ciphertext and authentication tag.

Encoding remains exclusively owned by:

```text
GroupInvitationProtocol
```

No wire-format duplication or protocol drift was introduced.

---

# 9. Persistence

No database schema changes were introduced.

Private invitation persistence rules are:

```text
body
=
canonical private envelope

local_render_source_body
=
canonical private-inner payload
```

The two bodies are never reversed.

The pipeline does not create `NearbyRoomMessageEntity` manually and does not call the DAO directly.

Insertion is performed only through:

```text
insertPreparedGroupInvitation()
```

using the exact repository-issued preparation.

---

# 10. Transaction Review

Transaction ordering remains:

```text
prepare
→ canonical private-inner encode
→ GI2D encryption
→ canonical private-envelope encode
→ prepared insertion
→ transaction commit
→ transport
```

Transport is never executed before successful insertion.

Transport failure never rolls back the persisted invitation.

No failure path after insertion performs a second preparation, second encryption, second encoding or second insertion.

---

# 11. Transport Integration

Transport continues to use the existing Nearby Room message path.

GI2E4 does not introduce:

* private invitation transport packets;
* private invitation relay packets;
* private invitation delivery queue;
* private invitation retry subsystem.

Transport always receives the persisted entity returned by repository insertion.

Public and private invitation pipelines share only the post-persistence transport submission bridge.

That shared bridge does not own:

* preparation;
* protocol encoding;
* encryption;
* insertion;
* retry policy.

---

# 12. Retry Compatibility

Retry behaviour remains unchanged.

Owner retry continues to operate using:

```text
roomId
+
messageId
```

The retry coordinator reloads the persisted message body.

For private invitations, retry sends the same persisted canonical private envelope.

Retry never:

* regenerates messageId;
* creates another preparation;
* reconstructs the private-inner payload;
* invokes GI2D;
* regenerates nonce;
* regenerates ephemeral key;
* regenerates ciphertext;
* re-encodes the private envelope;
* performs another insertion.

---

# 13. Security Review

Security review completed successfully.

Verified:

* no repository authority leak;
* no sender spoofing surface;
* no caller-owned invitation identity;
* no caller-owned recipient encryption identity;
* no production crypto injection;
* no caller-owned nonce, ephemeral key or ciphertext;
* no persistence bypass;
* no protocol bypass;
* no retry re-encryption;
* no transaction regression;
* no GI2D regression.

During Independent Review, one authority issue was discovered in the initial implementation:

```text
production constructor allowed caller-supplied PrivateRecipientCrypto
```

This was corrected before closure.

The final production constructor accepts only:

* Android Context;
* NearbyRoomRepository;
* NearbyRoomTransportManager.

The approved GI2D implementation is created internally.

The final implementation also validates GI2D success metadata against repository-frozen preparation before persistence.

---

# 14. Testing & Verification

## Focused JVM Tests

PASS

Verified:

* private preparation audience;
* repository-frozen recipient identity;
* full recipient algorithm, key ID and SEC1 public key binding;
* `cardId = messageId`;
* exact authenticated crypto context;
* GI2D invoked exactly once;
* plaintext buffer cleanup;
* canonical private-inner source;
* canonical private envelope;
* correct persisted `body`;
* correct persisted `local_render_source_body`;
* insertion before transport;
* persisted-entity-only transport;
* transport failure without re-encryption;
* all GI2D domain failures stop before insertion and transport;
* GI2D algorithm mismatch rejection;
* GI2D target-key mismatch rejection;
* invalid semantic input rejection before preparation;
* non-private preparation rejection;
* prepared-target mismatch rejection;
* missing recipient encryption identity rejection;
* caller request contains semantic fields only.

Final focused command:

```text
./gradlew \
  :app:testEmergencyDebugUnitTest \
  --tests app.peermesh.nearby.NearbyPrivateGroupInvitationSendPipelineGI2E4Test \
  --tests app.peermesh.nearby.NearbyPublicGroupInvitationSendPipelineGI2E3Test \
  --no-parallel
```

Final result:

```text
BUILD SUCCESSFUL
```

---

## Existing Regression Suites

The wider JVM run executed:

```text
546 tests
543 passed
3 failed
```

The three failures belong to the Map subsystem and were reproduced on baselines rolled back at least as far as GI2A.

They are therefore classified as pre-existing, unrelated baseline failures and are not attributable to GI2E4.

No failure was observed in:

* Group Invitation;
* GI2D crypto;
* Nearby Room;
* private send pipeline;
* public send pipeline;
* security;
* transport;
* moderation.

---

## Instrumentation Test

The intended instrumentation test was:

```text
NearbyGroupInvitationPreparationTest
```

Execution was blocked during:

```text
:app:compileEmergencyDebugAndroidTestKotlin
```

because of pre-existing, unrelated Map `androidTest` compilation failures in:

* `GroupMapActivityM3D2Test.kt`;
* `OsmdroidMapRuntimeRendererM3D2Test.kt`.

Therefore:

```text
GI2E4 instrumentation test:
NOT EXECUTED
```

This is not recorded as an instrumentation PASS.

It is recorded as:

```text
BLOCKED BY PRE-EXISTING UNRELATED BASELINE FAILURES
```

No GI2E4-attributable instrumentation failure was observed.

This limitation is accepted because:

* the failing sources are outside GI2E4;
* they predate GI2E4;
* GI2E4 does not modify Map production or test code;
* the repository preparation and insertion contract already has an existing instrumentation suite;
* the new private orchestration is covered by focused JVM tests;
* creating a new instrumentation architecture solely to bypass an unrelated source-set compilation failure would increase coupling and violate the stability-first priority.

---

## Independent Review

Initial Independent Review found one production authority leak:

```text
caller-supplied PrivateRecipientCrypto through production constructor
```

Required correction applied.

Independent Re-review:

```text
PASS
```

No remaining blocker or major finding.

---

## Manual Review

Manual source review covered:

* semantic-only public API;
* repository preparation authority;
* audience and recipient binding;
* invitation identity;
* canonical private-inner encoding;
* production GI2D boundary;
* authenticated context;
* encrypt-exactly-once invariant;
* plaintext lifecycle;
* GI2D output validation;
* canonical outer envelope;
* prepared insertion;
* transaction ordering;
* persisted-entity-only transport;
* transport failure semantics;
* retry compatibility;
* public pipeline regression;
* failure paths before persistence;
* exception handling;
* absence of secondary subsystems;
* focused test quality;
* patch scope.

Initial manual review found three minor test-hardening items:

* unnecessary non-null assertion;
* incomplete recipient identity assertion;
* missing focused coverage for early preparation rejection paths.

All three minor findings were corrected.

Final Manual Review:

```text
PASS
```

Final finding count:

```text
BLOCKER: 0
MAJOR: 0
MINOR: 0
```

---

# 15. Files Changed

Production:

* `NearbyPrivateGroupInvitationSendPipeline.kt`
* `NearbyGroupInvitationPersistedMessageSubmitter.kt`
* `NearbyPublicGroupInvitationSendPipeline.kt`

Tests:

* `NearbyPrivateGroupInvitationSendPipelineGI2E4Test.kt`
* existing `NearbyPublicGroupInvitationSendPipelineGI2E3Test.kt` used for regression verification.

Documentation:

* `GI2E4_Architecture_Review.md`
* `GI2E4_Production_Implementation.md`
* `GI2E4_Closure.md`

---

# 16. Locked Invariants

The following invariants are now locked.

## Repository Authority

Repository remains the sole authority for:

* preparation;
* message identity;
* sender identity;
* expiry;
* recipient encryption identity selection;
* insertion;
* persistence;
* transaction ownership.

---

## Private Invitation Identity

```text
cardId = messageId
```

This invariant is mandatory.

---

## Private Encryption

```text
one logical private send
=
at most one GI2D encryption invocation
```

No post-insertion or retry re-encryption is permitted.

---

## Private Persistence

```text
body = canonical private envelope

local_render_source_body = canonical private-inner payload
```

Always.

---

## Transport

Transport always uses the persisted entity returned by repository insertion.

No pre-persistence transport is permitted.

---

## Retry

Retry always reloads and sends the persisted outer body.

No preparation, encoding or encryption is permitted in retry.

---

## GI2D Boundary

GI2D remains a pure cryptographic component.

GI2D must not know about:

* DAO;
* repository;
* persistence transaction;
* transport;
* retry;
* UI.

Production callers must not be able to inject arbitrary crypto implementations or cryptographic material.

---

## Architecture

No secondary invitation subsystem.

No secondary persistence subsystem.

No secondary transport subsystem.

No secondary retry subsystem.

No protocol duplication.

No cryptographic orchestration outside repository-authoritative preparation and the GI2D boundary.

---

# 17. Future Milestone Boundary

GI2E4 does not authorize implementation of:

* invitation UI;
* rendering;
* parsing UI;
* local hide/unhide UI;
* join flow;
* invitation actions;
* GI2E5 responsibilities.

Later milestones must preserve every invariant locked by GI2E1, GI2E2, GI2E3 and GI2E4.

---

# 18. Final Verdict

Architecture Review

```text
PASS
```

Implementation

```text
PASS
```

Security Review

```text
PASS
```

Transaction Safety

```text
PASS
```

Focused JVM Verification

```text
PASS
```

GI2E3 Regression Verification

```text
PASS
```

Instrumentation Execution

```text
NOT EXECUTED
BLOCKED BY PRE-EXISTING UNRELATED MAP ANDROIDTEST COMPILATION FAILURES
```

Independent Review

```text
PASS AFTER REQUIRED CORRECTION
```

Manual Review

```text
PASS
```

Final Status

```text
GI2E4

CLOSED

LOCKED
```
