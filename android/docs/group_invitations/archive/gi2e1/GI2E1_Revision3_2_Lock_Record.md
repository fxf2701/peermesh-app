# GI2E1 Revision 3.2 — Lock Record

**Revision:** 3.2  
**Status:** LOCKED  
**Lock date:** 2026-08-05  
**Production source changed:** No

## Authority

Revision 3.2 is explicitly locked after the independent delta re-review returned:

```text
PASS — READY TO LOCK
```

The independent review reported zero BLOCKER, zero MAJOR and recommended:

```text
LOCK REVISION 3.2
AUTHORIZE GI2E2 AFTER REVISION 3.2 IS LOCKED
```

This record is the explicit lock action. The review verdict alone was not the lock action.

## Locked authority set

The following documents together form the locked GI2E1 Revision 3.2 authority:

```text
GI2E1_Structured_Content_Contract_LOCKED.md
GI2E1_Revision3_2_Amendment.md
GI2E1_Independent_Review.md
GI2E1_Revision3_2_Lock_Record.md
```

Revision 3.2 preserves all unchanged Revision 3.1 requirements and adds only the approved Hide/Unhide persistence clarification.

## Locked decision

```text
One production local actor
↔
One peer_mesh.db
```

`NearbyRoomMessageEntity.is_hidden_locally` is installation-local presentation state.

Debug/test actor switching remains an accepted limitation outside Hide/Unhide isolation guarantees.

## Stop condition

If production later introduces multiple local actors sharing one database:

```text
stop GI2E persistence work
→ architecture re-review
→ approve viewer-keyed persistence before implementation or release
```

The current boolean must not be extended to a production multi-actor model without that review.

## GI2E2 authorization

GI2E2 architecture and implementation planning are authorized under the locked Revision 3.2 contract.

Production implementation remains subject to the GI2E2 workflow:

```text
Architecture Review
→ Implementation Plan
→ Independent Review
→ Production Implementation
→ Manual Review
→ Lock
```

This lock record does not waive GI2E2's own independent review requirement.
