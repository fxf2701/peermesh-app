# Chat Edit Schema Change Test Matrix

## Purpose
- Provide a single manual QA matrix for the `CHAT.REPLY` + `CHAT.EDIT` schema change.
- Verify the new balanced event shape works across local send, transport, ingest, reducer, and UI render.
- Cover provisional/orphan edit behavior when `CHAT.EDIT` arrives before or without the base message.

## Scope
- `CHAT.MSG`
- `CHAT.REPLY`
- `CHAT.EDIT`
- New schema:
  - `CHAT.REPLY` uses `ref.parent`
  - `CHAT.EDIT` uses `ref.target`
  - `CHAT.EDIT.data.tct` carries target created time
- Backward compatibility:
  - new receiver reads legacy `data.reply_to`
  - new receiver reads legacy `data.target_id`
  - new receiver reads legacy `data.target_created_t`
- BLE direct
- BLE multi-hop
- Cloud direct
- BLE `<->` cloud bridge
- Out-of-order and orphan edit paths

## Non-Goals
- Old receiver compatibility with new sender is not guaranteed in this phase.
- Payload byte-budget guard is not implemented yet, so this matrix does not treat serialized-size rejection as a pass/fail requirement.

## Devices
- `A`: source or original author
- `B`: peer, relay-ingest observer, or bridge
- `C`: downstream receiver

## Target Event Conventions

`CHAT.MSG`
```json
{
  "ref": {
    "root": null,
    "parent": null,
    "target": null
  },
  "data": {
    "text": "hello"
  }
}
```

`CHAT.REPLY`
```json
{
  "ref": {
    "root": null,
    "parent": "message-id",
    "target": null
  },
  "data": {
    "text": "reply text"
  }
}
```

`CHAT.EDIT`
```json
{
  "ref": {
    "root": null,
    "parent": null,
    "target": "message-id"
  },
  "data": {
    "text": "edited text",
    "tct": 1782900050
  }
}
```

## Required Preconditions
- All devices are in the same private group.
- Unless a case explicitly says otherwise, all devices run the new schema build from branch `feature/chat-edit-mvp` at or after commit `1f2654b8`.
- Activity Log is enabled on all participating devices before each run.
- Devices have valid group membership and identity state.
- For migration/persistence checks, use a device upgraded from DB version `43` to `44+`.

## Compatibility Assumption
- Supported:
  - old sender -> new receiver
  - mixed transport paths among new receivers
- Not a pass condition in this phase:
  - new sender -> old receiver

## Activity Log Labels To Watch

```text
Sent message at
Sent edit at
APP batch emit
APP batch transport queued
BLE batch send progress
BLE batch send complete
BLE batch send failed
Relay push
DB event insert
DB event duplicate drop
Event validator reject
Chat relay ingest
Chat edit ingest
Chat edit provisional created
Chat edit applied
Chat edit skipped
Chat reducer upsert
Rendered message at
```

Focus on these expected messages:
- On sender `A`:
  - `transport_outcome=queued`
  - `transport_outcome=sent`
  - `transport_outcome=first_peer_delivered`
  - `transport_outcome=relay_echo_only`
  - `transport_outcome=final_failed`
- On receiver `B` or `C`:
  - `path=mesh`
  - `path=relay`
  - `action=applied_edit`
  - `reason=target_missing`
  - `reason=no_target`
  - `reason=actor_mismatch`

## Expected Data Rules
- `CHAT.REPLY` source of truth is `ref.parent`.
- `CHAT.EDIT` source of truth is `ref.target`.
- `CHAT.EDIT.data.tct` should equal the original target message `created_t`.
- New sender should not emit:
  - `CHAT.REPLY.data.reply_to`
  - `CHAT.EDIT.data.target_id`
- New receiver should still accept legacy forms during transition.
- If edit arrives before base message:
  - reducer creates a provisional row keyed by target message id
  - provisional row uses `tct` for `created_t`
  - UI renders edited content immediately
- If base message arrives later:
  - no duplicate row is created
  - row merges correctly
  - LWW still decides final visible text

## SQL Checks
Use these when you can inspect device DB.

