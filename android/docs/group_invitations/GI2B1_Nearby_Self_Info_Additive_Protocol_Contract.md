# GI2B1 — Nearby Self Info Additive Protocol Contract

**Status:** IMPLEMENTED AND VERIFIED  
**Architecture basis:** Product Spec v2, GI1R, Implementation Roadmap v2

## 1. Purpose

Extend existing `SELF_INFO_V1` additively so peers can advertise usable recipient encryption public identity.

GI2B1 does not transport, manage or render Group Invitations.

## 2. Additive Fields

Self Info may advertise:

- recipient encryption supported;
- algorithm identifier;
- recipient key ID;
- recipient public key;
- deterministic Self Info revision.

Self Info ACK may advertise:

- Self Info revision.

## 3. Capability Semantics

`recipient_encryption_supported` means only:

```text
usable recipient encryption identity is advertised
```

It does not mean:

```text
Group Invitation supported
Invitation Card supported
private invitation may be sent
```

Structured Group Invitation support, if needed, must be a separate later additive capability.

## 4. Availability

Advertise recipient identity only when:

```text
RecipientEncryptionIdentityStatus.Available
```

All other statuses suppress the complete extension.

No partial identity is valid.

## 5. Compatibility

- Keep existing Self Info kind.
- Keep existing required fields.
- Add optional key-based fields.
- Old clients ignore new fields.
- New clients accept absent fields.
- Malformed extension never invalidates legacy profile content.
- Old relay field stripping degrades to unknown identity capability.

## 6. Revision

Revision identifies complete Self Info snapshot content for:

- ACK;
- retry;
- relay dedupe.

Revision is not:

- a trust decision;
- a pinned identity;
- an invitation/card state;
- a persistence authority.

## 7. Explicit Prohibitions

GI2B1 must not add:

- Group Invitation transport type;
- invitation event/batch;
- Invitation Card fields;
- invitation send-limit state;
- invitation table;
- card repository;
- TOFU.

## 8. Verification

```text
Architecture: PASS
Compile: PASS
Focused tests: PASS
Backward compatibility: PASS
GI1R alignment: PASS
```
