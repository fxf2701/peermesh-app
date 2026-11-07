# GI2E6-P5 — Read-only Private Invitation Viewer Contract

**Version:** 1.1  
**Status:** LOCKED  
**Milestone:** GI2E6 — Private Invitation Presentation / Viewer Integration

## 1. Purpose

P5 adds a dedicated, read-only viewer for an authorized private Group Invitation.

The viewer presents:

- validated sender display name;
- a recipient-only privacy hint when the local user is the intended recipient;
- invitation title;
- invitation description when present.

The recipient-only hint is not shown to the local sender.

Required authority chain:

```text
Timeline PrivateInvitation item
        ↓
typed OPEN callback
        ↓
identity-only navigation
        ↓
Repository timeline inspection
        ↓
P3 immutable projection
        ↓
read-only viewer state
        ↓
viewer UI
```

## 2. Scope

P5 implements:

- typed navigation from a visible private invitation timeline card;
- identity-only viewer arguments;
- Repository revalidation on viewer creation;
- immutable viewer state;
- sender, recipient-only privacy hint, title and description rendering;
- generic unavailable state;
- accessibility-safe content;
- focused mapper and architecture tests.

P5 does not implement:

- Accept, Reject or Join;
- Hide or Unhide from inside the viewer;
- report, block or mute actions inside the viewer;
- invitation editing, deletion, recall, resend, reply or forward;
- invitation expiry presentation;
- public invitation viewer;
- invitation creation or send UI;
- protocol parsing, crypto or persistence mutation.

All invitation actions remain on the P4 timeline surface. P5 is display-only.

## 3. Locked Dependencies

P5 consumes the locked boundaries from:

- GI2E6-P1 Repository sender-local authority;
- GI2E6-P2 local Hide/Unhide authority;
- GI2E6-P3 immutable timeline model and mapper;
- GI2E6-P4 Repository timeline inspection and Adapter integration.

P5 must not duplicate or reinterpret those responsibilities.

## 4. Viewer Entry

The viewer may be opened only through the `OPEN` action of:

```kotlin
NearbyRoomTimelineItem.PrivateInvitation
```

It must not open from:

- normal messages;
- hidden invitations;
- failure placeholders;
- omitted invitations.

The viewer still revalidates the supplied identity and does not trust the originating UI item as authority.

## 5. Navigation Arguments

The viewer receives only:

```kotlin
data class ViewerArgs(
    val roomId: String,
    val messageId: String,
)
```

Equivalent Intent extras are permitted.

Navigation must not contain:

- sender name;
- title;
- description;
- credential;
- raw body;
- local render source;
- ciphertext or nonce;
- target identity;
- `NearbyRoomMessageEntity`.

## 6. Repository Revalidation

On creation the viewer must call:

```kotlin
NearbyRoomRepository.inspectPrivateGroupInvitationForTimeline(
    roomId,
    messageId,
)
```

The returned inspection must pass through the P3 mapper before viewer state is created.

The viewer must not:

- read DAO data;
- parse `message.body`;
- read `local_render_source_body`;
- decode Group Invitation JSON;
- decrypt;
- reconstruct sender or audience authority.

## 7. Viewer State

P5 uses an immutable state equivalent to:

```kotlin
sealed interface NearbyPrivateGroupInvitationViewerState {
    data object Loading

    data class Content(
        val senderDisplayName: String,
        val title: String,
        val description: String,
        val showRecipientOnlyHint: Boolean,
    )

    data object Unavailable
}
```

`Content` contains no message entity, credential, IDs, expiry, protocol data or action callbacks.

## 8. Exact Projection Mapping

```text
PrivateInvitation with `RECEIVED_PRIVATELY` audience
→ Content(senderDisplayName, title, description, showRecipientOnlyHint = true)

PrivateInvitation with `SENT_PRIVATELY` audience
→ Content(senderDisplayName, title, description, showRecipientOnlyHint = false)

HiddenPrivateInvitation
PrivateInvitationPlaceholder
NormalMessage
UseNormalMessage
Omit
→ Unavailable
```

All unavailable reasons use one generic user-facing state. The viewer must not reveal whether the cause was:

- unauthorized audience;
- hidden state;
- missing message;
- expiry;
- malformed envelope;
- authentication failure;
- unsupported crypto or protocol;
- unavailable recipient identity.

## 9. Display Contract

The viewer body displays only:

```text
Sender
Recipient-only privacy hint (intended recipient only)
Title
Description (when non-empty)
```

The locked recipient hint semantics are equivalent to:

```text
Only you can see this private invitation.
```

The exact localized wording may change without a contract amendment if the meaning and privacy boundary remain unchanged. The hint must not appear for the local sender. The Activity app-bar title is navigation chrome and is not an additional invitation-content field.

It does not display:

