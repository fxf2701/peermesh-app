# PeerMesh Map — Target UX Checklist

## 1. Purpose

This checklist is the user-visible acceptance contract for the Map refactor after
M2 and M3.

It exists to prevent a phase from being considered complete merely because its
domain models, controllers, coordinators, repositories, or tests exist while the
runtime experience still exposes the old marker/area CRUD flow.

Codex must use this checklist during implementation, review, runtime verification,
and roadmap closure.

This checklist does not replace the M2 or M3 architecture roadmaps. If a proposed
UX change conflicts with an accepted M2 invariant, the implementation must stop
and request an architecture decision rather than bypassing the invariant in UI.

---

## 2. Product North Star

The Map is a collaborative disaster-intelligence surface, not only a place to
create and edit generic markers or areas.

A user must be able to understand:

1. **What is happening?** — a Situation.
2. **Where can the group coordinate?** — a Coordination item.
3. **How is this information known?** — creator source for a new Situation.
4. **Is the information still current?** — lifecycle, freshness, and community
   confirmation.
5. **What can I do now?** — category-correct collaborative actions.
6. **Was my action saved locally?** — precise offline-first feedback.

The target UX must communicate these meanings without exposing event types,
Room, reducers, Lamport clocks, relay internals, raw GeoJSON, or confidence
weights.

---

## 3. How to Use This Checklist

### Status

- `[ ]` Not implemented or not verified.
- `[~]` Partially implemented; architecture exists but runtime UX or evidence is
  incomplete.
- `[x]` Implemented and verified with the required evidence.
- `[N/A]` Explicitly out of scope for the current phase, with a named future
  owner.

### Evidence required for `[x]`

Every checked item must have all applicable evidence:

- production code path;
- automated test at the narrowest reliable layer;
- runtime evidence on a real Activity/device or emulator for user-visible work;
- both Emergency and Official flavors where flavor behavior may differ;
- roadmap/checklist reference using the criterion ID;
- no protected-invariant regression.

Unit tests alone do not prove that a runtime UX criterion is complete.

### Closure rule

A phase may close only when:

- every in-scope P0 criterion is `[x]`;
- every in-scope P1 criterion is `[x]` or has an accepted, named deferral;
- no criterion is checked only because a skeleton, controller, seam, or test
  double exists;
- a manual smoke path proves the complete user journey;
- the final report lists every remaining `[~]`, `[ ]`, and `[N/A]`.

### Roadmap evidence audit — 2026-07-29

The `[x]` statuses below were reconciled against
`Refactor_Map_Activity-M2.md` and `Refactor_Map_Activity-M3.md`:

- `UX-ARCH-01` through `UX-ARCH-06` and `UX-ARCH-08` through `UX-ARCH-12`
  are backed by the accepted M2 final baseline, M3A cross-phase contract audit,
  M3D3 typed-creation acceptance, and M3D4 regression evidence.
- `UX-MAP-06` through `UX-MAP-08`, `UX-DETAIL-01`, `UX-DETAIL-02`, and the
  M3A real-map reachability gate are backed by the M3D4 exact-navigation
  implementation plus Emergency and Official device instrumentation.
- `UX-MAP-17` is backed by M3D1 batch-read acceptance and its bounded-query
  tests.
- `UX-REG-07` and `UX-REG-10` are backed by both-flavor M3 regression suites,
  the M2 final acceptance audit, and the retained Room schema version 52.
- The four M2 phase-level gates are backed by M2A–M2D final acceptance.

Other user-visible criteria remain unchecked where the M3 roadmap still records
missing runtime, instrumentation, or manual evidence. In particular, M3C4,
M3D3, and M3D4 are not accepted overall, and the M3B manual timeline checks
remain open.

Manual creation evidence received on 2026-07-29 from a Samsung Galaxy A10s
running the `OfficialDebug` flavor:

- `photo_2026-07-29_15-50-04.jpg` shows the real map entry instructions for
  marker placement and area drawing.
- `photo_2026-07-29_15-50-05.jpg` shows an active marker-position draft with
  explicit Cancel and Done controls.
- `photo_2026-07-29_15-50-05 (2).jpg` shows the runtime Situation marker form,
  a Situation-compatible emoji kind (`🚧 Blocked path`), and no preselected
  source.
- `photo_2026-07-29_15-50-05 (3).jpg` shows the runtime Coordination marker
  form without source, confidence, or lifecycle inputs.
- This evidence closes `UX-CREATE-01`, `UX-CREATE-02`, `UX-CREATE-05`,
  `UX-CREATE-SIT-01`, `UX-CREATE-SIT-02`, `UX-CREATE-SIT-05`,
  `UX-CREATE-COORD-01`, `UX-CREATE-COORD-03` through
  `UX-CREATE-COORD-05`, and `UX-REG-01` when combined with the accepted M3D3
  production and automated evidence.
- The same screenshots expose legacy wording (`Observed`, `Reported`,
  `Contribute`, and `Note (observed)`), so they do not close
  `UX-VOCAB-04`, `UX-CREATE-SIT-04`, or `UX-CREATE-SIT-06`.
- The Situation form displays an emoji kind item, but the Coordination form's
  kind selector is empty. Therefore this evidence does not close
  `UX-CREATE-03` or `UX-CREATE-COORD-02`; the Coordination kind-list behavior
  requires a fix or separate evidence showing compatible Coordination kinds.

Manual M3D5A Situation-creation evidence received on 2026-07-29 from the same
Samsung Galaxy A10s running `OfficialDebug`:

- `A1.jpg` shows the Situation kind selector with emoji plus human-readable
  Situation kinds only, including `🚧 Blocked path`, `🔥 Hot zone`, and
  `🚧 Ongoing disruption`.
- `A2.jpg` shows the three unselected source choices: `Seen firsthand`,
  `Reported by others`, and `Source uncertain`; it also shows `Note (optional)`
  and the in-form Save rejection `Please select an information source.`
- `A3.jpg` shows the accepted new Situation returned to the normal map/list
  presentation after saving.
- `A4.jpg` shows the persisted Situation detail after choosing `Source
  uncertain`: creator source is explicitly `Uncertain`, while the separate
  current-confirmation presentation remains distinct.
