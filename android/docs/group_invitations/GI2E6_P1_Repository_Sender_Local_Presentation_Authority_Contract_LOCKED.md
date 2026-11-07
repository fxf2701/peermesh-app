# GI2E6-P1 — Repository Sender-Local Presentation Authority Contract

**Status:** LOCKED

**Milestone:** GI2E6 — Private Invitation Presentation / Viewer Integration

**Phase:** P1 — Repository Sender-Local Presentation Authority

**Date:** 2026-08-06

---

# 1. Purpose

This contract defines the authoritative Repository behavior for presenting a persisted private Group Invitation to either:

- the local logical sender; or
- the intended local recipient.

Both authorized paths must return the same immutable validated domain model:

```text
NearbyPrivateGroupInvitationReceiveResult.Invitation(
    NearbyPrivateGroupInvitationView
)
```

Presentation must not know whether the immutable view was produced from:

- authenticated recipient decryption; or
- canonical sender-local render source validation.

This contract is additive to GI2E5. It does not replace or weaken the GI2E5 recipient receive contract.

---

# 2. Authority Statement

`NearbyRoomRepository` remains the only public authority for private invitation inspection.

The public API remains:

```kotlin
fun inspectPrivateGroupInvitation(
    roomId: String,
    messageId: String,
): NearbyPrivateGroupInvitationReceiveResult
```

No public sender-specific or recipient-specific inspection API may be introduced.

The caller must not select the inspection role.

Repository-owned inspection determines the authorized local role from authoritative persisted data.

Presentation must not:

- decode the Group Invitation protocol;
- inspect raw JSON fields;
- read `local_render_source_body` directly;
- reconstruct invitation identity;
- infer sender authority from UI ownership flags;
- infer recipient authority from display state;
- decrypt;
- resolve recipient cryptographic identity;
- build authenticated crypto context;
- invoke GI2D.

---

# 3. Locked Inputs

Inspection operates on an authoritative persisted `NearbyRoomMessageEntity` loaded by Repository using:

```text
roomId + messageId
```

The persisted row remains the authority for:

- room identity;
- message identity;
- canonical encrypted outer body;
- logical sender metadata;
- target participant identity contained in the validated outer envelope;
- creation time;
- TTL expiration time;
- sender-local render source presence.

The local participant identity is resolved by Repository-owned state. It is not supplied by presentation as an asserted role.

---

# 4. Logical Sender Rule

The logical sender identity is resolved exactly as:

```kotlin
message.owner_device_id ?: message.sender_device_id
```

The relay or currently observed transport sender must not override a non-null owner identity.

A local peer is authorized for the sender-local path only when:

```text
localParticipantId == logicalSenderId
```

A relaying peer is not the logical sender solely because:

```text
message.sender_device_id == localParticipantId
```

when `owner_device_id` identifies a different logical sender.

---

# 5. Common Validation Boundary

Before sender-local or recipient-specific processing, inspection must validate the common outer boundary.

Required common order:

```text
1. Load authoritative persisted row.
2. Verify exact Group Invitation candidacy.
3. Decode the outer protocol message.
4. Require a supported private invitation envelope.
5. Require envelope.cardId == message.message_id.
6. Resolve logicalSenderId.
7. Require a valid temporal context:
       message.ttl_expires_at_ms > message.created_at_ms
8. Require a non-blank local participant identity.
9. Resolve the local authorized path.
```

The persisted encrypted outer body remains the identity authority.

`local_render_source_body` must never replace or repair the outer-body identity contract.

---

# 6. Local Role Resolution

After common validation, Repository resolves the local role in this order:

```text
if localParticipantId == logicalSenderId:
    sender-local path
else if localParticipantId == envelope.targetParticipantId:
    intended-recipient path
else:
    NotForLocalParticipant
```

This resolution order is locked.

A third participant must be rejected before:

- decrypt;
- recipient identity lookup;
- private-inner parsing;
- presentation materialization.

Result:

```text
NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant
```

---

# 7. Sender-Local Path

## 7.1 Purpose

The sender-local path permits the logical sender to view the invitation they previously sent without decrypting recipient ciphertext.

It uses the persisted sender-local render source only after outer identity and sender authority have been established.

## 7.2 Required validation

The sender-local path must perform:

```text
1. Require nowMs < message.ttl_expires_at_ms.
2. Require message.local_render_source_body != null.
3. Decode the source using the locked private-inner decoder.
4. Re-encode the decoded private-inner value canonically.
5. Require canonical re-encoding to equal the exact stored source string.
6. Materialize the immutable invitation view from:
       authoritative persisted metadata,
       validated outer envelope,
       validated canonical private-inner payload.
```

