# GI2E3_Closure.md

# GI2E3 — Public Invitation Send Pipeline

**Status**

```text
CLOSED — LOCKED
```

---

# 1. Executive Summary

GI2E3 completes the production implementation of the **Public Invitation Send Pipeline**.

The milestone integrates Public Group Invitation creation into the existing Nearby Room architecture without introducing any parallel persistence, transport or retry subsystem.

Public Invitation is now produced through the repository-authoritative preparation capability, encoded canonically using the locked GI1 protocol, atomically persisted through the prepared insertion boundary, and transmitted through the existing Nearby Room transport pipeline.

No protocol changes, schema changes or architectural redesign were introduced.

---

# 2. Scope Completed

GI2E3 completed:

* Repository-owned public invitation preparation.
* Canonical Public Group Invitation payload construction.
* Canonical public protocol encoding.
* Prepared insertion integration.
* Existing Nearby transport integration.
* Existing owner retry compatibility.
* Existing relay compatibility.
* Focused JVM verification.
* Independent Review.
* Manual Review.

GI2E3 intentionally did **not** implement:

* Private Invitation pipeline.
* GI2D encryption.
* Invitation UI.
* Invitation renderer.
* Invitation parser UI.
* Hide / Unhide UI.
* Join flow.
* Invitation viewer.
* Invitation actions.

Those remain the responsibility of later milestones.

---

# 3. Final Architecture

The final production pipeline is:

```text
Semantic Public Request
        │
        ▼
prepareGroupInvitationContext(Public)
        │
        ▼
Repository-issued frozen preparation
        │
        ▼
PublicGroupInvitationPayload
(cardId = preparation.messageId)
        │
        ▼
GroupInvitationProtocol.encodePublic()
        │
        ▼
insertPreparedGroupInvitation()
        │
        ▼
Persisted NearbyRoomMessageEntity
        │
        ▼
Existing Nearby Room Transport
        │
        ▼
Existing Delivery / Retry / Relay
```

Repository remains the only authority responsible for:

* message identity;
* sender identity;
* effective expiry;
* preparation capability;
* authoritative insertion;
* send-limit enforcement.

---

# 4. Authority Preservation

GI2E3 preserves the authority model introduced by GI2E1 and GI2E2.

Caller-owned semantic input:

* roomId
* title
* shortDescription
* invitationCredential

Caller cannot supply:

* messageId
* cardId
* sender identity
* expiry
* encoded invitation body
* persisted entity

The public invitation identity is bound as:

```text
cardId = preparation.messageId
```

This keeps Invitation identity identical to the underlying Nearby message identity and prevents parallel identity ownership.

---

# 5. Repository Ownership

Repository remains responsible for:

* preparation issuance;
* preparation validation;
* canonical insertion validation;
* atomic quota enforcement;
* persistence;
* transaction ownership.

GI2E3 introduces no alternate insertion path.

The following invariant remains mandatory:

```text
prepare
→ encode
→ insertPreparedGroupInvitation
→ transport
```

---

# 6. Transport Integration

Transport continues to use the existing Nearby Room message path.

GI2E3 does not introduce:

* invitation transport packets;
* invitation relay packets;
* invitation retry subsystem;
* invitation delivery queue.

Transport always receives the persisted entity returned by repository insertion.

Transport never sends a pre-persistence message object.

---

# 7. Retry Compatibility

Retry behaviour remains unchanged.

Owner retry continues to operate using:

```text
roomId
+
messageId
```

The retry coordinator reloads the persisted message body.

Retry never:

* regenerates messageId;
* rebuilds protocol payload;
* re-encodes invitation;
* creates another preparation.

Public invitation remains fully compatible with the existing retry infrastructure.

---

# 8. Persistence

No database schema changes were introduced.

Public invitation persistence rules:

```text
body
=
canonical public invitation

local_render_source_body
=
null
```

No additional invitation tables or persistence models were introduced.

