# Problem: Private Group Edit Message MVP Tracking

Date:
- 2026-07-01

Status:
- In progress
- MVP implementation landed in code
- Needs device-level verification and follow-up hardening

Summary:
- `Private group` chat previously had no user-facing `edit message` feature even though the event model already defined `CHAT.EDIT`.
- The first MVP is now implemented for `edit own message`.
- This doc tracks what has already been done, what still needs follow-up, and what result is expected from the feature.

Why this matters:
- Users need a way to correct typos or amend their own message without deleting and resending.
- The chat model already supports `CHAT.EDIT`, so the UX gap created inconsistency between product capability and actual user access.
- Edit behavior must remain deterministic under delayed sync, replay, tombstones, and multi-device transport timing.

What has been done:
- Added user-facing `Edit` action for the sender's own active message in private group chat.
- Added compose-state support for edit mode:
  - tapping `Edit` prefills the input with the current message text
  - the composer shows an `Editing message` banner
  - closing the composer exits edit mode
- Added local send flow for `CHAT.EDIT` in group chat activity.
- Added `(edited)` badge rendering in chat message rows.
- Added persistent edit metadata to chat message state via `edited_at`.
- Added DB migration from Room version `43` to `44`.
- Added Room schema snapshot for DB version `44`.
- Hardened reducer behavior:
  - only the original message author can edit that message
  - deleted messages reject edits
  - pending `CHAT.EDIT` events are replayed when the base `CHAT.MSG` arrives later
- Hardened event validation:
  - `CHAT.EDIT` must include a valid target
  - `CHAT.EDIT` cannot target itself
- Added or updated unit coverage for:
  - validator rules for `CHAT.EDIT`
  - reducer rejection of cross-author edit
  - reducer replay of delayed edit after base message
  - ingest test support after reducer/membership dependencies changed
  - full `core + data` unit suites
- Added targeted diagnostics for edit propagation:
  - relay ingest visibility
  - reducer skip/apply visibility for `CHAT.EDIT`
  - per-`eventId` transport outcome logging

Files touched in the MVP:
- `android/app/src/main/java/app/peermesh/GroupChatActivity.kt`
- `android/app/src/main/java/app/peermesh/ChatMessageAdapter.kt`
- `android/app/src/main/res/layout/item_chat_message.xml`
- `android/app/src/main/res/values/strings.xml`
- `android/data/src/main/java/app/peermesh/data/reducer/ChatReducer.kt`
- `android/data/src/main/java/app/peermesh/data/db/entity/ChatMessageEntity.kt`
- `android/data/src/main/java/app/peermesh/data/db/AppDatabase.kt`
- `android/data/src/main/java/app/peermesh/data/db/DatabaseMigrations.kt`
- `android/data/schemas/app.peermesh.data.db.AppDatabase/44.json`

Verification already done:
- `./gradlew :core:testDebugUnitTest :data:testDebugUnitTest`
- Result: passing after aligning the remaining transport test with current app-batch envelope behavior.

Known scope limits of the current MVP:
- Only supports editing your own message.
- No explicit edit history UI.
- No server-side or relay-specific admin workflow around message edits.
- No dedicated instrumentation or real-device QA evidence yet in this branch.
- `CHAT.EDIT` still assumes the base message can arrive later and be replayed from event storage.
- No provisional/orphan edited-message rendering exists yet when `CHAT.EDIT` arrives but the base `CHAT.MSG` never does.

Decision update: event schema follow-up
- The current MVP schema works, but the follow-up implementation should move toward a more payload-balanced event shape for chat message variants.
- The team agreed on these target structures:

Target `CHAT.MSG` shape:
```json
{
  "ref": {
    "root": null,
    "parent": null,
    "target": null
  },
  "data": {
    "text": "..."
  }
}
```

Target `CHAT.REPLY` shape:
```json
{
  "ref": {
    "root": null,
    "parent": "target-message-id",
    "target": null
  },
  "data": {
    "text": "..."
  }
}
```

Target `CHAT.EDIT` shape:
```json
{
  "ref": {
    "root": null,
    "parent": null,
    "target": "target-message-id"
  },
  "data": {
    "text": "...",
    "tct": 1782900050
  }
}
```

Schema rules agreed for follow-up:
- `CHAT.REPLY` should rely on `ref.parent` for the reply target and should stop duplicating that target in `data.reply_to`.
- `CHAT.EDIT` should rely on `ref.target` for the edited message target and should stop duplicating that target in `data.target_id`.
- `CHAT.EDIT` should carry `data.tct` (`target_created_t`) so a receiver can place an edited message into chat history even if the base `CHAT.MSG` never arrives.
- `tct` should use epoch seconds, matching the rest of the chat event time model.

