# Problem: `sys_metadata` Profile Delivery Gap

Date:
- 2026-06-16

Status:
- Known issue
- Deferred while the team prioritizes the core member-removal flow

Summary:
- In some real-device runs, chat messages from a peer arrive and render correctly, but that peer's profile metadata does not arrive reliably.
- As a result, screens that depend on `user_profiles` can show an incomplete member list even though the chat timeline already contains messages from that peer.

Observed symptom:
- Device A can receive and render chat messages from peer C.
- `GroupMembersActivity` on device A may still show only A and B because C's `SYS.USER` / `sys_metadata` profile snapshot did not get persisted locally.

Why this matters:
- The issue creates an inconsistent UX:
  - chat timeline shows activity from a peer
  - members list does not show the same peer
- This can also make moderation and governance UX less trustworthy because operator-facing lists depend on complete peer metadata.

Current understanding:
- The issue appears to be a reliability gap in `sys_metadata` delivery and/or retry behavior, not a chat-ingest failure.
- The current UI path for members relies too heavily on `user_profiles` being complete.

Immediate product decision:
- Do not expand scope right now.
- Prioritize the core member-removal behavior first.
- Return later to:
  - harden `SYS.USER` / `SYS.GROUP` metadata delivery
  - add repair/retry behavior for missing profiles
  - make members UI resilient when profile metadata is incomplete

Suggested follow-up directions:
- Treat profile/group metadata as durable control data, not best-effort cosmetic sync.
- Add metadata repair when a client sees content from an unknown actor.
- Consider a durable retry queue for `SYS.USER` / `SYS.GROUP`.
- Make members list fall back to actor IDs observed in chat/events when profile rows are missing.
