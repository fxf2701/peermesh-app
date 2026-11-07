# Problem: Group Members List Can Be Incomplete Or Inconsistent

Date:
- 2026-06-19

Status:
- Known issue
- Deferred while the team prioritizes core moderation and membership enforcement

Summary:
- The members list can sometimes show fewer members than actually appear in chat activity.
- This can happen on ordinary members and also on the group owner.
- In some runs, the list may miss one or more active peers even though their messages are visible in the chat timeline.

Observed symptoms:
- Chat timeline shows activity from A, B, and C.
- `Members` or `Group Settings` may still show only a subset such as A and B.
- The issue may persist until additional metadata sync happens, or may remain inconsistent for the duration of the test round.

Why this matters:
- Members list is a core trust surface for moderation and governance.
- Owner actions such as `Remove member` depend on a complete and correct view of active members.
- An incomplete list makes it harder to reason about membership, enforcement, and accountability.

Current understanding:
- This problem is related to the broader metadata / profile sync reliability gap already noted in:
  - `docs/problems/sys_metadata_profile_delivery_gap.md`
- The current UI can depend too heavily on profile / metadata completeness when resolving the visible member set.
- The active-member resolution path should be consistent with the app's actor-selection logic and resilient when profile data is late or missing.

Likely contributing factors:
- delayed or missing `sys_metadata` / profile delivery
- incomplete repair when a client sees content from an actor whose profile row is missing
- member list derivation relying on one source of truth that may be stale
- edge cases around multiple actors on the same device and active-actor resolution

Current product posture:
- This issue is treated as a UX and governance reliability gap, not as the primary blocker for current member-removal V1 enforcement.
- The team should avoid over-claiming that the members list is always authoritative until this gap is hardened.

Suggested follow-up directions:
- Make profile / membership metadata delivery more durable.
- Add repair behavior when chat or other events reference an actor not fully represented in local profile state.
- Reuse a single active-actor resolution helper across:
  - Test users / switch user
  - members list
  - governance actions
- Consider a fallback member derivation path from observed valid activity when profile metadata is incomplete.
- Add targeted real-device tests for:
  - owner sees all active members
  - non-owner sees all active members
  - member list remains correct after removal and rekey

Acceptance goal for a future fix:
- Across repeated real-device runs, members list should consistently show the same active peers implied by converged group state and visible valid chat activity.
