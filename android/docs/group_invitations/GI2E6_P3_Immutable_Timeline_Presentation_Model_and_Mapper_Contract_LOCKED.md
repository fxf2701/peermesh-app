# GI2E6-P3 — Immutable Timeline Presentation Model and Mapper Contract

**Version:** 1.0  
**Status:** LOCKED  
**Milestone:** GI2E6 — Private Invitation Presentation / Viewer Integration  
**Change policy:** Contract amendment required before semantic production changes

---

## 1. Purpose

P3 defines the immutable presentation boundary between Repository-authoritative private invitation inspection and later Nearby Room rendering.

Required flow:

```text
NearbyRoomRepository
        ↓
NearbyPrivateGroupInvitationReceiveResult
        ↓
Repository-authoritative presentation context
        ↓
NearbyPrivateGroupInvitationPresentationMapper
        ↓
Immutable timeline projection
        ↓
P4 Renderer / RecyclerView
```

P3 prevents presentation code from becoming protocol, crypto, persistence or audience authority.

---

## 2. Scope

P3 implements only:

- immutable Nearby Room timeline item types;
- private invitation visible-card state;
- private invitation hidden state;
- private invitation safe placeholder state;
- explicit projection outcomes for item, normal-message compatibility and omission;
- safe receive-result mapping;
- sender/recipient audience labels;
- report, moderation and visibility action eligibility;
- focused presentation-mapper unit tests.

P3 does not implement:

- RecyclerView or Adapter integration;
- Activity integration;
- XML layouts;
- viewer navigation;
- Hide/Unhide execution;
- report execution;
- block or mute execution;
- Accept, Reject or Join;
- public invitation presentation;
- Repository, DAO, protocol or GI2D changes.

No visible UI change is required at P3 completion.

---

## 3. Authority

### 3.1 Repository remains authority

Repository remains authoritative for:

- persisted message lookup;
- exact Group Invitation classification;
- logical sender resolution;
- audience authorization;
- sender-local canonical validation;
- recipient decryption;
- typed receive result;
- local Hide/Unhide mutation.

### 3.2 Presentation owns projection only

P3 owns only:

- immutable user-facing state;
- safe failure categorization;
- renderer action eligibility.

P3 must not:

- parse `message.body`;
- read `local_render_source_body`;
- decrypt;
- lookup recipient identity;
- reconstruct target identity;
- reconstruct sender authority from UI state;
- mutate persistence;
- invoke transport.

### 3.3 GI2D remains pure crypto

GI2D must remain unaware of presentation models, timeline rows, placeholders, actions and viewer behavior.

---

## 4. Repository-Authoritative Presentation Context

Failure results do not always carry an immutable invitation view. P3 therefore accepts a small non-sensitive context only when an upstream Repository-owned orchestration has already established an authorized local audience.

```kotlin
data class NearbyPrivateGroupInvitationPresentationContext(
    val messageId: String,
    val createdAtMs: Long,
    val isHiddenLocally: Boolean,
    val audience: PrivateInvitationAuthorizedAudience,
    val senderId: String,
    val senderDisplayName: String,
    val canMutePeer: Boolean,
)
```

Allowed audience values:

```kotlin
enum class PrivateInvitationAuthorizedAudience {
    LOCAL_SENDER,
    INTENDED_RECIPIENT,
}
```

This context is evidence already established below presentation. It must not be constructed by parsing protocol in Activity, Adapter or mapper code.

If authoritative audience context is unavailable, P3 must omit private invitation success/failure projection rather than risk revealing a private invitation to a non-target participant.

---

## 5. Timeline Item Model

P3 defines:

```kotlin
sealed interface NearbyRoomTimelineItem {
    val messageId: String
    val createdAtMs: Long
}
```

Permitted item types:

- `NormalMessage`
- `PrivateInvitation`
- `HiddenPrivateInvitation`
- `PrivateInvitationPlaceholder`

### 5.1 Normal-message compatibility

`NormalMessage` may temporarily retain `NearbyRoomMessageEntity` to preserve existing non-invitation behavior without redesigning all Nearby Chat presentation.

This exception applies only to normal-message compatibility.

### 5.2 Private invitation isolation

No private invitation item may retain:

- `NearbyRoomMessageEntity`;
- raw body;
- sender-local source;
- encrypted envelope;
- invitation credential;
- expiry;
- crypto result details;
- Repository or DAO references.

---

## 6. Visible Private Invitation

A visible private invitation may contain only:

- message ID;
- creation timestamp;
- validated logical sender ID;
- safe sender display name;
- title;
- short description;
- audience label;
- immutable action set.

Audience labels:

```kotlin
enum class PrivateInvitationAudienceLabel {
    SENT_PRIVATELY,
    RECEIVED_PRIVATELY,
}
```

Mapping:

- `LOCAL_SENDER` → `SENT_PRIVATELY`
- `INTENDED_RECIPIENT` → `RECEIVED_PRIVATELY`