Why this schema was chosen:
- It removes duplicated target identifiers from `data`.
- It keeps `CHAT.REPLY` and `CHAT.EDIT` closer to `CHAT.MSG` in payload size.
- It enables a future provisional/orphan edited-message path without needing the original message event for ordering.
- It keeps the ref semantics consistent:
  - `ref.parent` means reply target
  - `ref.target` means edit/delete target

Estimated payload overhead compared with `CHAT.MSG`
- Assumptions:
  - message id length is approximately `43` bytes
  - `tct` uses `10` digit epoch-seconds
  - numbers below compare only the relative payload overhead, not the full encrypted envelope size

Current schema:
- `CHAT.MSG`: `+0`
- `CHAT.REPLY`: `+98` bytes
- `CHAT.EDIT`: `+99` bytes

Minimally reduced schema:
- `CHAT.MSG`: `+0`
- `CHAT.REPLY`: `+98` bytes
- `CHAT.EDIT`: `+58` bytes

Recommended balanced schema:
- `CHAT.MSG`: `+0`
- `CHAT.REPLY`: `+41` bytes
- `CHAT.EDIT`: `+58` bytes

Implication for text-cap rules:
- Keeping the same `MAX_CHAT_TEXT_LEN` for `CHAT.MSG`, `CHAT.REPLY`, and `CHAT.EDIT` is still desirable for UX consistency.
- However, equal text length does not mean equal serialized event size.
- The follow-up implementation should consider validating against both:
  - a shared text-length cap
  - a serialized event byte budget
- The byte budget should be sized against the heaviest chat variant, which is expected to remain `CHAT.EDIT`.

What should be done next:
- Run real-device QA on at least one `private group` round with multiple peers.
- Verify these flows end-to-end:
  - edit own freshly sent message
  - edit after message has already propagated to peers
  - edit while one peer is temporarily behind, then later syncs
  - delete-after-edit and ensure tombstone wins
  - attempt cross-author edit and confirm it is ignored
- Confirm UI polish:
  - edit banner clears correctly on send, cancel, and delete
  - reply mode and edit mode never conflict
  - `(edited)` badge stays stable after ACK / seen updates
- Add targeted manual or instrumentation coverage for:
  - edit propagation across devices
  - out-of-order arrival behavior
  - scroll / snapshot refresh behavior after edit
- Design and implement orphan edit handling:
  - accept and render `CHAT.EDIT` even when base `CHAT.MSG` is absent
  - create a provisional message row keyed by the edited target id
  - place that row in history using `data.tct`
  - merge cleanly if the base `CHAT.MSG` arrives later
- Update event factory, validator, reducer, and tests for the new balanced event schema:
  - remove duplicated `reply_to` from `CHAT.REPLY` payload
  - remove duplicated `target_id` from `CHAT.EDIT` payload
  - add `tct` support to `CHAT.EDIT`
- Add payload-budget validation coverage so message, reply, and edit events do not lose content unexpectedly under transport size constraints.
- Decide whether follow-up product scope should include:
  - edit time window restrictions
  - moderation visibility for edited content
  - explicit “edited at” timestamp or richer badge behavior
  - analytics / activity-log additions specific to edit UX

Risks to watch:
- Out-of-order transport could still reveal edge cases not covered by unit tests alone.
- Snapshot refresh and pagination paths should be regression-checked under heavy chat activity.
- The current implementation uses `edited_at` for badge state; future content-history requirements may need richer metadata than a single timestamp.
- Provisional/orphan edited-message support will introduce merge behavior between `CHAT.EDIT` and late-arriving `CHAT.MSG`; that reconciliation must preserve last-writer-wins semantics and stable ordering.
- Changing event payload shape requires careful backward-compatibility handling if devices with old and new schemas can coexist in the same test matrix.

Expected result:
- A user in `private group` chat can edit their own active message from the message action menu.
- The edited content replaces the visible original content in the timeline.
- The message shows a stable `(edited)` indicator.
- Other peers converge to the edited content after sync.
- Invalid edits do not apply:
  - editing someone else's message is ignored
  - editing a deleted message is ignored
  - stale or malformed edit events do not override newer valid state
- Follow-up schema result:
  - `CHAT.MSG`, `CHAT.REPLY`, and `CHAT.EDIT` use a more space-balanced structure
  - receivers can render an edited message even if the original message event never arrives
  - orphan edited-message ordering remains deterministic via `data.tct`
- Database migration remains safe for existing installs upgrading from version `43`.
- `core` and `data` unit suites remain green.

Acceptance goal for the next step:
- After device-level verification, the team should be able to treat `edit own message` in private group chat as an active MVP feature rather than a code-only implementation.