- Combined with the M3D5A production path and both-flavor automated tests, this
  closes `UX-CREATE-SIT-03`, `UX-CREATE-SIT-04`, `UX-CREATE-SIT-06`,
  `UX-CREATE-SIT-07`, `UX-CREATE-SIT-08`, and `UX-CREATE-SIT-09`.
- This set does not yet close the shared category-compatible criterion
  `UX-CREATE-03`, draft-leak criteria, overlay cleanup, or Coordination/area
  creation criteria; those require the B and C scenarios.

Manual M3D5A Coordination-marker evidence received on 2026-07-29 from the
same Samsung Galaxy A10s running `OfficialDebug`:

- `B1.jpg` shows the Coordination marker kind selector is nonempty and limited
  to `🏕 Campsite` and `📍 Waypoint`.
- `B2.jpg` shows a selected `📍 Waypoint` Coordination draft without information
  source, confidence, or lifecycle inputs.
- `B3.jpg` shows the persisted Coordination item with zero acknowledgements,
  participation-not-verification wording, an empty latest Coordination-note
  preview, and only Coordination actions. It exposes no Situation lifecycle,
  creator source, or confidence.
- `B4.jpg` shows a new draft switched back to Situation with all three source
  choices unselected, so a prior Situation source is not silently retained.
- Combined with M3D5A production and both-flavor automated coverage, this
  closes `UX-CREATE-COORD-02` and `UX-DETAIL-COORD-01` through
  `UX-DETAIL-COORD-05`.
- `B3.jpg` incorrectly labels the Coordination timeline section `Situation
  activity`. This is recorded as an open M3D5C vocabulary defect; it does not
  invalidate the category-specific data/actions verified above.
- This evidence still does not close the shared `UX-CREATE-03`,
  `UX-CREATE-08`, `UX-CREATE-COORD-06`, or any area criterion. Those need the
  area scenario and/or explicit invalid-submit evidence.

Manual M3D5A area-creation evidence received on 2026-07-29 from the same
Samsung Galaxy A10s running `OfficialDebug`:

- `C1.jpg` shows a valid Situation area draft with a compatible `🚧 Blocked
  path` kind and the three unselected Situation source choices.
- `C2.jpg` shows the Coordination-area state with the explicit no-compatible-
  kinds message, no source controls, disabled kind selector, and disabled Save.
- `C3.jpg` shows the draft restored to Situation with a compatible kind and no
  source selected after switching categories.
- `C4.jpg` is captured after Cancel on the Area Details form. It shows the
  three-point polygon still active, with explicit Cancel and Done controls.
  This fails the expected cancellation behavior: the whole draft and overlay
  should disappear and the original map screen should be restored.
- Combined with the M3D5A production path and automated geometry/category
  contracts, this closes `UX-CREATE-03`, `UX-CREATE-04`, and `UX-CREATE-06`.
- `UX-CREATE-07` and `UX-CREATE-09` remain open: C4 demonstrates that form
  Cancel leaves the active area draft and overlay visible instead of restoring
  the original map screen. This is the deferred M3E1 task.
- `UX-CREATE-08` remains open.
- `C1.jpg` through `C3.jpg` expose raw GeoJSON coordinates in the normal Area
  creation form. This reopens `UX-REG-09` and is recorded as the deferred
  M3E3 task; it does not change the accepted typed-geometry submission path.

Manual M3D5A owner-edit regression evidence received on 2026-07-29 from the
same Samsung Galaxy A10s running `OfficialDebug`:

- `D1a.jpg` shows an existing Situation marker edit form with its selected
  `🚨 Ongoing disruption` kind visible.
- `D1b.jpg` is an owner edit for `W1`, the Coordination `📍 Waypoint` created
  in Test B. It incorrectly displays `🚧 Blocked path` and exposes no category,
  so the canonical category/kind is not faithfully represented in owner edit.
- `D2.jpg` shows an existing area edit form and repeats the raw-GeoJSON leak
  already tracked by M3E3; its legacy `Reported`, `Note (observed)`,
  `Request removal`, and `Contributions` wording remains assigned to M3D5C.
- Owner edit availability remains evidenced, but Test D is only partial: the
  category/kind-fidelity failure is the deferred M3E4 task. The screenshots
  do not prove that Cancel leaves persisted content unchanged.

Manual M3E5/M3E6 marker-cue evidence received on 2026-07-29 from the same
Samsung Galaxy A10s running `OfficialDebug`:

- `active-situation-on-map.jpg` shows the active `🚨 Ongoing disruption` marker
  with its kind emoji and a separate `✓` confirmation badge. Its detail in
  `active-situation-detail.jpg` reports one current confirmation separately
  from creator source.
- `active-coodination-on-map.jpg` shows selected Coordination marker `W1`, but
  the selected label covers its `📍 Waypoint` kind cue. Its detail correctly
  identifies Coordination and participation, while the unrelated
  `Situation activity` heading remains tracked by M3E2.
- `no-longer-marker-on-map.jpg` shows the resolved marker's `×` cue and kind
  emoji, but the dashed border is not sufficiently visible to accept at device
  scale. `no-longer-marker-detail.jpg` correctly reports no current
  confirmation and historical evidence, but still exposes Add Situation Update
  (the open M3E7 defect).
- Therefore this evidence does not yet close `UX-MAP-01`, `UX-MAP-10`,
  `UX-MAP-11`, or `UX-A11Y-03`. Selected-cue layering is M3E5A and resolved
  dashed-border legibility is M3E6A.

Manual selected-marker evidence received on 2026-07-29 from the same Samsung
Galaxy A10s running `OfficialDebug`:

- `photo_2026-07-29_15-58-03.jpg` shows a persisted marker selected distinctly
  with label `B1`, while unselected runtime markers are rendered as plain
  circular dots. The list row retains the `🚧 Blocked path` emoji, but the map
  marker does not.
- The circular runtime markers do not establish a Situation-versus-Coordination
  visual family or a non-color category cue. This evidence therefore does not
  close `UX-MAP-01`, `UX-MAP-10`, `UX-MAP-11`, or the M3D visual-communication
  gate.
- `photo_2026-07-29_15-57-25.jpg` shows the owner marker edit form with canonical
  coordinates, kind, source, label, note, and Save/Cancel controls. Combined
  with the accepted ownership-gating tests, it closes `UX-DETAIL-11` and
  `UX-REG-04`.
