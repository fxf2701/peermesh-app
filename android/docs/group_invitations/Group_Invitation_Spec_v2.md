# Group Invitation Specification v2

## 1. Product Model

A Group Invitation is a **structured Nearby Chat message**.

It is not:

- a dedicated transport kind;
- an invitation event;
- an invitation batch;
- an independently managed card entity;
- a persistent invitation template;
- a separate invitation inbox;
- a card lifecycle subsystem.

Nearby Chat remains responsible for:

- message transport;
- room encryption;
- relay;
- retry;
- deduplication;
- ordering;
- persistence;
- chat history.

The UI recognizes valid Group Invitation structured content and renders it as an Invitation Card.

---

## 2. Authoritative Invitation Rules

Only the following invitation-management rules exist in v2.

### 2.1 Public invitation limit

A sender may send at most:

```text
1 public invitation per Nearby Room
```

Uniqueness scope:

```text
(roomId, senderId, audience = room)
```

This limit does not reset when:

- a different group is selected;
- the invitation message is locally hidden;
- the invitation message is soft-deleted.

It resets only when the room data lifecycle removes the underlying room messages, unless a later Product Specification explicitly changes this rule.

### 2.2 Private invitation limit

A sender may send at most:

```text
1 private invitation per recipient per Nearby Room
```

Uniqueness scope:

```text
(roomId, senderId, recipientId, audience = private)
```

Examples:

```text
A → B: maximum 1
A → C: maximum 1
B → A: maximum 1
```

No other invitation-card management rule may be added without revising this Product Specification.

---

## 3. Explicitly Prohibited Card Management

The implementation must not create:

- Invitation Card repository;
- Invitation Card database table;
- Invitation Card status table;
- Invitation Card lifecycle state;
- Invitation Card history subsystem;
- invitation resend workflow;
- invitation replacement workflow;
- card activation/deactivation;
- card conflict state;
- card acknowledgement state;
- template CRUD;
- invitation batch management.

An Invitation Card exists only as a UI representation of one structured chat message.

---

## 4. Send Entry Points

### 4.1 Public invitation

Room menu item:

```text
Send invitation to all participants
```

Flow:

1. Select a group.
2. Review the invitation group-name preview.
3. Enter a description.
4. Tap Send.

The action is unavailable when the public invitation limit has already been reached.

### 4.2 Private invitation

Participant or participant-message context menu item:

```text
Send Invitation
```

Flow:

1. Select a group.
2. Review the invitation group-name preview.
3. Enter a description.
4. Tap Send.

The action is unavailable when the sender has already sent a private invitation to that recipient in the room.

---

## 5. Send-Rule Enforcement

The UI may hide or disable an unavailable action, but UI state is not authoritative.

The send use case must check the applicable limit again immediately before inserting the chat message.

Expected results include:

```text
Allowed
AlreadySentToRoom
AlreadySentToRecipient
RoomInactive
RecipientUnavailable
PayloadInvalid
SecurityUnavailable
```

The rule must use existing Nearby Chat message persistence wherever possible.

No invitation-specific table is required.

---

## 6. Invitation Form

### 6.1 Group name

The group name comes from the selected group and is not editable in the invitation form.

Limits:

```text
maximum 40 Unicode characters
maximum 120 UTF-8 bytes
```

If the source group name exceeds the limit, it is normalized and safely truncated before structured-message construction.

Required hint:

```text
Group names longer than 40 characters will be shortened in the invitation.
```

The preview should display the final value that will be sent.

### 6.2 Description

Limits:

```text
maximum 60 Unicode characters
maximum 180 UTF-8 bytes
```

Required UI behavior:

- show a character counter;
- prevent input beyond 60 characters;
- do not silently truncate after Send.

Required hint:

```text
Maximum 60 characters.
```

---

## 7. Structured Nearby Chat Content

Normal chat:

```text
body = plain text
```

Public invitation:

```text
body = valid public Group Invitation structured payload
```

Private invitation:

```text
body = valid recipient-encrypted Group Invitation structured payload
```

No Group Invitation transport kind may be added.

No invitation-specific relay or retry mechanism may be added.

The invitation identifier should equal the enclosing Nearby message ID:

```text
invitationId == nearbyMessageId
```

---

## 8. Invitation Card Presentation

### Public card

Displays:

- Group name;
- Description;
- Join button.

### Private card

Displays:

- Group name;
- Description;
- `Only you can see this invitation`;
- Join button.

The card must not display:

- protocol version;
- algorithm;
- key ID;
- ephemeral public key;
- nonce;
- ciphertext;
- AAD;
- raw Join credential;
- transport diagnostics.

---

## 9. Private Invitation Security

Private invitation content must be readable only by the selected recipient.

The security design retains:

- recipient encryption identity;
- Android Keystore private key;
- P-256 ECDH;
- HKDF-SHA256;
- AES-256-GCM;
- recipient key-ID binding;
- canonical AAD;
- replay/context binding;
- expiry validation;
- strict payload validation.

Room encryption protects Nearby Chat from outsiders.

Recipient encryption protects the private invitation from other participants inside the room.

Relay participants must only forward ciphertext and must not materialize the private card.

---

## 10. Persistence Boundary

Group Invitation uses existing Nearby Chat persistence.

The implementation must not create an invitation-specific persistence subsystem.

A minimal generic extension to the existing message row is allowed only if private-message implementation needs separate:

```text
stable wire representation
local materialized representation
```

Such a decision requires a reviewed structured-message persistence contract.

---

## 11. Architecture Priority

```text
Stability
>
Correctness
>
Maintainability
>
Performance
```