## 7.3 Sender-local source authority

`local_render_source_body` is a local presentation source only.

It is authoritative for these private-inner values only after canonical validation:

- title;
- short description;
- invitation credential.

It is not authoritative for:

- room ID;
- message ID;
- card ID;
- logical sender ID;
- target participant ID;
- creation time;
- expiration time;
- protocol kind;
- outer algorithm or key identity.

Those values must come from authoritative persisted metadata and the validated outer envelope.

## 7.4 Prohibited sender-local behavior

The sender-local path must not:

- call GI2D;
- decrypt;
- resolve recipient private identity;
- perform recipient key lookup;
- build recipient crypto context;
- re-encrypt;
- compare sender-local plaintext with decrypted ciphertext;
- repair malformed source;
- normalize and persist sender-local source;
- rewrite the encrypted outer body.

---

# 8. Intended-Recipient Path

The intended-recipient path remains the locked GI2E5 receive pipeline.

It must continue to perform:

```text
validated target audience
        ↓
authenticated crypto context construction
        ↓
GI2D decrypt exactly once
        ↓
strict UTF-8 decode
        ↓
canonical private-inner decode/re-encode equality
        ↓
immutable invitation view
        ↓
plaintext zero-fill in finally
```

The recipient path must ignore `local_render_source_body` as a source of plaintext.

A malformed, forged or present sender-local source must not bypass recipient decryption.

P1 must not modify:

- recipient key selection;
- authenticated context;
- GI2D algorithms;
- decrypt failure mapping;
- plaintext lifecycle;
- zero-fill behavior.

---

# 9. Shared Immutable Materialization

Both authorized paths must converge on one shared materialization boundary.

The resulting view contains:

```kotlin
NearbyPrivateGroupInvitationView(
    roomId = message.room_id,
    messageId = message.message_id,
    cardId = envelope.cardId,
    logicalSenderId = logicalSenderId,
    targetParticipantId = envelope.targetParticipantId,
    title = inner.title,
    shortDescription = inner.shortDescription,
    invitationCredential = inner.invitationCredential,
    createdAtMs = message.created_at_ms,
    expiresAtMs = message.ttl_expires_at_ms,
)
```

Required identity invariant:

```text
messageId == cardId
```

The returned model must remain immutable.

No persistence entity, raw protocol body, raw sender-local source, ciphertext, crypto context or key material may be exposed through the view.

---

# 10. Locked Result Mapping

## 10.1 Sender-local outcomes

| Condition | Required result |
|---|---|
| Local participant is logical sender, invitation unexpired, source present and canonical | `Invitation(view)` |
| Local participant is logical sender, `nowMs >= ttl_expires_at_ms` | `Expired` |
| Sender-local source missing | `MalformedInnerPayload` |
| Sender-local source cannot decode | `MalformedInnerPayload` |
| Sender-local source decodes but is non-canonical | `MalformedInnerPayload` |

## 10.2 Common outcomes

| Condition | Required result |
|---|---|
| Persisted row not found | `MessageNotFound` |
| Exact Group Invitation candidacy fails | existing non-invitation result |
| Outer envelope unsupported or invalid | existing typed envelope/protocol result |
| `envelope.cardId != message.message_id` | `MessageIdentityMismatch` |
| Blank/invalid logical sender or invalid TTL context | `InvalidCryptoContext` |
| Blank local participant identity | `NotForLocalParticipant` |
| Local participant is neither logical sender nor intended recipient | `NotForLocalParticipant` |

## 10.3 Recipient outcomes

Recipient outcomes and crypto failure mapping remain exactly as locked by GI2E5 and GI2D.

P1 must not collapse, rename or reinterpret those typed results.

---

# 11. Expiry Boundary

Expiry remains a validation and persistence-lifecycle concern.

For the sender-local path:

```text
nowMs >= message.ttl_expires_at_ms
    → Expired
```

P1 does not introduce:

- expiry presentation UI;
- expiry countdown;
- durable expired invitation card;
- alternate cleanup behavior.

GI2E6 presentation may omit an `Expired` result while existing room/message cleanup removes expired persisted data.

---

# 12. Persistence Invariants

P1 is read-only.

P1 must not:

- persist decrypted plaintext;
- persist decoded sender-local content elsewhere;
- modify `body`;
- modify `local_render_source_body`;
- modify `is_hidden_locally`;
- update delivery state;
- update retry or relay state;
- create a new invitation table;
- add a schema migration;
- create a presentation cache;
- create a synthetic timeline row.

The encrypted outer body remains unchanged.

---

# 13. Security Invariants

The following invariants are locked:

1. Repository remains the inspection authority.
2. Outer encrypted body remains invitation identity authority.
3. Sender-local source is accepted only for the authenticated local logical sender role.
4. Sender-local source must be canonical.
5. Third participants receive no private content.
6. Sender-local inspection performs no decrypt.
7. Recipient inspection does not trust sender-local source.
8. Recipient decrypt remains exactly once per receive attempt.
9. Recipient plaintext remains memory-only and is zero-filled.
10. No protocol parser or crypto component is introduced into presentation.

---

# 14. Compatibility Invariants

P1 must preserve:

- the existing public Repository API;
- all existing `NearbyPrivateGroupInvitationReceiveResult` variants;
- GI2E5 recipient behavior;
- GI2D cryptographic behavior;
- transport and relay behavior;
- retry behavior;
- persisted envelope format;
- protocol version and canonical encoding;
- invitation title and description limits defined by `Group_Invitation_Spec_v2.md`:
  - title: maximum 40 characters;
  - description: maximum 60 characters.

P1 introduces no protocol amendment.

---

# 15. Required Verification

Focused unit verification must prove at minimum:

## Sender-local happy path

- canonical local source returns `Invitation(view)`;
- returned view uses authoritative room/message/card/sender/target identities;
- sender-local inspection calls decrypt zero times.

## Logical sender authority

- `owner_device_id` takes precedence over relay `sender_device_id`;
- relay sender does not gain sender-local authority.

## Sender-local source validation

- missing source returns `MalformedInnerPayload`;
- malformed source returns `MalformedInnerPayload`;
- non-canonical source returns `MalformedInnerPayload`;
- expired sender-local invitation returns `Expired`;
- all above call decrypt zero times.

## Recipient regression

- intended recipient still decrypts exactly once;
- recipient ignores sender-local source;
- successful recipient result remains `Invitation(view)`.

## Audience isolation

- third participant returns `NotForLocalParticipant`;
- third participant is rejected before decrypt.

## Outer identity authority

- card/message identity mismatch is rejected before sender-local materialization;
- sender-local source cannot repair or override an invalid outer envelope.

Existing GI2E5 focused tests must remain passing.

---

# 16. Approved Production Scope

P1 production changes are limited to:

```text
android/app/src/main/java/app/peermesh/nearby/
NearbyPrivateGroupInvitationReceive.kt
```

Focused P1 tests may be added at:

```text
android/app/src/test/java/app/peermesh/nearby/
NearbyPrivateGroupInvitationReceiveGI2E6Test.kt
```

A change outside this scope requires explicit architectural justification and a contract amendment before code modification.

---

# 17. Change-Control Rule

This document is the normative contract for GI2E6-P1.

After this contract is locked, any proposed behavior or implementation change affecting P1 must follow this order:

```text
1. Identify the required contract change.
2. Review the change against GI1 → GI2E5 locked invariants.
3. Amend this contract or issue a superseding locked decision document.
4. Record the architectural and security rationale.
5. Obtain approval for the contract change.
6. Only then modify production code or tests.
```

The following are prohibited:

- changing production behavior first and documenting it afterward;
- treating tests as authority over the locked contract;
- silently changing result mapping;
- adding a sender-specific public API without prior contract amendment;
- allowing presentation to select sender or recipient inspection mode;
- weakening canonical validation for convenience;
- introducing fallback decrypt or fallback plaintext source;
- changing the logical sender rule without a prior architecture decision.

If code and this locked contract disagree, the code is considered non-compliant until one of the following occurs:

- the code is corrected to match this contract; or
- this contract is formally amended and locked before the new code is accepted.

---

# 18. Implementation Conformance Record

The GI2E6-P1 patch prepared on 2026-08-06 is intended to conform to this contract by:

- preserving the existing Repository public API;
- resolving logical sender via `owner_device_id ?: sender_device_id`;
- adding sender-local role resolution before recipient audience rejection;
- validating expiry for the sender-local path;
- requiring and canonical-validating `local_render_source_body`;
- sharing immutable invitation materialization between sender and recipient paths;
- preserving recipient decrypt and plaintext zero-fill behavior;
- adding focused GI2E6-P1 unit tests.

Final implementation acceptance still requires focused local Gradle verification.

---

# 19. Lock Record

```text
GI2E6-P1 CONTRACT: LOCKED

Repository authority: PRESERVED
Sender-local authority: DEFINED
Recipient GI2E5 path: PRESERVED
Protocol change: NONE
GI2D change: NONE
Persistence change: NONE
Schema migration: NONE
Presentation parsing: FORBIDDEN
Code-before-contract changes: FORBIDDEN

Implementation verification: PENDING LOCAL TEST EXECUTION
GI2E6: NOT CLOSED
NOT LOCKED
```
