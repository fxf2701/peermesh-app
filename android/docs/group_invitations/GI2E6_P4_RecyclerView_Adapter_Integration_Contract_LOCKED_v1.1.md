# GI2E6-P4 — RecyclerView / Adapter Integration Contract

**Version:** 1.1  
**Status:** LOCKED  
**Milestone:** GI2E6 — Private Invitation Presentation / Viewer Integration

---

## 1. Purpose

P4 integrates the immutable timeline presentation model from GI2E6-P3 into the existing Nearby Chat RecyclerView pipeline.

P4 is the first GI2E6 phase expected to create a visible timeline UI change.

Its purpose is to ensure that:

```text
NearbyRoomRepository
        ↓
Repository-owned timeline inspection
        ↓
Typed receive result + authoritative presentation context
        ↓
P3 immutable presentation mapper
        ↓
Immutable timeline item
        ↓
Adapter view type
        ↓
ViewHolder
        ↓
Visible timeline UI
```

Private invitations must no longer be rendered as raw `message.body`.

The Adapter remains a renderer only.

It must not become:

- protocol authority;
- Repository authority;
- audience authority;
- crypto authority;
- persistence authority;
- moderation authority.

---

## 2. Scope

P4 implements:

- Repository-owned private invitation timeline inspection;
- authoritative non-sensitive presentation context;
- Activity-side timeline projection orchestration;
- Adapter migration from entity list to immutable timeline item list;
- normal-message compatibility wrapper;
- private invitation ViewHolder;
- private invitation placeholder ViewHolder;
- renderer selection;
- sender and recipient invitation card presentation;
- hidden invitation placeholder presentation;
- safe failure placeholder presentation;
- action-menu projection;
- Hide/Unhide callback wiring to the P2 Repository API;
- safe private-invitation report seam;
- timeline reload after successful local visibility mutation;
- RecyclerView identity and ordering preservation;
- accessibility for timeline cards and placeholders;
- focused unit and integration tests.

P4 does not implement:

- read-only invitation viewer screen;
- Accept;
- Reject;
- Join;
- group membership mutation;
- public invitation presentation;
- invitation creation-form title/description UX;
- protocol parsing in UI;
- crypto;
- new invitation persistence;
- expiry display;
- a generic structured-content renderer framework.

Card click may be introduced as a typed callback seam for P5, but P4 must not open a not-yet-implemented viewer.

---

## 3. Locked Dependencies

P4 depends on the following locked contracts and implementations:

```text
GI2E6-P1
Repository Sender-local Presentation Authority

GI2E6-P2
Repository Local Hide / Unhide Authority

GI2E6-P3
Immutable Timeline Presentation Model and Mapper
```

P4 consumes their public outputs and preserves their semantics.

P4 must not duplicate or reinterpret:

- sender-local validation;
- recipient decryption;
- audience validation;
- failure classification;
- action matrix;
- Hide/Unhide authorization;
- invitation identity;
- private invitation credential.

P4 additionally requires the Repository-owned timeline-inspection and safe-report seams defined in this contract.

---

## 4. Existing Timeline Baseline

The existing Nearby Chat timeline follows an entity-centric flow:

```text
NearbyRoomRepository.listMessages(...)
        ↓
List<NearbyRoomMessageEntity>
        ↓
NearbyRoomActivity
        ↓
NearbyRoomMessageAdapter
        ↓
message.body rendered directly
```

P4 changes the private-invitation presentation boundary to:

```text
NearbyRoomRepository.listMessages(...)
        ↓
Repository timeline inspection per persisted row
        ↓
P3 presentation mapper
        ↓
List<NearbyRoomTimelineItem>
        ↓
NearbyRoomMessageAdapter
```

The Adapter must no longer accept a raw message-entity list as its primary submitted list after P4.

---

## 5. Mandatory Architecture

The approved orchestration is:

