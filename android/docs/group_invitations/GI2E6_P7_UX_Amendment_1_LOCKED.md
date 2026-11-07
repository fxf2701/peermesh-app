# GI2E6-P7 — UX Amendment 1

**Status:** LOCKED — USER-APPROVED UX REVISION  
**Applies to:** `GI2E6_P7_Public_Invitation_Presentation_Viewer_Contract_LOCKED.md`

## 1. Purpose

This amendment records the user-approved P7 UX revision after first-device manual review. It is additive and overrides only the conflicting P7 presentation clauses listed below. Protocol, credential, send, crypto, transport, retry, relay, and membership semantics remain unchanged.

## 2. Timeline message chrome

A Public Invitation SHALL render as message content inside the normal Nearby message visual hierarchy:

```text
avatar + sender + timestamp
        ↓
invitation content card
        ↓
Join | Hide
```

The invitation content remains a typed immutable Repository projection. The adapter MUST NOT parse protocol content.

## 3. Join UI

P7 SHALL expose a visible **Join** control on the Public Invitation timeline card and Public Invitation Viewer.

This is presentation-only in P7:

```text
Join button visible
Membership mutation NOT IMPLEMENTED
Credential consumption NOT IMPLEMENTED
Group mutation NOT IMPLEMENTED
```

Activating Join SHALL provide a safe deferred/future-phase message and MUST NOT mutate membership.

## 4. Public local Hide

P7 SHALL expose a **Hide** control for Public Invitations.

Hide semantics are strictly local presentation state:

```text
canonical message unchanged
wire body unchanged
transport state unchanged
quota unchanged
other participants unaffected
local is_hidden_locally = true
```

Only a canonical validated Public Invitation may be hidden through this public visibility authority. A hidden Public Invitation is omitted from the local room timeline and room invitation index.

No protocol field, delete, revoke, or remote mutation is introduced.

## 5. Room Invitation Index

Clicking the Public Invitation content card SHALL open a room-scoped invitation index rather than opening the detail viewer directly.

The index SHALL:

- show all valid invitations visible to the local user in the room;
- include Public Invitations and locally-authorized visible Private Invitations;
- exclude hidden, invalid, unsupported, and unavailable invitation placeholders;
- sort by `createdAtMs` ascending, with deterministic identity tie-break;
- auto-scroll to the invitation identity clicked in the chat timeline;
- use only Repository-authoritative typed projections;
- never parse Group Invitation protocol in the Activity/adapter;
- open the appropriate existing public/private viewer when an index item is tapped.

Navigation from chat to the index remains identity-only:

```text
roomId + targetMessageId
```

## 6. Public Viewer revision

The Public Viewer remains Repository-revalidated and SHALL present:

```text
avatar
sender
timestamp
invitation title
description
Join
Hide
```

The viewer MUST NOT expose credential, raw JSON, protocol internals, crypto metadata, or internal IDs.

Hide may mutate only local presentation visibility as defined above. Join remains non-mutating in P7.

## 7. Unchanged invariants

The following remain locked and unchanged:

- Repository presentation authority;
- immutable typed projection;
- no protocol parsing in Activity/adapter/viewer;
- no credential in presentation state;
- no new plaintext persistence;
- no public send-pipeline change;
- one successful canonical insertion per logical sender per room;
- no crypto change;
- no transport/retry/relay change;
- no Join/membership implementation in P7;
- P1–P6 private semantics remain unchanged.

## 8. Verification additions

Focused verification SHALL additionally prove:

- Public Invitation timeline uses avatar + sender + timestamp message chrome;
- Join and Hide controls exist;
- Join performs no membership mutation;
- Hide changes only `is_hidden_locally` for a canonical Public Invitation;
- hidden Public Invitation is omitted locally;
- invitation index sorts oldest-to-newest;
- invitation index auto-scrolls to the clicked message identity;
- invitation index uses Repository projections and contains no protocol parser/credential authority;
- tapping an index item opens the correct viewer.
