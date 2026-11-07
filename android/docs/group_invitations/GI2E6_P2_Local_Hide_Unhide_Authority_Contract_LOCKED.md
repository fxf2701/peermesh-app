# GI2E6-P2 — Local Hide / Unhide Authority Contract

**Version:** 1.0  
**Status:** LOCKED  
**Milestone:** GI2E6 — Private Invitation Presentation / Viewer Integration  
**Phase:** P2 — Repository Local Hide / Unhide Authority

## 1. Purpose

P2 defines and implements the authoritative local Hide / Unhide mutation for a persisted private group invitation.

Hide / Unhide is a local presentation preference only. It is not invitation deletion, transport synchronization, invitation lifecycle mutation, membership mutation, or protocol mutation.

## 2. Contract-first authority

This document is the normative implementation authority for GI2E6-P2.

Any later behavioral, architectural, persistence, security, API, or UX change affecting P2 must follow:

```text
Contract amendment
    ↓
Architecture / Security / UX review as applicable
    ↓
Approval
    ↓
Production code change
    ↓
Verification
```

Code that diverges from this locked contract without an approved amendment is non-compliant even if tests pass.

## 3. Scope

P2 implements only:

- Repository-owned private invitation Hide / Unhide mutation;
- typed mutation outcomes;
- authoritative sender/recipient audience validation;
- targeted persistence update of `is_hidden_locally`;
- focused unit verification.

P2 does not implement:

- timeline presentation models;
- RecyclerView or adapter integration;
- invitation cards or placeholders;
- read-only viewer;
- Accept, Reject, Join, or membership mutation;
- deletion, recall, relay, retry, or transport synchronization;
- protocol, cryptography, or schema changes.

## 4. Existing ownership

Ownership remains:

```text
NearbyRoomRepository
    ↓
Repository-owned local visibility controller
    ↓
NearbyRoomMessageDao
    ↓
NearbyRoomMessageEntity.is_hidden_locally
```

Presentation must not access the DAO or mutate a persisted entity directly.

## 5. Persisted state

P2 reuses the existing field:

```kotlin
is_hidden_locally: Boolean
```

No new column, table, migration, lifecycle, or invitation subsystem is permitted.

## 6. Public Repository API

P2 adds exactly one public Repository operation:

```kotlin
fun setGroupInvitationHiddenLocally(
    roomId: String,
    messageId: String,
    hidden: Boolean,
): NearbyGroupInvitationLocalVisibilityResult
```

No sender-specific, recipient-specific, Hide-only, or Unhide-only public API is introduced.

The Repository resolves local identity internally and remains the mutation authority.

## 7. Typed result contract

```kotlin
sealed interface NearbyGroupInvitationLocalVisibilityResult {
    data object Updated : NearbyGroupInvitationLocalVisibilityResult
    data object AlreadyInRequestedState : NearbyGroupInvitationLocalVisibilityResult
    data object MessageNotFound : NearbyGroupInvitationLocalVisibilityResult
    data object NotGroupInvitation : NearbyGroupInvitationLocalVisibilityResult
    data object NotAuthorizedViewer : NearbyGroupInvitationLocalVisibilityResult
    data object InvitationInvalid : NearbyGroupInvitationLocalVisibilityResult
}
```

Boolean-only results are prohibited because they erase meaningful business outcomes.

## 8. Authorization

Authorized local viewers are exactly:

```text
logical sender
OR
intended recipient
```

Logical sender is resolved using the locked rule:

```kotlin
message.owner_device_id ?: message.sender_device_id
```

A relay sender does not gain sender-local authority when `owner_device_id` identifies another logical sender.

Any other participant receives:

```text
NotAuthorizedViewer
```

Presentation must not determine or supply a sender/recipient role.

## 9. Validation order

The Repository-owned mutation path shall execute in this order:

```text
1. Validate non-blank roomId and messageId.
2. Load authoritative persisted row.
3. Require an exact Group Invitation candidate.
4. Decode the supported invitation envelope.
5. Require a canonical private envelope.
6. Require envelope.cardId == persisted messageId.
7. Resolve valid logical sender and valid persisted time context.
8. Require local identity to be logical sender or intended recipient.
9. Return AlreadyInRequestedState when no mutation is needed.
10. Execute targeted DAO update.
11. If update count is zero, reload once to resolve cleanup/concurrent-state race.
12. Return typed result.
```

No GI2D decrypt, recipient private-key lookup, private-inner decode, or sender-local plaintext lookup is required for local visibility authorization.

## 10. Classification mapping

- missing row or blank identity input → `MessageNotFound`;
- ordinary message → `NotGroupInvitation`;
- public invitation → `NotGroupInvitation` for this private-only mutation API;
- unsupported, malformed, non-canonical, identity-mismatched, or invalid-context private invitation → `InvitationInvalid`;
- valid private invitation viewed by neither sender nor target → `NotAuthorizedViewer`;
- requested state already persisted → `AlreadyInRequestedState`;
- one targeted row updated → `Updated`.

