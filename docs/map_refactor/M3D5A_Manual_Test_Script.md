# M3D5A Manual Test Script — Category, Kind and Source Creation Repair

## Evidence metadata

Record before testing:

- date and time;
- device model and Android version;
- app flavor and build (`EmergencyDebug` or `OfficialDebug`);
- group profile if known (`urban` or `outdoor`);
- whether the app was freshly installed or upgraded.

Take screenshots with the category, kind selector, source choices and Save button
visible together whenever possible.

---

## Test A — Situation marker creation

Target criteria:

- `UX-CREATE-02`;
- `UX-CREATE-03`;
- `UX-CREATE-SIT-01` through `UX-CREATE-SIT-06`;
- `UX-CREATE-SIT-08`;
- `UX-CREATE-SIT-09`.

Steps:

1. Open the production group map.
2. Tap the map to begin marker creation.
3. Place the marker and press **Done**.
4. Confirm the form initially shows category **Situation**.
5. Open the kind selector.
6. Confirm it contains one or more emoji plus human-readable Situation kinds.
7. Confirm it does not contain `Campsite` or `Waypoint`.
8. Confirm the source choices are exactly:
   - **Seen firsthand**;
   - **Reported by others**;
   - **Source uncertain**.
9. Confirm no source is preselected.
10. Confirm the note hint is **Note (optional)** and does not contain
    `observed`.
11. Without selecting a source, enter the other required fields and press
    **Save**.
12. Confirm the dialog stays open and shows
    **Please select an information source**.
13. Select **Source uncertain** and save.
14. Confirm the persisted marker appears after reload.
15. Open its detail and confirm the creator source is uncertain/unknown-source
    wording, not firsthand or community confirmation.

Required screenshots:

- A1: Situation form with the kind dropdown open.
- A2: all three source choices visible with none selected.
- A3: required-source rejection while the form remains open.
- A4: persisted detail showing the selected creator source.

---

## Test B — Coordination marker creation

Target criteria:

- `UX-CREATE-03`;
- `UX-CREATE-COORD-01` through `UX-CREATE-COORD-06`;
- `UX-CREATE-08`.

Steps:

1. Begin a new marker draft and press **Done**.
2. Change category from **Situation** to **Coordination**.
3. Open the kind selector.
4. Confirm it is not blank and contains the accepted Coordination marker kinds:
   - `🏕 Campsite`;
   - `📍 Waypoint`.
5. Confirm no Situation kind such as `Blocked path`, `Hot zone`, or
   `Potential risk` appears.
6. Confirm the source choices disappear completely.
7. Confirm there is no Confidence or lifecycle input.
8. Confirm the note hint remains **Note (optional)**.
9. Select a Coordination kind, enter a short label/note and save.
10. Confirm the persisted marker appears after reload.
11. Open detail and confirm it is presented as **Coordination**, without creator
    source or Situation lifecycle.
12. Begin another marker draft, select a Situation source, switch to
    Coordination, then switch back to Situation.
13. Confirm the earlier source is not silently retained or preselected.

Required screenshots:

- B1: Coordination marker kind dropdown showing emoji kinds.
- B2: Coordination form with no source/confidence/lifecycle inputs.
- B3: persisted Coordination detail.
- B4: Situation form after switching back, with no source preselected.

---

## Test C — Area category and unsupported Coordination state

Target criteria:

- `UX-CREATE-03`;
- `UX-CREATE-06`;
- `UX-CREATE-07`;
- `UX-CREATE-08`;
- `UX-CREATE-COORD-02`;
- `UX-CREATE-COORD-03` through `UX-CREATE-COORD-06`.

Current catalog contract:

- Situation has supported area kinds.
- Coordination currently has no supported area kind.
- M3D5A must not invent a Coordination-area semantic kind.

Steps:

1. Long-press the map and draft a valid area with at least three points.
2. Press **Done**.
3. Confirm category **Situation** shows compatible emoji area kinds and the
   three source choices.
4. Change category to **Coordination**.
5. Confirm the kind field visibly says:
   **No compatible kinds are available for this category and shape.**
6. Confirm the kind selector is disabled.
7. Confirm **Save** is disabled.
8. Confirm source/confidence/lifecycle inputs are absent.
9. Switch back to **Situation**.
10. Confirm compatible area kinds return, Save becomes available, and no source
    is preselected.
11. Press **Cancel**.
12. Confirm no incomplete area is persisted.

Required screenshots:

- C1: Situation area form with compatible kind dropdown and source choices.
- C2: Coordination area explicit unavailable state with disabled Save.
- C3: Situation restored after switching back, with no source selected.
- C4: map after cancellation, showing no new persisted area.

---

## Test D — Owner-edit regression

This is a regression check only. Legacy edit vocabulary is assigned to M3D5C and
is not expected to be fixed by M3D5A.

Steps:

1. Open owner edit for an existing marker.
2. Confirm its existing kind, label, note and source/vote state remain readable.
3. Cancel without saving.
4. Open owner edit for an existing area.
5. Confirm its existing kind, label, note and source/vote state remain readable.
6. Cancel without saving.
7. Confirm neither cancel action changes persisted content.

Required screenshots:

- D1: existing marker edit form.
- D2: existing area edit form.

---

## Pass and reporting rule

For each screenshot, report:

- test ID (`A1`, `B2`, and so on);
- exact gesture immediately before the screenshot;
- visible result;
- whether the result passed or failed;
- any wording, empty selector, stale value or enabled Save button that differs
  from this script.

Do not mark `UX-CREATE-COORD-02` fully passed for areas while the centralized
catalog has no Coordination-area kind. The expected M3D5A behavior for that
combination is an explicit unavailable state that cannot submit.
