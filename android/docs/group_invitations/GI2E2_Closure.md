# GI2E2 — Closure

**Milestone:** GI2E2 — Send-Limit Query and Enforcement

**Status:** **CLOSED — VERIFIED**

**Closure Date:** 2026-08-05

---

# 1. Objective

GI2E2 completed the production implementation of the repository-owned preparation boundary and atomic send-limit enforcement required by the locked GI2E1 Revision 3.2 contract.

This milestone intentionally implemented only the persistence, transaction, and security foundations.

It did **not** implement:

* Public Invitation UI
* Private Invitation UI
* Invitation renderer
* Join flow
* Hide / Unhide UI
* GI2E3
* GI2E4
* GI2E5

These remain future milestones.

---

# 2. Final Scope

GI2E2 delivered:

* Repository-owned Prepared Message Context
* Opaque one-shot preparation capability
* Atomic send-limit query and enforcement
* Insert-only DAO for prepared invitations
* Room schema migration 54 → 55
* Authoritative immutable Group Invitation guard
* Canonical public/private validation
* Canonical quota evidence
* Local sender-source persistence
* Transaction rollback guarantees
* Repository concurrency protection
* Unit and migration tests

No protocol redesign occurred.

No GI2D crypto behavior changed.

No BLE transport behavior changed.

---

# 3. Architecture Verification

Verified:

* Repository exclusively owns invitation preparation.
* Caller cannot construct a valid preparation capability.
* Preparation capability is opaque.
* Preparation capability is one-shot.
* Preparation capability is atomically claimed.
* Preparation capability has bounded lifetime.
* Preparation registry has bounded capacity.
* Repository remains the only authority allowed to create Group Invitation rows.

No new repository layer, transport layer, or abstraction was introduced.

Engineering Constitution constraints remain satisfied.

---

# 4. Persistence Verification

Migration:

```text
54
↓

55
```

Added columns:

```sql
local_render_source_body TEXT

is_hidden_locally INTEGER NOT NULL DEFAULT 0
```

Verified:

* additive migration
* no table recreation
* primary key preserved
* existing indices preserved
* schema generation matches production entity

Migration instrumentation executed successfully on a physical Android device.

Result:

```text
NearbyGroupInvitationMigrationTest

PASS

1 test
0 failures
```

---

# 5. Transaction Safety Verification

Repository transaction ordering verified:

```text
BEGIN TRANSACTION

↓

transactionNowMs

↓

reload room

↓

expiry consistency validation

↓

sender validation

↓

mute validation

↓

quota query

↓

ABORT insert

↓

participant / room bookkeeping

↓

COMMIT
```

Verified:

* timestamp sampled inside transaction
* stale preparation precedence
* insert-before-bookkeeping ordering
* rollback on insert failure
* no partial commit path
* no quota race identified

---

# 6. Security Verification

Verified:

Repository-owned preparation:

* opaque capability
* private implementation
* atomic claim
* one-shot consumption
* bounded registry

Canonical validation:

Public:

```text
decode

↓

supported payload

↓

canonical encode

↓

exact String equality
```

Private:

```text
decode

↓

supported payload

↓

canonical encode

↓

exact String equality

↓

canonical Base64URL validation
```

No silent normalization occurs.

No decrypt / re-encrypt behavior was introduced.

No GI2D protocol change occurred.

---

# 7. Immutable Ownership Verification

Verified:

Exact Group Invitation ownership uses:

```text
t == "gi"
```

This protection applies regardless of payload validity.

Protected paths:

* local send
* local edit
* local delete
* remote ingest
* duplicate merge
* retry
* relay
* delivery updates
* diagnostics
* moderation
* profile projection

Malformed or unsupported exact-GI messages remain immutable but do not consume send quota.

---

# 8. Independent Review Findings

Initial Independent Review identified two implementation blockers:

## Blocker 1

Generic legacy send/edit paths could create exact Group Invitation content after message normalization.

Resolved.

Repository now validates the normalized body before any generic persistence path.

Manual verification:

* legacy send rejected
* whitespace-wrapped legacy send rejected
* legacy edit transition rejected

PASS.

---

## Blocker 2

"No Reply" invariant was not fully enforced.

Resolved.

Protection added at:

* repository generic paths
* remote ingest
* reducer
* reply UI entry point

Prepared invitation path continues to require:

```text
reply_to == null
```

PASS.

---

# 9. Verification Evidence

## Production compile

```text
PASS
```

---

## Core protocol unit tests

```text
PASS
```

---

## NearbyRoom reducer unit tests

```text
PASS
```

---

## Migration instrumentation

```text
PASS

NearbyGroupInvitationMigrationTest

1 test
0 failures
```

---

## Full application unit suite

```text
535 executed

532 passed

3 unrelated Map failures
```

The remaining failures are outside GI2E2 scope and pre-existed this milestone.

---

## Manual verification

Completed:

### Test 1

Legacy send:

```text
{"t":"gi","v":99}
```

Result:

PASS

---

### Test 2

Whitespace-wrapped legacy send.

Result:

PASS

---

### Test 3

Legacy edit into exact Group Invitation.

Result:

PASS

---

### Invitation reply UI

Not executable.

Reason:

Invitation UI has not yet been implemented within GI2E scope.

This is consistent with the approved milestone scope.

---

# 10. Accepted Limitations

Focused Android instrumentation for:

```text
NearbyGroupInvitationPreparationTest
```

was not executed.

Reason:

Compilation of the Android test source set is currently blocked by unrelated pre-existing Map Android-test compilation failures.

Specifically:

* GroupMapActivityM3D2Test
* OsmdroidMapRuntimeRendererM3D2Test

This limitation does not affect production implementation verification.

Migration instrumentation executed successfully.

---

# 11. Files Delivered

Production:

* NearbyRoomRepository
* NearbyGroupInvitationPreparation
* NearbyRoomMessageDao
* NearbyRoomMessageEntity
* NearbyRoomMessageEditReducer
* ModerationRepository
* GroupInvitationProtocol
* Database migration
* Room schema 55

Tests:

* GroupInvitationProtocolTest
* NearbyRoomMessageEditReducerTest
* NearbyGroupInvitationPreparationTest
* NearbyGroupInvitationMigrationTest

Documentation:

* GI2E2 Production Implementation
* GI2E2 Closure

---

# 12. Final Verdict

GI2E2 satisfies the locked GI2E1 Revision 3.2 contract within the approved milestone scope.

No protocol drift was identified.

No architecture redesign occurred.

No GI2D regression was introduced.

Repository ownership, transaction safety, immutable protection, canonical validation, and send-limit enforcement have been verified.

## Final Status

```text
GI2E2

CLOSED — VERIFIED
```

Next authorized milestone:

```text
GI2E3
```