```text
NearbyRoomActivity
        ↓
load persisted timeline rows
        ↓
for each row:
    Repository.inspectPrivateGroupInvitationForTimeline(...)
        ↓
    receive typed result and authoritative safe context
        ↓
    map through P3 mapper
        ↓
    omit or create immutable timeline item
        ↓
submit immutable list to Adapter
```

The following architectures are prohibited:

```text
Adapter
    ↓
Repository inspection
```

```text
ViewHolder
    ↓
protocol parse or decrypt
```

```text
Activity
    ↓
read message.body or local_render_source_body
    ↓
reconstruct invitation presentation context
```

Repository inspection and mapping must complete before items are submitted to RecyclerView.

---

## 6. Repository Timeline Inspection Contract

Repository shall expose one authoritative private-invitation timeline-inspection API.

An equivalent shape is:

```kotlin
data class NearbyPrivateGroupInvitationTimelineInspection(
    val receiveResult: NearbyPrivateGroupInvitationReceiveResult,
    val context: NearbyPrivateGroupInvitationPresentationContext?,
)
```

An equivalent Repository API is:

```kotlin
fun inspectPrivateGroupInvitationForTimeline(
    roomId: String,
    messageId: String,
): NearbyPrivateGroupInvitationTimelineInspection
```

Exact names may be refined during implementation only if semantics remain identical.

Any semantic change requires an approved contract amendment before code changes.

Repository remains authority for:

- persisted message lookup;
- exact Group Invitation classification;
- outer-envelope validation;
- message identity validation;
- logical sender resolution;
- local audience role;
- sender-local source validation;
- intended-recipient decryption;
- hidden local state;
- placeholder eligibility;
- audience omission;
- safe presentation context.

Presentation must not reconstruct this context from `NearbyRoomMessageEntity`.

---

## 7. Authoritative Presentation Context

Repository context may contain only non-sensitive data required by P3 and P4.

An equivalent shape is:

```kotlin
data class NearbyPrivateGroupInvitationPresentationContext(
    val messageId: String,
    val createdAtMs: Long,
    val logicalSenderId: String?,
    val senderDisplayName: String?,
    val localAudienceRole: NearbyPrivateGroupInvitationLocalAudienceRole,
    val hiddenLocally: Boolean,
    val moderationCapabilities: NearbyPrivateGroupInvitationModerationCapabilities,
)
```

The exact shape may be smaller if all required semantics remain available.

Context must not contain:

- invitation credential;
- raw `message.body`;
- `local_render_source_body`;
- ciphertext;
- nonce;
- recipient key ID;
- recipient private identity;
- authenticated crypto context;
- crypto exception;
- protocol debug information.

Display name is a presentation label only.

It must never be used as identity or authorization evidence.

---

## 8. Persisted Invitation Owner Authority

For P4 presentation and moderation, a persisted private Group Invitation must use:

```text
owner_device_id
```

as the authoritative invitation-sender identity.

P4 must not fall back to:

```text
sender_device_id
```

because `sender_device_id` is transport/hop metadata and must not replace the logical message origin.

If `owner_device_id` is null or blank:

```text
private invitation metadata is invalid for P4 presentation
→ no sender attribution
→ no peer moderation target
→ omit the row because authoritative audience context cannot be completed
```

P4 does not change the Nearby Room transport receive contract. The separate decision about making `origin_device_id` mandatory on wire is explicitly deferred until after invitation presentation is complete.

Sender display-name resolution must use:

```text
local profile, when owner_device_id == local device ID
OR
participant profile looked up by owner_device_id
OR
neutral unknown-sender fallback
```

P4 must not use relay `sender_display_name` as a fallback for a missing owner profile.

---

## 8. Context Visibility and Audience Omission

Repository shall return:

```text
context == null
```

for outcomes where no private invitation row may be projected, including:

```text
NotForLocalParticipant
MessageNotFound
Expired
```

Presentation must omit the row immediately.

It must not create:

- blank row;
- hidden placeholder;
- invalid placeholder;
- accessibility node;
- menu target;
- notification preview;
- sender attribution;
- evidence that the private invitation exists.

