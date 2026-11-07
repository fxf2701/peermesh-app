# Group Invitation Implementation Roadmap v2

## 1. Authority

Product behavior is defined exclusively by:

```text
android/docs/group_invitations/Group_Invitation_Spec_v2.md
```

Architecture realignment is defined by:

```text
android/docs/group_invitations/GI1R_Group_Invitation_Architecture_Realignment.md
```

This roadmap defines implementation order only.

If roadmap wording conflicts with the Product Specification, the Product Specification is authoritative.

---

## 2. Locked Product Boundary

Group Invitation is a structured Nearby Chat message.

There is no invitation-card management subsystem.

Only two invitation-management rules exist:

```text
1 public invitation per sender per room
1 private invitation per sender-recipient pair per room
```

No milestone may add card lifecycle, card state, card repository, invitation table, resend workflow or replacement workflow without an explicit Product Specification revision.

---

# GI1R — Architecture Realignment

- Supersede obsolete Template/Card aggregate assumptions.
- Preserve GI1 wire and security protocol.
- Lock card as presentation-only.
- Lock the two send-limit rules.

Exit:

```text
Product and architecture documents agree.
```

---

# GI2A — Recipient Encryption Identity

Status:

```text
CLOSED
```

Deliverables retained:

- Android Keystore key;
- deterministic key ID;
- algorithm identifier;
- availability model.

---

# GI2B — Nearby Self Info

## GI2B1 — Additive Protocol

- Publish recipient public identity fields through `SELF_INFO_V1`.
- Preserve backward and forward compatibility.
- Keep recipient-encryption capability separate from Group Invitation support.
- No invitation content in Self Info.

## GI2B2 — Runtime Integration

- Publish only when recipient identity is usable.
- Revision-aware ACK/retry.
- Mixed-version behavior.
- No invitation persistence or card state.

Exit:

```text
Recipient public identity is safely available through Nearby Self Info.
```

---

# GI2C — Room-scoped Recipient Key Pinning

Status:

```text
CLOSED — 2026-08-04
```

Closure evidence:

```text
GI2C_Closure_Evidence.md
```

Accepted verification limitation: app-level GI2C repository/DAO
instrumentation remains unexecuted because unrelated M3D2 test compilation
errors block the app `androidTest` source set. GI2C focused JVM tests,
production compilation and the `53 → 54` migration instrumentation test pass.
The M3D2 tests are outside GI2C scope.

Purpose:

```text
Pin the first valid recipient encryption key for one participant in one room.
```

Rules:

```text
No pinned key + valid incoming key → pin
Same incoming key → accept/no change
Different incoming key → do not overwrite; report conflict
Missing identity → no change
```

GI2C must not know about:

- Invitation Card;
- invitation send limits;
- Group name;
- Description;
- Join;
- invitation history.

GI2C must not create an invitation table or card subsystem.

Exit:

```text
Room-scoped recipient key continuity is available to later encryption use cases.
```

---

# GI2D — Private Invitation Cryptography

Status:

```text
CLOSED — 2026-08-04
```

Closure evidence:

```text
GI2D_Closure_Evidence.md
```

- ECDH;
- HKDF;
- AES-GCM;
- AAD/context binding;
- replay protection;
- expiry validation;
- recipient-only decryption.

Exit:

```text
Private structured invitation payload can be encrypted once and decrypted only by the target.
```

---

# GI2E — Structured Nearby Chat Integration

## GI2E1 — Structured Content Contract

- Map GI1 public/private payloads into existing chat message content.
- No transport kind.
- No event/batch type.
- No invitation-specific retry/relay.

## GI2E2 — Send-limit Query and Enforcement

Implement only:

```text
1 public invitation per sender per room
1 private invitation per sender-recipient pair per room
```

Requirements:

- derive from existing Nearby Chat messages;
- UI eligibility query;
- authoritative send-use-case recheck;
- double-tap/race protection;
- no invitation-specific table.

## GI2E3 — Public Send Flow

- room-menu action;
- group selection;
- group-name preview;
- description input;
- Send;
- normal chat insertion.

## GI2E4 — Private Send Flow

- participant/message context action;
- same form;
- recipient encryption;
- stable encrypted message content;
- normal chat insertion.

## GI2E5 — Parser and Fallback

- exact discriminator/version;
- strict validation;
- safe malformed/unsupported behavior;
- no raw JSON/ciphertext rendering.

Exit:

```text
Public and private invitations travel as normal Nearby Chat messages.
```

---

# GI2F — Invitation UI

- Public Invitation Card renderer.
- Private Invitation Card renderer.
- Private hint.
- Join action seam.
- UI disables only according to the two send-limit rules and runtime eligibility.

No card state repository or card lifecycle may be added.

---

# GI2G — Verification

- Nearby Chat regression;
- BLE relay/retry;
- mixed-version behavior;
- payload budgets;
- parser robustness;
- private visibility;
- public once-per-room rule;
- private once-per-recipient rule;
- double-tap/race behavior;
- no raw JSON/ciphertext;
- no invitation-specific database subsystem.

---

## Workflow

```text
Architecture Review
→ Contract
→ Production Implementation
→ Review
→ Fix
→ Close
```
