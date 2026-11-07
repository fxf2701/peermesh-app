# Map Markers/Areas Test Checklist

## Task 1: Area constraints (min area 10,000 m²; min angle 45°)
- [x] Create area < 10,000 m² → blocked with message about minimum area.
- [x] Create area with an internal angle < 45° → blocked with message about sharp corners.
- [x] Create area >= 10,000 m² with all angles >= 45° → saved successfully.
- [x] Area kind list is restricted (no "safe" and reduced vs marker kinds).

## Task 2: First marker rule (creator is Reported only)
- [x] New marker: Observed option hidden; Reported forced.
- [x] New area: Observed option hidden; Reported forced.
- [x] Creator edits own marker/area: Observed option hidden.
- [x] Non‑creator can see Observed on existing marker/area.

## Task 3: Anti‑herding (single switch per user)
- [x] Non‑creator switches Report → Observe once → allowed.
- [x] Same user tries to switch again (Observe → Report) → blocked (no toast).
- [x] No switch attempted (same confidence) → no block.
- [x] Actor switch: Actor A uses switch once; Actor B can still switch once independently.
    - [x] Per‑user single state: switching replaces count (no double‑count in list).
  Steps:
    - [x] (1) Actor A votes Report, then switches to Observe (allowed).
    - [x] (2) Switch to Actor B, open same item, switch Report → Observe (allowed).
    - [x] (3) Switch back to Actor A, attempt Observe → Report again (blocked).

## Task 4: Non‑owner edits disabled (core fields)
- [x] Non‑owner sees read‑only summary for marker (no lat/lon/radius/TTL/label inputs).
- [x] Non‑owner sees read‑only summary for area (no geometry/TTL/label inputs).
- [x] Non‑owner cannot change icon/type/label/TTL/geometry; Save only affects vote/note.

## Task 5: Per‑user notes (one per actor)
- [x] Non‑owner adds a note + vote → saved.
- [x] Same actor edits their note → updated.
- [x] Same actor deletes note → note removed.
- [x] Owner cannot edit/delete other user’s note.

## Task 6: Delete note removes vote
- [x] Dropped (no longer required): deleting note does NOT remove vote.

## Task 7: Edit window cap (lock core edits)
- [x] Owner can edit core fields before lock_at.
- [x] After lock_at, core fields are locked; only the actor’s note can be edited (vote locked).
- [x] After lock_at, actors without an existing vote/note cannot add a new vote/note.

## Task 8: Actor switch UI cache reset
- [x] Map UI: Actor A hides a marker + selects a trail; switch to Actor B → hidden set clears and no trail selected.
- [x] Trails UI: Actor A sees trail list; switch to Actor B → list reloads for B (no stale items).