```sql
select message_id, text, created_t, edited_at, reply_to, last_event_id, actor_did
from chat_messages
where gid = '<gid>' and message_id = '<target_message_id>';
```

Expected for edited message:
- one row only
- `text` matches latest applied content
- `edited_at` is not null after edit
- `created_t` equals original message create time or `tct` for provisional/orphan path

```sql
select event_id, type, ref_target, payload
from events
where gid = '<gid>' and (event_id = '<target_message_id>' or ref_target = '<target_message_id>');
```

Expected:
- base message event may or may not exist depending on scenario
- edit events exist with `type = 'CHAT.EDIT'`
- reducer behavior matches logs and final row state

## Test Cases

### S1. New Sender Emits New `CHAT.REPLY` Shape
Topology:
- `A` local only or `A <-> B`

Steps:
1. `A` sends message `M1`.
2. `A` replies to `M1` with `R1`.
3. Inspect local stored event or export log payload for `R1`.

Expected:
- `R1.ref.parent = M1.id`
- `R1.data.text` exists
- `R1.data.reply_to` is absent
- reply renders correctly in `A` timeline

### S2. New Sender Emits New `CHAT.EDIT` Shape
Topology:
- `A` local only or `A <-> B`

Steps:
1. `A` sends message `M1`.
2. Record `M1.created_t`.
3. `A` edits `M1` to `E1`.
4. Inspect local stored event or export log payload for `E1`.

Expected:
- `E1.ref.target = M1.id`
- `E1.data.text` exists
- `E1.data.tct = M1.created_t`
- `E1.data.target_id` is absent
- edited badge is visible on `A`

### S3. Legacy `CHAT.REPLY` Still Accepted By New Receiver
Topology:
- old `A` -> new `B`

Steps:
1. Use an older sender build that still emits `data.reply_to`.
2. `A` sends `M1` and then sends reply `R1`.
3. `B` receives both events.

Expected:
- `B` accepts `R1`
- reply preview resolves correctly
- no validator reject for missing `ref.parent`

### S4. Legacy `CHAT.EDIT` Still Accepted By New Receiver
Topology:
- old `A` -> new `B`

Steps:
1. Use an older sender build that emits `data.target_id` and optionally `data.target_created_t`.
2. `A` sends `M1`.
3. `A` edits `M1`.
4. `B` receives both events.

Expected:
- `B` accepts edit without requiring `ref.target + data.tct`
- visible text on `B` becomes edited text
- edited badge is visible

### S5. Edit Over BLE Direct
Topology:
- `A <-> B`

Steps:
1. `A` sends `M1`.
2. Confirm `B` renders `M1`.
3. `A` edits `M1` to `E1`.
4. Observe logs on both devices.

Expected:
- `A` logs per-event transport outcome for `E1`
- `B` logs `received over mesh`
- `B` logs `applied edit to target`
- `B` updates existing row, not a new row
- `(edited)` badge appears on both devices

### S6. Edit Over Cloud Direct
Topology:
- `A -> cloud -> B`

Steps:
1. Disable BLE or separate devices.
2. `A` sends `M1`.
3. `A` edits `M1`.
4. `B` receives via relay/cloud path.

Expected:
- `B` logs `received over relay`
- `B` applies edit correctly
- final timeline matches `A`

### S7. Edit Over BLE Multi-Hop
Topology:
- `A <-> B <-> C`
- `A` not directly connected to `C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1` through `B`.
3. `A` edits `M1`.
4. `C` receives edit through `B`.

Expected:
- `C` converges to edited text
- no duplicate row on `B` or `C`
- transport/debug logs identify the forwarding path

### S8. Edit Over BLE -> Cloud Bridge
Topology:
- `A --BLE--> B --cloud--> C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1` through bridge `B`.
3. `A` edits `M1`.
4. `C` receives edit through bridge path.

Expected:
- edit survives bridge unchanged
- `ref.target` remains correct
- `tct` survives bridge unchanged

### S9. Edit Over Cloud -> BLE Bridge
Topology:
- `A --cloud--> B --BLE--> C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1`.
3. `A` edits `M1`.
4. `C` receives edit through reverse bridge.

Expected:
- same as `S8`
- no bridge-specific schema loss

