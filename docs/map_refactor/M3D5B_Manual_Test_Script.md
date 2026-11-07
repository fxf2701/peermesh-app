# M3D5B Manual Test Script — Semantic Marker, Verification and Lifecycle Cues

## Environment

Record device, Android version, app flavor/build, group and map profile. The
primary acceptance device is Galaxy A10s on `OfficialDebug`; repeat the core
smoke path on `EmergencyDebug` when available.

The map must already contain:

- one active Situation marker with a recognizable emoji kind;
- one active Coordination marker (`🏕 Campsite` or `📍 Waypoint` is ideal);
- one Situation marker with a current confirmation;
- one no-longer-active Situation marker that had a prior confirmation.

Use distinct short labels so each marker can be identified in the screenshots.

---

## Test M1 — Kind and category cues on the map

1. Open a viewport containing both the active Situation and Coordination
   markers.
2. Confirm each marker displays its kind emoji/icon inside the marker itself;
   it must not render as an undifferentiated dot.
3. Confirm each marker's emoji/icon identifies its kind; no category-specific
   marker-frame shape is required for acceptance.
4. Select each marker in turn.
5. Confirm selection emphasis and any label do not hide the emoji/icon or
   lifecycle cues.

Required screenshots:

- M1: unselected Situation and Coordination markers visible together.
- M2: selected Situation marker.
- M3: selected Coordination marker.

Pass criteria:

- [x] every displayed marker retains a visible kind cue;
- [x] no marker regresses to a plain dot without an emoji/icon kind cue.

Evidence reviewed 2026-07-29: `coodination-on-map.jpg` and
`ack-coodination-on-map.jpg` show the selected `📍 Waypoint` cue with label
`W1` to its right, so the adjusted label no longer obscures the Coordination
kind. After acknowledgement, the additional blue participation badge is shown
without replacing the pin. This is only the selected-Coordination half of M1;
the full Situation-and-Coordination comparison remains required before ticking
the three viewport-wide criteria.

Additional evidence reviewed 2026-07-29:
`photo_2026-07-29_20-36-47.jpg` shows the same viewport with multiple persisted
markers: `🚧` markers (including resolved `×`), `📍 Waypoint` with its
acknowledgement badge, and `🚨` with its confirmation badge. Their labels and
badges remain separate from the emoji cues; no displayed marker is a plain dot.
This satisfies the two current M1 pass criteria after the category-frame-shape
criterion was intentionally removed.

---

## Test M2 — Area kind cue on the map

1. Create or open a persisted Area with a recognizable emoji kind, for example
   `🚧 Blocked path`.
2. Return to the map and keep the complete polygon visible.
3. Tap the centered emoji circle, then tap another point inside the polygon.

Pass criteria:

- [ ] A kind emoji is visible at the Area centroid.
- [ ] The emoji is enclosed by a dashed circular border slightly larger than
  the icon, making it visually distinct from a marker icon.
- [ ] Tapping either the centroid cue or polygon opens/selects the exact Area;
  it does not create a second map item.

Required screenshots: unselected area, selected area, and its detail screen.

---

## Test V1 — Current-confirmation cue

1. Open the current-confirmed Situation marker on the map.
2. Confirm it has the existing green confirmation outline.
3. Confirm it also has a visible `✓` badge/glyph on the marker.
4. Tap the marker and confirm the `✓` remains visible while selected.
5. Open detail and confirm it still reports current confirmation separately from
   the creator source.

Required screenshots:

- V1: unselected confirmed marker showing green outline plus `✓`.
- V2: the same marker selected, preserving the `✓`.
- V3: detail with current confirmation information.

Pass criteria:

- [x] confirmation can be understood without distinguishing green from other
  colors;
- [x] selection does not erase the kind or confirmation cue.

Evidence reviewed 2026-07-29: `active-situation-on-map.jpg` shows the central
kind emoji and visible `✓` badge while the marker is selected;
`active-situation-detail.jpg` separately confirms the current-confirmation
state. The M1 marker/category smoke path remains open because the prior
selected Coordination evidence exposed its label overlap, and the adjusted
label spacing still needs a fresh device screenshot.

---

## Test R1 — No-longer-active precedence

1. Open the no-longer-active Situation marker that has prior confirmation
   history.
2. Confirm it shows an `×` lifecycle badge/glyph, not a current `✓` badge.
3. Tap it and confirm `×` and kind emoji remain visible while
   selected.
4. Open detail and confirm lifecycle is no longer active, current confirmation
   is absent, and the earlier confirmation is historical only.

Required screenshots:

- R1: unselected resolved marker with `×`.
- R2: selected resolved marker preserving `×` and kind cue.
- R3: resolved detail and historical confirmation presentation.

Pass criteria:

- [x] resolved state is stronger than old confirmation styling;
- [x] `×` makes the resolved state recognizable without color alone.

Evidence reviewed 2026-07-29: `no-longer-marker-on-map.jpg` shows the resolved
`×` badge with the marker's kind cue and a label that no longer overlaps the
icon; `no-longer-marker-detail.jpg` reports “No longer active”, zero current
confirmations, and treats the prior confirmation as historical. The UX decision
on 2026-07-29 makes `×`, rather than a dashed outline, the required non-color
resolved cue. The visible `ADD SITUATION UPDATE` action is the separate
unresolved M3E7 defect.

---

## Test A1 — Accessibility smoke (out of scope)

TalkBack/accessibility smoke is not required for the current map UX acceptance
cycle. Existing content descriptions remain implementation support only and do
not require manual evidence or a checkbox in this script.

---

## Reporting

For every screenshot, send its ID, the gesture immediately before capture,
visible marker labels and whether it passed. Report separately if an emoji is
missing, a marker is a plain dot, a `✓`/`×` is hidden, or selected styling erases
another cue. Do not tick UX criteria until the screenshots are reviewed.