Audience omission remains a security boundary and has the highest precedence.

---

## 9. Timeline Projection Ownership

P4 may place orchestration in:

- `NearbyRoomActivity`;
- a small Activity-owned coordinator;
- an existing Nearby presentation coordinator if one already owns timeline preparation.

P4 must not create a new repository, service or subsystem solely for projection.

The projection owner may use:

- Repository timeline inspection;
- P3 mapper;
- participant display information already resolved or approved by Repository context;
- moderation capability flags supplied by authoritative context.

It must not:

- decode `message.body`;
- read `local_render_source_body`;
- decrypt;
- reconstruct logical sender identity;
- infer recipient status from adapter alignment;
- classify crypto or protocol failures itself.

---

## 10. Mapper Input Contract

P4 shall consume:

```text
NearbyPrivateGroupInvitationTimelineInspection
        ↓
P3 Mapper
        ↓
NearbyRoomTimelineItem
```

The Activity must not combine raw entity fields with `receiveResult` to reconstruct presentation authority.

For normal-message compatibility, the original persisted entity may continue through `NearbyRoomTimelineItem.NormalMessage`.

For private invitation items, only Repository inspection and P3 mapping are permitted.

---

## 11. Adapter Input Contract

`NearbyRoomMessageAdapter` shall consume:

```kotlin
List<NearbyRoomTimelineItem>
```

It must not consume:

```kotlin
List<NearbyRoomMessageEntity>
```

as its primary submitted list after P4.

Normal-message compatibility remains supported through:

```kotlin
NearbyRoomTimelineItem.NormalMessage
```

Private invitation items must never expose their underlying entity to the Adapter.

---

## 12. Required View Types

P4 shall introduce at least:

```text
NORMAL_MESSAGE

PRIVATE_INVITATION

PRIVATE_INVITATION_PLACEHOLDER
```

The Adapter may use one placeholder ViewHolder for both:

- `HiddenPrivateInvitation`;
- `PrivateInvitationPlaceholder`;

only if binding behavior remains explicit and leakage-safe.

It must not use body-content heuristics to select a view type.

View type selection must be based only on the sealed timeline item type.

---

## 13. Normal Message Compatibility

Normal messages must retain current behavior as far as P4 scope allows:

- sender name;
- avatar;
- timestamp;
- body;
- edited state;
- reply preview;
- delivery state;
- highlighting;
- existing popup actions;
- block/mute/report behavior;
- compose synchronization.

P4 must not redesign normal message rows.

Any normal-message regression caused by adapter migration is a P4 blocker.

---

## 14. Visible Private Invitation Card

A visible private invitation card may render only fields provided by:

```kotlin
NearbyRoomTimelineItem.PrivateInvitation
```

Required visual information:

- private invitation indicator;
- validated sender display name;
- invitation title;
- short description when present;
- message timestamp;
- sender/recipient audience wording;
- overflow action affordance where actions exist.

It must not display:

- raw body;
- JSON;
- ciphertext;
- nonce;
- credential;
- recipient key ID;
- protocol version;
- crypto reason;
- expiry time.

### Sender wording

For local sender:

```text
Đã gửi lời mời riêng tư
```

A target-recipient display label may be shown only if supplied by an approved immutable presentation field.

P4 must not reconstruct target identity from raw persisted data.

### Recipient wording

For intended recipient:

```text
Lời mời riêng tư dành cho bạn
```

or equivalent wording approved during UX review.

---

## 15. Title and Description Rendering

P4 must render title and description exactly as supplied by the P3 immutable model.

P4 must not:

- truncate for protocol reasons;
- normalize;
- silently repair;
- decode escape sequences;
- reconstruct from group data.

The locked invitation content limits remain:

```text
Title:
required
maximum 40 characters

Description:
optional
maximum 60 characters
```

Visual ellipsizing caused by layout width is permitted only if:

- no content mutation occurs;
- accessibility can announce the available full presentation text;
- P5 will later provide the complete read-only viewer.

