# Chat Reply + Delete Transport Test Matrix

## Purpose
- Provide a single manual test matrix for `reply message` and `delete message`.
- Cover BLE mesh, cloud, and BLE `<->` cloud bridge paths.
- Keep source-device behavior, receiver behavior, and export safety aligned.

## Scope
- `CHAT.REPLY`
- `CHAT.DEL`
- BLE direct
- BLE multi-hop
- Cloud direct
- BLE -> cloud bridge
- Cloud -> BLE bridge
- Replay/reorder/restart cases

## Devices
- `A`: source
- `B`: peer or bridge
- `C`: receiver or downstream peer

## Required Preconditions
- All devices run the same build.
- DB schema is the delete-tombstone build:
  - `AppDatabase` version `37+`
  - `deleted_events` table exists
- Activity Log is enabled on all devices before each run.
- Group membership and identity are already valid on all participating devices.

## Activity Log Labels
Use these labels for every run:

```text
Received message at
Rendered message at
DB event insert
Sent message at
Sent delete at
Chat reducer upsert
Deleted tombstone reject
APP batch emit
APP batch transport queued
BLE batch send progress
BLE batch send complete
BLE batch send failed
Relay push
```

## Transport Topologies
1. BLE direct: `A <-> B`
2. BLE 3-node: `A <-> B <-> C`
3. Cloud direct: `A -> cloud -> B`
4. Cloud fan-out: `A -> cloud -> B`, `A -> cloud -> C`
5. BLE -> cloud bridge: `A --BLE--> B --cloud--> C`
6. Cloud -> BLE bridge: `A --cloud--> B --BLE--> C`
7. Mixed concurrent: all devices have BLE + cloud

## Expected UI Rules
- Reply preview shows the correct parent snippet when parent exists locally.
- Tap reply preview scrolls to parent.
- Deleted message shows `Deleted`.
- Deleted message uses muted gray + italic style.
- Reply preview to deleted parent shows `Deleted`, muted gray + italic.
- User cannot delete another user's message.

## Expected Data Rules
- `CHAT.REPLY` preserves `reply_to` across all transports.
- `CHAT.DEL` only applies when delete actor owns the target message.
- After delete:
  - `chat_messages.text = 'Deleted'`
  - `chat_messages.status = 'deleted'`
  - original plaintext event is removed from `events`
  - tombstone exists in `deleted_events`
- Old plaintext event arriving later must not resurrect the message.

## SQL Checks
Use these after delete cases:

```sql
select message_id, text, status, last_event_id
from chat_messages
where message_id = '<target_message_id>';
```

Expected:
- `text = 'Deleted'`
- `status = 'deleted'`

```sql
select event_id, type, source
from events
where event_id = '<target_message_id>';
```

Expected:
- no row for the original plaintext chat event

```sql
select gid, target_event_id, delete_event_id, expires_at
from deleted_events
where target_event_id = '<target_message_id>';
```

Expected:
- one tombstone row exists

## Reply Test Cases

### R1. Reply over BLE Direct
Topology:
- `A <-> B`

Setup:
- `A` and `B` have BLE on
- cloud can be off to isolate BLE

Steps:
1. `A` sends original message `M1`.
2. `B` receives `M1`.
3. `B` long-presses `M1` and selects `Reply`.
4. `B` sends reply `R1`.
5. `A` receives `R1`.
6. `A` taps the reply preview inside `R1`.

Expected:
- `R1.reply_to = M1.id`
- `R1` shows a parent snippet
- tapping preview scrolls to `M1`
- parent highlight appears correctly
- no duplicate row is created

### R2. Reply over Cloud Direct
Topology:
- `A -> cloud -> B`

Setup:
- BLE off or no nearby contact
- cloud on for both

Steps:
1. `A` sends `M1`.
2. `B` receives `M1` via cloud.
3. `B` replies with `R1`.
4. `A` receives `R1`.

Expected:
- same behavior as `R1`
- propagation works without BLE

### R3. Reply over BLE Multi-hop
Topology:
- `A <-> B <-> C`
- `A` not directly connected to `C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1` via `B`.
3. `C` replies with `R1`.
4. `A` receives `R1` via `B`.

Expected:
- `R1` keeps the correct `reply_to`
- `A` preview resolves to `M1`
- tapping preview on `A` scrolls to `M1`

