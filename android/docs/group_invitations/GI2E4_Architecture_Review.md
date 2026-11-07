# GI2E4 — Private Invitation Send Pipeline Architecture Review

**Status:** PASS — IMPLEMENTATION AUTHORIZED

## Decision

GI2E4 requires no protocol, schema, repository, transport, retry, or GI2D redesign.

The smallest compliant production flow is:

```text
semantic private request
→ repository prepareGroupInvitationContext(Private)
→ canonical PrivateGroupInvitationInnerPayload
→ GI2D encrypt exactly once
→ canonical PrivateGroupInvitationEnvelope
→ repository insertPreparedGroupInvitation
→ shared persisted-message transport bridge
→ existing delivery / retry / relay
```

## Ownership

Repository remains sole authority for message ID, card ID binding, logical sender, expiry,
recipient encryption identity, prepared insertion, quota enforcement, and persistence.

GI2D remains a pure cryptographic component receiving only plaintext bytes, validated recipient
identity, and immutable primitive context. It has no DAO, repository, transport, retry, or UI dependency.

Transport receives only the entity returned by successful prepared insertion. Existing retry reloads
that persisted outer body and does not invoke preparation, protocol encoding, or encryption.

## Persistence

```text
body = canonical private envelope
local_render_source_body = canonical private-inner payload
```

No schema change or invitation-specific persistence is required.

## Transaction boundary

```text
prepare
→ encode inner
→ encrypt
→ encode envelope
→ repository transaction inserts
→ transaction commits
→ transport submits persisted entity
```

Encryption failure causes no insertion. Insertion failure causes no transport. Transport failure leaves
the already-persisted ciphertext available to the existing retry coordinator.

## Minimal shared extension

GI2E3's production transport submission logic is extracted into one internal shared function because
GI2E4 is now its second concrete caller. This prevents public/private transport drift without creating
a subsystem or generalized framework.

## Verdict

- Product architecture invariants: PASS
- Repository authority: PASS
- Protocol compatibility: PASS
- GI2D dependency direction: PASS
- Persistence model: PASS
- Transaction safety: PASS
- Retry compatibility: PASS
- Implementation authorized: YES