The timeline card may use a bounded number of visual lines.

---

## 16. Hidden Invitation Presentation

`HiddenPrivateInvitation` must render as a safe placeholder.

Required wording:

```text
Lời mời này đã ẩn.
```

Required action:

```text
Bỏ ẩn
```

The hidden ViewHolder must not receive or retain:

- title;
- description;
- credential;
- target identity;
- raw body;
- sender-local source.

The hidden item must preserve:

- original message ID;
- original creation timestamp;
- original timeline position;
- validated sender attribution when supplied.

It must not create a new persisted row or synthetic message identity.

---

## 17. Failure Placeholder Presentation

`PrivateInvitationPlaceholder` must render one of three safe categories.

### Unavailable

```text
Không thể mở lời mời này lúc này.
```

### Unsupported

```text
Phiên bản ứng dụng hiện tại chưa hỗ trợ lời mời này.
```

### Invalid

```text
Không thể hiển thị lời mời này.
```

Exact localized wording may be adjusted without a contract amendment if semantics and information exposure remain unchanged.

The UI must not reveal:

- authentication failure;
- wrong recipient key;
- malformed ciphertext;
- invalid crypto context;
- protocol algorithm;
- internal exception;
- target identity.

A failure placeholder may be created only when Repository context proves that the local user is an authorized viewer.

If an envelope is invalid to the extent that Repository cannot canonically decode it and cannot establish authoritative audience context, P4 must omit the row. It must not infer audience from malformed JSON and must not render a generic placeholder that reveals the invitation exists.

---

## 18. Action Rendering Contract

The Adapter must render only actions already contained in:

```kotlin
item.actions
```

It must not infer actions from:

- sender ID;
- bubble alignment;
- entity ownership;
- raw body;
- message-type heuristics;
- current ViewHolder class.

P4 must support the P3 action set:

```text
OPEN
HIDE
UNHIDE
REPORT_INVITATION
REPORT_PEER
BLOCK_PEER
MUTE_PEER
```

P4 must never introduce invitation actions:

```text
REPLY
EDIT
DELETE
RECALL
ACCEPT
REJECT
JOIN
COPY_RAW_CONTENT
COPY_CREDENTIAL
```

---

## 19. Reporting Policy

### Peer reporting

A local user may report any other peer according to the existing moderation policy.

A local user must not be offered an action to report themselves.

The invitation sender may report any other room participant, including the intended recipient, through the appropriate peer-level UI.

Any other participant may report the invitation sender through an authorized visible surface.

### Message and invitation reporting

Any user authorized to view a message or invitation may report that visible content.

For a private invitation:

```text
Local sender:
may view sender-local representation;
must not receive REPORT_INVITATION for their own invitation.

Intended recipient:
may receive REPORT_INVITATION;
may receive REPORT_PEER targeting the validated logical sender.

Non-target participant:
must receive no invitation row and no report-invitation action.
```

Private audience restrictions remain authoritative.

Reporting capability must not reveal a private invitation to a non-target participant.

---

## 20. Safe Private Invitation Report Seam

P4 shall not pass `NearbyRoomMessageEntity` or raw body content into the private-invitation report flow.

Repository or the existing moderation layer shall expose a safe private-invitation reporting operation.

An equivalent API is:

```kotlin
fun createNearbyRoomPrivateInvitationReport(
    roomId: String,
    messageId: String,
    reportedPeerId: String,
    reasonCode: String,
    userNote: String?,
    alsoBlock: Boolean,
): String
```

The exact return type may follow the existing moderation convention.

The operation must revalidate authoritative persisted identity and authorization before creating the report. It must require a canonical private invitation envelope, `cardId == messageId`, valid persisted room/message context, and a non-blank `owner_device_id` equal to the reported peer. It must not fall back to `sender_device_id`.

Presentation may supply only safe identity and user-input fields such as:

```text
roomId
messageId
validated logical sender ID
reason code
optional user note
also-block choice
content type = private_group_invitation
```

The report seam must not require or accept:

