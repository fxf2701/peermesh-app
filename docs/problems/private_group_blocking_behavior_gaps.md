# Problem: Private Group Blocking Behavior Gaps

Date:
- 2026-07-04

Status:
- Known issue
- Product direction clarified on 2026-07-04
- Needs implementation alignment

Implementation checklist:
- `docs/backlogs/block-bilateral-send-suppression-checklist.md`

Summary:
- Private Group blocking currently has at least two behavior gaps that make the safety model feel incomplete in real use.
- The current implementation does not yet cleanly align with the intended global blocking rule.
- The intended rule is:
  - `block => local hidden-receive / hidden-render behavior`
  - `block != send suppression`
  - `block != join denial / membership denial`
- In practical terms:
  - block does not prevent joining groups or nearby rooms
  - block does not act as a membership or discovery deny-list
  - block should not stop local user from sending messages in the same room/group
  - block should stop blocked peer messages from materializing into the local message table
  - clients may still receive and relay envelopes for BLE mesh / sync propagation

Observed behaviors:
- User B blocks user A.
- After that:
  - A can no longer send messages to B, which is the expected direction for immediate protection.
  - B may still be able to send messages to A.
  - A may still be able to create another group and invite B into it.
  - B may still be able to invite A into B's own group.

Confirmed product rule:
- Blocking must not be interpreted as "cannot join my group" or "cannot join my nearby room".
- Blocking must not automatically suppress membership, discovery, or invitation surfaces purely because of the block relationship.
- Blocking should be treated as a local hidden-receive safety rule:
  - if B blocks A, device B should not materialize A's chat messages locally
  - B may still send messages in the same room/group
  - the app may still receive and relay blocked-peer envelopes when needed for transport convergence
  - this should hold consistently across Private Group and Nearby Room

Why this matters:
- Without a written rule, the team can easily drift into inconsistent implementations:
  - one path treats block as content hiding
  - another path treats block as join denial
  - another path treats block as full contact suppression
- The shipped behavior needs one simple contract that can be enforced consistently across transports and UIs.

Open product questions:
- Should invites remain fully allowed, or should the product add softer warnings while still permitting join/invite flows?
- Should blocked-peer messages be recoverable locally after unblock, or is forward-only hidden behavior acceptable?

Current concern:
- The main risk is not "blocked peers can still join".
- The main risk is that some ingest/materialization paths may still persist blocked-peer messages into local message tables.
- Nearby Room also has an extra constraint:
  - hidden blocked-peer messages should not break relay / recovery behavior needed for BLE mesh traffic

Suggested next-step decisions:
- Keep block semantics narrow and explicit:
  - no join denial
  - no room/group membership denial
  - no discovery suppression purely because of block
  - no send suppression purely because of block
  - yes local hidden-receive / hidden-render behavior for blocked-peer messages
- Audit all ingest/materialization paths against that rule:
  - Private Group event ingest + reducer path
  - Nearby Room transport ingest + snapshot merge path
  - relay / recovery paths that still need hidden message availability without UI materialization

Recommended direction:
- Treat block as a global peer-level safety control.
- Do not overload it into a join/access control primitive.
- Preserve normal room/group join behavior unless a separate moderation rule explicitly says otherwise.
- Enforce the core invariant consistently:
  - once B blocks A, device B should stop materializing A's messages locally while still allowing transport propagation to continue