The mapper must verify that context message ID, creation timestamp and sender ID match the validated invitation view. A mismatch maps to a safe invalid placeholder for the already-authorized audience.

---

## 7. Content Limits

P3 follows `Group_Invitation_Spec_v2.md`:

```text
Title:
required
maximum 40 characters

Description:
optional
maximum 60 characters
```

The mapper must not truncate, normalize or repair content.

An unexpected blank/oversized title or oversized description maps to a safe invalid placeholder for an already-authorized audience.

Invitation expiry remains a validation and persistence-lifecycle concern and is not copied into P3 presentation models.

---

## 8. Hidden Private Invitation

When an authorized successful invitation has `isHiddenLocally == true`, P3 produces `HiddenPrivateInvitation` for the same message identity and timeline ordering.

The hidden item must not copy:

- title;
- description;
- credential;
- target label;
- raw protocol content;
- sender-local source.

It may retain only:

- message ID;
- creation timestamp;
- validated sender ID;
- safe sender display name;
- allowed actions.

A hidden item is a presentation projection of the existing persisted message, not a synthetic persisted placeholder.

---

## 9. Placeholder Categories

Only these user-facing placeholder kinds are permitted:

```kotlin
enum class PrivateInvitationPlaceholderKind {
    UNAVAILABLE,
    UNSUPPORTED,
    INVALID,
}
```

P3 must not expose placeholder kinds such as:

- wrong recipient;
- wrong key;
- authentication failed;
- malformed ciphertext;
- invalid crypto context;
- expired.

Security and protocol distinctions remain beneath presentation.

---

## 10. Projection Outcomes

P3 uses an explicit typed projection result:

```kotlin
sealed interface NearbyPrivateGroupInvitationTimelineProjection {
    data class Item(val item: NearbyRoomTimelineItem)
    data object UseNormalMessage
    data object Omit
}
```

Expected typed outcomes must not be represented by exceptions.

---

## 11. Exact Receive-Result Mapping

### 11.1 Success

`Invitation(view)`:

- authoritative context absent → `Omit`;
- authorized and visible → `PrivateInvitation`;
- authorized and hidden → `HiddenPrivateInvitation`;
- context/view mismatch or out-of-contract content → `INVALID` placeholder.

### 11.2 Audience or lifecycle omission

The following always map to `Omit`, regardless of hidden state:

- `NotForLocalParticipant`
- `Expired`
- `MessageNotFound`

Audience omission has the highest precedence.

### 11.3 Normal/public compatibility

The following map to `UseNormalMessage`:

- `NotGroupInvitation`
- `NotPrivateInvitation`

P3 does not implement public invitation cards.

### 11.4 Unavailable

The following map to `UNAVAILABLE` only when authoritative audience context exists; otherwise they map to `Omit`:

- `RecipientIdentityUnavailable`
- `RecipientIdentityInvalidated`
- `UnsupportedPlatform`
- `CryptoUnavailable`
- `KeyAgreementFailed`

### 11.5 Unsupported

The following map to `UNSUPPORTED` only when authoritative audience context exists; otherwise they map to `Omit`:

- `UnsupportedCryptoProtocol`
- `UnsupportedCryptoAlgorithm`

### 11.6 Invalid

The following map to `INVALID` only when authoritative audience context exists; otherwise they map to `Omit`:

- `UnsupportedOrInvalidEnvelope`
- `MessageIdentityMismatch`
- `WrongRecipientKey`
- `AuthenticationFailed`
- `InvalidEncryptedPayload`
- `InvalidCryptoContext`
- `MalformedInnerPayload`

UI must not distinguish these invalid reasons.

---

## 12. Report and Moderation Policy

### 12.1 Peer reporting

A local user may report any other peer according to the existing moderation policy.

A local user must not be offered an action to report themselves.

The invitation sender may report the intended recipient or any other user through an appropriate peer-level UI. The sender-local invitation card itself does not infer a peer target and therefore does not expose `REPORT_PEER` against the sender.

Any user may report the invitation sender through a visible surface that authoritatively identifies that sender.

### 12.2 Message/invitation reporting

Any user authorized to view a message or invitation may report that visible content.

Private audience restrictions remain authoritative. A non-target participant receives no private invitation timeline item, report-invitation action, sender attribution or evidence that the invitation exists.

The local sender is not offered `REPORT_INVITATION` for their own sender-local representation.

The intended recipient may report the visible private invitation and its validated sender.

---

## 13. Action Enum

Permitted P3 actions:

```kotlin
enum class PrivateInvitationAction {
    OPEN,
    HIDE,
    UNHIDE,
    REPORT_INVITATION,
    REPORT_PEER,
    BLOCK_PEER,
    MUTE_PEER,
}
```

Prohibited actions include:

- Reply
- Edit
- Delete
- Recall
- Accept
- Reject
- Join
- Copy raw content
- Copy credential
- Resend

