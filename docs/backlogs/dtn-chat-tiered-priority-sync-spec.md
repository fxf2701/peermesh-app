# DTN Chat Tiered Priority Sync Spec

## Status
- Draft

## Problem
- The current DTN sync flow can allow old backlog to get ahead of fresh chat traffic.
- In recent BLE/DTN rounds, `INV_HAVE` could contain many old items, which then caused `INV_WANT` to select backlog before newly created chat traffic.
- `SYS.ACK` is already low priority at the event-priority level, but fresh chat can still be delayed because the inventory window and want selection do not prioritize recent chat-family events strongly enough.

## Goal
- When two peers meet, recent chat-family events must sync first.
- `SYS.ACK` must be handled last.
- Old backlog must not block newly created messages during short sync sessions.

## Scope
- DTN `INV_HAVE`
- DTN `INV_WANT`
- DTN `DATA`
- Session task scheduling after `HELLO`
- Chat-family event selection

## Non-Goals
- Do not change the semantic meaning of `SYS.ACK`.
- Do not change TTL or hop-count behavior.
- Do not change UI behavior.
- Do not solve all starvation at the radio / GATT transport level.

## Definitions

### Chat-Family Event
Includes:
- `CHAT.MSG`
- `CHAT.REPLY`
- `CHAT.DEL`
- `CHAT.EDIT`

### Time Reference
- Any age-based classification must use `created_t`.
- It must not use `inserted_at`.

### Tier
1. `HIGH`
2. `NORMAL`
3. `LOW`

## Tier Rules

### HIGH
- Chat-family events
- With `created_t >= now - 3600s`

### NORMAL
- All remaining events
- Excluding `SYS.ACK`

### LOW
- `SYS.ACK` only

## Expected Behavior

### Priority Order
- `HIGH` must be processed before `NORMAL`
- `NORMAL` must be processed before `LOW`
- A peer may move to the next tier only after the current tier has been drained for that session, or when no valid items remain in that tier.

### Downtime Rule
- If a peer comes back online after a long downtime and all of its items have `created_t < now - 1h`, that peer may have no `HIGH` tier.
- In that case, the session skips `HIGH` and moves directly to `NORMAL`.

## Protocol Rules

### 1. `HELLO`
After `HELLO`, the engine may still initialize the session as it does today.

### 2. `INV_HAVE`
- `INV_HAVE` must not be built as a single flat inventory list.
- Instead, the engine must build inventory by tier.
- The session must advertise `HIGH` first.
- Once `HIGH` is complete, it may advertise `NORMAL`.
- Once `NORMAL` is complete, it may advertise `LOW`.

### 3. `INV_WANT`
- When receiving `INV_HAVE`, the peer must resolve missing ids only within the tier represented by that `INV_HAVE`.
- `INV_WANT` must not mix `HIGH`, `NORMAL`, and `LOW` in the same packet or session step while a higher tier is still pending.

### 4. `DATA`
- `DATA` must be served according to the tier of the corresponding `INV_WANT`.
- Within the same tier, data is returned in the same event-id order as `INV_WANT`.

### 5. `ACK`
- `SYS.ACK` belongs to `LOW`.
- ACK traffic must not push fresh chat behind older work while `HIGH` or `NORMAL` still has pending work.

## Selection Algorithm

### Have Selection
For each session `(peerId, gid, sessionId)`:
1. Compute local `HIGH` inventory items.
2. Compute local `NORMAL` inventory items.
3. Compute local `LOW` inventory items.
4. Select the first tier that still has unsynced items.
5. Build `INV_HAVE` from that tier only.

### Want Selection
When receiving `INV_HAVE`:
1. Determine the tier of the packet.
2. Compute which local items are still missing within that tier.
3. Apply existing suppression rules.
4. Build `INV_WANT` using ids from that tier only.

## Ordering Inside Each Tier

### HIGH
Within `HIGH`, prefer:
1. Newer `created_t` first
2. If equal, `event_id`

Rationale:
- The purpose of `HIGH` is to prioritize the freshest chat traffic, not oldest-first replay.

### NORMAL
Within `NORMAL`, the implementation may continue to use current ordering, or:
1. Existing event-type priority
2. `created_t`
3. `event_id`

### LOW
Within `LOW`, prefer:
1. `created_t`
2. `event_id`

Freshness optimization is not required for `LOW`.

## Session State Requirements
- The session must track the active tier.
- The session must know:
  - `current_have_tier`
  - `current_want_tier`
  - `tier_drained`
- Cursor and inventory state must not allow `LOW` items to appear while `HIGH` is still incomplete.

## Implementation Constraints
- The `250`-item inventory window must not prevent `HIGH` items from being advertised.
- If separate windows or cursors are needed, they should be maintained per tier.
- If tier-specific cursors are not introduced yet, the minimum acceptable implementation must re-rank and force-include `HIGH` items before building `INV_HAVE`.

## Observability
Add or retain markers to make the behavior debuggable:
- `DTN have tier`
- `DTN want tier`
- `DTN data tier`
- `DTN tier drained`
- `DTN tier skipped`
- `DTN high inventory count`
- `DTN normal inventory count`
- `DTN low inventory count`

## Acceptance Criteria
1. When recent chat-family events exist within the last hour, the session must advertise and request those events before old backlog.
2. `SYS.ACK` must not appear in `INV_WANT` while `HIGH` or `NORMAL` is still pending.
3. A peer returning from long downtime with no events newer than one hour must skip `HIGH` without failing the session.
4. Fresh chat-family events must not be delayed by old inventory backlog in normal BLE multi-hop or cloud-bridge test rounds.
5. New reply, delete, and edit events must be treated as `HIGH` when their `created_t` falls within the one-hour window.

## Open Questions
- Should each tier have its own `id` budget or byte budget per packet or session?
- Should `NORMAL` be further split into `old chat` and `non-chat`?
- Should there be a fairness quota so that `LOW` can still drain during very long sessions, instead of being starved indefinitely?
