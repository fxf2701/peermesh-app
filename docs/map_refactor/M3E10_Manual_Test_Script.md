# M3E10 Manual Test — Coordination Areas

Device/build: Galaxy A10s, `OfficialDebug` (repeat on `EmergencyDebug` when
available).

## CA1 — Create a Coordination-area

1. Open a group with the **Outdoor** profile.
2. Long-press the map, add at least three vertices, then tap **Done**.
3. In **Area Details**, choose **Coordination**.
4. Open the kind selector. Choose **Trail sector**, **Event zone**, or
   **Rescue staging**; enter an optional label/note and tap **Save**.

Pass criteria:

- [x] The Coordination kind selector is enabled; it never shows
  `No compatible kinds are available`.
- [x] Outdoor shows **Assembly zone**, **Trail sector**, **Event zone**, and
  **Rescue staging**, each with its matching emoji.
- [ ] Save succeeds and the polygon remains at the same shape and position.
- [ ] The Area centroid displays the selected kind emoji inside its Area cue;
  any label remains below the cue.

## CA2 — Coordination workflow and details

1. Select the saved Coordination-area and tap **Details**.
2. Confirm the header uses the selected emoji and readable kind name.
3. Confirm the page says **Coordination** and offers
   **Acknowledge Coordination** and **Add Coordination Note**.
4. Acknowledge it, refresh/return to the map, then add a Coordination note.

Pass criteria:

- [ ] Details shows the selected kind as `emoji + name`, not a raw key.
- [ ] Coordination actions work for the Area and acknowledgement count/activity
  update after refresh.
- [ ] Situation-only controls (**Confirm Situation**, **Mark Situation No
  Longer Active**) are absent.

## CA3 — Urban profile options

1. Switch to, or use, an **Urban** group and start drawing an Area.
2. Select **Coordination** and open the kind selector.

Pass criteria:

- [x] The selector includes **Assembly zone** and **Event zone** with emojis.
- [ ] Selecting either kind enables a valid Save flow.

## CA4 — Profile-specific Coordination Marker vocabulary

1. In an **Urban** group, tap to create a Marker and select
   **Coordination**.
2. Open the kind selector.
3. Repeat in an **Outdoor** group.

Pass criteria:

- [x] Urban offers exactly **📌 Meeting point** and **📍 Waypoint**; it does
  not offer **🏕 Campsite**.
- [x] Outdoor offers exactly **🏕 Campsite** and **📍 Waypoint**; it does not
  offer **📌 Meeting point**.
- [ ] Each shown kind saves successfully as a Coordination Marker and exposes
  the Coordination detail actions.
