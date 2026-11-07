# GI2E5 Product Architecture Decision
# Receive Capability / Presentation Responsibility Split

Status:
LOCKED

Independent Review:
PASS

Manual Review:
PASS

Supersedes:
Execution ordering description for GI2E5 contained in:

- Group_Invitation_Implementation_Roadmap_v2_GI2E_Amendment.md
- GI2E1 Structured Content Contract (execution wording only)

Does NOT supersede:

- Group_Invitation_Spec_v2.md
- Product_Architecture_Invariants.md
- GI2D cryptographic contract
- GI2E local hide persistence decision
- Any protocol contract
- Any persistence contract

---

# 1. Background

During implementation planning for GI2E5 it became clear that the roadmap
described several responsibilities under a single milestone:

- private invitation receive
- decrypt
- presentation integration
- viewer
- hide / unhide
- audience projection
- join flow preparation

While all of these belong to the same product feature, they are not at the
same architectural layer.

Keeping them inside one implementation milestone encourages temporary
cross-layer coupling during development.

No protocol issue exists.

No security issue exists.

No persistence issue exists.

The concern is implementation ordering.

---

# 2. Decision

GI2E5 is clarified as the milestone responsible only for establishing the
repository-owned receive capability.

Responsibilities:

- inspect persisted messages
- detect canonical private invitation envelope
- reconstruct authenticated decrypt context
- invoke GI2D exactly once
- decode private-inner payload
- validate domain contract
- expose immutable read-only invitation model

GI2E5 does NOT own presentation integration.

---

Presentation responsibilities are moved to GI2E6.

GI2E6 becomes responsible for:

- invitation viewer
- renderer integration
- audience projection
- hide / unhide presentation
- UI state
- join entry point
- notification projection
- timeline projection

GI2E6 consumes the repository capability implemented by GI2E5.

---

# 3. Architectural Motivation

Repository remains the single authority.

Presentation must never:

- decode protocol
- decrypt
- select recipient identity
- validate authenticated context
- reconstruct invitation identity

Presentation receives only immutable validated domain objects.

This preserves existing repository authority established during GI2E4.

---

# 4. Protocol Impact

None.

Wire protocol remains identical.

Canonical envelope remains identical.

Private-inner payload remains identical.

Authenticated context remains identical.

GI2D contract remains identical.

No sender-side behavior changes.

No receiver cryptographic behavior changes.

---

# 5. Persistence Impact

None.

Persisted body remains:

body = canonical encrypted private envelope

No plaintext persistence.

No new database table.

No schema migration.

No entity mutation introduced.

---

# 6. Security Impact

Security responsibilities remain unchanged.

Repository continues to construct authenticated context.

GI2D continues to own:

- recipient key lookup
- key ID validation
- authenticated decrypt
- context verification

Presentation receives only validated immutable results.

No fallback decrypt.

No alternative key lookup.

No partial plaintext exposure.

---

# 7. Retry Compatibility

Unchanged.

Receive inspection remains read-only.

Receive does not:

- rewrite message
- re-encrypt
- mutate persisted body

Retry pipeline continues using the exact persisted envelope.

---

# 8. Authority Boundary

Repository owns:

Persisted Message
↓

Envelope Detection
↓

GI2D Decrypt
↓

Domain Validation
↓

Immutable Invitation Model

Presentation owns:

Immutable Invitation Model
↓

Viewer
↓

UI Rendering

No authority inversion is introduced.

---

# 9. Migration

No production migration.

No protocol migration.

No database migration.

No compatibility layer.

No feature flag.

---

# 10. Compatibility

Existing GI1 → GI2E4 contracts remain fully valid.

Existing cryptographic tests remain valid.

Existing persistence behavior remains valid.

Existing retry behavior remains valid.

Only implementation ordering is clarified.

---

# 11. Decision Summary

This decision does not redesign the architecture.

This decision does not modify the protocol.

This decision does not modify persistence.

This decision only clarifies milestone ownership in order to:

- reduce implementation coupling
- preserve repository authority
- preserve security boundaries
- simplify review
- allow GI2E5 to be independently verified before presentation integration.

LOCK CONDITION

GI2E5 SHALL implement repository-owned receive capability only.

GI2E6 SHALL consume that capability for all presentation behavior.

This decision is authoritative for milestone execution ordering.