- The edit form still exposes `Reported`, `Voting`, `Contributions`, and
  `Request Removal`. This continues to block `UX-VOCAB-04`,
  `UX-VOCAB-09`, `UX-CREATE-SIT-06`, and `UX-REG-08`.
- `photo_2026-07-29_15-57-25 (2).jpg` shows the selected Situation detail with
  canonical content, lifecycle, explicit creator source, current verification,
  freshness, three category-correct Situation actions, an empty activity
  section, and a nontechnical mixed-version limitation.
- The action forms themselves were not opened in this evidence set, so it does
  not yet close `UX-VOCAB-08` or any `UX-FORM-*` criterion.
- Combined with the accepted M3A–M3C automated evidence, this detail screenshot
  closes `UX-VOCAB-06`, `UX-VOCAB-10`,
  `UX-DETAIL-08`, `UX-DETAIL-09`, `UX-DETAIL-SIT-01` through
  `UX-DETAIL-SIT-03`, `UX-DETAIL-SIT-05`, `UX-DETAIL-SIT-07`,
  `UX-ACTION-SIT-01` through `UX-ACTION-SIT-04`, `UX-ACTION-01`,
  `UX-ACTION-09`, `UX-TIMELINE-07`, `UX-VERIFY-01` through
  `UX-VERIFY-03`, `UX-VERIFY-05`, `UX-VERIFY-07`, `UX-VERIFY-08`, and
  `UX-OFFLINE-04` through `UX-OFFLINE-06`.

Manual Confirm evidence received on 2026-07-29 from the same Samsung Galaxy A10s
running `OfficialDebug`:

- `photo_2026-07-29_16-06-43.jpg` shows confirmed markers with a green outline
  on the production map. The confirmation cue has no accompanying icon, pattern,
  or text on the marker itself, so `UX-MAP-11` remains open because this meaning
  is encoded by color alone.
- `photo_2026-07-29_16-06-43 (2).jpg` shows persisted current confirmation count
  `1`, latest current confirmation time, current-evidence wording, and one
  human-readable `Situation confirmed` history row after the earlier zero-count
  detail state.
- The legacy map row still says `No vote yet` while the M3 detail and activity
  show one accepted confirmation, and the earlier owner-edit screenshot keeps
  `Contributions` in its own legacy section. This is runtime evidence that
  legacy vote/contribution presentation remains separate from dedicated
  confirmation history.
- Combined with the accepted M2/M3 automated authority and refresh evidence,
  this closes `UX-TIMELINE-02`, `UX-TIMELINE-09`, `UX-TIMELINE-12`,
  `UX-VERIFY-04`, `UX-VERIFY-06`, `UX-VERIFY-11`, and runtime scenario
  `UX-E2E-C1` through `UX-E2E-C3`.

Manual resolved-Situation evidence received on 2026-07-29 from the same Samsung
Galaxy A10s running `OfficialDebug`:

- `photo_2026-07-29_16-09-53 (2).jpg` shows the selected no-longer-active marker
  with a dashed border, distinct from the earlier active/confirmed solid border.
  Combined with the accepted lifecycle visual-policy tests, this closes
  `UX-MAP-03`.
- `photo_2026-07-29_16-09-53.jpg` shows `No longer active`, no current
  confirmation evidence, an explicit resolved-freshness explanation, and the
  earlier confirmation retained in activity history.
- The timeline is visibly newest-first: no-longer-active at 4:09:08 PM,
  still-active update at 4:03:59 PM, then confirmation at 4:02:28 PM. The three
  append-only actions remain distinct and use different human-readable
  vocabulary.
- No lifecycle-reactivation control is offered, and history remains visible.
  Combined with accepted M2/M3 automated semantics, this closes
  `UX-DETAIL-SIT-06`, `UX-ACTION-SIT-06`,
  `UX-FORM-RESOLVE-01`, `UX-FORM-RESOLVE-05`,
  `UX-TIMELINE-03`, `UX-TIMELINE-04`, `UX-TIMELINE-08`,
  `UX-VERIFY-10`, `UX-E2E-C4`, `UX-E2E-C7`, and `UX-E2E-C9`.
- The evidence shows a Still-active update rather than a Situation-changed
  update, and it does not show the resolution confirmation form itself.
  Therefore `UX-VOCAB-08` and `UX-FORM-RESOLVE-02` remain open.
- A resolved item still displays `ADD SITUATION UPDATE`. This is an invalid
  normal interaction for the resolved target, so `UX-ACTION-SIT-05`,
  `UX-ACTION-04`, and `UX-E2E-C8` remain open and require a fix.

---

## 4. Protected Architecture Contract

These are UX implementation constraints, not optional internal preferences.

- [x] `UX-ARCH-01` UI reads canonical and domain state through accepted
  application/repository seams; it does not access Room entities or DAOs.
- [x] `UX-ARCH-02` UI submits typed creation or interaction intents; it does not
  build raw event payloads.
- [x] `UX-ARCH-03` Every accepted write uses the existing local writer and
  `RoomMapEventProcessor` processing path.
- [x] `UX-ARCH-04` UI availability is advisory; processor validation remains
  authoritative for category, source, lifecycle, temporal, membership, replay,
  dedupe, and ownership rules.
- [x] `UX-ARCH-05` Persisted reload is the source of truth after a write; UI does
  not manually mutate lifecycle, confirmation count, acknowledgement count, or
  timeline history as authoritative state.
- [x] `UX-ARCH-06` Marker/area identity always includes group, item type, and
  item ID.
- [ ] `UX-ARCH-07` Marker and area with the same raw ID remain isolated in
  selection, detail, creation feedback, refresh, and timeline.
- [x] `UX-ARCH-08` M3 does not change the accepted M2 protocol, Room schema,
  reducer, ordering, replay, BLE, relay, envelope, or compatibility semantics.
- [x] `UX-ARCH-09` Dedicated actions never mutate canonical coordinates,
  geometry, label, kind, radius, or owner-authored note.
- [x] `UX-ARCH-10` Owner Edit is not presented as Add update; canonical Delete is
  not presented as Mark no longer active.
- [x] `UX-ARCH-11` Legacy votes, contributions, and removal requests are not
  relabeled as new confirmation, acknowledgement, update, or lifecycle actions.
