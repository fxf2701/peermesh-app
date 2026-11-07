# GI2E6-P7 — Public Invitation Presentation / Viewer Contract

**Milestone:** GI2E6 — Invitation Presentation Layer  
**Phase:** GI2E6-P7 — Public Invitation Presentation / Viewer Integration  
**Status:** LOCKED  
**Architecture Review:** PASS  
**Presentation Architecture Review:** PASS  
**Implementation Authorization:** AUTHORIZED

## 1. Purpose

GI2E6-P7 adds Public Group Invitation presentation to the existing Nearby Room timeline and adds a read-only viewer plus a public composer. P7 is additive. It MUST NOT redesign Group Invitation protocol, persistence, transport, retry, crypto, Repository send semantics, or the locked Private Invitation semantics from GI2E6-P1 through GI2E6-P6.

Priority remains:

```text
Stability > Correctness > Security > Maintainability > Performance
```

## 2. Locked baseline

GI2E6-P1 through GI2E6-P6 are CLOSED — VERIFIED. Their private-specific models, mapper, viewer, local hide/unhide behavior, composer, and send semantics remain authoritative and MUST NOT be generalized merely to accommodate Public Invitation.

Public Invitation is implemented as a small sibling presentation path.

## 3. Locked architecture

Read path:

```text
Persisted NearbyRoomMessageEntity
        ↓
NearbyRoomRepository
        ↓
Repository-owned Public Invitation inspection
        ↓
immutable typed presentation result
        ↓
Public presentation mapper
        ↓
NearbyRoomTimelineItem.PublicInvitation
        ↓
NearbyRoomMessageAdapter
        ↓
Public card
        ↓
identity-only navigation
        ↓
Read-only Public Invitation Viewer
```

Create path:

```text
Nearby Room UI
        ↓
Public Invitation Composer
        ↓
semantic draft(groupId, title, description)
        ↓
approved group-material authority
        ↓
NearbyPublicGroupInvitationSendPipeline
        ↓
existing Repository preparation/insertion authority
        ↓
existing transport / retry / relay
```

The read and creation paths MUST NOT exchange authority.

## 4. Repository authority

Repository is the authoritative boundary for interpreting persisted Nearby messages as Public Group Invitations. Activity, Adapter, Viewer, and Composer MUST NOT decode or encode Group Invitation wire content.

Forbidden:

```text
Activity / Adapter / Viewer → message.body → GroupInvitationProtocol.decode()
Composer → GroupInvitationProtocol.encodePublic()
```

Required:

```text
Persisted message → Repository → immutable typed result → Presentation
```

## 5. Public timeline inspection

Repository SHALL expose a Public Invitation timeline inspection operation equivalent to:

```text
inspectPublicGroupInvitationForTimeline(roomId, messageId)
```

It MUST resolve the authoritative persisted message, classify/decode within the Repository/protocol boundary, require supported public audience semantics, validate canonical representation, require `payload.cardId == message.message_id`, resolve authoritative logical sender information, and return only typed immutable presentation-safe data.

It MUST NOT decrypt, perform recipient lookup/key lookup, apply private audience filtering, consume private visibility hints, use `local_render_source_body` as public presentation authority, perform Join, or mutate group membership.

## 6. Public typed result

Public inspection SHALL use a public-specific result space rather than reuse private crypto receive results. It may distinguish valid public invitation, message-not-found, non-GI, non-public GI, unsupported/invalid GI, expiry, and message identity mismatch.

The typed presentation result MUST NOT expose raw JSON, canonical body, `InvitationCredential`, DAO entities, crypto state, recipient identity, or recipient key material.

## 7. Public visibility

A valid Public Invitation is room-visible. Its presentation MUST NOT depend on target participant identity, recipient identity, recipient key state, decrypt success, private visibility hints, or private audience omission rules.

## 8. Persistence

P7 introduces no new public plaintext persistence. Existing locked public persistence remains:

```text
body = canonical public invitation
local_render_source_body = null
```

No public presentation table, sender-local public plaintext copy, credential presentation storage, or secondary invitation cache is authorized.

## 9. Logical sender

Presentation MUST use the authoritative logical message origin. Relay/current transport identity MUST NOT replace the logical owner. P7 MUST NOT refactor locked private sender authority solely to share a helper; minimal public sibling logic is preferred when required.

## 10. Timeline model

`NearbyRoomTimelineItem` remains the shared renderer seam. P7 SHALL add an immutable additive sibling variant semantically equivalent to:

```text
NearbyRoomTimelineItem.PublicInvitation
```

It may contain message identity, ordering timestamp, sender identity needed internally, sender display name, title, and description. It MUST NOT contain credential, raw body/JSON, protocol metadata, crypto metadata, DAO entity, recipient identity, or private visibility state.

## 11. No generic invitation refactor

P7 SHALL NOT replace private/public sibling types with a generic invitation model or refactor P6 into a mode-driven generic composer. Private mapper/viewer/composer semantics remain unchanged. Small duplicated presentation glue is acceptable when it reduces regression risk.