- full `NearbyRoomMessageEntity`;
- raw `message.body`;
- `local_render_source_body`;
- decrypted title or description as raw evidence;
- invitation credential;
- ciphertext;
- nonce;
- target key identity;
- crypto details.

The report record may reference the persisted message by identity according to the existing moderation architecture.

It must not create new plaintext persistence for the invitation.

If the existing moderation API cannot satisfy this contract, P4 must add the smallest safe overload or specialized seam rather than bypassing the boundary.

---

## 21. Hide Action Flow

Approved flow:

```text
User selects Hide
        ↓
typed Adapter callback
        ↓
Activity or coordinator
        ↓
Repository.setGroupInvitationHiddenLocally(
    roomId,
    messageId,
    true
)
        ↓
typed Repository result
        ↓
timeline reload
```

P4 must not directly mutate the submitted item to simulate persistence success before Repository confirmation.

A temporary disabled state may be used to prevent repeated taps, but persisted state remains authoritative.

---

## 22. Unhide Action Flow

Approved flow:

```text
User selects Unhide
        ↓
typed Adapter callback
        ↓
Activity or coordinator
        ↓
Repository.setGroupInvitationHiddenLocally(
    roomId,
    messageId,
    false
)
        ↓
typed Repository result
        ↓
timeline reload
```

Unhide must reconstruct visible presentation from Repository inspection.

It must not restore title or description from ViewHolder memory or Adapter cache.

---

## 23. Local Visibility Result Handling

### Updated

```text
Reload timeline.
```

### AlreadyInRequestedState

```text
Reload or retain current projected state without error.
```

### MessageNotFound

```text
Reload timeline.
```

The message may have been removed by room cleanup.

### NotAuthorizedViewer

Show only a generic safe error, such as:

```text
Không thể thay đổi lời mời này.
```

Then refresh timeline.

### NotGroupInvitation / InvitationInvalid

Show a generic safe message, such as:

```text
Lời mời không còn khả dụng.
```

Then refresh timeline.

P4 must not expose typed internal reasons directly.

---

## 24. Typed Adapter Callbacks

Adapter callbacks must use typed item identity and action.

An approved shape is:

```kotlin
fun interface OnTimelineAction {
    fun onAction(
        messageId: String,
        action: PrivateInvitationAction,
    )
}
```

or equivalent separate typed callbacks.

Callbacks must not pass:

- raw body;
- credential;
- encrypted envelope;
- `local_render_source_body`;
- mutable ViewHolder;
- adapter position as identity authority.

`messageId` is the stable action identity.

---

## 25. Stable Identity and Ordering

P4 must preserve:

- message ID;
- created timestamp;
- Repository ordering.

Changing between:

```text
PrivateInvitation
↔
HiddenPrivateInvitation
↔
PrivateInvitationPlaceholder
```

must not create a new message identity.

If DiffUtil is used, item identity should be based on:

```text
messageId
```

Content comparison must use immutable timeline-item equality or equivalent explicit field comparison.

Adapter position must never be used as persistent identity.

---

## 26. RecyclerView Rebinding Safety

Every ViewHolder bind must clear stale state before applying the new item.

A recycled invitation row must not leak into:

- normal message row;
- hidden placeholder;
- failure placeholder;
- another invitation.

A recycled placeholder must not retain:

- previous title;
- previous description;
- previous sender action;
- private audience label;
- old click listener;
- old accessibility description.

Binding must reset:

- text;
- visibility;
- enabled state;
- click listeners;
- content descriptions;
- menu action data.

---

## 27. Plaintext Memory Boundary

P4 necessarily receives title and description through the immutable P3 model for authorized rendering.

P4 must minimize retention:

- do not copy plaintext into raw tags unnecessarily;
- do not persist Adapter items;
- do not save invitation plaintext in `savedInstanceState`;
- do not log title or description;
- do not place plaintext into analytics;
- do not include plaintext in report payload;
- clear recycled views during rebinding.

Normal Android view-memory behavior is accepted.