- a generic private-invitation label inside the viewer body;
- audience labels other than the recipient-only privacy hint;
- message timestamp;
- invitation expiry or room expiry;
- sender device ID;
- message ID or room ID;
- target participant;
- credential;
- protocol or crypto details;
- raw JSON or ciphertext.

The title and description are rendered exactly as supplied by the immutable P3 item. The viewer does not normalize, repair or truncate protocol content.

Locked content limits remain:

```text
Title: required, maximum 40 characters
Description: optional, maximum 60 characters
```

## 10. Read-only and Interaction Boundary

The viewer contains no invitation mutation or moderation actions.

It must not expose:

```text
Accept
Reject
Join
Hide
Unhide
Report invitation
Report peer
Block
Mute
Edit
Delete
Recall
Resend
Reply
Forward
Copy credential
Copy raw content
```

System back navigation returns to the existing timeline. The viewer does not own or duplicate timeline state.

## 11. Process Recreation and Plaintext Boundary

Only `roomId` and `messageId` may remain in Intent state.

The viewer must not persist invitation plaintext through:

- saved instance state;
- preferences;
- database;
- analytics;
- logs;
- clipboard;
- static or singleton caches.

After Activity recreation, content is rebuilt through Repository inspection and P3 mapping.

Normal Android Activity/View memory behavior while visible is accepted.

## 12. Failure Isolation

On missing or invalid arguments, stale data, unavailable Repository result or unexpected projection:

- clear any previously displayed sender/title/description;
- show one generic unavailable message;
- never fall back to raw message rendering;
- never expose internal failure details.

## 13. Accessibility

The viewer must provide a coherent reading order for:

1. sender;
2. recipient-only privacy hint when visible;
3. title as a heading;
4. description when present.

The content container must not use an aggregate `contentDescription` that causes sender, hint, title or description to be announced twice. Child views provide the reading order.

The unavailable state must be announced generically.

Accessibility must not expose hidden content, raw IDs, protocol details or credentials.

## 14. Architecture and File Scope

Expected production additions:

```text
app/src/main/java/app/peermesh/NearbyPrivateGroupInvitationViewerActivity.kt
app/src/main/java/app/peermesh/nearby/presentation/NearbyPrivateGroupInvitationViewerState.kt
app/src/main/res/layout/activity_nearby_private_group_invitation_viewer.xml
```

Expected modifications:

```text
app/src/main/java/app/peermesh/NearbyRoomActivity.kt
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
```

Expected tests:

```text
app/src/test/java/app/peermesh/nearby/presentation/NearbyPrivateGroupInvitationViewerMapperGI2E6Test.kt
app/src/test/java/app/peermesh/NearbyPrivateGroupInvitationViewerArchitectureGI2E6Test.kt
```

P5 must not modify protocol, GI2D, DAO, database schema, transport or P1–P4 authority semantics.

## 15. Testing Requirements

Focused tests must prove:

- intended-recipient invitation maps to sender/hint/title/description content;
- sender-local invitation maps to sender/title/description without the recipient-only hint;
- hidden invitation maps to unavailable;
- failure placeholder maps to unavailable;
- omitted projection maps to unavailable;
- viewer uses identity-only extras;
- viewer revalidates through Repository timeline inspection;
- viewer does not reference protocol decoder, GI2D, entity or credential;
- layout contains sender/recipient hint/title/description only;
- viewer body does not contain the generic private-invitation label;
- viewer does not assign an aggregate content description to the content container;
- background loading does not render after the Activity is finishing or destroyed;
- layout contains no Accept, Reject, Join or expiry UI.

Instrumentation and full sender-to-recipient E2E remain deferred until P6 provides production creation/send UI and the unrelated androidTest compilation blocker is resolved.

## 16. Contract Change Policy

Any semantic change to viewer inputs, displayed fields, Repository authority, state mapping, read-only behavior, plaintext handling or navigation requires an approved contract amendment before code changes.

Code that diverges from this contract without an approved amendment is non-compliant even if tests pass.

## 17. Expected Outcome

```text
Visible private invitation timeline card
        ↓
OPEN
        ↓
identity-only navigation
        ↓
Repository-revalidated viewer
        ↓
sender + title + description
```

## 18. Review Verdict

```text
GI2E6-P5 Contract: LOCKED
Architecture: PASS
Repository authority: PASS
Security boundary: PASS
Protocol impact: NONE
Crypto impact: NONE
Persistence impact: NONE
Visible UI impact: YES
Production implementation: AUTHORIZED
```


## 16. Revision 1.1 Amendment

Revision 1.1 locks the following changes before the corresponding code fix:

- intended recipients see a privacy hint explaining that only they can see the private invitation;
- local senders do not see that recipient-only hint;
- the redundant generic private-invitation label is removed from the viewer body;
- accessibility relies on child reading order rather than an aggregate container description;
- background Repository loading must not render after the Activity is finishing or destroyed.

These changes do not alter protocol, crypto, persistence or Repository authority.