- [x] `UX-ARCH-12` Unknown domain values fail safely and remain explicit; UI
  does not guess category, source, lifecycle, or action meaning.

---

## 5. Global Information Architecture and Vocabulary

### P0 — Required

- [ ] `UX-VOCAB-01` User-facing category vocabulary is **Situation** and
  **Coordination**.
- [ ] `UX-VOCAB-02` A Situation means an observed condition, hazard, need, or
  disruption whose current reality can be confirmed or updated.
- [ ] `UX-VOCAB-03` A Coordination item means an intentional shared point or
  resource for group action; it is acknowledged, not verified.
- [ ] `UX-VOCAB-04` The new flow does not expose Reported, Observed, Confidence,
  Contribute, or numeric confidence weights.
- [ ] `UX-VOCAB-05` Information source answers “How do you know?” and is never
  described as a confidence score.
- [x] `UX-VOCAB-06` Community verification is derived from accepted current
  confirmations, not entered by the creator.
- [ ] `UX-VOCAB-07` Acknowledgement is participation, not verification.
- [ ] `UX-VOCAB-08` “Situation changed” and “No longer active” are visibly and
  semantically different.
- [ ] `UX-VOCAB-09` “No longer active” and “Delete” are visibly and semantically
  different.
- [x] `UX-VOCAB-10` UI never exposes raw names such as `MAP.ITEM.CONFIRM`,
  reducer outcomes, DAO errors, SQLite errors, Lamport time, relay, or envelope.

### Kind/category consistency

- [ ] `UX-VOCAB-11` Every selectable kind belongs to exactly one category using
  the centralized kind catalog.
- [ ] `UX-VOCAB-12` Situation kinds and Coordination kinds are not mixed in the
  same unqualified list.
- [ ] `UX-VOCAB-13` UI does not independently reclassify a kind.
- [ ] `UX-VOCAB-14` Unknown or unsupported kinds do not receive a guessed
  category.

---

## 6. Map Overview and Visual Hierarchy

### P0 — Meaning and selection

- [ ] `UX-MAP-01` Situation and Coordination items are distinguishable without
  opening detail.
- [ ] `UX-MAP-02` Marker and area remain distinguishable and retain their exact
  geometry type.
- [x] `UX-MAP-03` Active, changed/stale, no-longer-active, expired, and unresolved
  states use distinguishable presentation where those states are available.
- [ ] `UX-MAP-04` Visual treatment does not imply that an area’s exact polygon
  boundary has been community-confirmed.
- [x] `UX-MAP-05` Selected item is visually distinct from unselected items.
- [x] `UX-MAP-06` Selecting a marker or area resolves the exact typed identity,
  even when a marker and area share the same raw ID.
- [x] `UX-MAP-07` Selection opens one shared detail/navigation path for runtime
  clicks and list “Details” actions.
- [x] `UX-MAP-08` A stale or delayed selection callback cannot replace a newer
  user selection.
- [ ] `UX-MAP-09` Closing detail or switching selection does not leave stale
  overlays or actions attached to the previous item.

### P1 — Legibility and cleanup

- [ ] `UX-MAP-10` Dense maps remain legible; category and lifecycle cues do not
  depend on tiny text alone.
- [ ] `UX-MAP-11` Meaning is not encoded by color alone.
- [ ] `UX-MAP-12` Selected, disabled, stale, and resolved styling meets usable
  contrast in light and dark/system themes where supported.
- [x] `UX-MAP-13` Normal item presentation never shows raw GeoJSON.
- [ ] `UX-MAP-14` Long labels and notes wrap, truncate safely, or expand without
  breaking map/detail layout.
- [ ] `UX-MAP-15` Area detail uses a safe human-readable geometry summary.
- [ ] `UX-MAP-16` Camera, viewport, drafting overlays, and selection are preserved
  across a normal refresh unless the user explicitly navigates elsewhere.
- [x] `UX-MAP-17` Visible scene data is batch-loaded; UI does not create an N+1
  per-item read pattern.

---

## 7. Creation Entry and Drafting

### Shared creation journey

- [x] `UX-CREATE-01` The entry point makes both marker and area creation
  discoverable.
- [x] `UX-CREATE-02` The flow makes the semantic category clear before final
  submission: Situation or Coordination.
- [x] `UX-CREATE-03` After category selection, only compatible kinds are offered.
- [x] `UX-CREATE-04` Marker and area use typed geometry; the form cannot submit a
  marker intent with area geometry or vice versa.
- [x] `UX-CREATE-05` Existing marker drafting behavior remains usable.
- [x] `UX-CREATE-06` Existing area drafting behavior remains usable.
- [ ] `UX-CREATE-07` Cancel/back returns to the prior map state without persisting
  an incomplete item.
- [ ] `UX-CREATE-08` Reopening or changing the form does not leak a previous draft,
  source choice, note, category, or geometry.
- [ ] `UX-CREATE-09` Temporary overlays belong only to the active draft and are
  cleared on cancel, rejection, completion, or Activity destruction.
- [ ] `UX-CREATE-10` The Activity does not directly construct ADD events or call a
  low-level writer; it delegates one typed creation submission.
- [ ] `UX-CREATE-11` Only one creation submission may be in flight for a draft.
- [ ] `UX-CREATE-12` Repeated taps cannot create duplicate visible items.
- [ ] `UX-CREATE-13` Persisted reload, not a temporary overlay, becomes map truth
  after local acceptance.

### Situation creation

- [x] `UX-CREATE-SIT-01` The form identifies the new item as a Situation.
- [x] `UX-CREATE-SIT-02` A Situation kind is required and category-compatible.
- [x] `UX-CREATE-SIT-03` Information source is required.
- [x] `UX-CREATE-SIT-04` Source choices use understandable wording:
  - I saw it myself / Seen firsthand;
  - Someone told me / Reported by others;
  - I’m not sure / Source uncertain.
- [x] `UX-CREATE-SIT-05` Source is not prefilled in a way that silently claims
  firsthand knowledge.
- [x] `UX-CREATE-SIT-06` Source selection is not labeled Confidence, Reported, or
  Observed.
- [x] `UX-CREATE-SIT-07` Creator source does not display as community
  confirmation.