P4 does not introduce additional plaintext persistence.

---

## 28. Activity State Boundary

P4 may hold the current immutable timeline list in memory while the Activity is active.

It must not:

- persist invitation presentation items to database;
- serialize credential;
- save raw decrypted models into instance state;
- reconstruct invitation after process death from UI state.

After Activity recreation, timeline must be rebuilt through:

```text
Repository
→ timeline inspection
→ P3 mapper
```

---

## 29. Reply / Edit / Delete Protection

Invitation rows must not enter normal-message action paths.

P4 must ensure:

- invitation card cannot start reply compose;
- hidden placeholder cannot start reply compose;
- failure placeholder cannot start reply compose;
- invitation cannot enter Edit;
- invitation cannot enter Delete/Recall through Adapter menu.

If stale compose state references an invitation message ID, Activity must clear or reject it using timeline classification or an existing Repository guard.

P4 must not add new raw-body protocol checks to Activity.

---

## 30. Block and Mute Boundary

`BLOCK_PEER` and `MUTE_PEER` must target the validated logical sender supplied by the immutable presentation item and Repository context.

P4 must not derive the target from:

- current relay sender;
- adapter position;
- display name;
- raw entity fields.

Existing moderation and room-owner policy remains authoritative.

If existing policy rejects an action, P4 must honor that result.

---

## 31. OPEN Action Boundary

P4 may expose `OPEN` as:

- card click;
- explicit button;
- menu action.

Because P5 owns the read-only viewer, P4 must choose one safe behavior until P5 is implemented:

```text
A. Wire a typed callback with no navigation yet.

B. Keep the card non-clickable until P5.
```

P4 must not:

- open a generic raw-message dialog;
- show JSON;
- create a temporary viewer that bypasses P5;
- display credential.

The chosen behavior must be documented in P4 closure.

---

## 32. Accessibility Requirements

Visible invitation card must expose:

- private invitation semantics;
- sender attribution;
- title;
- description when present;
- message timestamp;
- available actions.

Hidden placeholder must expose equivalent semantics to:

```text
Lời mời đã bị ẩn. Nút Bỏ ẩn.
```

Failure placeholder must announce only the safe failure category.

Accessibility must not expose:

- raw body;
- credential;
- crypto reason;
- hidden title or description;
- target identity to unauthorized users.

Color alone must not communicate private, hidden, invalid or unsupported status.

Touch targets must follow existing app accessibility conventions.

---

## 33. Layout Requirements

P4 may add:

```text
item_nearby_room_private_invitation.xml
item_nearby_room_invitation_placeholder.xml
```

Layouts should reuse:

- existing spacing system;
- typography;
- corner-radius conventions;
- timeline margins;
- avatar/sender-attribution patterns;
- dark/light theme resources.

P4 must not redesign the entire Nearby Chat screen.

Private invitation should be visually distinct but remain consistent with the existing timeline.

No hardcoded colors if appropriate theme resources already exist.

---

## 34. Threading and Performance

Repository inspection and projection must not occur in `onBindViewHolder()`.

P4 must follow the existing Activity/Repository threading model.

Potential decrypt work must happen before UI submission and outside bind.

P4 must not introduce:

- plaintext cache;
- global timeline cache;
- background worker;
- per-scroll decryption;
- per-bind Repository query.

Stability is preferred over premature optimization.

---

## 35. Empty-State Behavior

A timeline containing only audience-omitted private invitations must behave as an empty visible timeline.

P4 must not count omitted items when deciding:

- empty-state visibility;
- item count shown to user;
- accessibility list size;
- scrolling target.

Persisted entity count is not the same as visible presentation count.

---

## 36. Timeline Refresh Behavior

P4 must refresh projection after:

- new message arrival;
- message history load;
- Hide;
- Unhide;
- room cleanup;
- relevant participant-profile update if existing architecture supports it.

P4 must avoid duplicate list-submission loops.

Refresh must not mutate Repository results or persisted rows except through approved operations.

---