P3 projects eligibility only and executes no action.

---

## 14. Exact Action Matrix

### 14.1 Local sender — visible

Allowed:

- `OPEN`
- `HIDE`

### 14.2 Local sender — hidden

Allowed:

- `UNHIDE`

### 14.3 Intended recipient — visible

Allowed:

- `OPEN`
- `HIDE`
- `REPORT_INVITATION`
- `REPORT_PEER`
- `BLOCK_PEER`
- `MUTE_PEER` only when existing policy allows it

### 14.4 Intended recipient — hidden

Allowed:

- `UNHIDE`
- `REPORT_INVITATION`
- `REPORT_PEER`
- `BLOCK_PEER`
- `MUTE_PEER` only when existing policy allows it

### 14.5 Intended-recipient failure placeholder

Allowed when sender attribution and audience context remain Repository-authoritative:

- `REPORT_INVITATION`
- `REPORT_PEER`
- `BLOCK_PEER`
- `MUTE_PEER` only when existing policy allows it

No Open, Hide, Unhide, Reply, Edit, Delete or Join action is allowed.

### 14.6 Sender-local failure placeholder

No invitation-card report or peer action is projected because the card represents the local sender's own content and sender identity.

---

## 15. Immutability

All P3 presentation types must be immutable Kotlin value types.

They must not contain:

- mutable collections exposed to consumers;
- Repository or DAO references;
- mutation callbacks;
- protocol parsers;
- crypto callbacks;
- Android `Context`;
- View, Activity or Fragment references.

Action sets must be immutable from the consumer's perspective.

---

## 16. Mapper Purity

`NearbyPrivateGroupInvitationPresentationMapper` must be deterministic and side-effect free.

It must not perform:

- IO;
- database access;
- Repository calls;
- protocol parsing;
- crypto;
- identity lookup;
- persistence mutation;
- transport;
- navigation;
- Toast/Snackbar rendering.

RecyclerView binding must not invoke this mapper in a way that causes Repository inspection or decryption; inspection occurs upstream before P3 mapping.

---

## 17. Security Invariants

P3 must preserve:

- Repository authority;
- sender-local validated presentation;
- intended-recipient-only rendering;
- non-target omission;
- no plaintext persistence;
- no protocol bypass;
- no credential leakage;
- no expiry leakage;
- no crypto-detail leakage;
- no authority inversion.

When authoritative audience context is missing, privacy wins over placeholder visibility.

---

## 18. Testing Requirements

Focused tests must verify:

- sender visible mapping and exact action matrix;
- recipient visible mapping and exact action matrix;
- sender hidden mapping;
- recipient hidden mapping;
- title/description preservation;
- absence of credential, expiry, raw body and entity from private items;
- `NotForLocalParticipant`, `Expired` and `MessageNotFound` omission;
- normal/public compatibility projection;
- exact unavailable, unsupported and invalid grouping;
- no failure placeholder without authoritative audience context;
- context/view identity mismatch fail-safe behavior;
- 40/60 content-limit fail-safe behavior;
- sender cannot report self or own invitation through sender-local card;
- recipient can report visible invitation and sender;
- conditional mute action;
- prohibited actions and sensitive placeholder names are absent.

---

## 19. Approved File Scope

Production files:

```text
android/app/src/main/java/app/peermesh/nearby/presentation/
NearbyRoomTimelineItem.kt

android/app/src/main/java/app/peermesh/nearby/presentation/
NearbyPrivateGroupInvitationPresentationMapper.kt
```

Test file:

```text
android/app/src/test/java/app/peermesh/nearby/presentation/
NearbyPrivateGroupInvitationPresentationMapperGI2E6Test.kt
```

P3 must not modify:

- NearbyRoomRepository;
- NearbyRoomMessageDao;
- NearbyRoom Activity or Adapter;
- XML layouts;
- AndroidManifest;
- GI2D;
- Group Invitation protocol;
- database schema.

---

## 20. Change Control

Required sequence for any semantic change:

```text
Contract amendment
        ↓
Architecture / Security / UX review as applicable
        ↓
Approval
        ↓
Production code change
        ↓
Focused verification
        ↓
Independent code-contract review
```

Code that diverges from this locked contract without an approved amendment is non-compliant even if tests pass.

---

## 21. Expected Outcome

After P3:

```text
Repository-authoritative typed receive result and audience context
        ↓
Pure immutable mapper
        ↓
Private invitation item / hidden item / safe placeholder / omit
        ↓
Ready for GI2E6-P4 RecyclerView integration
```

P3 does not itself create visible UI changes.

---

## 22. Lock Verdict

```text
GI2E6-P3 Contract: LOCKED
Architecture: PASS
Authority preservation: PASS
Security: PASS
UX policy: PASS
Protocol impact: NONE
Crypto impact: NONE
Persistence impact: NONE
UI integration: OUT OF SCOPE
Implementation: AUTHORIZED
```