### S10. Orphan Edit Arrives Before Base Message
Topology:
- any path where reorder can be forced

Steps:
1. `A` sends base message `M1`.
2. Delay or block `M1` to `B`.
3. `A` sends edit `E1` for `M1`.
4. Allow `B` to receive `E1` first.

Expected:
- `B` inserts event successfully
- `B` logs `skipped because target missing` only if reducer cannot create provisional row
- current target build should instead create provisional row
- `B` logs `Chat edit provisional created`
- UI on `B` shows edited content in history at position derived from `tct`
- row uses `message_id = M1.id`

### S11. Base Message Never Arrives After Orphan Edit
Topology:
- any path with permanent loss of `M1`

Steps:
1. Repeat `S10`.
2. Keep `M1` blocked permanently.
3. Restart `B`.
4. Reopen the same chat.

Expected:
- edited provisional row remains visible after restart
- no crash or infinite spinner
- row ordering remains stable
- badge remains visible

### S12. Base Message Arrives After Provisional Edit
Topology:
- any path where reorder can be forced

Steps:
1. Execute `S10` until provisional row is visible on `B`.
2. Later allow `M1` to arrive.
3. Reopen chat if needed.

Expected:
- no duplicate row
- `created_t` remains aligned to original message order
- reply metadata merges correctly if `M1` is actually `CHAT.REPLY`
- final text follows LWW

### S13. Multiple Edits Around Late Base Message
Topology:
- reorder path

Steps:
1. Block `M1` to `B`.
2. `A` sends edits `E1`, then `E2` for `M1`.
3. `B` receives `E1`, then `E2`, then finally `M1`.

Expected:
- provisional row content ends at `E2`
- late `M1` does not revert text back to original
- only one visible row exists

### S14. Cross-Author Edit Is Ignored
Topology:
- `A <-> B`

Steps:
1. `A` sends `M1`.
2. `B` attempts to inject or replay a `CHAT.EDIT` targeting `M1`.

Expected:
- reducer rejects because actor does not match original message author
- `A` and `B` still show original content
- logs show `reason=actor_mismatch`

### S15. Edit To Deleted Message Is Ignored
Topology:
- `A <-> B`

Steps:
1. `A` sends `M1`.
2. `A` deletes `M1`.
3. After delete is applied, send or inject edit `E1` for `M1`.

Expected:
- delete remains final state
- edited content does not resurrect the row
- logs show delete/edit rejection behavior consistent with reducer guards

### S16. Reply Schema + Edit Schema Together
Topology:
- `A <-> B`

Steps:
1. `A` sends `M1`.
2. `A` replies with `R1`.
3. `A` edits `R1`.
4. `B` receives all events.

Expected:
- `R1` reply preview still points to `M1`
- edited text is visible in `R1`
- no dependency on legacy `data.reply_to`

### S17. Badge Stability After ACK / Seen Updates
Topology:
- any two-device path

Steps:
1. Create edited message on `A`.
2. Let `B` receive and ack/seen the message.
3. Refresh chat and reopen on both devices.

Expected:
- `(edited)` badge stays visible
- ack/seen updates do not clear `edited_at`

### S18. Transport Failure Visibility
Topology:
- any path where failure can be forced

Steps:
1. Prepare edit event `E1` on `A`.
2. Disable both BLE delivery and relay delivery before send completes.
3. Observe sender logs.

Expected:
- `A` logs transport outcome transitions for `E1`
- terminal state is visible as `final_failed` when nothing can deliver
- local UI should not silently invent remote success

## Suggested Run Order
1. `S1` to `S4` for schema and compatibility basics.
2. `S5` to `S9` for transport propagation.
3. `S10` to `S13` for provisional/orphan behavior.
4. `S14` to `S18` for guardrails and regressions.

## Exit Criteria
- New sender always emits `CHAT.REPLY` and `CHAT.EDIT` in the new schema shape.
- New receiver accepts both new and legacy sender payloads.
- Edited messages converge correctly across BLE, cloud, and bridge paths.
- Provisional/orphan edit behavior is stable:
  - visible without base message
  - ordered by `tct`
  - merged without duplication when base message arrives later
- No regression in reply preview, edited badge, tombstone precedence, or cross-author protection.