## 37. Failure Isolation

If projection of one row fails due to an unexpected programmer error:

- do not render raw body as fallback;
- do not crash into a protocol bypass;
- isolate the row with a safe placeholder or omit it according to established error handling;
- log only non-sensitive diagnostic context such as message ID and result category.

P4 must not log plaintext title, description, credential or encrypted body.

Expected typed failures must not be handled through exceptions.

---

## 38. Testing Requirements

### Repository timeline inspection

Verify:

- recipient success returns typed result and authoritative context;
- sender-local success returns typed result and authoritative context;
- authorized failure returns safe context;
- non-target returns no context;
- expired and missing rows return no context;
- context does not expose credential, raw body, local source or crypto metadata;
- Activity does not need to parse entity content.

### Adapter view type

Verify:

- normal message selects normal ViewHolder;
- visible private invitation selects invitation ViewHolder;
- hidden invitation selects placeholder ViewHolder;
- failure placeholder selects placeholder ViewHolder.

### Binding

Verify:

- title and description render correctly;
- sender/recipient audience wording is correct;
- expiry is absent;
- credential is absent;
- raw body is absent;
- placeholder text matches safe category.

### Recycling safety

Verify:

- normal → invitation does not leak normal-body state;
- invitation → hidden does not leak title/description;
- hidden → invalid resets Unhide action;
- recipient invitation → sender invitation resets report actions;
- old click listeners do not survive rebind.

### Action rendering

Verify:

- only P3-projected actions appear;
- sender does not report self;
- sender cannot report own invitation;
- recipient can report invitation and sender;
- prohibited actions never appear.

### Audience omission

Verify:

- omitted item never reaches Adapter;
- omitted item does not affect empty state;
- omitted item has no accessibility node.

### Hide/Unhide integration

Verify:

- Hide invokes P2 Repository API with `hidden = true`;
- Unhide invokes P2 Repository API with `hidden = false`;
- `Updated` causes reload;
- unauthorized or invalid results show safe messages;
- no direct DAO mutation occurs.

### Safe report integration

Verify:

- report invitation invokes safe report seam;
- no entity is passed;
- no raw body is passed;
- no plaintext title/description is passed as raw evidence;
- Repository revalidates canonical envelope, message identity and reported peer;
- `owner_device_id` is the only invitation-owner identity;
- missing owner is rejected even when `sender_device_id` exists;
- relay display name is never used as owner attribution fallback;
- unauthorized report attempt is rejected safely.

### Identity and ordering

Verify:

- same message ID across visible/hidden projection;
- same timestamp;
- stable ordering;
- no synthetic placeholder row.

### Normal regression

Verify existing normal-message behavior:

- Reply;
- Edit;
- Delete/Recall;
- Report;
- delivery state;
- reply preview;
- compose synchronization.

### Security

Verify:

- Adapter does not reference GI2D;
- Adapter does not reference protocol decoder;
- Activity does not parse raw private body;
- ViewHolder does not receive credential;
- logs do not contain plaintext invitation content.

---

## 39. Instrumentation Assessment

Instrumentation is justified only where unit tests cannot prove real RecyclerView behavior.

Candidate instrumentation tests:

```text
Private invitation row uses the correct ViewHolder.

Recycling does not leak title or description into placeholder.

Hide/Unhide refresh preserves timeline position.

Audience-omitted private invitation does not appear in RecyclerView.
```

Do not create broad instrumentation coverage solely to increase numbers.

If unit or Robolectric-style tests sufficiently prove behavior, instrumentation may be assessed as unnecessary with documented evidence.

---

## 40. Expected File Scope

Expected production modifications:

```text
android/app/src/main/java/app/peermesh/
NearbyRoomMessageAdapter.kt

android/app/src/main/java/app/peermesh/
NearbyRoomActivity.kt

android/app/src/main/java/app/peermesh/nearby/
NearbyRoomRepository.kt

android/app/src/main/java/app/peermesh/nearby/
NearbyPrivateGroupInvitationReceive.kt
```