## 11. DAO contract

P2 adds one targeted update:

```sql
UPDATE nearby_room_messages
SET is_hidden_locally = :hidden
WHERE room_id = :roomId
  AND message_id = :messageId
```

The DAO returns the affected-row count.

Full-row upsert for Hide / Unhide is prohibited because it can overwrite concurrently managed delivery, retry, relay, or metadata fields.

## 12. Mutation boundary

P2 may modify only:

```text
is_hidden_locally
```

The following must remain unchanged:

- `body`;
- `local_render_source_body`;
- room ID and message ID;
- logical sender and relay sender metadata;
- target participant identity;
- creation, receipt, edit, delete, and TTL timestamps;
- delivery state;
- retry and relay state;
- ordering;
- invitation credential;
- send-limit evidence.

## 13. Transport and cryptographic invariants

Hide / Unhide must not:

- send or enqueue a Nearby message;
- broadcast or relay state;
- enqueue retry work;
- alter send-limit accounting;
- call GI2D;
- decrypt the invitation;
- look up recipient private identity;
- parse private plaintext;
- alter the encrypted envelope or protocol.

## 14. Race behavior

If the targeted DAO update returns zero rows, the mutation controller reloads the row once:

- row missing after reload → `MessageNotFound`;
- row already contains requested state → `AlreadyInRequestedState`;
- row remains present but unchanged after a zero-row result → fail closed as `MessageNotFound`.

The controller must not claim `Updated` without a confirmed one-row update.

## 15. Timeline implications

P2 does not render UI. It establishes the persistence authority needed by later presentation phases.

Later projections must preserve the same persisted identity and ordering:

```text
Visible private invitation
    ⇄ is_hidden_locally
Hidden private invitation placeholder
```

No synthetic placeholder entity is created.

## 16. Security requirements

- Repository remains authority.
- Non-target participants cannot mutate local visibility.
- Relay identity cannot override logical sender authority.
- Mutation does not depend on or expose plaintext.
- Mutation does not expose credential, crypto reason, key material, or audience details.
- Invalid structured content fails closed and is not mutated.

## 17. Focused testing requirements

Tests shall verify:

### Authorization

- logical sender may Hide;
- logical sender may Unhide;
- intended recipient may Hide;
- intended recipient may Unhide;
- third participant is rejected;
- relay sender does not gain logical-owner authority.

### Typed outcomes

- `Updated`;
- `AlreadyInRequestedState`;
- `MessageNotFound`;
- `NotGroupInvitation`;
- `NotAuthorizedViewer`;
- `InvitationInvalid`.

### Mutation integrity

- only `is_hidden_locally` changes;
- sender-local plaintext presence is not required for recipient authorization;
- ordinary and public messages are not mutated;
- malformed/non-canonical and message-identity-mismatched invitations are rejected.

### Race behavior

- zero-row update followed by requested persisted state → `AlreadyInRequestedState`;
- zero-row update followed by missing row → `MessageNotFound`.

## 18. Approved file scope

Production:

```text
android/app/src/main/java/app/peermesh/nearby/
NearbyGroupInvitationLocalVisibility.kt

android/app/src/main/java/app/peermesh/nearby/
NearbyRoomRepository.kt

android/data/src/main/java/app/peermesh/data/db/dao/
NearbyRoomMessageDao.kt
```

Tests:

```text
android/app/src/test/java/app/peermesh/nearby/
NearbyGroupInvitationLocalVisibilityGI2E6Test.kt
```

No Activity, adapter, layout, viewer, protocol, GI2D, entity, or migration change is authorized in P2.

## 19. Implementation conformance record

The approved P2 implementation:

- adds the exact typed result model;
- adds an internal Repository-owned local visibility controller;
- adds the single public Repository mutation;
- adds a targeted DAO update;
- authorizes only logical sender or intended recipient;
- performs no decrypt or plaintext lookup;
- adds focused tests for authorization, classification, mutation integrity, and races.

## 20. Verification gate

Required local verification command:

```bash
./gradlew :app:testEmergencyDebugUnitTest \
  --tests 'app.peermesh.nearby.NearbyPrivateGroupInvitationReceiveGI2E5Test' \
  --tests 'app.peermesh.nearby.NearbyPrivateGroupInvitationReceiveGI2E6Test' \
  --tests 'app.peermesh.nearby.NearbyGroupInvitationLocalVisibilityGI2E6Test' \
  --no-parallel
```

P2 is not considered verified until focused tests pass in an environment with the required Gradle distribution available.

## 21. Final contract verdict

```text
GI2E6-P2 Contract: LOCKED
Architecture: PASS
Authority preservation: PASS
Protocol impact: NONE
GI2D impact: NONE
Database schema impact: NONE
Transport impact: NONE
Production implementation: AUTHORIZED AND CONFORMING
Focused verification: PENDING LOCAL EXECUTION
GI2E6: NOT CLOSED / NOT LOCKED
```