- [x] `UX-CREATE-SIT-08` Required canonical fields are validated before
  submission.
- [x] `UX-CREATE-SIT-09` Invalid source/kind/geometry combinations are blocked
  before submission and remain rejectable by the processor.

### Coordination creation

- [x] `UX-CREATE-COORD-01` The form identifies the new item as Coordination.
- [x] `UX-CREATE-COORD-02` A Coordination kind is required and
  category-compatible.
- [x] `UX-CREATE-COORD-03` The form does not show or submit information source.
- [x] `UX-CREATE-COORD-04` The form does not show or submit Confidence.
- [x] `UX-CREATE-COORD-05` The form does not show or submit lifecycle state.
- [ ] `UX-CREATE-COORD-06` Required canonical fields are validated before
  submission.

### Creation outcome

- [ ] `UX-CREATE-OUT-01` Invalid form input is shown inline and does not invoke
  submission.
- [ ] `UX-CREATE-OUT-02` Submitting state disables duplicate submit while keeping
  the current draft understandable.
- [ ] `UX-CREATE-OUT-03` Local acceptance is reported accurately.
- [ ] `UX-CREATE-OUT-04` A post-commit delivery warning does not claim that local
  creation failed.
- [ ] `UX-CREATE-OUT-05` Missing group key, enqueue rejection/failure, transport
  exception, semantic rejection, and persistence failure are not collapsed into
  one misleading message.
- [ ] `UX-CREATE-OUT-06` Exact duplicate is presented as already recorded or
  equivalent idempotent wording.
- [ ] `UX-CREATE-OUT-07` Rejected submission retains enough draft input for safe
  correction where appropriate.
- [ ] `UX-CREATE-OUT-08` Accepted submission closes/clears the matching draft only;
  stale feedback cannot close or mutate a newer dialog.
- [ ] `UX-CREATE-OUT-09` Executor/scheduler rejection cannot leave the UI stuck in
  Submitting.
- [ ] `UX-CREATE-OUT-10` Successful local persistence refreshes the map and reveals
  the persisted item without duplicate temporary rendering.

---

## 8. Item Detail

### Loading and identity

- [x] `UX-DETAIL-01` Marker detail opens by exact group + marker + item ID.
- [x] `UX-DETAIL-02` Area detail opens by exact group + area + item ID.
- [ ] `UX-DETAIL-03` Loading, found, canonical-only, unresolved legacy, deleted,
  invalid data, and read failure are distinguishable.
- [ ] `UX-DETAIL-04` A valid canonical item remains readable when domain
  enrichment is missing.
- [ ] `UX-DETAIL-05` An unresolved legacy item uses explicit read-only
  compatibility presentation.
- [ ] `UX-DETAIL-06` A deleted or missing item does not leave active controls on
  screen.
- [ ] `UX-DETAIL-07` Reopening detail does not accumulate duplicate observers,
  controllers, callbacks, or timeline rows.

### Canonical content

- [x] `UX-DETAIL-08` Detail shows label/title, kind, marker/area identity, and
  owner-authored note where supported.
- [x] `UX-DETAIL-09` Marker detail shows safe location information.
- [ ] `UX-DETAIL-10` Area detail shows a human-readable geometry summary rather
  than raw GeoJSON.
- [x] `UX-DETAIL-11` Owner Edit remains available only under accepted ownership
  and edit-window rules.
- [ ] `UX-DETAIL-12` Owner Delete remains separate from collaborative lifecycle
  actions.

### Situation detail

- [x] `UX-DETAIL-SIT-01` Recognized category is shown as Situation.
- [x] `UX-DETAIL-SIT-02` Lifecycle is shown as active or no longer active.
- [x] `UX-DETAIL-SIT-03` Creator information source is shown only when explicitly
  known.
- [ ] `UX-DETAIL-SIT-04` Legacy creator source is shown as unspecified/unknown,
  never inferred from old confidence.
- [x] `UX-DETAIL-SIT-05` Current confirmation summary uses accepted M2 projection;
  UI does not recalculate it.
- [x] `UX-DETAIL-SIT-06` Changed/resolved state cannot appear currently confirmed
  from stale historical confirmations.
- [x] `UX-DETAIL-SIT-07` Expiry and interaction availability are understandable
  without exposing timestamp-policy internals.
- [ ] `UX-DETAIL-SIT-08` Area detail explains that confirmation concerns the
  reported condition, not the exact polygon boundary.

### Coordination detail

- [x] `UX-DETAIL-COORD-01` Recognized category is shown as Coordination.
- [x] `UX-DETAIL-COORD-02` Coordination does not display Situation lifecycle.
- [x] `UX-DETAIL-COORD-03` Coordination does not display information source or
  confidence.
- [x] `UX-DETAIL-COORD-04` Acknowledgement count is labeled as participation, not
  verification.
- [x] `UX-DETAIL-COORD-05` Recent-note preview may be shown, while full history
  remains the timeline’s responsibility.

---

## 9. Interaction Availability

### Situation controls

- [x] `UX-ACTION-SIT-01` Active recognized Situation offers **Confirm** when
  available.
- [x] `UX-ACTION-SIT-02` Active recognized Situation offers **Add update** when
  available.
- [x] `UX-ACTION-SIT-03` Active recognized Situation offers **Mark no longer
  active** when available.
- [x] `UX-ACTION-SIT-04` Situation does not show Acknowledge or Add note.
- [ ] `UX-ACTION-SIT-05` Resolved Situation offers no normal Situation
  interactions.
- [x] `UX-ACTION-SIT-06` UI never offers lifecycle reactivation.

### Coordination controls

- [ ] `UX-ACTION-COORD-01` Recognized Coordination offers **Acknowledge** when
  available.
- [ ] `UX-ACTION-COORD-02` Recognized Coordination offers **Add note** when
  available.
- [ ] `UX-ACTION-COORD-03` Coordination does not show Confirm, Add update, or Mark
  no longer active.

### Common gating

- [x] `UX-ACTION-01` Category-incompatible actions are hidden rather than merely
  disabled.
- [ ] `UX-ACTION-02` Important blocked actions show a stable, understandable
  reason when visible.
- [ ] `UX-ACTION-03` Unresolved category is read-only.
- [ ] `UX-ACTION-04` Deleted, expired, interaction-closed, or resolved targets do
  not offer invalid actions.
