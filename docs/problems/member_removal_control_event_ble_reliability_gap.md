# Problem: Member Removal Control Event Reliability In BLE Mesh

Date:
- 2026-06-19

Status:
- Known issue
- Deferred while the team prioritizes the current moderation and membership baseline

Summary:
- `member_removed` and group rekey / rotation events are control-plane events with high product impact.
- In some real-device BLE mesh runs, these events do not reach every member reliably.
- As a result, different clients can temporarily disagree about who is still an active member and which group key epoch is current.

Observed symptoms:
- Group owner removes member B.
- Some clients apply `removed=true` for B, while another client does not.
- Some clients move to the new key / epoch, while another client remains on older invitation or decrypt state.
- In bad rounds, one valid member can stop seeing another valid member's new messages because key-state convergence is incomplete.

Why this matters:
- Member removal is a governance and moderation control, not just a cosmetic event.
- Rekey is required to prevent a removed member from continuing normal access to new group activity.
- If these control events are not delivered reliably, the product can show inconsistent safety behavior across devices.

Current understanding:
- The issue appears to be a control-event propagation and convergence gap in BLE mesh delivery.
- `member_removed` and rekey already have stronger priority and longer retention intent, but real-device coverage is still not sufficient in all rounds.
- The gap may involve one or more of:
  - send ordering
  - resend / retry behavior
  - TTL / retention policy
  - inventory visibility
  - batching behavior
  - ingest timing relative to ordinary group traffic

Current product posture:
- The present V1 behavior is acceptable only as synced-client enforcement.
- The system should not claim instant or universal enforcement before all relevant clients converge on the control events.

Suggested follow-up directions:
- Treat `member_removed` and rekey as top-priority control events across BLE / DTN paths.
- Ensure these events are sent and ingested ahead of ordinary group activity whenever possible.
- Increase durable retry / replay coverage for these event types.
- Add explicit observability for:
  - control event creation
  - send attempts
  - batch inclusion
  - peer ingest
  - local application of removed state
  - local activation of the new key epoch
- Add repair behavior when a client receives ordinary traffic that implies it missed a required control event.

Acceptance goal for a future fix:
- In repeated 3-device real-device BLE runs, `member_removed` and the follow-up rekey should converge on all active members with materially higher reliability than ordinary chat traffic.
