# M3E9 Manual Test Script — Immediate Refresh After Map Mutation

Device/build evidence: record device model, app flavor, build type, and time
with each screenshot. Test on both `OfficialDebug` and `EmergencyDebug`.

## Preparation

1. Sign in as the owner of at least two visible items: item A (marker) and item
   B (marker or area).
2. Keep both item A and item B visible in the map/list before each mutation.
3. Do not open **Details**, pull-to-refresh, change the map filter, or leave and
   reopen the map screen between confirming an action and checking its result.

## Test R1 — Save/edit refreshes marker and area immediately

1. Open owner **Edit marker** for item A. Change its note or label and select
   **SAVE**.
2. Immediately inspect the selected card/list row and its map cue.
3. Repeat steps 1–2 for an owner **Edit area**.

Pass criteria:

* [ ] The changed marker data appears immediately in its map/list/card
  presentation without requiring **Details** or a manual refresh.
* [ ] The changed area data appears immediately in its map/list/card
  presentation without requiring **Details** or a manual refresh.
* [ ] The unrelated item B remains visible and unchanged after each save.

## Test R2 — Cancel Delete preserves the item

1. On item A, choose **DELETE** and choose **CANCEL** in the confirmation box.
2. Inspect the map and list/card immediately.

Pass criteria:

* [ ] Item A remains visible with the same kind, label and note; item B remains
  unchanged.

## Test R3 — Confirm Delete refreshes map and list immediately

1. On item A, choose **DELETE** and confirm with **DELETE**.
2. Without opening **Details** or doing any refresh/navigation action, inspect
   the map and list/card.
3. Repeat with an area when available.

Pass criteria:

* [x] The deleted marker disappears immediately from the map overlay and
  list/card/selection.
* [ ] The deleted area disappears immediately from the map overlay and
  list/card/selection.
* [ ] Item B remains visible and unchanged; only the confirmed target is
  removed.

Evidence reviewed (2026-07-30, Galaxy A10s `OfficialDebug`): after confirming
**DELETE** for a marker, the user observed that it disappeared immediately from
both the map and marker list, without opening **Details**.

## Evidence to provide

For each applicable scenario, provide a before/after pair that includes both
the map and the list/card. For R3, the after screenshot must be captured before
opening **Details**. Include an additional visible item B in the after image.

## Test R4 — Owner mutation availability follows canonical constraints

1. Inspect an active owner item whose owner-edit window is still open.
2. Inspect an owner Situation item after it is marked **No longer active**.
3. If available, inspect an expired owner item or one whose owner-edit window
   has closed.
4. Inspect another user's item.

Pass criteria:

* [ ] An eligible active owner marker/area still exposes **Edit** and **Delete**.
* [ ] A **No longer active** Situation marker/area does not expose **Edit** or
  **Delete**; **Details** remains available.
* [ ] An expired or owner-edit-window-closed item does not expose **Edit** or
  **Delete**.
* [ ] A non-owner item does not expose **Edit** or **Delete**.

The UI derives this availability from canonical domain state. Missing or
unrecognized canonical state and a locally removed group member are handled
conservatively by hiding **Edit** and **Delete**.