---

# 9. Security Review

Security review completed successfully.

Verified:

* no authority leak;
* no sender spoofing;
* no persistence bypass;
* no protocol bypass;
* no retry replay regression;
* no transaction regression.

During Independent Review one authority issue was discovered:

```text
cardId supplied by caller
```

This was corrected before closure by binding:

```text
cardId = preparation.messageId
```

The final implementation no longer exposes invitation identity ownership to callers.

An additional hardening improvement replaced broad `runCatching` validation with explicit `IllegalArgumentException` handling to avoid suppressing unrelated runtime failures.

---

# 10. Transaction Review

Transaction ordering remains:

```text
prepare
→ canonical encode
→ prepared insertion
→ transaction commit
→ transport
```

Transport is never executed before successful insertion.

Transport failure never rolls back the persisted invitation.

No retry path performs re-encoding.

---

# 11. Protocol Compatibility

No protocol drift was introduced.

GI2E3 reuses the locked GI1 protocol exactly as specified.

No new fields were added.

No wire format modifications were introduced.

Canonical encoding remains exclusively owned by:

```text
GroupInvitationProtocol
```

---

# 12. Testing & Verification

## Focused Unit Tests

PASS

Verified:

* repository preparation;
* public audience;
* canonical encoding;
* authoritative card identity;
* null local_render_source_body;
* insertion sequencing;
* transport sequencing;
* retry compatibility;
* authority preservation.

---

## Existing Regression Suites

PASS

Verified:

* GroupInvitationProtocol
* GroupInvitationDomain
* Nearby transport codec
* Moderation regression

Existing unrelated Map regression failures were confirmed to be outside the scope of GI2E3.

---

## Independent Review

Initial Independent Review:

```text
FAIL
```

Reason:

```text
cardId authority leak
```

Corrective patch applied.

Independent Re-review:

```text
PASS
```

No remaining blocker, major or minor findings.

---

## Manual Review

PASS

Manual code review verified:

* authority ownership;
* repository sequencing;
* persistence;
* retry behaviour;
* transport ordering;
* public-only boundary.

Device end-to-end manual testing was **Not Applicable** because GI2E3 intentionally does not introduce production Invitation UI or runtime entry points.

---

# 13. Files Changed

Production:

* NearbyPublicGroupInvitationSendPipeline.kt

Tests:

* NearbyPublicGroupInvitationSendPipelineGI2E3Test.kt

Documentation:

* GI2E3_Production_Implementation.md
* GI2E3_Closure.md

---

# 14. Locked Invariants

The following invariants are now locked.

## Repository Authority

Repository remains the sole authority for:

* preparation;
* message identity;
* sender identity;
* insertion;
* persistence.

---

## Public Invitation Identity

```text
cardId = messageId
```

This invariant is mandatory.

---

## Public Persistence

```text
local_render_source_body = null
```

Always.

---

## Transport

Transport always uses the persisted entity returned by repository insertion.

---

## Retry

Retry always reloads the persisted message body.

No re-encoding is permitted.

---

## Architecture

No secondary invitation subsystem.

No secondary retry subsystem.

No protocol duplication.

No transport duplication.

---

# 15. Future Milestone Boundary

GI2E4 becomes responsible for:

* Private Invitation Send Pipeline.
* Canonical private-inner payload.
* GI2D recipient encryption.
* Canonical private envelope.
* Persisted encrypted outer body.
* Canonical local_render_source_body.
* Private retry compatibility.

GI2E4 must preserve every invariant locked by GI2E3.

---

# 16. Final Verdict

Architecture Review

```text
PASS
```

Implementation

```text
PASS
```

Security Review

```text
PASS
```

Transaction Safety

```text
PASS
```

Focused Verification

```text
PASS
```

Independent Review

```text
PASS
```

Manual Review

```text
PASS
```

Final Status

```text
GI2E3

CLOSED

LOCKED
```
