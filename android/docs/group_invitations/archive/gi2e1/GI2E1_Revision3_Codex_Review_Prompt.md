# Prompt for Codex — GI2E1 Revision 3 Independent Review

We are restarting GI2E1 review from a new architecture baseline.

## Critical reference reset

Do not implement, extend, patch, or preserve GI2E1 Revision 1 or Revision 2 architecture.

Revision 1 and Revision 2 are historical review evidence only.

They are not implementation authority.

The current reference baseline is:

```text
GI2E Product Architecture Decision:
Invitation Immutability and Local Hide/Unhide

Group_Invitation_Spec_v2_GI2E_Amendment.md

Group_Invitation_Implementation_Roadmap_v2_GI2E_Amendment.md

GI2E1_Structured_Content_Contract_LOCKED.md
Revision 3
```

Revision 3 is intentionally rewritten from the new Product Decision:

```text
Invitation is immutable after successful message creation.

Invitation cannot be edited, soft-deleted, recalled or replaced.

Authorized viewers may locally Hide/Unhide the same message.

Hidden placeholder is presentation state, not another message.
```

Do not bring back Revision 1–2 mechanisms such as:

```text
invitation soft-delete
deleted-private placeholder caused by sender delete
retained wire body solely for delete recovery
quota evidence recovery after invitation soft-delete
remote invitation delete propagation
```

---

# Task

Perform an Independent Architecture Review of GI2E1 Revision 3.

Do not implement production code.

Do not modify the contract automatically.

Do not change status to LOCKED.

Create or replace:

```text
android/docs/group_invitations/GI2E1_Independent_Review.md
```

---

# Required reading

Read in full:

```text
android/docs/Engineering_Constitution.md

android/docs/group_invitations/Product_Architecture_Invariants.md
android/docs/group_invitations/Group_Invitation_Spec_v2.md
android/docs/group_invitations/GI2E_Product_Architecture_Decision_Invitation_Immutability_Local_Hiding.md
android/docs/group_invitations/Group_Invitation_Spec_v2_GI2E_Amendment.md
android/docs/group_invitations/GI1R_Group_Invitation_Architecture_Realignment.md
android/docs/group_invitations/Group_Invitation_Implementation_Roadmap_v2.md
android/docs/group_invitations/Group_Invitation_Implementation_Roadmap_v2_GI2E_Amendment.md

android/docs/group_invitations/GI2A1_Current_Architecture_Contract.md
android/docs/group_invitations/GI2A2_Recipient_Encryption_Identity_Contract.md
android/docs/group_invitations/GI2A3_Android_Keystore_Recipient_Identity_Implementation.md
android/docs/group_invitations/GI2B1_Nearby_Self_Info_Additive_Protocol_Contract_v2_Realigned.md
android/docs/group_invitations/GI2B2_Nearby_Self_Info_Runtime_Integration_Contract.md
android/docs/group_invitations/GI2C_Room_Scoped_Recipient_Key_Pinning_Contract_LOCKED.md
android/docs/group_invitations/GI2C_Closure_Evidence.md
android/docs/group_invitations/GI2D_Private_Invitation_Cryptography_Contract_LOCKED.md
android/docs/group_invitations/GI2D_Closure_Evidence.md

android/docs/group_invitations/GI2E1_Structured_Content_Contract_LOCKED.md
```

Read current source for:

```text
NearbyRoomMessageEntity
NearbyRoomMessageDao
NearbyRoomRepository
NearbyRoomPolicy
NearbyRoomMessageEditReducer
NearbyRoomActivity
NearbyRoomMessageAdapter
NearbyRoomTransportManager
NearbyRoomTransportReceiver
NearbyRoomOwnerMessageRetryCoordinator
NearbyRoomRelayRecoveryManager
NearbyRoomMessageCrypto
NearbyRoom transport codec/envelope
BLE Chunking and transport admission
Room database/migrations
moderation/report paths
all existing local hide/visibility mechanisms

GroupInvitationProtocol
GI1 wire/domain models
GI2C pinned identity
GI2D crypto
```

---

# Review principles

Follow Engineering Constitution:

```text
Avoid unnecessary complexity.
Reuse existing mechanisms.
Prefer a small extension over a new abstraction.
Do not invent a subsystem.
If a required architecture or product change is discovered,
report it for discussion instead of silently redesigning.
```

Priority:

```text
Stability
>
Correctness
>
Maintainability
>
Performance
```

