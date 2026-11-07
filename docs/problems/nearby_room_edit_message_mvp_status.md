# Problem: Nearby Room Edit Message MVP

Date:
- 2026-07-02

Status:
- Implemented in current branch
- Unit tests passing
- Needs device-level propagation QA

Summary:
- `Private Group` already had edit-message MVP via event log + reducer + `CHAT.EDIT`.
- `Nearby Room` chat had no edit UX and no edit metadata in storage.
- Nearby Room transport is snapshot/envelope-based, not event-sourced, so the Private Group implementation could not be copied directly.

What was audited first:
- Nearby Room message persistence lives in `nearby_room_messages` and is updated directly from `NearbyRoomRepository`.
- Nearby Room transport uses `NearbyRoomTransportEnvelope` over BLE with encrypted message body and relay recovery via `MESSAGE_OFFER/FETCH/DATA`.
- Private Group chat uses `events`, `chat_messages`, `ChatReducer`, validator rules, tombstones, and LWW event clocks.

Key differences vs Private Group:
- Data model:
  - Private Group row stores reducer/event metadata such as `last_event_*`, `status`, `reply_to`, `delivery_status`, `edited_at`.
  - Nearby Room row stores message snapshot fields such as `owner_device_id`, `sender_device_id`, `body`, `created_at_ms`, `received_at_ms`, `ttl_expires_at_ms`, `delivery_state`.
- Reducer:
  - Private Group has a true reducer with replay, tombstones, and out-of-order event handling.
  - Nearby Room had no reducer; it simply replaced rows by primary key on receive.
- Transport:
  - Private Group propagates edits as `CHAT.EDIT`.
  - Nearby Room propagates encrypted message snapshots over custom BLE envelopes and later relay fetches can re-send the current full message body.

Reuse chosen from Private Group:
- Edit-own-message UX pattern.
- `(edited)` badge backed by explicit persisted metadata.
- Logging pattern for `applied` / `skipped` / propagation visibility.
- Hardening mindset: reject cross-author edits, reject stale updates, avoid silent downgrade.

What had to be Nearby-Room-specific:
- No `CHAT.EDIT` event type was introduced.
- Edit propagation reuses the existing message snapshot channel by re-broadcasting the same `message_id` with new `body` plus `edited_at_ms`.
- A small snapshot merge reducer was added for Nearby Room to protect against stale edit, actor mismatch, and unedited downgrade.
- Relay fetch path was extended implicitly because `MESSAGE_DATA` now carries `edited_at_ms` too, so a late fetch can converge to edited content.

MVP scope implemented:
- Edit own message only.
- `(edited)` badge in Nearby Room rows.
- Reducer hardening for Nearby Room snapshot merge:
  - reject actor mismatch
  - reject stale edit
  - reject conflicting base snapshot without edit timestamp
  - reject downgrade from edited content back to unedited content
- Room migration for `nearby_room_messages.edited_at_ms`.
- Unit tests first for merge/reducer logic and transport codec round-trip.
- Added debug logs for send / receive / apply / skip.

Implementation notes:
- New persisted field: `NearbyRoomMessageEntity.edited_at_ms`.
- New DB version: `44 -> 45`.
- New merge logic: `NearbyRoomMessageEditReducer`.
- Nearby Room local edit updates the same row and reuses existing message send flow.
- Transport envelope now optionally carries `edited_at_ms`.
- Existing BLE relay recovery remains compatible because message identity stays the same.

Known limits of this MVP:
- No orphan-edit path exists if an edited snapshot arrives before the base message has ever been seen.
- No edit history UI.
- No delete/edit conflict system comparable to Private Group tombstones because Nearby Room still has no delete event model.
- No separate delivery state for “edit pending”; MVP reuses existing message delivery semantics.

Verification:
- `./gradlew :app:testOfficialDebugUnitTest :data:kaptReleaseKotlin`
- Result: passing

Expected user-visible result:
- A user in Nearby Room can tap their own message and edit it.
- The existing message updates in place instead of creating a new row.
- The row shows `(edited)`.
- Peers that receive the newer snapshot converge to the edited content.
