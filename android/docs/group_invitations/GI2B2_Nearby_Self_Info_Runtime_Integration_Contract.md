# GI2B2 — Nearby Self Info Runtime Integration Contract

**Status:** IMPLEMENTED AND VERIFIED  
**Depends on:** GI2A3, GI2B1, GI1R

## 1. Purpose

Integrate recipient encryption public identity into live Nearby Self Info runtime.

GI2B2 is a security/runtime milestone only.

It does not manage Group Invitations or Invitation Cards.

## 2. Runtime Flow

```text
RecipientEncryptionIdentityProvider
→ immutable Self Info snapshot
→ deterministic revision
→ SELF_INFO_V1
→ ACK/retry/relay
```

## 3. Availability

Only `Available` publishes the complete identity.

Other statuses publish no recipient identity fields.

Provider failure or unavailable state must not invalidate participant profile information.

## 4. Snapshot and Retry

- One immutable envelope snapshot per targeted retry cycle.
- Retry reuses the stored envelope.
- Retry does not query Keystore repeatedly.
- New snapshot revision does not reuse stale ACK ownership.

## 5. ACK

Modern ACK:

```text
(roomId, target, selfInfoRevision)
```

Legacy ACK may stop transport retry using profile timestamp.

Legacy ACK does not prove:

- recipient encryption support;
- Group Invitation support;
- Invitation Card support.

## 6. Relay

Prefer revision for Self Info and ACK dedupe.

Fallback to legacy profile timestamp only when revision is absent.

## 7. Receive

Receiver:

- always processes legacy profile;
- validates recipient identity independently;
- ignores malformed extension;
- keeps participant valid;
- echoes validated revision;
- performs no TOFU;
- performs no invitation parsing;
- performs no card materialization.

## 8. Explicit Prohibitions

GI2B2 must not add:

- invitation card state;
- invitation send limits;
- invitation repository;
- invitation table;
- Group Invitation transport type;
- structured invitation content;
- private invitation encryption;
- Join behavior.

## 9. Verification

```text
Architecture: PASS
Compile: PASS
Focused tests: PASS
Runtime integration: PASS
GI1R alignment: PASS
```