---

# Mandatory review questions

## 1. Product authority

Verify that the new immutability and Hide/Unhide decisions are correctly represented in:

- Product Decision;
- Spec amendment;
- Roadmap amendment;
- Revision 3 contract.

Check for any remaining higher-precedence contradiction.

## 2. Message immutability

Verify authoritative rejection for:

- local Edit;
- local soft-delete;
- Recall/Unsend;
- local body replacement;
- remote edit;
- remote delete;
- normal→structured replacement;
- structured→normal replacement;
- structured→different structured replacement.

Check whether system retention/hard purge remains normal Nearby ownership.

## 3. Sender-local private rendering

Verify whether one `local_render_source_body` field is sufficient when wire body is immutable.

Check:

- process death;
- history reload;
- canonical verification;
- transport exclusion;
- logging/reporting;
- migration;
- security threat model.

Do not propose retained wire body unless source proves it is still required.

## 4. Local Hide/Unhide

Search source for existing generic local visibility/hide mechanisms.

The contract explicitly requires reuse first.

Determine whether Hide/Unhide can use:

- existing message row field;
- existing local preference mechanism;
- existing hidden-item mechanism;
- or requires a minimal new seam.

If a new table/column/architecture is needed, mark it as a discussion-required finding. Do not silently design it.

Verify:

```text
same message
same messageId
same ordering position
no transport event
no body mutation
reversible Unhide
```

## 5. Atomic send limits

Verify that quota recheck and insert can occur in one serialized Room transaction.

Test conceptual races with different prepared message IDs.

No quota table is allowed.

## 6. Prepared context and immutable expiry

Verify:

- ID allocation before GI2D;
- frozen logical sender;
- frozen expiry;
- receiver decrypt uses immutable first persisted TTL;
- structured TTL cannot be widened;
- collision-safe insert;
- no REPLACE.

## 7. Exact body ingress

Verify pre-sanitizer classification and exact persistence.

Ensure normal text cannot bypass limits.

## 8. Public/private mapping

Verify:

- `cardId == messageId`;
- private target gating before decrypt;
- sender never decrypts own envelope;
- GI2D encrypt exactly once;
- retry/relay use same body.

## 9. Reply

Revision 3 prohibits invitation `reply_to` and removes Reply action.

Check that this avoids the current generic relay recovery loss without changing normal message behavior.

## 10. Safe projection/actions

Verify one shared safe projection covers:

- timeline;
- history;
- hidden placeholder;
- accessibility;
- notifications;
- report excerpt;
- action menu.

Required wording/actions:

```text
Hide invitation
Unhide invitation
Report invitation
Report peer
```

No:

```text
Edit
Delete
Recall
Reply
```

## 11. Roadmap boundaries

Verify Revision 3 Section 22 matches:

```text
GI2E2 — Send-limit query and enforcement
GI2E3 — Public invitation send
GI2E4 — Private invitation send
GI2E5 — Parser, fallback and presentation
```

No silent milestone reassignment.

---

# Finding format

Use severity:

```text
BLOCKER
MAJOR
MINOR
NOTE
```

Each finding must include:

```text
ID
Severity
Affected document/section
Source evidence
Why it matters
Required correction
```

Do not carry forward an old finding merely because it existed in Revision 1–2.

Re-evaluate from Revision 3 and current source.

Explicitly mark old findings as:

```text
RESOLVED
NOT APPLICABLE AFTER PRODUCT DECISION
STILL PRESENT
```

where useful.

---

# Allowed verdicts

```text
PASS — READY TO LOCK

PASS WITH REQUIRED CHANGES

FAIL — ARCHITECTURE REVISION REQUIRED

STOP — INSUFFICIENT SOURCE EVIDENCE
```

`PASS — READY TO LOCK` requires:

- zero BLOCKER;
- zero MAJOR;
- no authority conflict;
- Hide/Unhide persistence mechanism proven or clearly discussion-gated;
- send-limit transaction proven;
- immutable message rules implementable;
- GI2E2–GI2E5 boundaries aligned.

---

# Final report

Report:

```text
1. Verdict
2. Findings by severity
3. BLOCKER/MAJOR list
4. Whether Product Decision is internally consistent
5. Whether existing mechanisms can support Hide/Unhide
6. Whether Revision 3 may be locked
7. Whether GI2E2 may be authorized
8. Exact file created
9. Exact files modified
```

Do not implement production code.
