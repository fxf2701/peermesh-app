# GI1R — Group Invitation Architecture Realignment

**Status:** ACCEPTED ARCHITECTURE AMENDMENT  
**GI1 status:** REMAINS CLOSED

## 1. Purpose

This amendment supersedes obsolete Group Invitation product/domain assumptions while preserving the completed GI1 wire and security protocol.

Authoritative Product Specification:

```text
Group_Invitation_Spec_v2.md
```

## 2. Core Architecture

A Group Invitation is:

```text
one structured Nearby Chat message
```

An Invitation Card is:

```text
a UI rendering of that message
```

It is not:

- a domain aggregate;
- a separately persisted record;
- an independently managed lifecycle;
- a transport event;
- a transport batch.

## 3. Only Invitation-Management Rules

Only the following rules are authorized:

```text
1. One public invitation per sender per Nearby Room.
2. One private invitation per sender-recipient pair per Nearby Room.
```

No other Invitation Card management rule may be introduced without a Product Specification revision.

## 4. Superseded Concepts

The following concepts are removed from v2:

- persisted `InvitationTemplate`;
- independent `InvitationCardInstance`;
- card repository;
- card table;
- card state table;
- invitation inbox;
- invitation delivery table;
- card activation/deactivation;
- card replacement;
- invitation resend policy;
- card history subsystem;
- invitation event/batch transport.

## 5. Preserved GI1 Decisions

The following remain valid:

- exact structured-content discriminator;
- protocol versioning;
- strict bounded parsing;
- public invitation wire payload;
- private invitation wire payload;
- recipient-only encryption;
- P-256 ECDH;
- HKDF-SHA256;
- AES-256-GCM;
- key-ID binding;
- AAD/context binding;
- replay protection;
- expiry validation;
- payload budgets;
- safe malformed-content behavior.

## 6. Identifier Ownership

For new implementation:

```text
invitationId == nearbyMessageId
```

The existing GI1 wire field remains unchanged.

## 7. Send-Rule Ownership

UI may present eligibility, but the send use case must enforce:

```text
Public:
(roomId, senderId, audience = room)

Private:
(roomId, senderId, recipientId, audience = private)
```

Use existing Nearby Chat message persistence wherever possible.

Do not create invitation-specific tables.

## 8. Security Boundary

Recipient key advertisement, key pinning and encryption are security foundations.

They must not depend on:

- Invitation Card UI;
- card lifecycle;
- invitation history;
- invitation send-limit state.

Likewise, key pinning must not encode invitation business rules.

## 9. Persistence Boundary

The stored Nearby Chat message is the invitation record.

A later structured-message contract may approve a minimal generic message-row extension if private messages need separate wire and local representations.

It must not create an invitation persistence subsystem.

## 10. Verdict

```text
Product realignment: PASS
GI1 wire compatibility: PASS
Private invitation security: PASS
Card management removal: PASS
Authorized invitation rules: EXACTLY TWO
GI1 status: REMAINS CLOSED
```
