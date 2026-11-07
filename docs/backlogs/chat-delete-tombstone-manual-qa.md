# Chat Delete Tombstone Manual QA

## Purpose
- Verify source-device delete flow.
- Verify receiver redaction after BLE/cloud sync.
- Verify local export safety for `chat_messages` and `events`.
- Capture enough logs to debug `CHAT.DEL` failures without a second repro pass.

## Preconditions
- Build/install the same app version on source and receiver devices.
- Confirm DB schema is the delete-tombstone build:
  - `AppDatabase` version `37` or newer.
  - `deleted_events` table exists in local DB.
- Enable activity log capture on all devices before repro.

## Activity Log Preset
Use these labels in Activity Log capture:

```text
Received message at
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

## Scenario 1: Source Delete Own Message
1. On source device A, send a new chat message.
2. Wait until the message is visible in the list.
3. Long-press the message.
4. Confirm `Delete` is shown.
5. Tap `Delete`, then confirm the dialog.

Expected:
- Source message body changes to `Deleted`.
- Source message style is muted gray + italic.
- Reply preview to this message also shows `Deleted` if applicable.
- Activity log contains:
  - `DB event insert`
  - `Sent delete at`
  - `Chat reducer upsert` with `kind=delete`

## Scenario 2: Cannot Delete Other User Message
1. On receiver device B, send a message.
2. On source device A, long-press B's message.

Expected:
- No `Delete` action is shown for B's message.

## Scenario 3: Receiver Applies Delete
1. With A and B connected over BLE or cloud, let A delete its own message.
2. Wait for sync to B.

Expected on B:
- The same message row is still present.
- The message body is `Deleted`.
- Old plaintext does not reappear in chat UI.
- Activity log contains `Received message at` for the delete event if captured.

## Scenario 4: Old Event Does Not Resurrect
1. Delete a message on A.
2. Reconnect BLE or force delayed sync so old queued data may re-arrive.
3. Observe A and B after replay/backlog sync.

Expected:
- Deleted message stays deleted.
- No old plaintext is restored.
- If replay happens, logs may show `Deleted tombstone reject`.

## Scenario 5: Export Safety
Run on the source-device DB dump after delete:

```sql
select message_id, text, status, last_event_id
from chat_messages
where message_id = '<target_message_id>';
```

Expected:
- `text = 'Deleted'`
- `status = 'deleted'`

Run:

```sql
select event_id, type, source
from events
where event_id = '<target_message_id>';
```

Expected:
- No row for the original plaintext chat event.

Run:

```sql
select gid, target_event_id, delete_event_id, expires_at
from deleted_events
where target_event_id = '<target_message_id>';
```

Expected:
- One tombstone row exists for the deleted target.

## Failure Triage
- No `Sent delete at` on source:
  - delete flow likely did not create/enqueue `CHAT.DEL`
- `Sent delete at` exists on source but B never updates:
  - inspect `APP batch transport queued`, `BLE batch send complete`, `BLE batch send failed`, and relay health
- B receives old plaintext after delete:
  - inspect `deleted_events` presence and `Deleted tombstone reject`
- DB has no `deleted_events` table:
  - wrong build installed; verify schema version first
