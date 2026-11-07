# Group Invitation Specification

Version: Draft v1.0

## 1. Overview

Group Invitation allows a participant in a Nearby Room to introduce one
of their Private Groups to other participants.

Nearby Room remains a temporary BLE chat room. Private Group remains
independent and private.

The feature introduces no Group Directory and no profile-based group
browsing.

------------------------------------------------------------------------

## 2. Product Principles

-   Nearby Room is for meeting people.
-   Private Group is for long-term collaboration.
-   Group owners decide when to introduce a Group.
-   Invitations are contextual, not promotional.
-   Sending an invitation feels like sending a structured chat message.

------------------------------------------------------------------------

## 3. Architecture

### 3.1 Template

Each Private Group owns one reusable Invitation Card Template.

The template stores only presentation data:

-   title
-   short description
-   icon
-   presentation options

Template does not grant join permission.

------------------------------------------------------------------------

### 3.2 Card Instance

Whenever a template is shared, a Card Instance is created.

A Card Instance:

-   belongs to exactly one Nearby Room
-   is immutable after creation
-   expires with the Nearby Room
-   cannot outlive the Room

There is no custom expiration.

------------------------------------------------------------------------

### 3.3 Transport

Group Invitation is transported as a normal Nearby Room message.

It differs only by:

-   message type
-   structured payload
-   audience

Transport pipeline remains unchanged:

Create → Encrypt → BLE → Receive → Persist → Render

------------------------------------------------------------------------

### 3.4 Audience

Two audiences exist.

ROOM

Visible to every participant.

PARTICIPANT

Visible only to:

-   sender
-   target participant

The message still belongs to the Nearby Room.

No private conversation is created.

------------------------------------------------------------------------

### 3.5 Visibility

ROOM

Everyone renders the card.

PARTICIPANT

Only sender and recipient materialize the message into the chat
timeline.

------------------------------------------------------------------------

### 3.6 Card Lifetime

Card lifetime is tied to the Nearby Room.

Room active

→ Card active

Room expired

→ Card expired

Room deleted

→ Card unavailable

------------------------------------------------------------------------

### 3.7 Join Flow

User selects Join.

System validates:

-   room still active
-   card still valid
-   recipient allowed
-   user not already member

If successful:

Join Group

Otherwise:

Display an informative error.

------------------------------------------------------------------------

### 3.8 Payload Design

Payload should remain compact.

Only essential information is transmitted:

-   card identifier
-   group title
-   short description
-   invitation credential

Large snapshots, images and duplicated metadata should not be
transmitted.

------------------------------------------------------------------------

## 4. UX

### Public Share

Owner selects:

Share Invitation

↓

Everyone in Nearby Room

A card appears naturally inside the chat timeline.

No popup.

No notification.

Participants may ignore it.

------------------------------------------------------------------------

### Private Share

Owner selects:

Share Invitation

↓

One Participant

Only sender and recipient see the card.

No separate DM thread is created.

------------------------------------------------------------------------

### Card

Displays:

-   icon
-   title
-   description
-   Join button

Never displays:

-   raw invite URI
-   internal identifiers
-   cryptographic material

------------------------------------------------------------------------

### Expired Card

Card remains visible in history.

Join button becomes disabled.

Status:

Expired with Nearby Room.

------------------------------------------------------------------------

## 5. Design Constraints

-   No Group Directory.
-   No automatic profile exposure.
-   No invitation inbox.
-   No Rich Message Framework.
-   Group Invitation remains a dedicated Nearby Room feature.
-   Existing text chat behaviour remains unchanged.

------------------------------------------------------------------------

## 6. Success Criteria

-   Public share works.
-   Private share works.
-   Visibility policy enforced.
-   Join flow succeeds.
-   Cards expire with room.
-   Existing Nearby Chat remains backward compatible.