- [ ] `UX-ACTION-05` Unknown or unavailable membership fails closed.
- [ ] `UX-ACTION-06` Owner-edit cutoff alone does not close valid member
  interactions while the item remains active.
- [ ] `UX-ACTION-07` Local unsupported state is distinct from peer compatibility
  uncertainty.
- [ ] `UX-ACTION-08` Mixed/unknown peer capability may show a warning but does not
  override a semantic blocker.
- [x] `UX-ACTION-09` Capability warning does not claim that all peers will receive,
  relay, or display the action.
- [ ] `UX-ACTION-10` A button that became stale may still be rejected cleanly by
  the processor without partial UI state.
- [ ] `UX-ACTION-11` An in-flight action cannot be submitted twice.

---

## 10. Interaction Forms and Outcomes

### Confirm

- [ ] `UX-FORM-CONFIRM-01` Confirm permits an optional source only where the
  accepted contract allows it.
- [ ] `UX-FORM-CONFIRM-02` Confirm permits an optional note.
- [ ] `UX-FORM-CONFIRM-03` Empty optional fields are omitted or normalized safely.
- [ ] `UX-FORM-CONFIRM-04` Area confirmation includes the condition-versus-boundary
  explanation.
- [ ] `UX-FORM-CONFIRM-05` One user gesture submits one typed Confirm command.

### Add Situation update

- [ ] `UX-FORM-UPDATE-01` Nonblank update content is required.
- [ ] `UX-FORM-UPDATE-02` Disposition is required and limited to:
  - Still active;
  - Situation changed.
- [ ] `UX-FORM-UPDATE-03` Optional information source is offered only where
  supported.
- [ ] `UX-FORM-UPDATE-04` Add update cannot set No longer active.
- [ ] `UX-FORM-UPDATE-05` Add update cannot modify canonical item fields.

### Mark no longer active

- [x] `UX-FORM-RESOLVE-01` The action is worded as no longer active/resolved, not
  Delete.
- [ ] `UX-FORM-RESOLVE-02` Only `NO_LONGER_ACTIVE` is offered as target.
- [ ] `UX-FORM-RESOLVE-03` Optional note/source is shown only where supported.
- [ ] `UX-FORM-RESOLVE-04` Destructive/irreversible meaning is confirmed before
  submission.
- [x] `UX-FORM-RESOLVE-05` Reactivation is not offered after acceptance.

### Acknowledge

- [ ] `UX-FORM-ACK-01` Acknowledge is a simple one-step action.
- [ ] `UX-FORM-ACK-02` It does not request source, confidence, lifecycle, or note.
- [ ] `UX-FORM-ACK-03` The count is reloaded from persisted state rather than
  authoritatively incremented in UI.

### Add Coordination note

- [ ] `UX-FORM-NOTE-01` Nonblank note content is required.
- [ ] `UX-FORM-NOTE-02` It does not request source, confidence, lifecycle, or
  Situation update disposition.
- [ ] `UX-FORM-NOTE-03` One user gesture submits one typed Add note command.

### Common feedback

- [ ] `UX-FEEDBACK-01` Accepted local persistence is distinguishable from remote
  delivery.
- [ ] `UX-FEEDBACK-02` Exact duplicate does not create a second visible effect.
- [ ] `UX-FEEDBACK-03` Semantic rejection uses state-aware, actionable wording.
- [ ] `UX-FEEDBACK-04` Membership rejection is distinguishable.
- [ ] `UX-FEEDBACK-05` Expired, interaction-closed, resolved, and deleted
  rejections are distinguishable where known.
- [ ] `UX-FEEDBACK-06` Invalid input is shown before submission.
- [ ] `UX-FEEDBACK-07` Persistence failure is distinguishable from post-commit
  delivery warning.
- [ ] `UX-FEEDBACK-08` Accepted action with delivery warning still refreshes and
  shows accepted local state.
- [ ] `UX-FEEDBACK-09` Stale-state rejection refreshes detail where useful.
- [ ] `UX-FEEDBACK-10` Success/error feedback is transient and cannot mutate a
  newer form or selected item.

---

## 11. Timeline and Activity

### P0

- [ ] `UX-TIMELINE-01` Each item detail can expose its own activity history.
- [x] `UX-TIMELINE-02` Accepted dedicated actions use human-readable vocabulary.
- [x] `UX-TIMELINE-03` Timeline is deterministic newest-first using accepted
  ordering, not UI arrival time.
- [x] `UX-TIMELINE-04` Distinct append-only actions remain distinct rows.
- [ ] `UX-TIMELINE-05` Stable action/event identity prevents duplicate rows.
- [ ] `UX-TIMELINE-06` Pending or rejected forensic actions are not shown as
  normal community history.
- [x] `UX-TIMELINE-07` Current state is visually separate from historical
  activity.
- [x] `UX-TIMELINE-08` Stale confirmations remain historical but are not presented
  as current verification.
- [x] `UX-TIMELINE-09` Legacy contributions remain semantically separate and are
  not translated into new actions.

### Pagination and refresh

- [ ] `UX-TIMELINE-10` Pagination is keyset-based and stable.
- [ ] `UX-TIMELINE-11` Refresh plus pagination produces no duplicate or skipped
  rows.
- [x] `UX-TIMELINE-12` Accepted local action refreshes the first page.
- [ ] `UX-TIMELINE-13` Empty, loading, partial, and error states are usable.
- [ ] `UX-TIMELINE-14` UI does not rebuild history from the durable event log.

---

## 12. Community Verification and Participation

### Situation verification

- [x] `UX-VERIFY-01` Shows current distinct confirmer count.
- [x] `UX-VERIFY-02` Shows whether current confirmation evidence exists.
- [x] `UX-VERIFY-03` Shows freshness/currentness supplied by the accepted
  projection.
- [x] `UX-VERIFY-04` Shows latest confirmation time where available and useful.
- [x] `UX-VERIFY-05` Shows creator source only when explicitly known.
- [x] `UX-VERIFY-06` Does not count legacy votes or Coordination acknowledgements.
- [x] `UX-VERIFY-07` Does not derive freshness independently in UI.
- [x] `UX-VERIFY-08` Does not use unsupported trust percentages, weighted scores,
  or reputation.