### R4. Reply over BLE -> Cloud Bridge
Topology:
- `A --BLE--> B --cloud--> C`

Setup:
- `A`: BLE only
- `B`: BLE + cloud
- `C`: cloud only

Steps:
1. `A` sends `M1`.
2. `B` receives `M1` via BLE.
3. `C` receives `M1` via cloud.
4. `C` replies with `R1`.
5. `B` receives `R1` via cloud.
6. `A` receives `R1` via BLE bridge from `B`.

Expected:
- `reply_to` survives the bridge
- `A` sees the correct parent snippet

### R5. Reply over Cloud -> BLE Bridge
Topology:
- `A --cloud--> B --BLE--> C`

Setup:
- `A`: cloud only
- `B`: cloud + BLE
- `C`: BLE only

Steps:
1. `A` sends `M1` via cloud.
2. `B` receives `M1`.
3. `B` bridges `M1` to `C` via BLE.
4. `C` replies with `R1`.
5. `B` receives `R1` via BLE.
6. `A` receives `R1` via cloud.

Expected:
- `reply_to` survives the reverse bridge
- parent snippet renders correctly on all devices

### R6. Reply When Parent Arrives Late
Topology:
- any path where reorder can be forced

Steps:
1. `A` sends `M1`.
2. Delay `M1` to `C`.
3. `B` already has `M1` and sends reply `R1`.
4. `C` receives `R1` before `M1`.
5. `C` later receives `M1`.

Expected:
- before `M1` arrives, reply preview uses safe fallback
- after `M1` arrives, preview resolves to real snippet
- no duplicate rows or broken linkages

### R7. Reply Chain
Steps:
1. `A` sends `M1`.
2. `B` replies `R1` to `M1`.
3. `C` replies `R2` to `R1`.

Expected:
- `R2` points to `R1`, not `M1`
- tap-to-scroll always targets the direct parent

### R8. Reply Survives App Restart
Steps:
1. Create `M1` and `R1`.
2. Restart the app on a receiver.
3. Reopen the chat.

Expected:
- reply preview still resolves correctly
- tapping preview still scrolls to parent

## Delete Test Cases

### D1. Delete Own Message over BLE Direct
Topology:
- `A <-> B`

Steps:
1. `A` sends `M1`.
2. `B` receives `M1`.
3. `A` long-presses `M1` and selects `Delete`.
4. `A` confirms the dialog.

Expected on `A`:
- `M1` becomes `Deleted`
- style is muted gray + italic
- old plaintext is no longer visible

Expected on `B`:
- same row remains present
- text is `Deleted`
- old plaintext is not visible

### D2. Delete Own Message over Cloud Direct
Topology:
- `A -> cloud -> B`

Steps:
1. `A` sends `M1`.
2. `B` receives `M1`.
3. `A` deletes `M1`.

Expected:
- same result as `D1`
- propagation works through cloud

### D3. Delete Own Message over BLE Multi-hop
Topology:
- `A <-> B <-> C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1`.
3. `A` deletes `M1`.

Expected:
- `B` and `C` both show `Deleted`
- no later replay restores plaintext

### D4. Delete over BLE -> Cloud Bridge
Topology:
- `A --BLE--> B --cloud--> C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1`.
3. `A` deletes `M1`.
4. `B` bridges delete to cloud.

Expected:
- `C` updates to `Deleted`
- old text is not visible in UI

### D5. Delete over Cloud -> BLE Bridge
Topology:
- `A --cloud--> B --BLE--> C`

Steps:
1. `A` sends `M1`.
2. `C` receives `M1`.
3. `A` deletes `M1`.
4. `B` bridges delete to BLE.

Expected:
- `C` updates to `Deleted`
- no plaintext leak

### D6. Cannot Delete Another User's Message
Steps:
1. `A` sends `M1`.
2. `B` long-presses `M1`.

Expected:
- `Delete` action is not shown for `B`
- no `CHAT.DEL` is created

### D7. Delete Arrives Before Original Message
Topology:
- any path with reorder/delay

Steps:
1. `A` sends `M1`.
2. `A` deletes `M1` immediately.
3. `C` receives delete before original `M1`.
4. old `M1` later arrives.

Expected:
- `M1` must not resurrect
- ingest may log `Deleted tombstone reject`

### D8. Delete Parent After a Reply Exists
Steps:
1. `A` sends `M1`.
2. `B` replies `R1` to `M1`.
3. `A` deletes `M1`.

