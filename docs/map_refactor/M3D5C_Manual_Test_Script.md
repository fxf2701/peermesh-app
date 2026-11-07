# M3D5C Manual Test Script

Target: Galaxy A10s, `OfficialDebug` first. Repeat the smoke cases on
`EmergencyDebug` after they pass.

## Test C1 — Area geometry presentation

1. Long-press the map and draw a Situation area with at least three points.
2. Press **Done** to open Area Details.
3. Save it, reopen **Edit area**, then open the item detail screen.

Pass criteria:

* [x] Creation and owner-edit forms show a short drawn-area summary with vertex
  count; raw GeoJSON is not visible.
* [x] The area remains in the same place and shape after Save.
* [x] Cancel closes the metadata form, retains the active drawn-area draft and
  returns to the map drawing state with **Done** and **Cancel** available; no
  incomplete area is persisted.

Evidence: creation form, owner-edit form, and map after Save/Cancel.

Evidence reviewed (2026-07-29, Galaxy A10s `OfficialDebug`):
`area-creation.jpg` and `area-update.jpg` show `Drawn area · 3 vertices`
without GeoJSON. `area-on-map.jpg` shows the saved polygon. The accepted UX
decision confirms that creation-form Cancel retains the active drawing draft.
Product verification additionally confirms the saved polygon retains the same
point count and point positions as the creation draft.

## Test C2 — Coordination marker owner edit

1. Create a Coordination marker with kind `📍 Waypoint`, label `W1`, and a note.
2. Open **Edit marker** as the owner.
3. Change only the note, save, and reopen the form.
4. Open the form again, change a field, press Cancel, and reopen it.

Pass criteria:

* [x] The form explicitly shows `Category: Coordination`.
* [x] `📍 Waypoint` remains selected; it never falls back to `🚧 Blocked path`.
* [x] Save changes only the intended field.
* [x] Cancel preserves category, kind, label, and the last saved note.

Evidence: edit form before Save, edit form after Save, and edit form after
Cancel/reopen.

Evidence reviewed (2026-07-30, Galaxy A10s `OfficialDebug`):
`edit-coodination-before-save.jpg` shows Coordination + `📍 Waypoint` before
creation Save. `coodination-after-save.jpg` and
`edit-coodination-after-save.jpg` show the persisted `W1` / `Test C2` item and
the same explicit `Category: Coordination` + `📍 Waypoint` in owner edit.
`edit-coodination-note.jpg` and `coodination-on-map-after-edit-note.jpg` show
the saved note `Test C2, edit note` while coordinates, category, kind and label
remain unchanged.
`edit-coodination-cancel.jpg` changes the note without saving; after Cancel,
`coodination-after-cancel-edit.jpg` still shows the last saved note
`Test C2, edit note` with the same `W1` / `📍 Waypoint` item.

## Test C3 — Situation marker/area owner edit

1. Open owner edit for one Situation marker and one Situation area.
2. Save a label or note change on each, then reopen both forms.

Pass criteria:

* [x] Situation marker owner edit explicitly shows `Category: Situation` and
  retains its emoji kind.
* [x] Situation area owner edit explicitly shows `Category: Situation` and
  retains its emoji kind.
* [x] The reviewed Situation area owner-edit form has no `Observed`, bare
  `Reported`, `Confidence`, `Vote`, `Voting`, `Contributions`, `Request
  removal`, or `Note (observed)` control.
* [x] Repeat the same vocabulary/control check for Situation marker owner edit.

Evidence: marker edit and area edit forms.

Evidence reviewed (2026-07-30, Galaxy A10s `OfficialDebug`):
`photo_2026-07-30_00-20-07 (2).jpg` shows the owner-edit form with
`Category: Situation` and `🔥 Hot zone`. It contains `Note (optional)` and no
legacy Observed/Reported, Confidence, Vote/Voting, Contributions, Request
removal, or `Note (observed)` control. The companion map screenshot confirms
the persisted marker's same `🔥 Hot zone` kind cue.

## Test C4 — Canonical owner actions and non-owner isolation

1. As the owner, inspect a marker row and an area row.
2. Tap Delete but cancel the confirmation; verify the item remains.
3. On another device/user, inspect the same rows and open Details.

Pass criteria:

* [x] An eligible owner Area row exposes separate **Edit area** and **Delete**
  actions.
* [x] An eligible owner Marker row exposes separate **Edit marker** and
  **Delete** actions.
* [x] Canceling Delete keeps the selected marker.
* [x] Confirming Delete removes the selected marker from the map and list
  immediately, without opening **Details**.
* [x] Confirming Delete removes only its target; an unrelated visible marker or
  area remains unchanged.
* [x] The reviewed Area row has no legacy eye/megaphone vote badges or Request
  Removal link.
* [x] The reviewed Marker row has no legacy eye/megaphone vote badges or Request
  Removal link.
* [x] Non-owner rows do not expose Edit, Delete, Contribute, or Contributions;
  Details remains available for dedicated Situation/Coordination actions.

Evidence: owner row, Delete confirmation, and non-owner row/detail.

Delete evidence reviewed (2026-07-30, Galaxy A10s `OfficialDebug`):
`photo_2026-07-30_00-27-02.jpg` shows the explicit **Delete marker**
confirmation with separate **CANCEL** and **DELETE** actions. After choosing
Cancel, `photo_2026-07-30_00-27-02 (2).jpg` shows the same 🔥 marker and its
owner card/actions still present. Product manual verification subsequently
confirmed that accepting **DELETE** removes that marker from both the map and
marker list immediately, without opening **Details**. The unrelated-item and
Area-delete variants remain open. `photo_2026-07-30_12-29-21.jpg` confirms the
resolved `B1` marker and other visible map content remain present after the
separate target marker was deleted; product verification confirms no unrelated
item changed.

Evidence reviewed (2026-07-29, Galaxy A10s `OfficialDebug`):
`area-on-map.jpg` shows the separate Area owner actions and no legacy vote or
removal link. `edit-coodination-after-save.jpg` shows the Marker owner's
separate **Edit marker** and **Delete** actions; `coodination-after-save.jpg`
shows no legacy vote or removal link.

Non-owner evidence reviewed (2026-07-30): product manual verification confirms
that, for both a Marker and an Area created by user A, user B sees **Details**
only. **Edit**, **Delete**, **Contribute**, and **Contributions** are absent.