- [ ] `UX-VERIFY-09` A materially changed Situation clearly invalidates prior
  confirmations as current evidence.
- [x] `UX-VERIFY-10` A resolved Situation cannot appear currently confirmed.
- [x] `UX-VERIFY-11` Historical evidence remains visible without being called
  current.
- [ ] `UX-VERIFY-12` Area wording never claims exact-boundary confirmation.

### Coordination participation

- [ ] `UX-PARTICIPATE-01` Shows acknowledgement actor count where useful.
- [ ] `UX-PARTICIPATE-02` Uses participation/acknowledgement language.
- [ ] `UX-PARTICIPATE-03` Does not apply Situation verification or freshness
  rules.
- [ ] `UX-PARTICIPATE-04` Does not introduce lifecycle or confidence for
  Coordination.

---

## 13. Offline, Mixed-Version, and Failure UX

- [ ] `UX-OFFLINE-01` The user can create and interact through the accepted local
  offline-first path.
- [ ] `UX-OFFLINE-02` Local success is never retracted solely because BLE/relay
  enqueue later fails.
- [ ] `UX-OFFLINE-03` Delivery warning says the item/action is saved locally while
  sharing may be delayed or limited.
- [x] `UX-OFFLINE-04` UI does not promise per-peer delivery.
- [x] `UX-OFFLINE-05` Mixed-version limitation is understandable and nontechnical.
- [x] `UX-OFFLINE-06` Canonical detail remains readable when collaboration
  capability is unknown.
- [ ] `UX-OFFLINE-07` Recognized legacy items allow only category-correct actions
  supported by materialized state.
- [ ] `UX-OFFLINE-08` Unresolved legacy items remain readable and read-only.
- [ ] `UX-OFFLINE-09` Legacy confidence remains separate and is not presented as
  creator source or community verification.
- [ ] `UX-OFFLINE-10` Legacy contributions remain separate from dedicated action
  history.
- [ ] `UX-OFFLINE-11` Unknown optional fields or unsupported new actions do not
  crash the map or corrupt valid sibling items.
- [ ] `UX-OFFLINE-12` Process recreation reloads persisted state and does not
  restore a false in-flight success.

---

## 14. Race Conditions and State Changes

- [ ] `UX-RACE-01` A delayed read for item A cannot replace the currently selected
  item B.
- [ ] `UX-RACE-02` A delayed submission result for form A cannot enable, close,
  clear, refresh, or call callbacks for newer form B.
- [ ] `UX-RACE-03` Target deletion while detail is open transitions to unavailable
  state and removes actions.
- [ ] `UX-RACE-04` Remote resolution while a Situation form is open results in a
  clean refresh/rejection, not partial local state.
- [ ] `UX-RACE-05` Membership loss while detail is open is handled by advisory
  refresh or authoritative rejection.
- [ ] `UX-RACE-06` Remote update while timeline/detail is open does not duplicate
  entries or overwrite newer local selection.
- [ ] `UX-RACE-07` Activity recreation does not duplicate controllers,
  subscriptions, overlays, or submission callbacks.
- [ ] `UX-RACE-08` Scheduler/executor failure returns the UI to a recoverable state.
- [ ] `UX-RACE-09` Double tap, retry, exact duplicate, and replay converge to one
  visible accepted effect.
- [ ] `UX-RACE-10` Marker and area same-ID isolation survives all delayed callback
  scenarios.

---

## 15. Accessibility and Interaction Quality

- [ ] `UX-A11Y-01` All action controls have meaningful accessibility labels.
- [ ] `UX-A11Y-02` Icons do not carry meaning without text or content
  descriptions.
- [ ] `UX-A11Y-03` Situation/Coordination and lifecycle meaning do not depend on
  color alone.
- [ ] `UX-A11Y-04` Touch targets are usable.
- [ ] `UX-A11Y-05` Dialog/sheet focus begins at a meaningful field and returns to
  the initiating control when closed.
- [ ] `UX-A11Y-06` Validation errors are associated with the affected input.
- [ ] `UX-A11Y-07` Loading/submitting state is announced and does not trap focus.
- [ ] `UX-A11Y-08` Long labels, large font scale, and localized text do not hide
  primary actions or overflow the screen.
- [ ] `UX-A11Y-09` Back/cancel behavior is predictable and does not submit.
- [ ] `UX-A11Y-10` Resolved, disabled, warning, and failure states remain readable
  at supported contrast levels.

---

## 16. Regression Guard

- [x] `UX-REG-01` Marker creation remains available.
- [ ] `UX-REG-02` Area creation remains available.
- [ ] `UX-REG-03` Marker and area drafting remain available.
- [x] `UX-REG-04` Canonical owner edit remains available under existing rules.
- [ ] `UX-REG-05` Canonical owner delete remains available under existing rules.
- [ ] `UX-REG-06` Map camera, viewport, selection, and visibility behavior do not
  regress unintentionally.
- [x] `UX-REG-07` Both Emergency and Official variants compile and expose the same
  domain semantics.
- [ ] `UX-REG-08` No user-visible legacy control duplicates a new control while
  claiming the same semantic action.
- [ ] `UX-REG-09` No raw GeoJSON or internal event/persistence vocabulary leaks
  into normal UI.
- [x] `UX-REG-10` Room schema and M2 processing invariants remain unchanged unless
  a separately reviewed phase explicitly changes them.

---

## 17. End-to-End Target Scenarios

These scenarios are mandatory closure smoke tests. Each must be exercised through
the real Activity/runtime path, not only through isolated presenters.

### Scenario A — Create a Situation marker

- [ ] `UX-E2E-A1` Enter marker creation.
- [ ] `UX-E2E-A2` Choose Situation and a compatible kind.
- [ ] `UX-E2E-A3` Place/draft marker geometry.
- [ ] `UX-E2E-A4` Complete required canonical fields.
- [ ] `UX-E2E-A5` Choose a required information source.
- [ ] `UX-E2E-A6` Submit once.
- [ ] `UX-E2E-A7` Observe accurate local acceptance/delivery feedback.
- [ ] `UX-E2E-A8` See the persisted Situation on the map after reload.
- [ ] `UX-E2E-A9` Open exact detail and see Situation source/lifecycle/actions.