## 12. Public presentation mapper

P7 SHALL use a small Public-specific pure mapper from typed Repository result to `NearbyRoomTimelineItem.PublicInvitation`, normal-message continuation, or safe omission. The locked private presentation mapper MUST NOT become a public/private mega-mapper.

Malformed or unsupported structured GI content MUST NOT fall through to normal message rendering if doing so would expose raw structured content.

## 13. RecyclerView

Public Invitation renders inside the existing Nearby Room RecyclerView. No second timeline/list/history source is authorized. Adapter receives immutable timeline items and MUST NOT parse protocol, derive credentials, access Repository, perform crypto, decide quota, or mutate membership.

Public card presentation is:

```text
Sender
Title
Description
```

General Nearby timeline time metadata MAY remain. No recipient label, private visibility hint, credential, protocol/crypto/internal IDs, Join, Accept, or Reject is allowed.

P7 interaction is OPEN only.

## 14. Public viewer

P7 SHALL implement a read-only Public Invitation Viewer as a sibling of the private viewer. Navigation is identity-only, semantically `roomId + messageId`. Sender/title/description MUST NOT be passed as authoritative Intent display payload.

Viewer re-resolves Repository authority and renders only:

```text
Sender
Title
Description
```

If revalidation fails, it renders a safe unavailable state. It MUST NOT display raw content, credential, protocol, crypto, internal IDs, recipient data, Join, Accept, or Reject.

## 15. Public composer

Public Composer contains group selection, title, description, preview, and send. It has no recipient selection or target participant state.

Immutable semantic draft contains only:

```text
groupId
title
description
```

It MUST NOT contain message/card identity, credential, canonical JSON, protocol payload, Repository entity, transport state, or retry state.

Composer presentation bounds are locked to the verified P6 UX bounds:

```text
Title: required, max 40 characters
Description: optional, max 60 characters
```

No silent truncation is allowed.

## 16. Composer entry and credential boundary

Public creation is room-level:

```text
Nearby Room → room menu/action → Public Composer(roomId)
```

No target participant argument is permitted.

Group selection stores only `groupId`. `InvitationCredential` is resolved transiently through the already-approved group material authority at the send boundary. It MUST NOT be rendered, previewed, placed in composer state or Intent extras, logged as UI state, persisted by P7, or included in timeline/viewer state.

## 17. Send semantics

P7 MUST use the locked `NearbyPublicGroupInvitationSendPipeline` unchanged. UI MUST NOT serialize, directly insert via DAO, create alternate Repository insertion, transport, retry, or relay flows.

Required chain:

```text
Composer draft → credential resolution → NearbyPublicGroupInvitationSendPipeline
→ Repository preparation → canonical public encoding → Repository canonical insertion
→ existing transport
```

Existing quota remains:

```text
One successful canonical insertion per logical sender per room
```

Repository transaction remains final quota authority. UI may prevent repeated taps but correctness MUST NOT rely on the UI guard.

Pre-insert creation failure preserves the draft and may retry. Once canonical insertion succeeds, including `PersistedForRetry`, the invitation exists, quota is consumed, composer completes, and P7 MUST NOT create a second invitation.

## 18. No recipient crypto dependency

Public presentation/composer MUST NOT depend on recipient encryption identity, recipient key lookup, `PrivateRecipientCrypto`, `JcaPrivateRecipientCrypto`, or private decrypt. P6 recipient-encryption runtime limitations do not block P7 public behavior.

## 19. Explicitly out of scope

P7 does not authorize:

```text
Public local hide/unhide
Join
Accept
Reject
membership mutation
group mutation
credential redemption
invitation completion/revocation lifecycle
new retry queue
protocol change
crypto change
database schema change
```

## 20. Stop-review conditions

Implementation MUST return to Architecture Review before making semantic changes to Group Invitation protocol/domain, GI2D crypto, recipient encryption identity, private receive/presentation/viewer/composer semantics, database schema, Nearby transport, retry, relay recovery, or BLE runtime.

## 21. Focused verification

P7 verification MUST establish:

- valid public GI projects to immutable `PublicInvitation` timeline state;
- non-GI remains normal message;
- private GI does not become public GI;
- invalid/unsupported structured GI never exposes raw body;
- public projection does not decrypt or perform recipient filtering;
- card and viewer render sender/title/description only;
- viewer uses identity-only navigation and Repository revalidation;
- composer contains no recipient state or credential state;
- composer delegates to existing public send pipeline;
- `Sent`, `PersistedForRetry`, `SendLimitReached`, and pre-insert failure preserve locked semantics;
- duplicate-tap UI guard does not replace Repository quota authority;
- relevant GI2E3 and P1–P6 regression tests pass;
- production compile passes.

## 22. Closure gate

This contract is LOCKED and authorizes implementation, but GI2E6-P7 itself remains:

```text
NOT CLOSED
```

P7 may close only after implementation, focused verification, production compile, security/UX review, Independent Review, and Manual Review all PASS, with P1–P6 semantics unchanged.