Expected resource additions:

```text
android/app/src/main/res/layout/
item_nearby_room_private_invitation.xml

android/app/src/main/res/layout/
item_nearby_room_invitation_placeholder.xml
```

Possible small supporting files:

```text
android/app/src/main/java/app/peermesh/nearby/presentation/
NearbyRoomTimelineProjectionCoordinator.kt
```

Only add a coordinator if Activity integration would otherwise become materially coupled or difficult to test.

A safe report input/result model may be added near the existing moderation domain if needed.

Expected tests may include:

```text
android/app/src/test/java/app/peermesh/nearby/
NearbyPrivateGroupInvitationTimelineInspectionGI2E6Test.kt

android/app/src/test/java/app/peermesh/nearby/presentation/
NearbyRoomTimelineProjectionGI2E6Test.kt

android/app/src/test/java/app/peermesh/
NearbyRoomMessageAdapterGI2E6Test.kt

android/app/src/test/java/app/peermesh/nearby/
NearbyPrivateGroupInvitationReportGI2E6Test.kt
```

Instrumentation only if required by the assessment.

P4 must not modify:

- GI2D semantics;
- Group Invitation protocol;
- database schema;
- P1/P2 authority semantics;
- P3 failure mapping;
- public invitation protocol.

---

## 41. Out of Scope

The following remain outside P4:

```text
P5
Read-only invitation viewer

P6
Invitation creation-form UX alignment

Future contract
Public invitation timeline presentation

Future milestone
Accept / Reject / Join
```

P4 must not absorb these features for convenience.

---

## 42. Contract Change Policy

Implementation must follow:

```text
Contract review
        ↓
Contract approval
        ↓
Contract lock
        ↓
Production implementation
        ↓
Focused verification
        ↓
Independent code-contract review
        ↓
Manual or instrumentation assessment
        ↓
P4 closure record
```

Any semantic change to:

- Repository timeline-inspection result;
- authoritative presentation context;
- Adapter input type;
- projection ownership;
- audience omission;
- view types;
- action behavior;
- Hide/Unhide flow;
- plaintext boundary;
- safe report seam;
- viewer boundary;

requires an approved contract amendment before production code changes.

Code that diverges from this contract without an approved amendment is non-compliant even if tests pass.

---

## 43. Expected Outcome

After P4:

```text
NearbyRoomRepository
        ↓
Typed receive result + authoritative safe context
        ↓
P3 immutable mapper
        ↓
Immutable timeline list
        ↓
RecyclerView
        ↓
Visible private invitation card or safe placeholder
```

Expected visible changes:

- sender sees sender-local private invitation card;
- intended recipient sees private invitation card;
- non-target peer sees no row;
- hidden invitation appears as a safe hidden placeholder;
- malformed, unsupported or unavailable invitation appears only as a safe placeholder for an authorized viewer;
- raw encrypted invitation body no longer appears as ordinary message text.

---

## 44. Amendment Record — Version 1.1

This revision is contract-first and precedes the P4 correction patch.

Locked corrections:

- persisted `owner_device_id` is the sole P4 invitation-sender authority;
- no P4 fallback to `sender_device_id`;
- no relay `sender_display_name` fallback for owner attribution;
- missing owner metadata causes omission, not relay attribution;
- canonically undecodable envelopes without authoritative audience context are omitted;
- safe private-invitation reporting requires canonical envelope validation and owner-only identity;
- Nearby Room wire-level `origin_device_id` hardening remains out of P4 scope and is deferred.

---

## 44. Review Verdict

```text
GI2E6-P4

Contract status:
LOCKED

Architecture:
PASS

Repository timeline inspection:
REQUIRED AND LOCKED

Safe private invitation report seam:
REQUIRED AND LOCKED

Authority preservation:
PASS

Security boundary:
PASS

Protocol impact:
NONE

Crypto semantic impact:
NONE

Persistence schema impact:
NONE

Visible UI impact:
YES

Ready for:
Production implementation
```