### Scenario B — Create a Coordination area

- [ ] `UX-E2E-B1` Enter area creation.
- [ ] `UX-E2E-B2` Choose Coordination and a compatible area kind.
- [ ] `UX-E2E-B3` Draft valid area geometry.
- [ ] `UX-E2E-B4` Confirm no source/confidence field appears or is submitted.
- [ ] `UX-E2E-B5` Submit and see persisted Coordination area after reload.
- [ ] `UX-E2E-B6` Open detail and see Acknowledge/Add note only.

### Scenario C — Situation collaboration

- [x] `UX-E2E-C1` Open an active Situation marker.
- [x] `UX-E2E-C2` Confirm it.
- [x] `UX-E2E-C3` Verify persisted confirmation summary refreshes.
- [x] `UX-E2E-C4` Add a Still active update.
- [ ] `UX-E2E-C5` Add a Situation changed update.
- [ ] `UX-E2E-C6` Verify prior confirmation is no longer presented as current.
- [x] `UX-E2E-C7` Mark the Situation no longer active.
- [ ] `UX-E2E-C8` Verify normal Situation actions are no longer available.
- [x] `UX-E2E-C9` Verify history remains visible and no reactivation is offered.

### Scenario D — Coordination collaboration

- [ ] `UX-E2E-D1` Open a Coordination item.
- [ ] `UX-E2E-D2` Acknowledge it without source/confidence/note input.
- [ ] `UX-E2E-D3` Verify persisted acknowledgement count refreshes.
- [ ] `UX-E2E-D4` Add a nonblank Coordination note.
- [ ] `UX-E2E-D5` Verify the note appears in activity/recent preview without being
  called verification.

### Scenario E — Offline delivery warning

- [ ] `UX-E2E-E1` Force post-commit transport enqueue failure.
- [ ] `UX-E2E-E2` Submit creation or interaction.
- [ ] `UX-E2E-E3` Verify UI says it is saved locally and sharing is delayed/limited.
- [ ] `UX-E2E-E4` Verify persisted state refreshes and remains visible.
- [ ] `UX-E2E-E5` Verify retry/reopen does not create a duplicate visible effect.

### Scenario F — Stale UI race

- [ ] `UX-E2E-F1` Open an action form for item A.
- [ ] `UX-E2E-F2` Cause a delayed callback or submit result.
- [ ] `UX-E2E-F3` Move to item/form B before callback A completes.
- [ ] `UX-E2E-F4` Verify callback A does not mutate B.
- [ ] `UX-E2E-F5` Verify the current map selection/detail remains B.

### Scenario G — Legacy compatibility

- [ ] `UX-E2E-G1` Open recognized legacy Situation and Coordination items.
- [ ] `UX-E2E-G2` Verify source is not guessed from legacy confidence.
- [ ] `UX-E2E-G3` Open an unresolved legacy item.
- [ ] `UX-E2E-G4` Verify canonical content is readable in read-only mode.
- [ ] `UX-E2E-G5` Verify legacy contribution/history is not relabeled as a new
  action.

---

## 18. Phase-Level UX Gate

### M2

M2 may be complete with no major visible redesign, provided it supplies and
enforces the domain state needed by M3. M2 is not evidence that the target UX is
visible.

- [x] M2 persists/materializes category, source, action, lifecycle, update type,
  stable identity, and aggregates.
- [x] M2 enforces all semantic rules outside UI for local and remote actions.
- [x] M2 keeps legacy semantics separate.
- [x] Existing UI still compiles through compatibility seams.

### M3A — Detail and interactions

- [x] Item detail is reachable from the real map.
- [ ] All five category-correct interactions are usable at runtime.
- [ ] Accepted/rejected/delivery-warning outcomes are accurate.
- [ ] Stale state and duplicate in-flight submission are safe.

### M3B — Timeline

- [ ] Per-item timeline is visible at runtime.
- [ ] Ordering, pagination, refresh, and history coexistence are proven.

### M3C — Verification

- [ ] Situation current verification and freshness are understandable.
- [ ] Coordination participation remains independent.
- [ ] Runtime Confirm/Acknowledge actions refresh the correct presentation.

### M3D — Map and creation UX

- [ ] Map visually communicates Situation versus Coordination.
- [ ] Creation uses the new semantic vocabulary and source rules.
- [ ] Marker/area drafting is preserved.
- [ ] Selection and list Details share one identity-safe navigation path.
- [ ] Raw-data and long-label issues are corrected at presentation level.

### M3 closure

M3 cannot close while the normal runtime creation screen and core map interaction
experience remain materially indistinguishable from the pre-M2/M3 UI, unless
every missing target UX item has been explicitly moved to an accepted later
milestone.

---

## 19. Codex Implementation Report Template

Every implementation or fix report must include:

1. **Checklist scope** — exact criterion IDs targeted.
2. **User-visible change** — what a user can now see or do.
3. **Architecture path** — presentation → typed application boundary → accepted
   writer/processor or read seam.
4. **Exact files changed**.
5. **Automated evidence** — test names and commands actually run.
6. **Runtime evidence** — Activity, flavor, device/emulator, user gesture, visible
   result.
7. **Failure/race evidence** — rejection, duplicate, stale callback, or delivery
   warning as applicable.
8. **Regression evidence** — drafting, selection, edit/delete, schema, or
   architecture invariants as applicable.
9. **Checklist update** — which IDs changed from `[ ]`/`[~]` to `[x]`.
10. **Remaining gaps** — every in-scope ID still not `[x]`.

Codex must not report “UX complete” when only non-runtime layers were verified.

---

## 20. Final Definition of Done

The target Map UX is complete only when a normal user can:

1. create a Situation or Coordination marker/area with category-correct fields;
2. understand the category and current state directly from map/detail;
3. perform only the five valid category-specific collaborative actions;
4. distinguish source, community verification, acknowledgement, lifecycle,
   owner edit, and delete;
5. inspect deterministic human-readable activity;
6. understand local save versus delayed/limited delivery;
7. safely use legacy and unresolved items without invented semantics;
8. complete these journeys without stale callbacks, duplicate visible effects,
   raw internal terminology, or regression of marker/area drafting.

Passing architecture and unit tests is necessary, but this end-to-end runtime
experience is the final acceptance boundary.