Expected:
- `M1` becomes `Deleted`
- `R1` remains present
- `R1` preview now shows `Deleted`
- old parent text is not leaked

### D9. Delete After Message Was Edited
Steps:
1. `A` sends `M1`.
2. `A` edits `M1`.
3. `A` deletes `M1`.

Expected:
- final state is `Deleted`
- old edit chain does not restore plaintext

### D10. Edit After Delete Must Be Rejected
Steps:
1. `A` sends `M1`.
2. `A` deletes `M1`.
3. a delayed or stale `CHAT.EDIT` for `M1` arrives later.

Expected:
- edit is rejected
- message stays `Deleted`

### D11. Replay of Old Original Event After Delete
Steps:
1. `A` sends `M1`.
2. `A` deletes `M1`.
3. reconnect BLE or replay backlog so old `M1` may be resent.

Expected:
- plaintext does not come back
- tombstone blocks resurrection

### D12. Delete Survives App Restart
Steps:
1. delete `M1`
2. restart app on one or more devices
3. reopen chat

Expected:
- row still shows `Deleted`
- no reversion to plaintext

## Combined Reply + Delete Cases

### RD1. Reply Then Delete Parent
Steps:
1. `A` sends `M1`
2. `B` replies `R1` to `M1`
3. `A` deletes `M1`

Expected:
- `R1` remains
- parent preview in `R1` changes to `Deleted`
- tap preview scrolls to the deleted parent row if present locally

### RD2. Delete Parent Before Stale Reply Is Sent
Steps:
1. `A` sends `M1`
2. `A` deletes `M1`
3. stale device `C` still sees plaintext and attempts reply `R1`

Expected:
- no old parent plaintext is leaked on receivers
- if reply is allowed by current policy, preview must show `Deleted`

### RD3. Delete the Reply Message Itself
Steps:
1. `A` sends `M1`
2. `B` replies `R1`
3. `B` deletes `R1`

Expected:
- `R1` becomes `Deleted`
- `M1` is unaffected

## Export / Safety Checks

### E1. Source DB Safety After Delete
Run SQL checks from the `SQL Checks` section on the source device.

Expected:
- `chat_messages` contains `Deleted`
- original plaintext `events` row is gone
- tombstone exists

### E2. Receiver DB Safety After Delete Sync
Run the same SQL checks on the receiver device.

Expected:
- receiver also does not expose old plaintext through local storage state

### E3. Reply Preview Safety After Parent Delete
Steps:
1. create `M1`
2. create reply `R1`
3. delete `M1`

Expected:
- `R1` preview renders `Deleted`
- no cached plaintext snippet is shown

## Minimum Recommended Regression Set
If time is limited, run at least:
1. `R1` Reply over BLE direct
2. `R2` Reply over cloud direct
3. `R4` Reply over BLE -> cloud bridge
4. `R5` Reply over cloud -> BLE bridge
5. `R6` Reply when parent arrives late
6. `D1` Delete over BLE direct
7. `D2` Delete over cloud direct
8. `D4` Delete over BLE -> cloud bridge
9. `D11` Replay old original after delete
10. `RD1` Reply then delete parent

## Pass Criteria
- Reply keeps correct parent linkage across every tested path.
- Delete only works for own messages.
- After delete, source and receivers show only `Deleted`.
- Old plaintext is not shown in UI after delete.
- Old events replayed later do not resurrect messages.
- Reply preview to deleted parent never leaks the original text.
- BLE `<->` cloud bridge does not break reply or delete semantics.

## Test Run Log
- Tester:
- Date:
- Build:
- Devices:
- Group ID:
- Notes:

### Results
- [ ] `R1`
- [ ] `R2`
- [ ] `R3`
- [ ] `R4`
- [ ] `R5`
- [ ] `R6`
- [ ] `R7`
- [ ] `R8`
- [ ] `D1`
- [ ] `D2`
- [ ] `D3`
- [ ] `D4`
- [ ] `D5`
- [ ] `D6`
- [ ] `D7`
- [ ] `D8`
- [ ] `D9`
- [ ] `D10`
- [ ] `D11`
- [ ] `D12`
- [ ] `RD1`
- [ ] `RD2`
- [ ] `RD3`
- [ ] `E1`
- [ ] `E2`
- [ ] `E3`
