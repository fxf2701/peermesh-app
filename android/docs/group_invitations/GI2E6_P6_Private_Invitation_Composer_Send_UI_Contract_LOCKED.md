# GI2E6-P6 — Private Invitation Composer / Send UI Contract

**Version:** 1.0  
**Status:** LOCKED  
**Milestone:** GI2E6 — Private Invitation Presentation / Viewer Integration

## 1. Purpose

P6 introduces the production UI used to create and send a private Group Invitation from a Nearby Room participant context.

Mandatory flow:

```text
Participant context
→ identity-only composer args
→ group selection
→ editable title and optional description
→ immutable semantic draft
→ existing GI2E4 private send pipeline
→ canonical local insertion
→ immediate transport submission or retry of the same persisted message
```

The composer is not protocol, crypto, persistence, quota, or transport authority.

## 2. Scope

P6 implements:

- entry from a non-local Nearby Room participant;
- identity-only composer navigation using `roomId` and `targetParticipantId`;
- eligible local-group selection;
- title prefill from the selected group name;
- editable title;
- optional description;
- live counters and bounded input;
- preview;
- send/cancel/progress state;
- invocation of the existing private invitation send pipeline;
- typed result presentation;
- sender-local timeline refresh through normal room reload behavior.

P6 does not implement public invitation composition, Accept, Reject, Join, membership mutation, protocol encoding in UI, encryption in UI, DAO access, draft persistence, or a new retry queue.

## 3. Authority

```text
Composer UI
→ NearbyPrivateGroupInvitationSendPipeline
→ NearbyRoomRepository preparation/insertion authority
→ GI2D encryption authority
→ persisted NearbyRoomMessageEntity
→ existing transport/retry/relay authority
```

The composer must not reference `GroupInvitationProtocol`, GI2D implementations, recipient private identity, or Nearby Room DAOs.

## 4. Entry and recipient

The composer is opened from a non-local participant row in a Nearby Room.

Navigation args contain only:

```kotlin
roomId
targetParticipantId
```

The target is not user-editable. The composer must reject missing, blank, or self-target args.

## 5. Group eligibility

Only groups available to the local actor may be selected. A group is eligible only when:

- the local actor is not removed;
- a current group key is available;
- a non-blank key ID is available;
- invite material is not invalidated by the existing rotation policy.

Selecting a group pre-fills the invitation title from the group name. Editing the title does not rename the persisted group.

## 6. Input limits

Title:

- required;
- editable;
- maximum 40 characters;
- live `x / 40` counter;
- no send-time truncation.

Description:

- optional;
- maximum 60 characters;
- live `x / 60` counter;
- no send-time truncation.

The UI prevents input beyond the limits. Repository/protocol validation remains authoritative.

## 7. Draft

The immutable composer draft contains only:

```kotlin
groupId
title
description
```

It must not contain recipient crypto identity, ciphertext, protocol envelope, message ID, or persistence state.

The invitation credential is derived only at the send boundary from existing eligible group invite material and passed to the existing send pipeline. It is not displayed, logged, persisted by the composer, or included in UI state.

## 8. Preview

Preview may show:

- local sender display name;
- title;
- description when present.

Preview is non-authoritative and must not serialize or encrypt content.

## 9. Send eligibility

Send is enabled only when:

- a group is selected;
- trimmed title is non-empty;
- title and description are within limits;
- no send attempt is active.

During send, group selection, title, description, send, and cancel are disabled and a progress indicator is shown.

## 10. Quota semantics

Repository transaction remains the final quota authority.

```text
Public invitation:
one successful canonical local insertion per logical sender per room.

Private invitation:
one successful canonical local insertion per logical sender per target participant per room.
```

P6 does not count quota locally.

Hide, Unhide, delivery-state changes, relay, retry, or presentation state do not restore quota.

## 11. Failure boundary

The composer distinguishes creation from delivery.

```text
Creation failed before canonical DB insert
→ quota not consumed
→ retain form input
→ user may retry creation.

Canonical DB insert succeeded and immediate delivery succeeded
→ close composer
→ show success.

Canonical DB insert succeeded but immediate delivery failed
→ quota consumed
→ close composer
→ existing retry/relay continues with the same messageId
→ do not create a second invitation.
```

`SendLimitReached` is shown as an already-sent state. Internal crypto, protocol, key, stack-trace, or exception details must not be exposed.

## 12. State and persistence

The composer does not persist drafts to Room, preferences, files, analytics, or logs.

The Activity may hold draft text in normal in-memory view state while active. Process recreation must not persist invitation credentials or finalized protocol content.

## 13. Security

The composer must not log title, description, group key, invitation credential, raw invite link, recipient key identity, plaintext inner JSON, or ciphertext.

The composer must not directly access Nearby message persistence.

## 14. Accessibility

Group, title, description, counters, preview, send, cancel, progress, and errors must have understandable labels and normal focus order. Error and success feedback must not expose internal failure distinctions.

## 15. Testing

Focused tests must verify:

- title/description bounds;
- immutable draft creation;
- send enablement;
- sending disables repeat submission;
- `Sent` mapping;
- `PersistedForRetry` mapping;
- `SendLimitReached` mapping;
- pre-insert failure remains retryable;
- composer uses the existing send pipeline;
- identity-only navigation;
- no protocol, GI2D, or DAO references in UI.

Runtime E2E and instrumentation are deferred to the GI2E6 post-P6 verification bundle because the androidTest source set has a known unrelated compile blocker.

## 16. Expected files

Production additions:

```text
app/src/main/java/app/peermesh/NearbyPrivateInvitationComposerActivity.kt
app/src/main/java/app/peermesh/nearby/presentation/NearbyPrivateInvitationComposerState.kt
app/src/main/res/layout/activity_nearby_private_invitation_composer.xml
```

Expected modifications:

```text
app/src/main/java/app/peermesh/NearbyRoomActivity.kt
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
```

Tests:

```text
app/src/test/java/app/peermesh/nearby/presentation/NearbyPrivateInvitationComposerGI2E6Test.kt
app/src/test/java/app/peermesh/NearbyPrivateInvitationComposerArchitectureGI2E6Test.kt
```

## 17. Contract change policy

Any semantic change to entry context, target selection, group eligibility, field limits, draft shape, quota handling, pre/post-insert behavior, send pipeline, credential boundary, or retry semantics requires an approved amendment before production code changes.

## 18. Verdict

```text
GI2E6-P6 Contract: LOCKED
Architecture: PASS
Authority preservation: PASS
Protocol impact: NONE
Crypto semantic impact: NONE
Persistence schema impact: NONE
Visible UI impact: YES
Production implementation: AUTHORIZED
```
