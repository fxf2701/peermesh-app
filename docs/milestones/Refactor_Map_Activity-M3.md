# Phase M3 — Collaborative Disaster Intelligence UX

## Objective

Transform the existing map experience from canonical marker/area CRUD into a collaborative disaster-intelligence interface built on the accepted M2 distributed map-event engine.

M3 must expose Situation and Coordination semantics through clear interaction, history, verification, map presentation and offline feedback while preserving all M2 protocol, persistence, reducer, replay, transport and compatibility invariants.

## M2 protected baseline

M3 must preserve:

* [ ] Room schema version 52;
* [ ] all accepted canonical map event types;
* [ ] all accepted dedicated `MAP.ITEM.*` action types;
* [ ] immutable append-only durable event history;
* [ ] immutable append-only dedicated action history;
* [ ] deterministic canonical and action ordering;
* [ ] the earliest-resolution irreversible Situation lifecycle cutoff;
* [ ] confirmation-freshness invalidation after material Situation change;
* [ ] acknowledgement independence from Situation verification;
* [ ] legacy confidence, contribution and removal-state separation;
* [ ] pending-target recovery semantics;
* [ ] mixed-version compatibility;
* [ ] BLE batching, envelope and relay behavior;
* [ ] `RoomMapEventProcessor` as the sole Room transaction owner;
* [ ] the accepted shared local and remote processing path.

M3 must not:

* [ ] change the event protocol;
* [ ] change Room schema;
* [ ] introduce a Room migration;
* [ ] change canonical or dedicated-action reducers;
* [ ] change duplicate or fingerprint semantics;
* [ ] change action ordering;
* [ ] change replay or pending-target recovery;
* [ ] change relay, BLE or envelope formats;
* [ ] reinterpret legacy votes or contributions;
* [ ] create a second persistence or materialization flow;
* [ ] expose Room entities to UI;
* [ ] allow UI to call DAOs or reducers directly.

---

# Phase M3A — Interaction and Item-Detail Foundation

## Goal

Establish the application, presentation and interaction boundaries required to expose collaborative Situation and Coordination behavior through map-item detail UI.

M3A must allow users to:

* open a marker or area detail surface;
* understand its basic canonical and domain state;
* see which interactions are currently available;
* submit supported dedicated actions;
* receive accurate local acceptance, rejection and post-commit feedback.

M3A must not complete:

* full item activity timeline;
* full Community Verification presentation;
* normal map rendering redesign;
* global terminology migration;
* per-peer delivery tracking;
* moderation or reputation behavior.

M3A is divided into:

* Phase M3A1 — Presentation Contracts and Item-Detail Read Composition;
* Phase M3A2 — Interaction Availability and Capability Gating;
* Phase M3A3 — Dedicated Action Command and Submission Boundary;
* Phase M3A4 — Item-Detail Interaction Shell and M3A Closure.

---

## M3A protected M2 baseline

M3A must preserve:

* Room schema version 52;
* existing canonical marker and area entities;
* existing M2 domain-state read models;
* existing M2 action-query contracts;
* existing dedicated action codecs and factories;
* existing local map writer;
* `RoomMapEventProcessor` as the sole map transaction owner;
* transactional membership enforcement;
* transactional ownership enforcement;
* transactional lifecycle and temporal enforcement;
* immutable append-only durable event history;
* immutable append-only dedicated action history;
* deterministic duplicate classification;
* deterministic pending-target recovery;
* confirmation-freshness semantics;
* acknowledgement independence from confirmation;
* mixed-version limitations;
* existing post-commit BLE and relay handling;
* existing marker and area creation behavior until M3D changes it explicitly.

M3A must not:

* add a Room migration;
* add a new persistence table;
* expose Room entities to UI;
* allow ViewModels, Activities, Fragments or Composables to call map DAOs directly;
* call canonical or action reducers from UI code;
* duplicate category, lifecycle or temporal semantic rules;
* implement a second map transaction owner;
* manually insert durable events or action rows;
* manually mutate confirmation or acknowledgement counts;
* derive current domain state by replaying action history in UI;
* change transport, relay or envelope formats;
* translate dedicated actions into legacy canonical UPD events.

---

# Phase M3A1 — Presentation Contracts and Item-Detail Read Composition

## Goal

Create UI-safe item-detail contracts and a read-composition boundary over existing canonical map data and M2 domain-state repositories.

M3A1 must provide enough read data for later interaction decisions without introducing interaction submission or production UI behavior yet.

## Application package boundary

* [x] Add a dedicated M3 application package outside Activity, Fragment, Compose and Room implementation packages.
* [x] Keep M3 application contracts independent from Android UI classes.
* [x] Keep M3 application contracts independent from Room annotations and entities.
* [x] Keep M3 application contracts independent from BLE and relay classes.
* [x] Reuse existing M2 domain models where they are already UI-safe.
* [x] Introduce new projection models only where canonical and domain data must be composed.
* [x] Do not create a parallel domain engine in the M3 package.
* [x] Do not move M2 semantic policy into presentation code.

## Stable map-item identity

* [x] Add one stable application identity for map-item lookup.
* [x] Include:

  * group ID;
  * item type;
  * item ID.
* [x] Require item type to remain explicit as marker or area.
* [x] Do not identify an item only by raw item ID.
* [x] Preserve marker/area isolation when both use the same raw ID.
* [x] Validate nonblank group ID and item ID at the application boundary.
* [x] Return a typed invalid-identity result instead of throwing during normal input handling.
* [x] Reuse the existing M2 identity only through an explicit adapter after retaining group scope in the M3 identity; the M2 identity alone does not satisfy this boundary.

## Canonical item projection

* [x] Define a UI-safe canonical item projection for marker and area detail.
* [x] Expose only fields needed by item detail, such as:

  * item identity;
  * normalized marker or area type;
  * canonical kind;
  * label or title;
  * owner-authored note where currently supported;
  * coordinates for marker presentation;
  * radius where applicable;
  * geometry summary for area presentation;
  * canonical deletion or availability status where exposed safely;
  * canonical expiry information where already available.
* [x] Do not expose canonical field clocks.
* [x] Do not expose Room row IDs.
* [x] Do not expose reducer ordering tuples.
* [x] Do not expose DAO entities.
* [x] Do not expose raw persistence values when typed values already exist.
* [x] Preserve currently readable legacy canonical content.
* [x] Do not reinterpret legacy confidence as domain verification.

## Domain-state projection

* [x] Reuse the accepted M2 M3-facing domain read model.
* [x] Expose:

  * normalized nullable category;
  * category origin where needed for safe presentation;
  * nullable creator information source;
  * nullable Situation lifecycle;
  * expiry;
  * owner-edit cutoff only if needed for owner-control presentation;
  * interaction cutoff;
  * temporal origin where needed for safe presentation;
  * confirmation actor count;
  * acknowledgement actor count.
* [x] Preserve unresolved category explicitly.
* [x] Preserve unresolved lifecycle explicitly.
* [x] Preserve unresolved temporal state explicitly.
* [x] Do not convert unknown persisted values to a guessed default.
* [x] Do not infer category again from canonical kind in M3 presentation code.
* [x] Do not infer lifecycle from action history.
* [x] Do not recalculate confirmation or acknowledgement aggregates.
* [x] Do not expose internal M2C materialization snapshots.

## Item-detail projection

* [x] Add one UI-safe `MapItemDetail` projection.
* [x] Compose canonical item content and M2 domain state by exact map-item identity.
* [x] Represent marker and area details through one generic outer contract.
* [x] Permit type-specific canonical content through sealed or explicitly discriminated presentation models.
* [x] Include a stable item identity in every successful result.
* [x] Keep domain category nullable or unresolved where the M2 read model cannot establish it.
* [x] Keep Situation lifecycle absent for Coordination items.
* [x] Do not attach a synthetic lifecycle to Coordination.
* [x] Do not expose pending or rejected actions as current domain state.
* [x] Do not include full activity history in the detail projection.
* [x] Allow only a minimal recent-activity summary if proven necessary for the M3A shell; M3A1 adds none.
* [x] Prefer deferring all timeline rows to M3B.
* [x] Do not persist the composed detail projection.

## Detail load result contract

* [x] Define a typed item-detail load result.
* [x] Distinguish:

  * found;
  * canonical item missing;
  * domain state missing;
  * unresolved legacy domain state;
  * invalid persisted canonical record;
  * invalid persisted domain-state record;
  * invalid input identity;
  * internal read failure.
* [x] Decide explicitly: valid canonical content with missing domain state returns renderable `CanonicalOnly(DOMAIN_STATE_MISSING)`.
* [x] Do not fail all valid canonical presentation merely because optional domain enrichment is missing.
* [x] Do not silently present an invalid domain row as a valid unresolved row.
* [x] Ensure one invalid marker or area does not corrupt unrelated item-detail loads.
* [x] Keep technical persistence details out of UI-facing error text.

## Read-composition service

* [x] Add a narrowly scoped detail-read use case or composition service.
* [x] Load canonical marker data only for marker identities.
* [x] Load canonical area data only for area identities.
* [x] Load domain state by exact:

  * group;
  * item type;
  * item ID.
* [x] Do not query both canonical tables and choose whichever returns first.
* [x] Do not issue action-history queries merely to derive current state.
* [x] Do not replay durable events.
* [x] Keep orchestration outside DAO implementations.
* [x] Permit future refresh through repeatable one-shot loading without coupling the contract to one UI framework.
* [ ] Define cancellation behavior for obsolete detail requests where coroutines or flows are used.
* [ ] Ensure repeated observation does not create duplicate repository collectors.

## Observation contract

* [x] Audit whether current canonical and domain repositories already expose observable streams; both are synchronous one-shot reads.
* [ ] Reuse existing observable contracts where available and appropriate.
* [x] If observation is unavailable, define a small application-level refresh contract without changing persistence (`LoadMapItemDetail.load` may be called again).
* [x] Do not add database triggers or new tables for observation.
* [x] Ensure accepted local changes can be reflected after repository refresh.
* [x] Do not emit synthetic optimistic aggregate state as persisted truth.
* [ ] Define behavior when the target is deleted while detail is open.
* [ ] Define behavior when domain state changes from active to resolved while detail is open.
* [ ] Define behavior when membership changes while detail is open, without making UI state authoritative.

## Suggested production contracts

* [x] `MapItemIdentity`
* [x] `MapItemCanonicalPresentation`
* [x] `MarkerCanonicalPresentation`
* [x] `AreaCanonicalPresentation`
* [x] `MapItemDetail`
* [x] `MapItemDetailLoadResult`
* [x] `LoadMapItemDetail`
* [ ] `ObserveMapItemDetail` where supported
* [x] `DefaultMapItemDetailComposer`
* [ ] `MapItemDetailReadDependencies`

Exact names may follow existing project conventions, but responsibilities must remain narrow and UI-safe.

## M3A1 tests

* [x] Verify marker identity includes group, item type and item ID.
* [x] Verify area identity includes group, item type and item ID.
* [x] Verify marker and area with the same raw ID remain isolated.
* [x] Verify blank identity inputs fail safely.
* [x] Verify marker canonical content maps to UI-safe detail.
* [x] Verify area canonical content maps to UI-safe detail.
* [x] Verify recognized Situation domain state maps correctly.
* [x] Verify recognized Coordination domain state maps correctly.
* [x] Verify Coordination lifecycle remains absent.
* [x] Verify unresolved legacy category remains unresolved.
* [x] Verify legacy creator source remains unspecified.
* [x] Verify domain confirmation count is reused rather than recalculated.
* [x] Verify acknowledgement count is reused rather than recalculated.
* [x] Verify missing optional domain enrichment does not corrupt valid canonical detail.
* [x] Verify invalid persisted domain state is distinguishable from missing state.
* [x] Verify invalid persisted canonical state fails safely.
* [x] Verify Room entities are not exposed through public M3A1 contracts.
* [x] Verify no action-history replay is used to construct detail.
* [x] Verify Room schema remains version 52.

## M3A1 non-goals

* [x] Do not implement interaction availability.
* [x] Do not construct dedicated action events.
* [x] Do not submit local actions.
* [x] Do not add dialogs, sheets or action buttons.
* [x] Do not implement full timeline history.
* [x] Do not implement Community Verification vocabulary.
* [x] Do not redesign map rendering.
* [x] Do not change marker or area creation flow.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3A1 acceptance

* [x] One stable marker/area application identity exists.
* [x] One UI-safe item-detail projection exists.
* [x] Canonical and domain state are composed without exposing Room entities.
* [x] Marker and area identities remain isolated.
* [x] Missing, unresolved and invalid domain states remain distinguishable.
* [x] M2 aggregate and lifecycle semantics are reused without recalculation.
* [x] No interaction submission or UI behavior has been implemented early.
* [x] Room schema remains version 52.
* [x] M3A1 is reviewed and accepted before interaction availability begins.

### M3A1 implementation evidence (2026-07-18)

* Application identity, UI-safe projections, typed results and one-shot composition:
  `android/app/src/main/java/app/peermesh/map/application/detail/`.
* App composition boundary:
  `android/app/src/main/java/app/peermesh/map/composition/MapItemDetailReadFactory.kt`.
* Exact typed canonical lookup, strict entity mapping and safe Polygon/MultiPolygon summaries:
  `android/data/src/main/java/app/peermesh/data/map/canonical/`.
* Application behavior tests:
  `MapItemIdentityM3A1Test` and `DefaultMapItemDetailComposerM3A1Test`.
* Data mapping/repository/schema tests:
  `MapItemCanonicalEntityMapperM3A1Test`, `RoomMapItemCanonicalReadRepositoryM3A1Test` and
  `MapItemM3A1SchemaContractInstrumentationTest`.
* Observation, interaction availability, submission, timeline and production UI remain intentionally unchecked/deferred.

### M3A1 external review conclusion

M3A1 externally reviewed and accepted.

The implementation provides stable typed map-item identity, exact canonical
marker/area lookup, strict UI-safe canonical projection, M2 domain-state
composition, typed missing/unresolved/invalid outcomes, and one-shot refresh
without introducing action-history replay, interaction submission, UI behavior,
persistence, migration, reducer, protocol, replay or transport changes.

Room remains version 52.

Non-blocking future hardening:
- preserve coroutine cancellation if the synchronous read boundary becomes suspend;
- document 3D GeoJSON position handling and area vertex-summary wording.

---

# Phase M3A2 — Interaction Availability and Capability Gating

## Goal

Define advisory presentation decisions for Situation and Coordination interactions using existing M2 read state, local participation context and available compatibility information.

M3A2 must decide what the UI should show or enable. It must not become the semantic authority for accepting an action.

## Supported interaction families

Situation interactions:

* Confirm;
* Add update;
* Mark no longer active.

Coordination interactions:

* Acknowledge;

* Add note.

* [x] Define one stable application-level interaction type for each supported action.

* [x] Keep Situation and Coordination interaction families explicitly separate.

* [x] Do not expose raw `MAP.ITEM.*` event names as UI interaction types.

* [x] Do not add marker-specific and area-specific copies of the same semantic interaction.

* [x] Preserve marker/area identity only as target context.

* [x] Do not add kind-specific actions such as “I’m here” or “I can help”.

* [x] Do not add owner canonical edit/delete controls to the dedicated-action family.

* [x] Keep canonical owner actions under the existing canonical writer flow.

## Availability model

* [x] Add an availability result for each supported interaction.
* [x] Distinguish:

  * visible and enabled;
  * visible but disabled;
  * hidden because incompatible;
  * unsupported locally;
  * compatibility-limited;
  * unavailable due to unresolved state.
* [x] Attach stable machine-readable availability reasons.
* [x] Allow multiple informational reasons where necessary, but define one primary blocking reason.
* [x] Keep localized user text outside the application availability model.
* [x] Do not reuse processor rejection strings directly as presentation copy.
* [x] Define deterministic priority when several blocking conditions apply.
* [x] Ensure the availability evaluator is pure where practical.
* [x] Avoid reading Room or transport state directly inside the evaluator.

## Category compatibility

* [x] Permit Confirm only for recognized Situation items.
* [x] Permit Add update only for recognized Situation items.
* [x] Permit Mark no longer active only for recognized Situation items.
* [x] Permit Acknowledge only for recognized Coordination items.
* [x] Permit Add note only for recognized Coordination items.
* [x] Reject or disable all dedicated interactions for unresolved category.
* [x] Do not infer category from the chosen action.
* [x] Do not infer category from labels or UI vocabulary.
* [x] Use only the category supplied by the accepted M2 domain read model.
* [x] Preserve safe legacy detail presentation even when interactions are unavailable.

## Lifecycle availability

* [x] Disable normal Situation interactions when the item is resolved according to M2 state.
* [x] Do not offer lifecycle reactivation.
* [x] Decide whether repeated no-longer-active submission should be hidden or disabled in the normal UI: hidden.
* [x] Keep the processor authoritative if a repeated resolution command is submitted through a stale UI.
* [x] Do not attach Situation lifecycle behavior to Coordination.
* [x] Do not derive lifecycle from recent history rows.
* [x] Define presentation behavior for unresolved Situation lifecycle: fail closed as unresolved.
* [x] Ensure resolved Situation presentation does not appear currently confirmable.
* [x] Keep canonical delete distinct from Situation resolution.

## Target availability

* [x] Disable interactions for deleted or tombstoned targets.
* [x] Disable interactions when canonical target data is missing.
* [x] Disable interactions when the target type conflicts with the canonical item.
* [x] Preserve safe read-only detail where possible.
* [x] Do not create placeholder targets.
* [x] Do not treat a missing domain-state row as permission to guess an action.
* [x] Do not use UI visibility to mutate pending-target state.

## Membership context

* [x] Add a read-only local participation or membership context dependency.
* [x] Distinguish:

  * current member;
  * not a member;
  * membership unknown;
  * membership unavailable.
* [x] Disable member-generated interactions when the local actor is known not to be a member.
* [x] Define safe UI behavior when membership is temporarily unknown: disable fail-closed.
* [x] Keep membership evaluation advisory; the processor transaction remains authoritative.
* [x] Do not cache membership as semantic authority.
* [x] Do not bypass the processor transaction membership recheck.
* [x] Handle membership changes through repeatable one-shot reevaluation without caching authority.
* [x] Ensure stale advisory state cannot bypass the existing processor semantic rejection path.

## Temporal availability

* [x] Evaluate item expiry for presentation.
* [x] Evaluate interaction cutoff for presentation.
* [x] Keep owner-edit cutoff separate from member interaction availability.
* [x] Do not block dedicated member interactions merely because owner-edit time ended.
* [x] Treat expiry and interaction cutoff as exclusive boundaries.
* [x] Use one application clock snapshot per evaluation.
* [x] Do not alter persisted event time or temporal semantics.
* [x] Define safe behavior for unresolved temporal state: fail closed.
* [x] Do not guess that an unresolved temporal state is permanently open.
* [x] Keep processor temporal evaluation authoritative at submission time.
* [x] Add boundary tests for exact expiry and interaction cutoff.

## Local feature support

* [x] Add an explicit local feature-support input where necessary.
* [x] Distinguish local code support from peer/network support.
* [x] Do not show an interaction that the current app build cannot construct safely.
* [x] Keep local support static or configuration-driven without adding persistence.
* [x] Do not query transport internals from UI controls.
* [x] Do not let a remote capability signal override local M2 semantic compatibility.

## Peer-capability audit

* [x] Audit current production source for any accepted peer-version or capability source.
* [x] Document exactly what capability information currently exists: no reliable runtime source.
* [x] Do not assume peer capability can be known merely because a peer is connected.
* [x] Do not infer dedicated-action support from transport availability alone.
* [x] Do not infer old-peer forwarding behavior without evidence.
* [x] Do not add a new wire capability-negotiation protocol in M3A.
* [x] If no reliable peer capability source exists, represent capability as unknown.
* [x] Avoid building a fake capability provider from volatile guesses.
* [x] Keep capability decisions replaceable by a later reviewed capability source.

## Capability summary model

* [x] Add a read-only capability summary for presentation.
* [x] Support states such as:

  * supported;
  * mixed or partially supported;
  * unknown;
  * unavailable.
* [x] Define what each state means using evidence available in the current app.
* [x] Avoid implying per-peer delivery certainty.
* [x] Avoid implying all group members run the same version.
* [x] Keep capability summary separate from semantic action availability.
* [x] Allow a semantically valid action to be presented with a compatibility warning.
* [x] Do not disguise actions as legacy contributions when capability is mixed.
* [x] Do not block canonical map reading because dedicated actions may be unsupported.

## Gating policy

* [x] Define product behavior for supported capability: enable without warning.
* [x] Define product behavior for mixed capability: enable with warning.
* [x] Define product behavior for unknown capability: enable with warning.
* [x] Define product behavior for unavailable capability: enable with warning.
* [x] Decide explicitly that mixed or unknown capability permits submission with an informational warning and no acknowledgement gate:

  * permits submission with an informational warning;
  * requires acknowledgment before submission;
  * disables submission.
* [x] Preserve local collaborative capability for mixed, unknown and unavailable peer capability.
* [x] Do not claim that an old peer will receive, forward or display an action.
* [x] Do not make capability warning a persistence outcome.
* [x] Do not conflate compatibility limitation with semantic rejection.

## Availability evaluation order

Use a stable evaluation order, for example:

1. valid target identity;
2. canonical target existence;
3. resolved domain category;
4. category/action compatibility;
5. target deletion state;
6. lifecycle state;
7. expiry and interaction cutoff;
8. local membership;
9. local feature support;
10. peer-capability presentation warning.

* [x] Document and implement the chosen order exactly as listed above.
* [x] Ensure primary blocking reasons are deterministic.
* [x] Ensure capability warnings do not hide stronger semantic blockers.
* [x] Ensure owner-edit cutoff is not treated as a dedicated-action blocker.
* [x] Keep evaluation free from Room writes.
* [x] Keep evaluation free from event construction.

## Suggested production contracts

* [x] `MapInteractionType`
* [x] `MapItemInteractionAvailability`
* [x] `MapInteractionAvailabilityState`
* [x] `MapInteractionAvailabilityReason`
* [x] `EvaluateMapItemInteractions`
* [x] `MapInteractionAvailabilityEvaluator`
* [x] `LocalMapParticipationContext` (implemented as `LocalMapParticipationProvider`)
* [x] `MapPeerCapabilityProvider`
* [x] `MapPeerCapabilitySummary`
* [x] `MapPeerCapabilityLevel`

Exact names may follow existing conventions.

### M3A2 production evidence

The capability audit found no accepted runtime source for peer app version or dedicated-action
support. BLE connection state, relay/route availability, recent activity and local build support
are not capability evidence. Production therefore uses explicit `UNKNOWN`, which permits a
semantically valid interaction with a separate informational warning and no acknowledgement gate.

Local participation reuses persisted removal rows as positive non-member evidence and matching
user-profile rows as positive current-member evidence. Missing evidence maps to `UNKNOWN`; read
failure maps to `UNAVAILABLE`. Both disable advisory controls, while `RoomMapEventProcessor`
continues to recheck membership transactionally. Resolved Situations keep Confirm and Add update
visible-disabled, hide repeated resolution, and never offer reactivation. Room remains version 52.

## M3A2 tests

* [x] Verify Situation exposes Confirm.
* [x] Verify Situation exposes Add update.
* [x] Verify active Situation exposes Mark no longer active.
* [x] Verify Situation does not expose Acknowledge.
* [x] Verify Situation does not expose Coordination note.
* [x] Verify Coordination exposes Acknowledge.
* [x] Verify Coordination exposes Add note.
* [x] Verify Coordination does not expose Confirm.
* [x] Verify Coordination does not expose Situation update.
* [x] Verify unresolved category disables all dedicated interactions safely.
* [x] Verify deleted target disables compatible interactions while incompatible controls remain hidden by higher priority.
* [x] Verify resolved Situation cannot appear confirmable.
* [x] Verify resolved Situation cannot appear updateable.
* [x] Verify lifecycle reactivation is never offered.
* [x] Verify Coordination lifecycle remains irrelevant when absent and fails safely when unexpectedly present.
* [x] Verify exact expiry boundary disables interactions.
* [x] Verify exact interaction cutoff disables interactions.
* [x] Verify owner-edit cutoff does not disable valid dedicated interactions.
* [x] Verify removed local member cannot interact.
* [x] Verify unknown membership uses the accepted safe presentation policy.
* [x] Verify supported capability produces no compatibility warning.
* [x] Verify mixed capability produces the defined warning.
* [x] Verify unknown capability produces the defined warning.
* [x] Verify capability warning does not override semantic rejection.
* [x] Verify availability contracts introduce no processor/submission dependency and remain advisory.
* [x] Verify no transport protocol change is introduced (change-scope audit).
* [x] Verify Room schema remains version 52 through the existing M3A1 regression contract.

## M3A2 non-goals

* [x] Do not submit actions.
* [x] Do not construct `EventCore`.
* [x] Do not change membership persistence.
* [x] Do not change temporal rules.
* [x] Do not add capability negotiation to BLE or relay.
* [x] Do not add per-peer delivery tracking.
* [x] Do not add user reputation.
* [x] Do not implement verification scoring.
* [x] Do not add UI dialogs yet beyond test fixtures.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3A2 acceptance

* [x] Every supported dedicated interaction has a stable presentation type.
* [x] Situation and Coordination availability is category-correct.
* [x] Deleted, resolved, expired and interaction-closed targets are represented safely.
* [x] Owner-edit cutoff remains separate from interaction cutoff.
* [x] Membership and temporal availability remain advisory.
* [x] Capability limitations are represented without inventing delivery certainty.
* [x] No compatibility translation or transport change is introduced.
* [x] Room schema remains version 52.
* [x] M3A2 is reviewed and accepted before submission integration begins.

---

# Phase M3A3 — Dedicated Action Command and Submission Boundary

## Goal

Provide one typed app-facing command path for dedicated map interactions that reuses M2 event construction, local writing, semantic processing and post-commit transport behavior.

M3A3 must make dedicated actions usable by application code without allowing UI code to construct raw payloads or bypass the M2 processor.

## Command model

Add typed application commands for:

* Confirm Situation;
* Add Situation update;
* Mark Situation no longer active;
* Acknowledge Coordination item;
* Add Coordination note.

Each command must contain only user intent and required application input.

* [x] Include exact target identity.
* [x] Include action-specific user input.
* [x] Include information source only where allowed.
* [x] Include action note only where allowed or required.
* [x] Include Situation update disposition only for Add update.
* [x] Do not include Room entities.
* [x] Do not include raw JSON or arbitrary payload maps.
* [x] Do not include action ID supplied by arbitrary UI input.
* [x] Do not include actor DID supplied by arbitrary form input.
* [x] Do not include Lamport time supplied by UI.
* [x] Do not include transport source metadata.
* [x] Do not include canonical marker or area snapshots.
* [x] Keep marker and area targeting generic through explicit item type.

## Command-specific contracts

### Confirm Situation

* [x] Require a Situation target through the authoritative M2 category policy.
* [x] Allow optional information source according to M2 policy.
* [x] Allow optional action note.
* [x] Do not allow lifecycle target.
* [x] Do not allow update disposition.
* [x] Do not allow canonical coordinates or geometry.
* [x] Preserve the rule that area confirmation concerns the reported condition, not exact geometry.

### Add Situation update

* [x] Require a Situation target through the authoritative M2 category policy.
* [x] Require nonblank action content.
* [x] Require a recognized update disposition.
* [x] Allow optional information source according to M2 policy.
* [x] Do not allow lifecycle target.
* [x] Do not permit canonical mutation fields.

### Mark Situation no longer active

* [x] Require a Situation target through the authoritative M2 category policy.
* [x] Use only the accepted no-longer-active lifecycle target.
* [x] Allow optional note where supported by the M2 wire contract.
* [x] Allow optional information source where supported.
* [x] Do not support lifecycle reactivation.
* [x] Keep resolution distinct from canonical deletion.

### Acknowledge Coordination item

* [x] Require a Coordination target through the authoritative M2 category policy.
* [x] Do not accept information source.
* [x] Do not accept action note.
* [x] Do not accept lifecycle.
* [x] Do not accept update disposition.
* [x] Keep acknowledgement unweighted.

### Add Coordination note

* [x] Require a Coordination target through the authoritative M2 category policy.
* [x] Require nonblank note content.
* [x] Do not accept information source.
* [x] Do not accept lifecycle.
* [x] Do not accept update disposition.
* [x] Do not reinterpret note as verification.

## Input validation boundary

* [x] Add lightweight command-input validation before event construction.
* [x] Validate nonblank required text.
* [x] Audit text length according to existing accepted UI or wire limits; no dedicated-action limit exists, so none was invented.
* [x] Validate command shape.
* [x] Validate target identity shape.
* [x] Validate permitted enum values.
* [x] Return typed input failures.
* [x] Do not duplicate database-dependent semantics.
* [x] Do not treat input validation as membership authority.
* [x] Do not treat input validation as lifecycle authority.
* [x] Do not treat input validation as temporal authority.
* [x] Do not duplicate M2 structural event validation.
* [x] Ensure final factory output still passes accepted M2 validation.

## Actor and ordering context

* [x] Resolve the local actor through an accepted application identity provider.
* [x] Do not accept arbitrary actor identity from presentation input.
* [x] Use the accepted M2 event ID generation path.
* [x] Use the accepted timestamp source.
* [x] Use the accepted Lamport-time source.
* [x] Preserve `action_id == event_id`.
* [x] Preserve original generated event time through persistence and transport.
* [x] Do not generate a second action ID.
* [x] Do not regenerate identity after post-commit transport failure.
* [x] Do not use UI invocation order as semantic ordering.

## Event factory adapter

* [x] Reuse accepted M2 dedicated action factories or codecs.
* [x] Add only a narrow application adapter if existing factories are inconvenient for UI-facing commands.
* [x] Map each command to exactly one accepted event type.
* [x] Map stable application enums explicitly to M2 domain values.
* [x] Do not duplicate payload serialization logic.
* [x] Do not construct arbitrary payload maps in ViewModels.
* [x] Ensure factory output contains exact target item ID and item type.
* [x] Ensure factory output contains no canonical mutation fields.
* [x] Ensure factory output passes `EventValidator`.
* [x] Keep factory adapter independent from Room.
* [x] Keep factory adapter independent from BLE and relay.

## Submission use case

* [x] Add one app-facing `SubmitMapInteraction` boundary.
* [x] Accept a typed interaction command.
* [x] Perform command-input validation.
* [x] Resolve trusted local actor and ordering context.
* [x] Construct the exact M2 dedicated event.
* [x] Submit the event through the accepted local map writer.
* [x] Do not call `RoomMapEventProcessor` directly from UI.
* [x] Do not call map DAOs.
* [x] Do not open a Room transaction.
* [x] Do not directly enqueue BLE or relay traffic.
* [x] Preserve existing post-commit transport ownership.
* [x] Ensure all five dedicated action families share the same submission architecture.

## Processing-result mapping

Define a UI-safe submission result distinguishing:

* accepted and persisted;

* exact duplicate;

* semantic rejection;

* invalid command input;

* compatibility warning or limitation;

* invalid local/application state;

* persistence failure;

* accepted locally with post-commit delivery warning.

* [x] Map accepted newly inserted events to accepted.

* [x] Map exact duplicates to a non-destructive idempotent outcome.

* [x] Do not report exact duplicate as a newly created interaction.

* [x] Map conflicting duplicates to a safe failure outcome.

* [x] Map semantic rejection to a user-correctable or state-refresh outcome.

* [x] Preserve stable rejection classification for presentation mapping.

* [x] Map invalid persisted state to a generic safe application failure.

* [x] Map persistence failure distinctly from semantic rejection.

* [x] Map post-commit transport failure without implying local rollback.

* [x] Do not expose DAO, reducer or SQLite terminology.

* [x] Do not expose Room entities.

* [x] Do not expose internal replay results.

## Stable rejection mapping

* [x] Audit stable M2 processing rejection reasons.
* [x] Define application-level rejection categories.
* [x] Cover at least:

  * not a current member;
  * target missing;
  * target type conflict;
  * target deleted;
  * unresolved category;
  * action incompatible with category;
  * target expired;
  * interaction window closed;
  * target resolved;
  * lifecycle reactivation forbidden;
  * conflicting duplicate;
  * invalid persisted state.
* [x] Preserve unknown future reasons as a safe generic rejection.
* [x] Do not map unknown reasons to success.
* [x] Keep localized strings in presentation resources, not domain/application results.
* [x] Allow UI to request detail refresh after stale-state rejection.

## Compatibility warning integration

* [ ] Accept an optional advisory capability summary from M3A2 where required.
* [x] Keep capability warning separate from processor result.
* [x] Do not claim that compatibility warning caused semantic rejection.
* [x] Do not alter event payload based on old-peer capability.
* [x] Do not translate the action into legacy UPD.
* [x] Do not generate multiple semantic events for compatibility.
* [x] Record no new compatibility persistence state.
* [x] Ensure local acceptance remains determined by M2 processor semantics.

## Concurrency and stale UI behavior

* [x] Assume item state may change after availability evaluation.
* [x] Handle membership removal between button display and submission.
* [x] Handle expiry between button display and submission.
* [x] Handle interaction cutoff between button display and submission.
* [x] Handle Situation resolution between button display and submission.
* [x] Handle canonical deletion between button display and submission.
* [x] Return clean semantic rejection outcomes.
* [x] Do not retry rejected commands automatically.
* [x] Do not mutate local presentation aggregates optimistically as authoritative state.
* [ ] Trigger repository refresh after accepted submission.
* [ ] Trigger repository refresh after stale-state semantic rejection where useful.

## Submission idempotency

* [x] Ensure accidental UI re-delivery of the same generated event does not create duplicate history.
* [ ] Avoid generating multiple event IDs for one in-flight submission due solely to recomposition.
* [ ] Define one submission ownership boundary in the ViewModel or controller.
* [ ] Disable or guard repeated submission while one command is in flight where appropriate.
* [x] Do not rely on UI guarding as the persistent duplicate policy.
* [x] Keep the M2 processor authoritative for exact versus conflicting duplicate classification.
* [x] Preserve accepted event identity through post-commit retry.
* [x] Do not alter counts optimistically on repeated clicks.

## Suggested production contracts

* [x] `MapInteractionCommand`
* [x] `ConfirmSituationCommand`
* [x] `AddSituationUpdateCommand`
* [x] `ResolveSituationCommand`
* [x] `AcknowledgeCoordinationCommand`
* [x] `AddCoordinationNoteCommand`
* [x] `MapInteractionInputValidator`
* [x] `MapInteractionEventFactoryAdapter`
* [x] `SubmitMapInteraction`
* [x] `MapInteractionSubmissionResult`
* [x] `MapInteractionRejection`
* [x] `LocalMapActorProvider` equivalent via `MapEventConstructionContextProvider`
* [x] `MapEventOrderingContextProvider` equivalent via `MapEventConstructionContextProvider`

Exact names may follow the existing project architecture.

## M3A3 tests

* [x] Verify Confirm command maps to `MAP.ITEM.CONFIRM`.
* [x] Verify Situation update command maps to `MAP.ITEM.SITUATION.UPDATE`.
* [x] Verify resolution command maps to `MAP.ITEM.SITUATION.STATUS`.
* [x] Verify Acknowledge command maps to `MAP.ITEM.ACKNOWLEDGE`.
* [x] Verify Coordination note command maps to `MAP.ITEM.NOTE`.
* [x] Verify marker and area target identity are preserved.
* [x] Verify action ID equals event ID.
* [x] Verify actor DID comes from trusted local identity context.
* [x] Verify timestamp and Lamport values come from accepted sources.
* [x] Verify UI cannot supply arbitrary actor or ordering identity.
* [x] Verify required Situation update content.
* [x] Verify required Coordination note content.
* [x] Verify forbidden fields cannot enter Acknowledge.
* [x] Verify forbidden fields cannot enter Coordination note.
* [x] Verify lifecycle reactivation command cannot be constructed.
* [x] Verify every constructed event passes M2 structural validation.
* [x] Verify submission uses the accepted local writer.
* [x] Verify submission does not call DAOs or reducers directly.
* [x] Verify accepted result maps correctly.
* [x] Verify exact duplicate maps idempotently.
* [x] Verify conflicting duplicate maps safely.
* [x] Verify removed-member rejection maps correctly.
* [x] Verify expired-target rejection maps correctly.
* [x] Verify interaction-cutoff rejection maps correctly.
* [x] Verify resolved-target rejection maps correctly.
* [x] Verify deleted-target rejection maps correctly.
* [x] Verify unresolved-category rejection maps correctly.
* [x] Verify persistence failure remains distinct from semantic rejection.
* [x] Verify post-commit transport failure does not become local persistence failure.
* [ ] Verify accepted interaction triggers detail refresh.
* [ ] Verify stale-state rejection can trigger detail refresh.
* [x] Verify no Room schema change.
* [x] Verify Room schema remains version 52.

## M3A3 non-goals

* [x] Do not implement final dialogs or sheets.
* [x] Do not implement the full item-detail screen.
* [x] Do not implement activity timeline.
* [x] Do not calculate verification summaries.
* [x] Do not alter transport enqueue logic.
* [x] Do not add retry workers.
* [x] Do not add per-peer delivery state.
* [x] Do not change action codecs.
* [x] Do not change semantic rejection rules.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3A3 acceptance

* [x] All five dedicated interaction commands have typed application contracts.
* [x] Commands contain user intent rather than raw wire payloads.
* [x] M2 event factories and validation are reused.
* [x] Every command is submitted through the accepted local map writer.
* [x] UI cannot bypass `RoomMapEventProcessor`.
* [x] Submission results distinguish acceptance, duplicate, semantic rejection, persistence failure and post-commit delivery warning.
* [x] Stale UI state is handled by authoritative processor rejection.
* [x] No transport, reducer, persistence or schema semantics have changed.
* [x] Room schema remains version 52.
* [x] M3A3 is reviewed and accepted before production interaction UI begins.

---

# Phase M3A4 — Item-Detail Interaction Shell and M3A Closure

## Goal

Integrate M3A1 read composition, M3A2 advisory availability and M3A3 submission into a minimal production item-detail surface.

M3A4 must establish the interaction shell required by later M3 phases without completing timeline, Community Verification presentation or normal map redesign.

## Existing UI integration audit

* [x] Inspect the current marker and area selection flow.
* [x] Identify the current item-detail, popup, dialog or bottom-sheet entry point.
* [x] Identify current owner edit and delete controls.
* [x] Identify current Confidence and Contribute presentation.
* [x] Identify current marker and area drafting dependencies.
* [x] Identify current feedback mechanisms for persistence and transport failures.
* [x] Document the minimum insertion point for the M3 detail shell.
* [x] Avoid rewriting `GroupMapActivity` beyond the accepted integration boundary.
* [x] Avoid moving unrelated map orchestration into new M3 components.
* [x] Preserve current canonical creation behavior.

## Detail shell navigation

* [x] Allow opening detail for a marker.
* [x] Allow opening detail for an area.
* [x] Pass exact group, item type and item ID.
* [x] Do not pass Room entities through navigation arguments.
* [x] Do not serialize full canonical or domain state as navigation truth.
* [x] Reload detail from repositories.
* [x] Handle missing target after navigation.
* [x] Handle target deletion while detail is open.
* [x] Preserve marker/area same-ID isolation.
* [x] Preserve current map camera and selection behavior unless a minimal correction is required.

## Detail shell state model

Define a UI state capable of representing:

* initial loading;

* loaded item;

* loaded canonical item with unavailable domain enrichment;

* unresolved legacy item;

* target deleted or no longer available;

* invalid item data;

* read failure;

* interaction submission in progress;

* accepted interaction;

* semantic rejection;

* post-commit delivery warning.

* [x] Keep canonical data and domain enrichment distinguishable internally.

* [x] Keep interaction availability as derived presentation state.

* [x] Keep submission feedback transient where appropriate.

* [x] Do not persist UI state as domain state.

* [x] Do not expose raw M2 processing results directly to views.

* [x] Ensure process recreation can reload from persisted state where supported.

* [x] Ensure duplicate collectors do not accumulate when reopening detail.

## Basic canonical presentation

* [x] Render marker label, kind and basic location data.
* [x] Render area label, kind and safe geometry summary.
* [x] Render owner-authored note where currently supported.
* [x] Preserve long-label safety.
* [x] Avoid displaying raw GeoJSON as normal user content.
* [x] Defer complete geometry-presentation cleanup to M3D where necessary.
* [x] Preserve legacy canonical fields.
* [x] Do not reinterpret legacy confidence.

## Basic domain presentation

* [x] Render Situation or Coordination category when recognized.
* [x] Render a safe unresolved state for unknown legacy category.
* [x] Render active or no-longer-active Situation lifecycle.
* [x] Do not render lifecycle for Coordination.
* [x] Render basic expiration or availability information where clear.
* [x] Render basic confirmation count for Situation without final Community Verification wording.
* [x] Render basic acknowledgement count for Coordination without calling it verification.
* [x] Avoid trust percentages or confidence scores.
* [x] Avoid presenting historical confirmations as currently fresh when M2 count is zero.
* [x] Defer full freshness explanation to M3C.

## Interaction entry points

Situation:

* [x] Show Confirm entry point when advised by M3A2.
* [x] Show Add update entry point when advised by M3A2.
* [x] Show Mark no longer active entry point when advised by M3A2.

Coordination:

* [x] Show Acknowledge entry point when advised by M3A2.
* [x] Show Add note entry point when advised by M3A2.

General:

* [x] Hide category-incompatible controls.
* [x] Disable or hide blocked controls according to accepted presentation policy.
* [x] Show a clear reason for important disabled states where appropriate.
* [x] Preserve the processor as final authority.
* [x] Do not expose event-type names.
* [x] Do not add kind-specific interactions.
* [x] Do not add moderator controls.
* [x] Do not add per-peer delivery status.

## Confirm interaction surface

* [x] Provide optional source input only where allowed.
* [x] Provide optional note input.
* [x] Explain that confirming an area concerns the reported condition, not exact geometry.
* [x] Prevent empty optional input from being sent as invalid semantic content.
* [x] Submit one Confirm command.
* [x] Prevent duplicate in-flight submission.
* [x] Display accepted, rejected and delivery-warning outcomes.
* [x] Refresh detail after acceptance.

## Situation update surface

* [x] Require action content.
* [x] Require one accepted update disposition:

  * still active;
  * situation changed.
* [x] Allow optional information source where supported.
* [x] Validate required content before submission.
* [x] Submit one Add Situation update command.
* [x] Display stale-state rejection safely.
* [x] Refresh detail after acceptance.
* [x] Do not implement full timeline rendering.

## Situation resolution surface

* [x] Clearly distinguish no-longer-active from delete.
* [x] Allow only the accepted no-longer-active lifecycle target.
* [x] Allow optional note/source only where supported by M2 contract.
* [x] Require explicit user confirmation before submission where appropriate.
* [x] Do not offer reactivation.
* [x] Refresh detail after acceptance.
* [x] Ensure resolved presentation disables normal Situation interactions.

## Coordination acknowledgement surface

* [x] Provide a simple acknowledgement action.
* [x] Do not request information source.
* [x] Do not request confidence.
* [x] Do not request lifecycle.
* [x] Do not request a note in the acknowledgement command.
* [x] Submit one Acknowledge command.
* [x] Refresh acknowledgement count from persisted state.
* [x] Do not increment count optimistically as authoritative truth.

## Coordination note surface

* [x] Require nonblank note content.
* [x] Do not request information source.
* [x] Do not request lifecycle.
* [x] Do not request update disposition.
* [x] Submit one Add Coordination note command.
* [x] Refresh detail after acceptance where a recent-note preview is included.
* [x] Defer full note history to M3B.

## Capability warning presentation

* [x] Present mixed-version limitations before or during interaction according to the accepted M3A2 policy.
* [x] Do not block canonical detail viewing.
* [x] Do not claim peer-specific delivery.
* [x] Do not claim all peers will display the action.
* [x] Distinguish compatibility warning from semantic rejection.
* [x] Distinguish compatibility warning from local persistence failure.
* [x] Keep language understandable and non-technical.
* [x] Do not expose relay or envelope terminology.
* [x] Do not add protocol negotiation.

## Submission feedback

* [x] Show accepted local persistence accurately.
* [x] Show exact duplicate as already recorded or equivalent idempotent wording.
* [x] Show semantic rejection with actionable state-aware feedback.
* [x] Show membership rejection.
* [x] Show expired or interaction-closed state.
* [x] Show resolved or deleted state.
* [x] Show invalid input without invoking submission.
* [x] Show persistence failure distinctly.
* [x] Show post-commit transport warning without claiming local failure.
* [x] Do not display reducer, DAO, SQLite or transaction terminology.
* [x] Refresh detail after rejection caused by stale state where useful.
* [x] Avoid indefinite success banners after state has refreshed.

## Refresh and state synchronization

* [x] Refresh canonical and domain detail after accepted interaction.
* [x] Refresh after semantic rejection that indicates stale state.
* [x] Avoid manual aggregate mutation.
* [x] Avoid manual lifecycle mutation.
* [x] Avoid inserting synthetic history rows.
* [x] Reload persisted state as the source of truth.
* [x] Ensure accepted post-commit transport warning still refreshes persisted detail.
* [x] Ensure exact duplicate does not create a second visible effect.
* [x] Handle remote updates arriving while detail is open.
* [x] Handle item resolution arriving remotely while an interaction form is open.
* [x] Define whether the form closes, refreshes or shows rejection after state change.

## Owner canonical controls

* [x] Preserve existing owner edit behavior.
* [x] Preserve existing owner delete behavior.
* [x] Keep canonical edits routed through the existing local writer.
* [x] Do not merge owner edit with dedicated Situation update.
* [x] Do not merge canonical delete with no-longer-active.
* [x] Keep owner-edit cutoff distinct from interaction cutoff.
* [x] Preserve current user feedback unless an M3A consistency fix is required.
* [x] Do not redesign owner editing broadly in M3A.

## Legacy item behavior

* [x] Show recognized legacy Situation items safely.
* [x] Show recognized legacy Coordination items safely.
* [x] Permit category-correct actions only where M2 domain state recognizes the category.
* [x] Keep legacy creator information source unspecified.
* [x] Keep legacy confidence presentation separate where still retained.
* [x] Keep legacy contributions separate.
* [x] Show unresolved legacy items in read-only compatibility mode.
* [x] Do not offer dedicated actions for unresolved category.
* [x] Do not migrate or rewrite legacy history.
* [x] Do not imply legacy votes are confirmations.

## Current UI compatibility

* [x] Preserve current marker creation.
* [x] Preserve current area creation.
* [x] Preserve marker drafting.
* [x] Preserve area drafting.
* [x] Preserve existing map selection behavior unless explicitly changed.
* [x] Preserve canonical owner edit and delete.
* [x] Avoid removing existing Confidence/Contribute UI globally in M3A.
* [x] Prevent duplicate legacy and M3 action controls from claiming the same semantics.
* [x] Document any temporary coexistence behavior.
* [x] Keep complete terminology migration assigned to M3D/M3F.

## Dependency wiring

* [x] Construct M3A use cases at the existing application composition boundary.
* [x] Reuse the accepted local writer instance.
* [x] Reuse accepted domain-state and canonical repositories.
* [x] Reuse the capability provider defined by M3A2.
* [x] Do not construct DAOs in the Activity, Fragment or ViewModel.
* [x] Do not construct reducers in UI.
* [x] Do not construct a second `RoomMapEventProcessor`.
* [x] Avoid expanding a global service locator.
* [x] Keep test doubles available at application interfaces.
* [x] Preserve existing non-map dependency wiring.

## M3A4 automated tests

### Detail loading

* [x] Verify marker detail opens by exact identity.
* [x] Verify area detail opens by exact identity.
* [x] Verify marker and area with the same raw ID remain isolated.
* [x] Verify missing item presentation.
* [x] Verify deleted item presentation.
* [x] Verify unresolved legacy item presentation.
* [x] Verify invalid domain enrichment does not silently become valid state.

### Interaction visibility

* [x] Verify active Situation controls.
* [x] Verify resolved Situation controls are blocked.
* [x] Verify Coordination controls.
* [x] Verify category-incompatible controls are absent.
* [x] Verify unresolved category is read-only.
* [x] Verify expired item controls are blocked.
* [x] Verify interaction-closed controls are blocked.
* [x] Verify owner-edit cutoff does not hide valid member interactions.
* [x] Verify removed-member controls are blocked or rejected safely.
* [x] Verify capability warning presentation.

### Submission integration

* [x] Verify Confirm submits through `SubmitMapInteraction`.
* [x] Verify Situation update submits through `SubmitMapInteraction`.
* [x] Verify resolution submits through `SubmitMapInteraction`.
* [x] Verify Acknowledge submits through `SubmitMapInteraction`.
* [x] Verify Coordination note submits through `SubmitMapInteraction`.
* [x] Verify UI does not construct raw event payloads.
* [x] Verify UI does not call DAOs.
* [x] Verify UI does not call reducers.
* [x] Verify duplicate in-flight submission is guarded.
* [x] Verify accepted interaction refreshes persisted detail.
* [x] Verify exact duplicate does not create duplicate visible effects.
* [x] Verify semantic rejection refreshes stale detail where applicable.
* [x] Verify post-commit transport warning retains accepted local state.

### Regression

* [x] Verify current marker creation compiles and remains available.
* [x] Verify current area creation compiles and remains available.
* [x] Verify current owner edit remains available.
* [x] Verify current owner delete remains available.
* [x] Verify no full timeline has been introduced.
* [x] Verify no Community Verification scoring has been introduced.
* [x] Verify no normal map redesign has been introduced.
* [x] Verify no BLE or relay protocol change.
* [x] Verify Room schema remains version 52.



* [ ] Open marker detail from the map.
* [ ] Open area detail from the map.
* [ ] Confirm an active Situation marker.
* [ ] Confirm an active Situation area and verify boundary wording.
* [ ] Add a still-active Situation update.
* [ ] Add a situation-changed update.
* [ ] Mark a Situation no longer active.
* [ ] Verify a resolved Situation no longer offers normal interactions.
* [ ] Acknowledge a Coordination item.
* [ ] Add a Coordination note.
* [ ] Verify unresolved legacy items remain safe and read-only.
* [ ] Verify accepted local state remains visible after transport enqueue failure.
* [ ] Verify stale enabled controls produce clean rejection after remote state change.
* [ ] Verify current marker drafting remains unchanged.
* [ ] Verify current area drafting remains unchanged.
* [ ] Verify current canonical owner edit/delete feedback remains usable.

## M3A4 non-goals

* [x] Do not implement full activity timeline.
* [x] Do not implement activity pagination.
* [x] Do not implement full Community Verification vocabulary.
* [x] Do not expose confirmer identity lists.
* [x] Do not add global activity feeds.
* [x] Do not redesign marker icons or area styling.
* [x] Do not redesign clustering.
* [x] Do not replace the map creation toolbar.
* [x] Do not remove legacy presentation globally.
* [x] Do not add moderation.
* [x] Do not add reputation or trust scoring.
* [x] Do not add per-peer delivery status.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3A4 acceptance

* [x] Marker and area detail can be opened through exact typed identity.
* [x] Basic canonical and domain state render safely.
* [x] Situation and Coordination interaction entry points are category-correct.
* [x] All five dedicated interaction families are usable through typed M3 commands.
* [x] All writes use the accepted M2 local writer and processor path.
* [x] UI availability remains advisory.
* [x] Stale-state rejection is handled without partial or synthetic state.
* [x] Accepted local persistence remains visible after post-commit transport warning.
* [x] Recognized legacy items remain compatible.
* [x] Unresolved legacy items remain safe and read-only.
* [x] Existing marker and area creation, drafting, edit and delete behavior remains available.
* [x] No timeline, Community Verification redesign or normal map redesign has been implemented prematurely.
* [x] Room schema remains version 52.
* [x] M3A4 is reviewed and accepted before overall M3A acceptance.

---

# M3A cross-phase contract audit

## Read-path contract

* [x] UI reads canonical item data through accepted repository or query seams.
* [x] UI reads M2 domain state through the M3-facing domain repository.
* [x] UI does not expose Room entities.
* [x] UI does not replay durable events.
* [x] UI does not derive aggregates from action history.
* [x] UI does not infer category independently.
* [x] Marker and area identity always includes item type.

## Write-path contract

* [x] UI submits typed interaction commands.
* [x] Commands reuse M2 factories and codecs.
* [x] Commands use the accepted local writer.
* [x] `RoomMapEventProcessor` remains the sole transaction owner.
* [x] UI never inserts durable events.
* [x] UI never inserts action rows.
* [x] UI never updates domain-state aggregates.
* [x] UI never calls reducers.
* [x] Post-commit transport remains outside the Room transaction.

## Authority contract

* [x] M3A availability is advisory.
* [x] Processor membership checks remain authoritative.
* [x] Processor category compatibility remains authoritative.
* [x] Processor lifecycle checks remain authoritative.
* [x] Processor temporal checks remain authoritative.
* [x] Processor duplicate classification remains authoritative.
* [x] Processor persistence outcomes remain authoritative.
* [x] Capability warnings do not alter semantic acceptance.

## Compatibility contract

* [x] Dedicated actions are not translated into legacy UPD.
* [x] Legacy contributions are not shown as dedicated actions.
* [x] Legacy confidence is not shown as creator source.
* [x] Mixed-version limitations are visible without claiming delivery certainty.
* [x] Unknown capability remains explicit.
* [x] No BLE, relay or envelope protocol change is introduced.
* [x] Room schema remains version 52.

---

# M3A overall non-goals

* [x] Do not implement M3B timeline and pagination.
* [x] Do not implement M3C complete Community Verification presentation.
* [x] Do not implement M3D map rendering redesign.
* [x] Do not implement M3E performance caching or delivery-state systems.
* [x] Do not implement M3F global terminology migration.
* [x] Do not add a Room migration.
* [x] Do not add new tables.
* [x] Do not change M2 reducers.
* [x] Do not change duplicate classification.
* [x] Do not change action ordering.
* [x] Do not change earliest-resolution behavior.
* [x] Do not change pending-target replay.
* [x] Do not change BLE or relay protocols.
* [x] Do not add orphan retention or pruning.
* [x] Do not add moderation.
* [x] Do not add reputation or trust scoring.
* [x] Do not add per-peer delivery tracking.
* [x] Do not add kind-specific interactions.

---

# M3A overall acceptance

* [x] M3A1 presentation and item-detail read composition is accepted.
* [x] M3A2 interaction availability and capability gating is accepted.
* [x] M3A3 dedicated-action command and submission boundary is accepted.
* [x] M3A4 item-detail interaction shell is accepted.
* [x] One clean M3 application boundary exists.
* [x] Marker and area item identities remain explicitly typed.
* [x] Canonical and domain detail are composed without exposing Room entities.
* [x] Situation and Coordination interactions are category-correct.
* [x] Availability remains advisory rather than authoritative.
* [x] Capability limitations are represented honestly.
* [x] Every dedicated interaction uses accepted M2 event construction.
* [x] Every local action uses the accepted local writer.
* [x] `RoomMapEventProcessor` remains the sole Room transaction owner.
* [x] Semantic rejection leaves no synthetic UI domain state.
* [x] Accepted local state survives post-commit transport warning.
* [x] Legacy and new semantics remain separate.
* [x] Current marker and area creation, drafting, owner edit and delete remain available.
* [x] No full timeline, Community Verification presentation or normal map redesign has been implemented early.
* [x] No protocol, reducer, replay, persistence or transport semantics have changed.
* [x] Room schema remains version 52.
* [x] No blocking findings remain.
* [x] M3A is reviewed and accepted before Phase M3B begins.

---

# Phase M3B — Item Activity Timeline

## Goal

Expose deterministic, paginated and human-readable dedicated-action history for one exact map item by reusing the accepted M2 applied-action history query.

M3B must allow users to understand what collaborative actions have occurred for a Situation or Coordination item without treating historical activity as current domain state.

M3B must:

* reuse the accepted M2 applied-action history repository;
* preserve exact item identity:

  * group ID;
  * item type;
  * item ID;
* preserve deterministic newest-first ordering;
* preserve the complete M2 keyset cursor;
* expose only applied dedicated actions in the normal timeline;
* keep current state and historical activity structurally separate;
* keep legacy votes and contributions semantically separate;
* integrate the timeline into the accepted M3A detail shell;
* preserve Room schema version 52.

M3B must not:

* rebuild history from the durable event log;
* replay events in application or UI code;
* query Room DAOs from Activity, Fragment, ViewModel, controller or Composable code;
* expose Room entities or M2 ordering internals to UI;
* derive current confirmation count from timeline rows;
* derive current acknowledgement count from timeline rows;
* derive lifecycle, category, freshness or interaction availability from timeline rows;
* expose pending or rejected actions in the normal community timeline;
* merge dedicated actions with legacy votes or contributions;
* create optimistic synthetic timeline rows;
* add persistence, tables or migrations;
* change protocol, reducers, replay, processor, transport or relay behavior.

M3B is divided into:

* Phase M3B1 — Timeline Projection and Vocabulary;
* Phase M3B2 — Keyset Pagination and Refresh;
* Phase M3B3 — Timeline UI and History Coexistence.

---

# Phase M3B1 — Timeline Projection and Vocabulary

## Goal

Create a UI-safe application projection for one page of applied dedicated-action history.

M3B1 must define how accepted M2 action-history rows are represented to presentation code without introducing pagination state management or production timeline UI yet.

## Application package boundary

* [x] Add a dedicated M3B application package outside:

  * Android Activity packages;
  * Fragment packages;
  * Compose packages;
  * Room implementation packages.
* [x] Keep timeline application contracts independent from Android UI classes.
* [x] Keep timeline application contracts independent from Room annotations and entities.
* [x] Keep timeline application contracts independent from BLE, relay and transport classes.
* [x] Reuse accepted M2 typed history models only behind an explicit application adapter.
* [x] Do not expose M2 data-layer page, cursor or ordering contracts directly to presentation code.
* [x] Do not create a second history engine.
* [x] Do not create a parallel action materialization model.
* [x] Do not duplicate M2 action semantics.

## Exact map-item identity

* [x] Reuse the accepted M3A `MapItemIdentity`.
* [x] Require:

  * group ID;
  * item type;
  * item ID.
* [x] Preserve explicit marker or area type.
* [x] Preserve marker/area isolation when both use the same raw item ID.
* [x] Translate M3 identity to the accepted M2 query identity through one explicit adapter.
* [x] Do not query history by raw item ID alone.
* [x] Return a typed invalid-identity result for malformed application input.
* [x] Do not throw for normal invalid identity handling.

## UI-safe activity entry

* [x] Add one UI-safe `MapItemActivityEntry` projection.
* [x] Include:

  * stable entry identity;
  * semantic activity type;
  * actor presentation value;
  * action timestamp;
  * optional information source;
  * optional note or action content;
  * optional Situation update disposition;
  * optional lifecycle meaning.
* [x] Use accepted action ID or event ID as stable entry identity.
* [x] Preserve `action_id == event_id` without exposing that protocol invariant to UI.
* [x] Do not use timestamp, list index or note hash as stable row identity.
* [x] Do not expose:

  * Lamport time;
  * reducer ordering tuples;
  * event fingerprints;
  * materialization state;
  * rejection reason;
  * Room row IDs;
  * DAO entities;
  * raw persistence action names.
* [x] Do not persist the activity projection.

## Timeline activity vocabulary

Define stable application-level activity types for:

### Situation

* [x] Situation confirmed.
* [x] Situation update added.
* [x] Situation marked no longer active.

### Coordination

* [x] Coordination item acknowledged.
* [x] Coordination note added.

Requirements:

* [x] Map each accepted M2 dedicated action type to exactly one presentation activity type.
* [x] Keep Situation and Coordination activity families explicitly separate.
* [x] Do not expose raw `MAP.ITEM.*` event names.
* [x] Do not expose raw persistence action strings.
* [x] Do not call Confirm a vote.
* [x] Do not call Acknowledge verification.
* [x] Do not call Coordination notes confirmations.
* [x] Do not call no-longer-active deletion.
* [x] Preserve the distinction between:

  * `still_active`;
  * `situation_changed`.
* [x] Preserve the meaning that area confirmation concerns the reported condition, not exact geometry.
* [x] Keep localized sentences and final user-facing copy in presentation resources.
* [x] Do not return prelocalized sentences from application contracts.

## Actor presentation boundary

* [x] Expose actor attribution through a UI-safe field.
* [x] Do not expose `MapActionOrderKey` merely to obtain actor identity.
* [x] Reuse an accepted actor-display resolver only if one already exists and is in scope.
* [x] Do not add:

  * profile lookup;
  * contact lookup;
  * network identity resolution;
  * display-name persistence;
  * actor reputation.
* [x] Provide a deterministic fallback when no richer actor presentation source exists.
* [x] Do not hide actor identity solely because no display name is available.

## Applied-history loading

* [x] Add one narrowly scoped application use case for loading one history page.
* [x] Query through the accepted `MapItemActionQueryRepository`.
* [x] Query by exact:

  * group;
  * item type;
  * item ID.
* [x] Request only applied dedicated-action history through the accepted repository.
* [x] Do not call `MapItemActionDao` directly.
* [x] Do not read the durable event log.
* [x] Do not replay events.
* [x] Do not query pending or rejected actions.
* [x] Do not query canonical ADD, UPD or DEL events.
* [x] Do not derive current state from history.
* [x] Preserve repository result order.
* [x] Do not sort again in the mapper or presentation layer.
* [x] Do not use UI arrival time as ordering.

## UI-safe page contract

* [x] Add one application-level timeline page contract.
* [x] Include:

  * ordered activity entries;
  * opaque continuation cursor or continuation token;
  * enough information to determine whether another page may be requested.
* [x] Keep the complete M2 keyset cursor behind the application boundary.
* [x] Do not expose:

  * action timestamp cursor fields;
  * Lamport cursor fields;
  * actor cursor fields;
  * event-ID cursor fields.
* [x] Do not simplify the cursor to timestamp only.
* [x] Do not simplify the cursor to timestamp plus event ID.
* [x] Do not introduce offset pagination.
* [x] Do not infer continuation from UI row position.

## Load result contract

Define typed results distinguishing:

* [x] loaded page;
* [x] invalid input identity;
* [x] invalid persisted action history;
* [x] internal history-read failure.

Requirements:

* [x] Do not silently skip malformed persisted action rows.
* [x] Do not convert malformed history into an empty timeline.
* [x] Do not expose DAO, SQLite or Room terminology.
* [x] Do not expose raw repository exceptions.
* [x] Preserve ordinary exception isolation.
* [x] Do not swallow fatal JVM `Error` subclasses.
* [x] Ensure failure for one target does not corrupt unrelated timeline loads.

## Current-state separation

* [x] Do not include current confirmation count in timeline projection.
* [x] Do not include current acknowledgement count in timeline projection.
* [x] Do not include current lifecycle as timeline-derived truth.
* [x] Do not include current interaction availability.
* [x] Do not include current verification freshness.
* [x] Do not infer category from history rows.
* [x] Do not infer resolved state from the latest timeline row.
* [x] Keep historical confirmation rows visible even when current M2 freshness has been invalidated.
* [x] Defer current verification presentation to M3C.

## Suggested production contracts

* [x] `MapItemActivityEntry`
* [x] `MapItemActivityType`
* [x] `MapItemActivityPage`
* [x] `MapItemActivityCursor`
* [x] `MapItemActivityLoadResult`
* [x] `LoadMapItemActivity`
* [x] `DefaultLoadMapItemActivity`
* [x] `MapItemActivityProjector`
* [x] `MapItemActivityIdentityAdapter`

Exact names may follow existing project conventions, but responsibilities must remain narrow and UI-safe.

## M3B1 tests

### Identity and query scope

* [x] Verify marker history uses exact group, marker type and item ID.
* [x] Verify area history uses exact group, area type and item ID.
* [x] Verify marker and area with the same raw item ID remain isolated.
* [x] Verify blank identity input fails safely.
* [x] Verify history is not queried by raw item ID alone.

### Projection

* [x] Verify Confirm maps to Situation confirmed.
* [x] Verify Situation update maps to Situation update activity.
* [x] Verify resolution maps to Situation no-longer-active activity.
* [x] Verify Acknowledge maps to Coordination acknowledged.
* [x] Verify Coordination note maps to Coordination note activity.
* [x] Verify update disposition is preserved.
* [x] Verify lifecycle meaning is preserved only where valid.
* [x] Verify optional source is preserved only where present.
* [x] Verify optional note or action content is preserved.
* [x] Verify actor attribution is exposed without exposing ordering internals.
* [x] Verify stable entry identity uses accepted action or event identity.
* [x] Verify Lamport time is not exposed.
* [x] Verify reducer ordering tuple is not exposed.
* [x] Verify Room entities are not exposed.

### History semantics

* [x] Verify only applied actions are returned.
* [x] Verify pending actions do not appear.
* [x] Verify rejected actions do not appear.
* [x] Verify dedicated actions are not merged.
* [x] Verify canonical events are not queried.
* [x] Verify durable event replay is not used.
* [x] Verify repository newest-first order is preserved without resorting.
* [x] Verify current confirmation count is not calculated from history.
* [x] Verify current acknowledgement count is not calculated from history.
* [x] Verify current lifecycle is not calculated from history.

### Failure handling

* [x] Verify invalid persisted action is distinguishable from empty history.
* [x] Verify ordinary repository failure maps to typed read failure.
* [x] Verify fatal JVM `Error` subclasses propagate.
* [x] Verify one invalid target history does not corrupt unrelated loads.

### Regression

* [x] Verify no DAO is exposed to application or presentation contracts.
* [x] Verify no new persistence flow is introduced.
* [x] Verify no Room migration is introduced.
* [x] Verify Room schema remains version 52.

## M3B1 non-goals

* [x] Do not implement pagination state management.
* [x] Do not implement load-more behavior.
* [x] Do not implement refresh merging.
* [x] Do not integrate timeline into `MapItemDetailActivity`.
* [x] Do not modify submission behavior.
* [x] Do not add optimistic timeline rows.
* [x] Do not add legacy votes or contributions to the new timeline.
* [x] Do not implement verification status.
* [x] Do not add global activity feed.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3B1 acceptance

* [x] One UI-safe activity-entry projection exists.
* [x] All five dedicated action families have stable application vocabulary.
* [x] History is loaded through the accepted M2 applied-action repository.
* [x] Exact group, item type and item ID are preserved.
* [x] Repository deterministic order is preserved.
* [x] M2 keyset cursor internals remain hidden from presentation.
* [x] Current state is not derived from timeline.
* [x] Pending and rejected actions are absent from normal timeline projection.
* [x] No UI integration or pagination controller is implemented early.
* [x] Room schema remains version 52.
* [x] M3B1 is reviewed and accepted before pagination orchestration begins.

---

# Phase M3B2 — Keyset Pagination and Refresh

## Goal

Add deterministic timeline state management for initial loading, keyset pagination, first-page refresh, deduplication and concurrent read safety.

M3B2 must manage timeline collection behavior without changing M2 query ordering or introducing final production timeline UI yet.

## Timeline state owner

* [x] Add one timeline-specific Android-independent presentation controller or state owner.
* [x] Keep timeline paging orchestration separate from `SubmitMapInteraction`.
* [x] Keep timeline paging orchestration separate from M3A interaction availability.
* [x] Avoid placing all paging logic directly in `MapItemDetailActivity`.
* [x] Avoid expanding the M3A detail controller into an unbounded multi-purpose controller.
* [x] Permit later composition of timeline state into the detail shell.
* [x] Keep test doubles available at application and presentation interfaces.
* [x] Keep Android Activity, Fragment, ViewModel and Compose classes outside paging logic.
* [x] Keep M3B application contracts limited to timeline loading, projection and pure merge behavior.
* [x] Keep observer lifecycle, loading state and retry state in the presentation controller rather than the application read use case.
* [x] Bind one controller instance to one exact `MapItemIdentity` for its full lifecycle.

## Timeline state model

Represent at least:

* [x] not loaded;
* [x] initial loading;
* [x] loaded empty;
* [x] loaded entries;
* [x] refreshing first page;
* [x] loading next page;
* [x] initial load failure;
* [x] refresh failure with existing content preserved;
* [x] append failure with existing content preserved;
* [x] end of history reached.

Requirements:

* [x] Model content state, refresh operation state and append operation state independently where needed.
* [x] Do not require loaded content, refresh status and append status to be mutually exclusive sealed states.
* [x] Preserve already loaded content during refresh failure.
* [x] Preserve already loaded content during append failure.
* [x] Do not convert append failure into an empty timeline.
* [x] Do not convert refresh failure into an initial-load failure.
* [x] Keep transient loading and failure state separate from historical data.
* [x] Keep visible entries separate from append-chain cursor ownership.
* [x] Do not persist timeline UI state as domain state.

## Page-size ownership

* [x] Configure one positive page size when constructing the controller.
* [x] Keep page size immutable for the controller lifecycle.
* [x] Use the same page size for initial load, first-page refresh and append.
* [x] Do not accept arbitrary page-size changes from UI load-more callbacks.
* [x] Do not infer end-of-history from the number of entries remaining after deduplication.

## Initial page loading

* [x] Load the first page with no cursor.
* [x] Publish deterministic newest-first entries.
* [x] Represent empty history explicitly.
* [x] Preserve typed load failures.
* [x] Avoid duplicate initial requests.
* [x] Define `load` as initial-load or initial-retry behavior rather than an implicit reset of already loaded content.
* [x] Use explicit first-page refresh after content has been established.
* [x] Ensure obsolete initial-load results cannot overwrite a newer request.
* [x] Ensure callbacks after controller close do not publish state.
* [x] Reuse an accepted execution abstraction where appropriate.
* [x] Do not perform Room reads directly on the main thread.
* [x] Establish entries, append cursor and end-of-history state atomically after successful initial load.

## Keyset pagination

* [x] Load older entries using the opaque application cursor.
* [x] Preserve the complete accepted M2 cursor.
* [x] Do not use offset pagination.
* [x] Do not reconstruct a cursor from visible text or UI position.
* [x] Do not reconstruct a cursor from the merged list tail.
* [x] Permit only one append request in flight.
* [x] Ignore or reject duplicate load-more requests while one is in flight.
* [x] Capture the append cursor and paging-chain ownership when the request starts.
* [x] Append older entries after existing entries.
* [x] Preserve repository order within each page.
* [x] Preserve global newest-first order through page placement and repository order.
* [x] Do not globally re-sort merged entries by timestamp or other exposed presentation fields.
* [x] Do not reset previously loaded pages on normal append.
* [x] Apply append results to the current accepted state rather than publishing a state snapshot captured before the read.

## End-of-history behavior

* [x] Define one deterministic end-of-history policy.
* [x] Treat a raw page smaller than the requested page size as end reached.
* [x] Calculate end-of-history before page-overlap deduplication.
* [x] Handle an exactly full final page followed by an empty page.
* [x] Prevent repeated empty-page requests after end is established.
* [x] Do not treat a non-null repository cursor alone as proof that more rows exist.
* [x] Preserve existing entries when the final append returns empty.
* [x] Do not treat a full page containing overlap as end reached merely because fewer unique entries were appended.

## Stable entry identity and deduplication

* [x] Deduplicate by stable action or event identity.
* [x] Do not deduplicate by:

  * actor;
  * timestamp;
  * action type;
  * note content;
  * adapter position.
* [x] Prevent duplicates across:

  * adjacent pages;
  * first-page refresh and existing pages;
  * repeated load-more callbacks;
  * exact duplicate refresh results.
* [x] Preserve the first deterministic occurrence according to the accepted repository and merge order.
* [x] Do not merge distinct append-only actions into one row.
* [x] Do not collapse repeated Confirm actions merely because actor or timestamp matches.
* [x] Treat repeated identical entry identity and content as a deduplicated overlap.
* [x] Treat repeated entry identity with conflicting projected content as an invalid merge result.
* [x] Preserve previously accepted content when a refresh or append fails because of conflicting identity content.

## First-page refresh

* [x] Refresh the first page from the beginning with no cursor.
* [x] Use persisted applied history as the source of truth.
* [x] Do not synthesize a row from the submitted command.
* [x] Merge refreshed first-page entries with the existing loaded collection.
* [x] Place refreshed persisted entries before older existing entries.
* [x] Deduplicate by stable entry identity.
* [x] Preserve repository order within the refreshed first page.
* [x] Preserve previously loaded older entries that are absent from the refreshed first page.
* [x] Preserve newest-first order without globally sorting by timestamp.
* [x] Preserve visible page stability where possible.
* [x] Ensure a failed refresh leaves prior content usable.
* [x] Treat first-page refresh as head reconciliation rather than full-history reconciliation.
* [x] Rely on the protected immutable append-only applied-history invariant when preserving older loaded pages.

## Append-cursor stability across refresh

* [x] Track the continuation cursor of the existing append chain separately from the refreshed first-page tail.
* [x] Treat the append cursor as the oldest accepted paging boundary, not the cursor of the latest refreshed head.
* [x] Before any older page has been appended, allow a successful first-page refresh to rebuild the first-page chain and adopt the refreshed page cursor and end state.
* [x] After one or more append pages have been accepted, preserve the existing append cursor and end state across successful first-page refresh.
* [x] Do not replace the append cursor with the last row of the refreshed first page when older pages are already loaded.
* [x] Do not derive the append cursor from the merged list tail unless the complete paging chain has been rebuilt.
* [x] Verify refresh does not cause skipped older rows.
* [x] Verify refresh does not cause duplicated older rows.

## Concurrent update behavior

* [x] Handle new local actions arriving between page loads.
* [x] Handle new remote actions arriving between page loads.
* [x] Handle refresh while append is in flight.
* [x] Handle append completion after a newer refresh.
* [x] Ensure obsolete callbacks cannot overwrite newer timeline state.
* [x] Use distinct lifecycle, head-request, append-request and paging-chain ownership tokens where needed.
* [x] Do not rely on one undifferentiated generation counter for all read lanes.
* [x] Keep refresh request ownership distinct from append request ownership.
* [x] Permit an append started before refresh to merge after refresh only when it still owns the active append request and its paging chain remains valid.
* [x] Ensure such append completion cannot replace the refreshed head, restore obsolete operation flags or publish a pre-refresh state snapshot.
* [x] Invalidate an old append when the complete paging chain is rebuilt.
* [x] Ensure a newer refresh supersedes an older refresh result.
* [x] Ensure controller close invalidates pending publication.
* [x] Do not confuse read cancellation with persistence rollback.

## Controller lifecycle and publication

* [x] Keep controller state mutation thread-safe under one consistent synchronization discipline.
* [x] Do not call observers while holding the controller state lock.
* [x] Publish immutable state snapshots through the accepted delivery abstraction.
* [x] Ensure execution cancellation is only a best-effort optimization; stale-result tokens remain authoritative.
* [x] Ensure `close` cancels queued tasks where possible and invalidates all later publication.
* [x] Ensure callbacks delivered after `close` cannot mutate or publish state.
* [x] Do not treat controller close as history deletion or persistence rollback.

## Accepted-action refresh hook

* [x] Expose one presentation-level first-page refresh operation.
* [x] Keep the hook outside `SubmitMapInteraction`.
* [x] Do not add timeline dependencies to M3A3 submission contracts.
* [x] Permit M3B3 to request refresh after:

  * accepted and persisted;
  * accepted with post-commit delivery warning.
* [x] Do not create a second row for exact duplicate outcome.
* [x] Define exact duplicate as no automatic timeline refresh and no visible synthetic change.
* [x] Preserve explicit user-requested refresh as an independent operation.
* [x] Do not refresh timeline as proof of semantic rejection.
* [x] Keep current-detail refresh and timeline refresh as separate operations.
* [x] Ensure timeline refresh failure does not convert accepted submission into failure.

## Remote and lifecycle refresh

* [x] Support explicit timeline refresh while detail is open.
* [x] Permit M3B3 to request refresh after known remote state changes.
* [x] Do not require a new database observation table.
* [x] Do not add transport-level delivery tracking.
* [x] Do not infer remote capability from timeline changes.
* [x] Do not derive target availability from timeline refresh.
* [x] Do not derive current lifecycle, confirmation freshness or acknowledgement state from refreshed history.

## Error isolation

* [x] Timeline failure must not prevent canonical detail from rendering.
* [x] Timeline failure must not prevent interaction submission.
* [x] Detail-read failure must not be represented as successful timeline state.
* [x] Append failure must remain local to load-more behavior.
* [x] Refresh failure must preserve existing entries.
* [x] Invalid merge during refresh or append must preserve previously accepted entries.
* [x] Keep initial, refresh and append failure classifications distinct.
* [x] Do not expose Room or DAO terminology.
* [x] Catch ordinary `Exception` failures where mapping is required.
* [x] Do not swallow fatal JVM `Error` subclasses.

## Suggested production contracts

* [x] `MapItemTimelineState`
* [x] `MapItemTimelineContentState`
* [x] `MapItemTimelineRefreshState`
* [x] `MapItemTimelineAppendState`
* [x] `MapItemTimelineFailure`
* [x] `MapItemTimelineController`
* [x] `DefaultMapItemTimelineController`
* [x] `MapItemTimelineExecution`
* [x] `MapItemTimelineTask`
* [x] `MapItemTimelineMergePolicy`
* [x] `MapItemTimelineMergeResult`

Exact names may follow existing project conventions.

The controller belongs to an Android-independent presentation package. The existing M3B1 loader,
projection and opaque cursor remain in the application timeline package. A pure merge policy may
remain in the application timeline package if it has no observer, execution or lifecycle dependency.

## M3B2 tests

### Initial loading

* [x] Verify first page loads with no cursor.
* [x] Verify newest-first order is preserved.
* [x] Verify empty history state.
* [x] Verify initial failure state.
* [x] Verify duplicate initial-load requests are guarded.
* [x] Verify `load` does not reset already loaded content.
* [x] Verify obsolete initial result cannot overwrite newer state.
* [x] Verify successful initial load establishes entries, cursor and end state atomically.
* [x] Verify callbacks after close do not publish state.

### Pagination

* [x] Verify next page uses the complete opaque cursor.
* [x] Verify no offset pagination is used.
* [x] Verify no cursor is reconstructed from list position or merged-list tail.
* [x] Verify older entries append after newer entries.
* [x] Verify one append request may be in flight.
* [x] Verify repeated load-more requests are guarded.
* [x] Verify page overlap is deduplicated by stable entry ID.
* [x] Verify conflicting content for one entry ID fails the append while preserving existing entries.
* [x] Verify distinct actions with equal timestamps remain distinct.
* [x] Verify distinct actions from the same actor remain distinct.
* [x] Verify repository order is preserved without global timestamp sorting.
* [x] Verify page smaller than limit marks end reached.
* [x] Verify end-of-history uses raw page size before deduplication.
* [x] Verify a full overlapping page does not falsely mark end reached.
* [x] Verify exactly full final page followed by empty page marks end reached.
* [x] Verify repeated load-more after end does not query again.

### Refresh merging

* [x] Verify new first-page entries are prepended.
* [x] Verify existing older pages remain visible.
* [x] Verify duplicate refreshed entries are not repeated.
* [x] Verify conflicting content for one refreshed entry ID fails refresh while preserving prior content.
* [x] Verify refreshed order remains newest-first without global timestamp sorting.
* [x] Verify failed refresh preserves prior content.
* [x] Verify refresh before append rebuilds first-page cursor and end state.
* [x] Verify refresh after multiple append pages preserves append cursor and established end state.
* [x] Verify refresh does not skip older entries.
* [x] Verify refresh does not duplicate older entries.
* [x] Verify append cursor is not replaced by refreshed first-page tail after append history exists.

### Concurrency

* [x] Verify refresh while append is in flight.
* [x] Verify an owned append may merge into the refreshed state when its paging chain remains valid.
* [x] Verify append completion cannot replace the refreshed head or restore obsolete operation state.
* [x] Verify a paging-chain rebuild invalidates an older append completion.
* [x] Verify stale refresh completion cannot overwrite a newer refresh state.
* [x] Verify head and append ownership are not controlled by one undifferentiated token.
* [x] Verify remote new action during paging does not corrupt order.
* [x] Verify local accepted action during paging does not create synthetic row.
* [x] Verify controller close invalidates pending publication.
* [x] Verify observers are not invoked under the controller state lock.
* [x] Verify ordinary execution failures map safely.
* [x] Verify fatal JVM `Error` subclasses propagate.

### Submission coexistence

* [x] Verify accepted submission may request timeline first-page refresh.
* [x] Verify accepted-with-delivery-warning may request timeline refresh.
* [x] Verify exact duplicate triggers no automatic timeline refresh and creates no second visible row.
* [x] Verify timeline refresh failure does not change accepted submission outcome.
* [x] Verify current-detail refresh and timeline refresh remain independent.
* [x] Verify `SubmitMapInteraction` has no timeline dependency.

### Boundary and regression

* [x] Verify controller is Android-independent presentation code.
* [x] Verify M3B1 application loader has no presentation-controller dependency.
* [x] Verify no Room schema change.
* [x] Verify no DAO access from presentation controller.
* [x] Verify no durable-event replay.
* [x] Verify no pending or rejected action query.
* [x] Verify no global sorting by timestamp or UI arrival order.
* [x] Verify Room schema remains version 52.

## M3B2 non-goals

* [x] Do not add final timeline views.
* [x] Do not integrate the controller into `MapItemDetailActivity` yet.
* [x] Do not redesign the detail screen.
* [x] Do not add legacy history to the timeline.
* [x] Do not add automatic observation infrastructure.
* [x] Do not add optimistic timeline rows.
* [x] Do not modify submission semantics or submission contracts.
* [x] Do not modify M3A detail controller state.
* [x] Do not modify M3B1 repository, DAO, ordering or cursor semantics.
* [x] Do not calculate verification state.
* [x] Do not add global activity feed.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3B2 acceptance

* [x] Initial timeline load is deterministic.
* [x] Timeline content, refresh operation and append operation remain independently representable.
* [x] Pagination uses the complete accepted keyset cursor.
* [x] No offset pagination exists.
* [x] No cursor is reconstructed from presentation fields or merged-list position.
* [x] No duplicate or skipped rows occur across pages.
* [x] Conflicting content for one stable entry identity fails safely without discarding prior content.
* [x] First-page refresh preserves already loaded older pages.
* [x] Refresh before append may rebuild the first-page chain.
* [x] Refresh after append preserves the oldest append paging boundary.
* [x] End-of-history is derived from raw page size rather than cursor presence or deduplicated count.
* [x] Existing content survives refresh, append and merge failures.
* [x] Obsolete async results cannot overwrite newer state.
* [x] An append begun before refresh may merge only while its request and paging chain remain valid.
* [x] Controller close prevents all later publication.
* [x] Accepted-action refresh remains presentation orchestration.
* [x] Exact duplicate creates no automatic timeline refresh and no visible synthetic row.
* [x] Submission contracts remain unchanged.
* [x] M3B1 repository, DAO, ordering and cursor contracts remain unchanged.
* [x] Room schema remains version 52.
* [x] M3B2 is reviewed and accepted before production UI integration begins.

---

# Phase M3B3 — Timeline UI and History Coexistence

## Goal

Integrate the accepted M3B timeline projection and paging controller into the M3A item-detail shell while keeping current domain state, dedicated history and legacy presentation visually and semantically separate.

## M3B3 architecture lock

M3B3 integrates two already accepted presentation state owners:

* the M3A4 detail-shell controller;
* the M3B2 timeline controller.

The following ownership model is locked:

* [x] `MapItemDetailActivity` remains the Android lifecycle and composition host for both controllers.
* [x] One detail controller instance and one timeline controller instance are created for one decoded `MapItemIdentity`.
* [x] Both controller instances remain bound to that exact identity for the full Activity lifecycle.
* [x] Timeline state remains `MapItemTimelineState`.
* [x] Detail state remains `MapItemDetailShellState`.
* [x] Do not add timeline entries, timeline cursors, append state or timeline failures to `MapItemDetailShellState`.
* [x] Do not add canonical detail, interaction availability, active forms or submission state to `MapItemTimelineState`.
* [x] Do not merge the M3A4 and M3B2 controllers into one controller.
* [x] Do not make either controller depend on the other controller.
* [x] Do not add timeline dependencies to `SubmitMapInteraction`.
* [x] Do not add submission dependencies to `LoadMapItemActivity`.
* [x] Keep coordination between detail feedback and timeline refresh in the M3B3 Android presentation integration.
* [x] Close both controller instances exactly once from the Activity lifecycle.
* [x] Ensure controller callbacks delivered after Activity destruction cannot update views.
* [x] Do not recreate either controller from render callbacks.
* [x] Do not treat Activity recreation as timeline persistence; reload from accepted repositories.
* [x] Keep all Room and repository construction inside existing composition factories.

The Activity may coordinate presentation operations, but it must not:

* [x] merge timeline pages;
* [x] own the continuation cursor;
* [x] deduplicate activity entries;
* [x] sort timeline entries;
* [x] derive current domain state;
* [x] construct action events;
* [x] query DAOs;
* [x] replay history.

The M3B2 controller remains the sole owner of:

* page-size use;
* continuation cursor;
* first-page refresh;
* append-chain ownership;
* deduplication and merge policy;
* end-of-history state;
* concurrent timeline request validity.

## Detail-shell integration

* [x] Integrate timeline into the existing marker detail surface.
* [x] Integrate timeline into the existing area detail surface.
* [x] Reuse exact M3A typed identity.
* [x] Do not pass Room entities through navigation.
* [x] Do not serialize history pages as navigation truth.
* [x] Load timeline through M3B application and controller boundaries.
* [x] Preserve existing detail loading.
* [x] Preserve existing interaction availability.
* [x] Preserve existing submission behavior.
* [x] Preserve existing owner edit and delete behavior.
* [x] Avoid rewriting `GroupMapActivity` beyond the minimum accepted integration boundary.

## Composition and lifecycle boundary

* [x] Add timeline dependencies at the existing map composition boundary.
* [x] Reuse `MapItemTimelineControllerFactory`.
* [x] Reuse the accepted `MapItemActivityReadFactory`.
* [x] Reuse the accepted `MapItemActionQueryRepository` implementation behind those factories.
* [x] Obtain one shared `AppDatabase` instance through the accepted database provider.
* [x] Do not construct DAOs in Activity, Fragment, ViewModel, adapter or controller code.
* [x] Do not construct repositories directly in `MapItemDetailActivity`.
* [x] Do not construct reducers in UI.
* [x] Do not construct a second `RoomMapEventProcessor`.
* [x] Keep timeline factory responsibilities narrow.
* [x] Keep page size owned by composition and immutable for the timeline-controller lifecycle.
* [x] Do not accept page size from button callbacks, adapter callbacks or navigation input.
* [x] Compose timeline and detail controllers without merging their application or state contracts.
* [x] Avoid expanding the global service locator.
* [x] Preserve existing non-map dependency wiring.

Lifecycle requirements:

* [x] Decode and validate `MapItemIdentity` once.
* [x] Do not create a timeline controller when navigation identity is invalid.
* [x] Create the detail and timeline controllers only after valid identity decoding.
* [x] Bind both controllers to the same exact identity instance or equal typed value.
* [x] Start the timeline initial load once per Activity instance.
* [x] Do not restart initial loading from ordinary render callbacks.
* [x] Use `refreshFirstPage` for later timeline refreshes.
* [x] Use `loadOlder` only for explicit older-history requests.
* [x] Close both controllers in `onDestroy`.
* [x] Null or otherwise release Activity-held controller and adapter references after closing.
* [x] Preserve stale-result protection inside each accepted controller.
* [x] Do not interpret execution cancellation as persistence rollback.

## Timeline section states

Render distinct states for:

* [x] initial loading;
* [x] empty history;
* [x] loaded history;
* [x] refreshing;
* [x] loading older activity;
* [x] first-load error;
* [x] refresh error with prior entries retained;
* [x] append error with prior entries retained;
* [x] end of history.

Requirements:

* [x] Keep timeline errors local to the timeline section.
* [x] Do not replace valid detail content with timeline error.
* [x] Keep loaded entries visible during refresh.
* [x] Keep loaded entries visible after append failure.
* [x] Provide retry behavior appropriate to each failure type.
* [x] Avoid indefinite loading indicators.
* [x] Preserve screen usability while timeline is unavailable.

## Activity-row presentation

Each row may show:

* [x] human-readable activity label;
* [x] actor attribution;
* [x] action time;
* [x] information source where applicable;
* [x] note or update content where applicable;
* [x] update disposition where applicable;
* [x] lifecycle meaning where applicable.

Requirements:

* [x] Do not expose raw event-type names.
* [x] Do not expose Lamport time.
* [x] Do not expose ordering tuples.
* [x] Do not expose materialization state.
* [x] Do not expose rejection reasons.
* [x] Do not expose Room or DAO terminology.
* [x] Preserve long-content safety.
* [x] Preserve multiline note readability.
* [x] Avoid presenting raw persistence enum values.
* [x] Use stable entry identity for UI list keys.
* [x] Do not use adapter position as identity.

## Timeline list and adapter boundary

* [x] Use one timeline-specific adapter or renderer.
* [x] Submit immutable entry snapshots from `MapItemTimelineState`.
* [x] Use `MapItemActivityEntry.entryId` as the stable row identity.
* [x] Do not use adapter position as semantic identity.
* [x] Do not use timestamp as row identity.
* [x] Do not merge or deduplicate entries in the adapter.
* [x] Do not sort entries in the adapter.
* [x] Preserve the exact entry order published by the timeline controller.
* [x] Keep localized labels, actor formatting, timestamp formatting and optional-field visibility in Android presentation code.
* [x] Do not expose application enum names directly as displayed text.
* [x] Preserve multiline note and update content.
* [x] Apply bounded rendering safeguards without mutating or truncating persisted data.
* [x] Keep load-more as an explicit presentation command to `MapItemTimelineController.loadOlder`.
* [x] Do not calculate the continuation cursor from RecyclerView state.
* [x] Do not calculate end-of-history from adapter item count.
* [x] Do not automatically issue repeated append requests from multiple layout or scroll callbacks.
* [x] Keep append retry separate from initial-load retry and first-page-refresh retry.

## Situation activity vocabulary

* [x] Render Confirm as a community confirmation action.
* [x] Render Situation update as historical update activity.
* [x] Distinguish:

  * still active;
  * situation changed.
* [x] Render no-longer-active as resolution history.
* [x] Do not call resolution deletion.
* [x] Do not imply historical confirmation remains currently fresh.
* [x] Do not calculate current verification from displayed rows.
* [x] Preserve area-confirmation wording that refers to the reported condition rather than exact geometry.
* [ ] Defer full freshness explanation and Community Verification presentation to M3C.

## Coordination activity vocabulary

* [x] Render Acknowledge as participation or acknowledgement.
* [x] Do not call acknowledgement verification.
* [x] Render Coordination notes as coordination history.
* [x] Do not attach Situation lifecycle language to Coordination.
* [x] Do not attach weighted confidence.
* [x] Do not calculate coordination participation from visible rows.
* [x] Continue using M2 current acknowledgement count for current-state presentation.

## Current-state and history separation

* [x] Present current canonical and domain state separately from historical activity.
* [x] Ensure the current state section remains authoritative.
* [x] Do not place historical rows inside current-status labels.
* [x] Do not calculate status badges from timeline entries.
* [x] Keep current confirmation count from M2 domain state.
* [x] Keep current acknowledgement count from M2 domain state.
* [x] Keep current lifecycle from M2 domain state.
* [x] Keep interaction availability from M3A2.
* [x] Keep stale historical confirmations visible without marking them currently valid.
* [x] Ensure resolved or materially changed Situations do not appear currently verified merely because confirmation rows exist.

## Legacy-history coexistence policy

M3B adopts the following policy:

* [x] The new timeline contains only applied dedicated actions.
* [x] Legacy votes and contributions remain in the existing legacy presentation.
* [x] Do not import legacy votes into the new timeline.
* [x] Do not import legacy contributions into the new timeline.
* [x] Do not create compatibility timeline rows that imitate dedicated actions.
* [x] Do not translate:

  * legacy vote to Confirm;
  * legacy contribution to Situation update;
  * legacy contribution to Coordination note;
  * legacy confidence to information source.
* [ ] Where necessary, show a small compatibility explanation that legacy activity is displayed separately.
* [ ] Keep complete terminology migration assigned to M3F.
* [ ] Keep Community Verification migration assigned to M3C.

## Refresh after local submission

Timeline refresh is presentation orchestration and must use the accepted persisted submission outcome.

* [x] Request timeline first-page refresh after `MapItemDetailShellFeedback.Accepted`.
* [x] Request timeline first-page refresh when accepted feedback contains a post-commit delivery warning.
* [x] Do not request automatic timeline refresh for `AlreadyRecorded`.
* [x] Do not request automatic timeline refresh for invalid input.
* [x] Do not request automatic timeline refresh for semantic rejection.
* [x] Do not request automatic timeline refresh for persistence or internal failure.
* [x] Do not infer successful submission from the active form closing.
* [x] Do not infer successful submission from changed detail counts.
* [x] Do not infer successful submission from a Toast or localized message.
* [x] Do not inspect command fields to construct or predict a timeline row.
* [x] Do not change `SubmitMapInteraction`.
* [x] Do not add a timeline callback to M3A3 application contracts.
* [x] Do not create optimistic timeline rows.
* [x] Do not derive timeline rows from command input.
* [x] Do not create a second visible row for exact duplicate.
* [x] Keep detail refresh and timeline refresh separate.
* [x] Allow detail refresh to succeed while timeline refresh fails.
* [x] Allow timeline refresh to succeed while detail refresh fails.
* [x] Preserve accepted local persistence feedback when timeline refresh fails.
* [x] Preserve post-commit delivery-warning semantics.
* [x] Consume one accepted feedback event at most once for automatic timeline refresh.
* [x] Ensure ordinary rerendering cannot trigger repeated refreshes for the same feedback.
* [x] Keep existing entries visible while the first page refreshes.
* [x] Avoid resetting user scroll position when refreshed entries are reconciled.
* [x] Let the M3B2 controller determine entry deduplication and merge behavior.

## Refresh after remote activity

* [x] Permit explicit timeline refresh while detail is open.
* [x] Reflect remote applied actions after refresh.
* [x] Preserve page stability when new remote actions appear.
* [x] Do not infer peer delivery certainty.
* [x] Do not add per-peer delivery status.
* [x] Do not add transport observation merely for timeline UI.
* [x] Do not show pending remote activity as accepted history.

## Load-more behavior

* [x] Provide a clear way to load older activity.
* [x] Disable repeated load-more while append is in flight.
* [x] Preserve entries after append failure.
* [x] Permit retry after append failure.
* [x] Stop offering load-more after end of history.
* [x] Do not reload all history to obtain the next page.
* [x] Do not use offset-based list position.
* [x] Preserve scroll stability as older rows append.

## Missing, deleted and unresolved targets

* [x] Do not create either controller for invalid navigation identity.
* [x] Do not load timeline for invalid input identity.
* [x] Handle target missing on initial detail load.
* [x] Handle target deletion or disappearance while detail is open.
* [x] Keep unresolved legacy item detail read-only according to M3A policy.
* [x] Do not infer unresolved category from action history.
* [x] Do not enable interactions based on timeline content.
* [x] Do not create placeholder targets.

Initial target policy:

* [x] When the canonical target is missing on the initial detail load, do not present timeline rows as a substitute for the missing item.
* [x] Timeline failure or timeline success must not convert an initially missing target into a renderable current item.
* [x] If timeline loading was already started concurrently, suppress its normal section presentation while the initial detail target is unavailable.
* [x] Do not use historical actions to reconstruct canonical target content.

Previously loaded target policy:

* [x] If the target was previously rendered successfully and later becomes deleted or unavailable, current detail state remains authoritative.
* [x] Preserve already loaded dedicated history as read-only historical content.
* [x] Clearly separate retained history from the unavailable current target state.
* [x] Disable or hide interactions according to the accepted M3A state.
* [x] Do not clear already accepted timeline entries merely because the current target becomes unavailable.
* [x] Do not automatically request older pages after the target becomes unavailable.
* [x] Permit explicit retry or refresh only where the Activity remains in a valid lifecycle state.
* [x] Never present retained historical rows as evidence that the target is currently active, available or verified.

## Accessibility and usability

* [ ] Ensure activity type is understandable without relying only on color.
* [ ] Ensure loading and error states are announced accessibly where supported.
* [ ] Preserve readable actor and timestamp hierarchy.
* [ ] Preserve note readability under long content.
* [ ] Avoid dense raw technical metadata.
* [ ] Avoid implying that newest activity is automatically authoritative truth.
* [ ] Keep current state visually more authoritative than historical rows.

## M3B3 automated tests

### Detail integration

* [x] Verify marker detail renders timeline by exact identity.
* [x] Verify area detail renders timeline by exact identity.
* [x] Verify marker and area with the same raw ID remain isolated.
* [x] Verify timeline failure does not block valid detail rendering.
* [x] Verify timeline loading does not block interaction controls.
* [x] Verify no DAO is constructed in UI code.
* [x] Verify no reducer is constructed in UI code.
* [x] Verify one detail controller and one timeline controller are created for one valid identity.
* [x] Verify invalid navigation identity creates no timeline controller.
* [x] Verify render callbacks do not recreate controllers.
* [x] Verify Activity destruction closes both controllers.
* [x] Verify timeline state is not added to `MapItemDetailShellState`.
* [x] Verify detail state is not added to `MapItemTimelineState`.
* [x] Verify the detail controller has no timeline-controller dependency.
* [x] Verify the timeline controller has no detail-controller dependency.

### Timeline states

* [x] Verify initial loading presentation.
* [x] Verify empty history presentation.
* [x] Verify loaded history presentation.
* [x] Verify first-load error and retry.
* [x] Verify refresh retains prior rows.
* [x] Verify append loading presentation.
* [x] Verify append failure retains prior rows.
* [x] Verify end-of-history presentation.
* [x] Verify stable row identity.

### Vocabulary

* [x] Verify Situation Confirm wording.
* [x] Verify still-active update wording.
* [x] Verify situation-changed update wording.
* [x] Verify no-longer-active wording is distinct from delete.
* [x] Verify Coordination acknowledgement is not called verification.
* [x] Verify Coordination note is not called confirmation.
* [x] Verify raw event names are absent.
* [x] Verify Lamport and ordering fields are absent.
* [x] Verify area confirmation wording does not imply exact geometry verification.

### Current-state separation

* [x] Verify timeline rows do not determine current confirmation count.
* [x] Verify timeline rows do not determine current acknowledgement count.
* [x] Verify timeline rows do not determine current lifecycle.
* [x] Verify timeline rows do not determine interaction availability.
* [x] Verify historical confirmations remain visible after freshness invalidation.
* [x] Verify stale historical confirmations are not presented as currently valid.
* [x] Verify resolved Situation current state remains authoritative over historical confirmations.
* [x] Verify an initially missing target does not present history as reconstructed current detail.
* [x] Verify previously loaded history may remain visible read-only after the target becomes unavailable.
* [x] Verify retained history does not keep interactions enabled.
* [x] Verify retained history does not make the target appear active or verified.

### Legacy coexistence

* [x] Verify legacy votes are absent from dedicated timeline.
* [x] Verify legacy contributions are absent from dedicated timeline.
* [x] Verify existing legacy UI remains available where currently retained.
* [x] Verify no legacy-to-dedicated semantic translation occurs.
* [ ] Verify compatibility explanation where configured.

### Submission integration

* [x] Verify accepted Confirm requests timeline refresh.
* [x] Verify accepted Situation update requests timeline refresh.
* [x] Verify accepted resolution requests timeline refresh.
* [x] Verify accepted Acknowledge requests timeline refresh.
* [x] Verify accepted Coordination note requests timeline refresh.
* [x] Verify accepted-with-delivery-warning requests timeline refresh.
* [x] Verify exact duplicate creates no second visible row.
* [x] Verify no optimistic timeline row is created.
* [x] Verify timeline refresh failure does not change accepted submission feedback.
* [x] Verify current detail and timeline refresh remain independent.
* [x] Verify accepted detail feedback requests exactly one first-page timeline refresh.
* [x] Verify accepted-with-delivery-warning feedback requests exactly one first-page timeline refresh.
* [x] Verify repeated rendering of the same accepted feedback does not request repeated timeline refresh.
* [x] Verify `AlreadyRecorded` requests no automatic timeline refresh.
* [x] Verify semantic rejection requests no automatic timeline refresh.
* [x] Verify persistence failure requests no automatic timeline refresh.
* [x] Verify localized Toast text is not used as refresh authority.
* [x] Verify the submitted command is not converted into a timeline row.

### Pagination integration

* [x] Verify load-more uses controller cursor rather than UI position.
* [x] Verify duplicate load-more is guarded.
* [x] Verify appended entries preserve newest-first order.
* [x] Verify page overlap does not create duplicate rows.
* [x] Verify refresh after several loaded pages preserves older entries.
* [x] Verify new remote action appears after refresh without resetting loaded history.
* [x] Verify end-of-history prevents additional queries.

### Regression

* [x] Verify current marker creation remains available.
* [x] Verify current area creation remains available.
* [x] Verify marker drafting remains unchanged.
* [x] Verify area drafting remains unchanged.
* [x] Verify canonical owner edit remains available.
* [x] Verify canonical owner delete remains available.
* [x] Verify all five M3A interactions remain usable.
* [x] Verify no verification scoring is introduced.
* [x] Verify no map rendering redesign is introduced.
* [x] Verify no BLE, relay or envelope change.
* [x] Verify no Room migration.
* [x] Verify Room schema remains version 52.

## M3B3 manual checks

* [ ] Open marker detail with no dedicated activity.
* [ ] Open area detail with no dedicated activity.
* [ ] Open marker detail with multiple dedicated actions.
* [ ] Open area detail with multiple dedicated actions.
* [ ] Verify newest activity appears first.
* [ ] Load multiple older pages.
* [ ] Verify no duplicate or skipped visible rows.
* [ ] Submit Confirm and verify persisted row appears after refresh.
* [ ] Submit Situation update and verify correct disposition wording.
* [ ] Mark Situation no longer active and verify delete wording is not used.
* [ ] Submit Acknowledge and verify it is not called verification.
* [ ] Add Coordination note and verify content is readable.
* [ ] Verify timeline remains usable after append failure.
* [ ] Verify timeline remains visible after refresh failure.
* [ ] Verify accepted local state remains accepted when timeline refresh fails.
* [ ] Verify post-commit delivery warning remains distinct from timeline state.
* [ ] Verify new remote action appears after explicit refresh.
* [ ] Verify legacy confidence and contributions remain separate.
* [ ] Verify stale confirmations remain visible as history without appearing currently valid.
* [ ] Verify long actor, note and source content remains readable.
* [ ] Verify current marker and area drafting remain unchanged.

## M3B3 non-goals

* [x] Do not implement complete Community Verification presentation.
* [x] Do not calculate freshness from timeline timestamps.
* [x] Do not calculate trust scores or percentages.
* [x] Do not expose confirmer identity lists as a verification feature.
* [x] Do not add pending or rejected forensic history.
* [x] Do not merge legacy history.
* [x] Do not add a global activity feed.
* [x] Do not add moderation.
* [x] Do not add reputation.
* [x] Do not add per-peer delivery status.
* [x] Do not redesign marker icons, area styling or clustering.
* [x] Do not replace the map creation toolbar.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3B3 acceptance

* [x] Marker and area detail expose dedicated applied-action history by exact typed identity.
* [x] Timeline is deterministic and newest-first.
* [x] Pagination is keyset-based and stable.
* [x] No duplicate or skipped entries occur across pages and refresh.
* [x] First-page refresh preserves already loaded older history.
* [x] Current state and historical activity are visually and semantically distinct.
* [x] Stale historical confirmations remain visible without being presented as currently valid.
* [x] Legacy votes and contributions remain separate.
* [x] Timeline errors do not block detail or interactions.
* [x] Accepted local actions refresh persisted history without optimistic rows.
* [x] Post-commit delivery warning remains distinct from timeline state.
* [x] UI uses M3B application contracts rather than DAO or Room entities.
* [x] Existing M3A detail, interaction, drafting, edit and delete behavior remains available.
* [x] No protocol, reducer, replay, processor, transport or persistence semantics have changed.
* [x] Room schema remains version 52.
* [x] M3B3 is reviewed and accepted before overall M3B acceptance.

### M3B3 closure verification (2026-07-21)

External architecture review accepted the final coordinator fix. Detail and timeline
controllers remain independent; detail startup precedes timeline startup; ordinary
timeline startup failures are contained while fatal `Error` propagates. Manual refresh
captures its timeline command before the detail callback; accepted feedback refreshes
only a current target and is consumed once; retained history is frozen only on an
explicit unavailable transition. Focused M3B3/M3B2/M3A4 verification and full app
unit-test regressions passed for both Emergency and Official distributions.

---

# M3B cross-phase contract audit

## Read-path contract

* [x] UI reads dedicated activity through M3B application contracts.
* [x] M3B reads applied history through the accepted M2 action-query repository.
* [x] UI does not call action DAOs.
* [x] UI does not read durable events.
* [x] UI does not replay events.
* [x] UI does not expose Room entities.
* [x] UI does not expose Lamport or reducer ordering internals.
* [x] Exact group, item type and item ID are preserved.
* [x] Marker and area same-ID isolation is preserved.

## Ordering and pagination contract

* [x] Repository newest-first ordering is preserved.
* [x] The complete M2 keyset cursor is preserved.
* [x] Offset pagination is absent.
* [x] UI arrival time is not used for sorting.
* [x] Stable action or event identity is used for deduplication.
* [x] Distinct append-only actions are never merged.
* [x] First-page refresh preserves append-cursor stability.
* [x] No duplicate or skipped rows occur across page boundaries.

## Authority contract

* [x] Timeline is historical presentation only.
* [x] M2 domain-state projection remains current-state authority.
* [x] Timeline does not calculate confirmation count.
* [x] Timeline does not calculate acknowledgement count.
* [x] Timeline does not calculate lifecycle.
* [x] Timeline does not calculate freshness.
* [x] Timeline does not determine interaction availability.
* [x] Timeline does not determine semantic acceptance.

## History-scope contract

* [x] Normal timeline includes applied dedicated actions only.
* [x] Pending actions are absent.
* [x] Rejected actions are absent.
* [x] Canonical ADD, UPD and DEL history is absent.
* [x] Legacy votes are not translated.
* [x] Legacy contributions are not translated.
* [x] Historical confirmation remains visible after freshness invalidation.
* [x] Historical activity is not presented as current truth.

## Write-path contract

* [x] M3B performs no writes.
* [x] M3B does not submit events.
* [x] M3B does not insert action rows.
* [x] M3B does not modify domain aggregates.
* [x] M3B does not open Room transactions.
* [x] M3B does not enqueue transport.
* [x] Accepted-action refresh remains presentation orchestration.
* [x] `RoomMapEventProcessor` remains the sole Room transaction owner.

## Compatibility contract

* [x] Legacy and dedicated histories remain separate.
* [x] No dedicated action is translated into legacy canonical UPD.
* [x] No legacy contribution is presented as Confirm, Acknowledge or Note.
* [x] No peer delivery certainty is inferred.
* [x] No capability negotiation is introduced.
* [x] No BLE, relay or envelope behavior changes.
* [x] Room schema remains version 52.

---

# M3B overall non-goals

* [x] Do not implement M3C complete Community Verification presentation.
* [x] Do not implement M3D map rendering redesign.
* [x] Do not implement M3E caching or delivery-state systems.
* [x] Do not implement M3F global terminology migration.
* [x] Do not add a Room migration.
* [x] Do not add new tables.
* [x] Do not change M2 reducers.
* [x] Do not change duplicate classification.
* [x] Do not change action ordering.
* [x] Do not change earliest-resolution behavior.
* [x] Do not change confirmation-freshness semantics.
* [x] Do not change pending-target recovery.
* [x] Do not change replay.
* [x] Do not change processor semantics.
* [x] Do not change BLE or relay protocols.
* [x] Do not add pending or rejected forensic history.
* [x] Do not add moderation.
* [x] Do not add reputation or trust scoring.
* [x] Do not add per-peer delivery tracking.
* [x] Do not add a global activity feed.
* [x] Do not add optimistic timeline rows.
* [x] Do not merge legacy and dedicated histories.

---

# M3B overall acceptance

* [x] M3B1 timeline projection and vocabulary is accepted.
* [x] M3B2 keyset pagination and refresh is accepted.
* [x] M3B3 timeline UI and history coexistence is accepted.
* [x] One clean M3B application timeline boundary exists.
* [x] Marker and area history is loaded by exact typed identity.
* [x] Applied dedicated actions are projected without exposing Room entities.
* [x] All five dedicated action families have clear human-readable vocabulary.
* [x] Timeline ordering remains deterministic and newest-first.
* [x] Pagination preserves the complete M2 keyset cursor.
* [x] No offset pagination exists.
* [x] No duplicate or skipped entries occur across pages and refresh.
* [x] First-page refresh preserves loaded older history and append-cursor stability.
* [x] Timeline remains historical and does not become current-state authority.
* [x] Historical confirmations remain visible after freshness invalidation.
* [x] Historical confirmations are not presented as currently valid without M2 current-state evidence.
* [x] Legacy votes and contributions remain visually and semantically separate.
* [x] Pending and rejected actions remain absent from normal timeline.
* [x] Accepted local actions refresh persisted history without optimistic rows.
* [x] Timeline failures do not alter accepted submission outcomes.
* [x] Existing M3A detail and interaction behavior remains available.
* [x] No protocol, event model, reducer, replay, processor, persistence, transport or relay semantics have changed.
* [x] Room schema remains version 52.
* [x] No blocking findings remain.
* [x] M3B is reviewed and accepted before Phase M3C begins.

---

# Phase M3C — Community Verification

## Goal

Present understandable current Situation confirmation evidence and Coordination participation by reusing accepted M2 materialized domain state and M3B applied-action history without creating a second verification engine.

M3C must help users distinguish:

* current domain truth;
* historical collaborative activity;
* current Situation confirmation evidence;
* stale historical confirmation activity;
* Coordination participation;
* unresolved or legacy limitations.

M3C must preserve the architectural distinction:

```text
M2 materialized domain state = current-state authority
M3B applied-action timeline  = historical activity
M3C presentation             = UI-safe interpretation only
```

M3C must not derive current verification semantics from visible timeline rows.

M3C is divided into:

* Phase M3C1 — Verification Presentation Contract;
* Phase M3C2 — Freshness and Material-Change UX;
* Phase M3C3 — Coordination Participation Presentation;
* Phase M3C4 — Verification Interaction Integration.

---

## M3C protected baseline

M3C must preserve:

* Room schema version 52;
* all accepted M2 canonical and dedicated-action event types;
* accepted M2 domain-state materialization;
* distinct-actor confirmation counting;
* distinct-actor acknowledgement counting;
* confirmation invalidation after material Situation change;
* irreversible earliest-resolution cutoff;
* acknowledgement independence from Situation verification;
* append-only durable event history;
* append-only dedicated applied-action history;
* deterministic action ordering;
* exact marker and area identity isolation;
* M3A current-detail read and interaction boundaries;
* M3B timeline ordering, pagination and history boundaries;
* `RoomMapEventProcessor` as the sole Room transaction owner;
* accepted local writer and post-commit transport paths;
* mixed-version compatibility behavior.

M3C must not:

* change the event protocol;
* change Room schema;
* add a Room migration;
* add persistence tables;
* change reducers;
* change materialization semantics;
* change duplicate or fingerprint semantics;
* change action ordering;
* change replay or pending-target recovery;
* change confirmation or acknowledgement counting;
* introduce weighted confirmations;
* introduce trust percentages;
* introduce reputation or moderation;
* derive current state from timeline rows;
* expose Room entities to application or UI code;
* expose internal M2 ordering tuples;
* let presentation state become semantic authority;
* let UI call DAOs, reducers or processors directly.

---

# Phase M3C1 — Verification Presentation Contract

## Goal

Create a UI-safe, Android-independent presentation contract for current Situation confirmation evidence using the accepted M3A item-detail domain projection.

M3C1 must define what current verification information may be presented without implementing complete material-change explanation, Coordination participation UI or verification-specific interaction orchestration.

M3C1 must be a read-only projection phase.

## Architecture boundary

* [x] Add a dedicated M3C application package outside:

  * Activity packages;
  * Fragment packages;
  * Compose packages;
  * Room implementation packages;
  * transport packages.
* [x] Keep verification application contracts independent from Android UI classes.
* [x] Keep verification application contracts independent from Room entities and annotations.
* [x] Keep verification application contracts independent from BLE and relay classes.
* [x] Reuse the accepted M3A `MapItemIdentity`.
* [x] Reuse the accepted M3A current item-detail or domain projection.
* [x] Do not introduce a second domain-state repository path merely for item-detail verification.
* [x] Do not query M3B timeline history to calculate current confirmation evidence.
* [x] Do not create a parallel verification engine.
* [x] Do not persist the verification presentation projection.
* [x] Do not add an asynchronous controller when pure projection from accepted detail state is sufficient.

## Verification applicability

* [x] Apply Situation verification presentation only to a recognized Situation category.
* [x] Return an explicit not-applicable result for recognized Coordination items.
* [x] Return an explicit unresolved result for unresolved legacy category.
* [x] Return an explicit unavailable result when valid canonical content has no domain enrichment.
* [x] Return an explicit invalid-state result for invalid persisted domain state.
* [x] Do not infer Situation category from:

  * canonical kind;
  * item label;
  * interaction type;
  * timeline activity;
  * legacy confidence;
  * legacy contributions.
* [x] Keep recognized legacy Situation presentation distinguishable from explicit domain-v1 Situation presentation.
* [x] Do not convert unresolved category into Situation merely because confirmation history exists.

## Current confirmation evidence

* [x] Reuse the accepted M2 `confirmationActorCount`.
* [x] Treat the value as the current distinct confirmer count produced by M2 materialization.
* [x] Do not recount action-history rows.
* [x] Do not count visible timeline rows.
* [x] Do not count legacy votes.
* [x] Do not count Coordination acknowledgements.
* [x] Do not count multiple confirmation actions from one actor as multiple current actors.
* [x] Do not calculate confirmation evidence from actor names or timestamps.
* [x] Represent current evidence presence explicitly:

  * current evidence present;
  * current evidence absent;
  * not applicable;
  * unresolved;
  * unavailable.
* [x] Define current evidence as present only when the accepted current confirmation actor count is greater than zero.
* [x] Do not interpret zero current confirmations as proof that no historical confirmation ever existed.
* [x] Do not assign a confidence percentage.
* [x] Do not create minimum-vote or quorum semantics.
* [x] Do not automatically label an item trusted, safe, true or verified merely because a threshold is reached.

## Verification status vocabulary

* [x] Add stable application-level verification presentation vocabulary.
* [x] Keep vocabulary descriptive rather than authoritative.
* [x] Permit presentation such as:

  * current community confirmations available;
  * no current community confirmation;
  * Situation resolved;
  * verification unavailable;
  * unresolved legacy semantics.
* [x] Do not expose raw persistence enums.
* [x] Do not expose raw M2 event names.
* [x] Do not expose processor rejection strings.
* [x] Keep localized user-facing sentences in Android presentation resources.
* [x] Do not call confirmation a vote.
* [x] Do not call acknowledgement confirmation.
* [x] Do not call historical activity current evidence.
* [x] Do not reuse an old threshold-based verification policy as M3C authority unless separately reviewed and accepted.
* [x] Do not create an automatic conflicting-updates status.

## Creator information source

* [x] Reuse `creatorInformationSource` from the accepted M2/M3A domain projection.
* [x] Preserve an explicitly known creator information source.
* [x] Preserve unspecified legacy creator source honestly.
* [x] Keep creator source absent for Coordination.
* [x] Do not infer creator source from:

  * legacy confidence;
  * latest confirmation source;
  * owner note;
  * timeline note;
  * contribution content;
  * canonical kind.
* [x] Distinguish:

  * explicitly known source;
  * unspecified legacy source;
  * not applicable;
  * unresolved;
  * unavailable.
* [x] Do not present unspecified legacy source as a corrupt explicit-domain record.
* [x] Do not present missing explicit-domain creator source as valid legacy ambiguity.

## Latest current confirmation time

* [x] Audit the accepted current read model for latest confirmation-time support.
* [x] Do not read `MapItemDomainStateEntity` from M3C application or UI code.
* [x] Do not expose internal `MapActionOrderKey`.
* [x] Do not expose Lamport time, actor ordering or event-ID ordering fields.
* [x] If latest confirmation time is not exposed publicly, extend only an accepted UI-safe read projection.
* [x] Do not add persistence or a migration to expose an already persisted timestamp.
* [x] Treat the persisted latest confirmation ordering value as historical unless current confirmation evidence is also valid.
* [x] Expose a latest **current** confirmation time only when:

  * category is recognized Situation;
  * lifecycle is currently active;
  * current confirmation actor count is greater than zero;
  * the accepted read projection contains a valid timestamp.
* [x] Return no latest current confirmation time when current confirmation evidence is absent.
* [x] Do not use a historical confirmation timestamp to make stale evidence appear current.
* [x] Defer presentation of historical latest-confirmation time after invalidation to M3C2.
* [x] Keep timestamp localization and relative-time formatting in Android presentation code.

## Lifecycle handling

* [x] Reuse the accepted M2 Situation lifecycle.
* [x] Keep Coordination lifecycle absent.
* [x] Represent active Situation verification separately from resolved Situation presentation.
* [x] Ensure resolved Situation cannot present current confirmation evidence.
* [x] Ensure resolved Situation does not present a latest current confirmation time.
* [x] Do not derive resolution from timeline.
* [x] Do not derive resolution from confirmation count.
* [x] Do not offer lifecycle reactivation.
* [x] Keep resolution distinct from canonical deletion.
* [x] Defer complete explanation of stale historical confirmations after resolution to M3C2.

## Area verification wording contract

* [x] Expose enough typed presentation context to identify marker versus area.
* [x] Require area verification presentation to explain that community confirmation concerns the reported condition.
* [x] Do not imply confirmation validates exact area geometry or boundary accuracy.
* [x] Do not add a second area-specific confirmation semantic model.
* [x] Keep marker and area target identity exact and explicitly typed.
* [x] Keep final wording in Android presentation resources.

## Result contract

Define typed projection results distinguishing:

* [x] Situation verification available;
* [x] Coordination verification not applicable;
* [x] unresolved legacy category;
* [x] canonical-only detail with domain state unavailable;
* [x] target unavailable;
* [x] invalid current domain state;
* [x] invalid input identity where projection accepts identity input;
* [x] internal projection failure only where an ordinary dependency failure exists.

Requirements:

* [x] Prefer a pure projector that does not normally throw.
* [x] Do not convert invalid persisted state into zero confirmations.
* [x] Do not convert unresolved state into no current evidence.
* [x] Do not convert target missing into unverified Situation.
* [x] Do not expose Room, DAO or SQLite terminology.
* [x] Do not swallow fatal JVM `Error` subclasses.
* [x] Keep technical failures distinct from valid no-evidence presentation.

## Suggested production contracts

* [x] `MapItemVerificationPresentation`
* [x] `SituationVerificationPresentation`
* [x] `MapVerificationApplicability`
* [x] `MapVerificationEvidence`
* [x] `MapVerificationLimitation`
* [x] `MapCreatorInformationSourcePresentation`
* [x] `MapItemVerificationProjectionResult`
* [x] `ProjectMapItemVerification`
* [x] `DefaultMapItemVerificationProjector`
* [x] `MapItemVerificationPresentationFactory`

Exact names may follow existing project conventions, but the responsibilities must remain narrow, read-only and UI-safe.

## Dependency flow

The preferred dependency flow is:

```text
M2 MapItemDomainStateRepository
        ↓
M3A DefaultMapItemDetailComposer
        ↓
M3A MapItemDetailLoadResult
        ↓
M3C1 verification projector
        ↓
M3C1 UI-safe verification presentation
        ↓
Android verification renderer
```

Requirements:

* [x] Do not add `LoadMapItemActivity` as a verification dependency.
* [x] Do not add `MapItemTimelineController` as a verification dependency.
* [x] Do not add `SubmitMapInteraction` as an M3C1 dependency.
* [x] Do not make the M3A detail composer depend on Android verification rendering.
* [x] Do not make the M3B timeline controller depend on M3C.
* [x] Keep the current M3A detail controller as the current-state refresh owner.
* [x] Derive M3C1 presentation from the same accepted detail snapshot where practical.

## Refresh behavior

* [ ] Recompute verification presentation after accepted M3A detail refresh.
* [x] Do not add an independent Room refresh path for the same item state.
* [x] Do not use timeline refresh as proof of current confirmation.
* [x] Do not mutate confirmation count optimistically.
* [x] Do not synthesize latest confirmation time from a submitted command.
* [x] Do not synthesize current verification evidence before persisted state is reloaded.
* [x] Ensure detail refresh failure does not become a successful verification state.
* [x] Keep timeline refresh and detail refresh independent.

## M3C1 automated tests

### Applicability

* [x] Verify recognized active Situation produces verification presentation.
* [x] Verify recognized Coordination returns not applicable.
* [x] Verify unresolved legacy category remains unresolved.
* [x] Verify canonical-only detail does not become unverified Situation.
* [x] Verify invalid persisted domain state remains invalid.
* [x] Verify target missing does not become no-evidence Situation.
* [x] Verify category is not inferred from canonical kind.
* [x] Verify category is not inferred from timeline history.

### Current evidence

* [x] Verify M2 confirmation actor count is reused exactly.
* [x] Verify zero current confirmations maps to current evidence absent.
* [x] Verify positive current confirmations maps to current evidence present.
* [x] Verify current evidence is not recounted from timeline rows.
* [x] Verify legacy votes are not counted.
* [x] Verify acknowledgements are not counted.
* [x] Verify no trust score or percentage is produced.
* [x] Verify no threshold-based verified status is invented.
* [x] Verify distinct actor semantics are not recalculated.

### Creator source

* [x] Verify explicit creator source is preserved.
* [x] Verify recognized legacy Situation may have unspecified creator source.
* [x] Verify Coordination creator source remains not applicable.
* [x] Verify unresolved category does not guess creator source.
* [x] Verify creator source is not taken from latest confirmation history.
* [x] Verify legacy confidence is not used as creator source.

### Latest current confirmation time

* [x] Verify active Situation with current evidence may expose latest current confirmation time.
* [x] Verify zero current confirmations expose no latest current confirmation time.
* [x] Verify resolved Situation exposes no latest current confirmation time.
* [x] Verify Coordination exposes no latest confirmation time.
* [x] Verify internal ordering tuple is not exposed.
* [x] Verify Lamport time is not exposed.
* [x] Verify historical stale timestamp cannot appear as current confirmation time.

### Lifecycle and area behavior

* [x] Verify resolved Situation cannot appear currently confirmed.
* [x] Verify lifecycle is reused from current domain state.
* [x] Verify lifecycle is not inferred from timeline.
* [x] Verify area presentation requires reported-condition wording.
* [x] Verify area presentation does not imply exact geometry verification.
* [x] Verify marker and area same-ID isolation remains preserved.

### Regression

* [x] Verify M3C1 performs no writes.
* [x] Verify no DAO is exposed.
* [x] Verify no Room entity is exposed.
* [x] Verify no action-history replay is introduced.
* [x] Verify no timeline query is required for projection.
* [x] Verify no M2 reducer or materialization logic is duplicated.
* [x] Verify no Room migration.
* [x] Verify Room schema remains version 52.

## M3C1 non-goals

* [x] Do not implement stale-after-change explanation.
* [x] Do not implement stale-after-resolution explanation.
* [x] Do not distinguish never-confirmed from stale history unless accepted current-state metadata supports it.
* [x] Do not display historical confirmer identity lists.
* [x] Do not implement Coordination participation UI.
* [x] Do not implement latest Coordination note preview.
* [x] Do not add verification-specific CTA orchestration.
* [x] Do not change Confirm submission.
* [x] Do not add optimistic verification state.
* [x] Do not calculate verification from timeline.
* [x] Do not add weighted confidence.
* [x] Do not add trust scores.
* [x] Do not add voting thresholds.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3C1 acceptance

* [x] One UI-safe current Situation verification contract exists.
* [x] Verification presentation is projected from accepted current detail/domain state.
* [x] Current distinct confirmer count is reused without recalculation.
* [x] Current evidence presence is represented explicitly.
* [x] Coordination is explicitly not applicable to Situation verification.
* [x] Creator information source limitations are represented honestly.
* [x] Resolved Situation cannot appear currently confirmed.
* [x] Historical activity is not used as current-state authority.
* [x] Latest current confirmation time cannot expose stale historical evidence as current.
* [x] Area verification does not imply exact geometry verification.
* [x] No controller, persistence or write path is introduced unnecessarily.
* [x] No protocol, reducer, replay, processor or materialization semantics change.
* [x] Room schema remains version 52.
* [x] M3C1 is reviewed and accepted before freshness explanation begins.

---

# Phase M3C2 — Freshness and Material-Change UX

## Goal

Explain why historical Situation confirmations may no longer count as current evidence after an accepted material Situation change or resolution.

M3C2 must preserve all historical confirmation activity while keeping current verification presentation aligned with M2 materialized state.

## Freshness authority

* [x] Reuse accepted M2 confirmation invalidation semantics.
* [x] Keep M2 materialized current confirmation count authoritative.
* [x] Do not reimplement latest-material-change ordering in presentation code.
* [x] Do not compare visible timeline timestamps to calculate freshness.
* [x] Do not derive freshness from timeline row position.
* [x] Do not derive freshness from UI arrival order.
* [x] Do not derive freshness from localized timestamps.
* [x] Do not use wall-clock age alone to mark confirmation stale.
* [x] Do not introduce time-to-live semantics for confirmations unless M2 explicitly provides them.
* [x] Do not treat an old but still-current confirmation as stale merely because time passed.

## Material-change metadata audit

* [x] Audit accepted current read models for material-change explanation support.
* [x] Identify which accepted metadata exists for:

  * current confirmation evidence;
  * latest confirmation ordering or time;
  * latest material Situation change;
  * current lifecycle;
  * resolution.
* [x] Do not expose Room entities.
* [x] Do not expose full M2 ordering tuples.
* [x] If required metadata is internal only, expose a narrow UI-safe current-state projection.
* [x] Do not add persistence fields already available in materialized state.
* [x] Do not add a Room migration merely for presentation.
* [x] Ensure the UI-safe metadata cannot be used to reorder or replay actions.
* [x] Preserve ordinary invalid persisted-state detection.

## Freshness presentation model

Represent at least:

* [x] current evidence available;
* [x] no current evidence and no accepted stale-history reason available;
* [x] prior confirmation evidence invalidated after Situation change;
* [x] prior confirmation evidence invalidated by resolution;
* [x] resolved Situation;
* [x] unresolved legacy semantics;
* [x] unavailable material-change explanation.

Requirements:

* [x] Keep current evidence state separate from historical activity existence.
* [x] Keep material-change reason separate from current confirmer count.
* [x] Do not claim stale confirmations were incorrect.
* [x] Do not claim a material change creates an automatic conflict.
* [x] Do not hide historical confirmation activity.
* [x] Do not delete or mutate historical confirmation rows.
* [x] Do not turn stale confirmation history into a processor rejection.
* [x] Do not persist presentation freshness labels.

## `situation_changed` behavior

* [x] Show that prior confirmation evidence may no longer describe the current Situation.
* [ ] Show current confirmation count from M2 after invalidation.
* [x] Preserve previous confirmation rows in the timeline.
* [x] Do not mark historical confirmers as invalid actors.
* [x] Do not label the Situation false.
* [x] Do not label the update disputed unless accepted semantics explicitly say so.
* [x] Permit new current evidence only through accepted Confirm actions.
* [x] Do not automatically resubmit or recreate confirmation actions.
* [x] Do not copy old confirmation notes or sources into a new confirmation.
* [x] Do not alter the materialized domain state.

## Resolution behavior

* [x] Show resolved or no-longer-active state as current lifecycle truth.
* [x] Ensure resolved Situation presents no current confirmation evidence.
* [x] Preserve historical confirmation rows.
* [x] Explain that historical confirmation reflects the Situation before resolution.
* [x] Do not call historical confirmation currently fresh.
* [ ] Do not offer normal re-confirmation while resolved.
* [ ] Do not offer lifecycle reactivation.
* [x] Keep resolution distinct from canonical delete.
* [x] Keep deletion or missing-target presentation under accepted M3A behavior.

## Never-confirmed versus stale-history behavior

* [x] Distinguish never-confirmed from stale-history only when accepted current-state metadata can establish the distinction safely.
* [x] Do not load all timeline pages merely to decide whether historical confirmation exists.
* [x] Do not infer never-confirmed from an empty first timeline page when history is paginated.
* [x] Do not infer stale-history from one visible historical confirmation row without accepted current-state material-change context.
* [x] Permit an honest generic no-current-evidence presentation when the reason cannot be established.
* [x] Preserve legacy ambiguity explicitly.

## Timeline coexistence

* [x] Continue displaying stale historical confirmations through M3B.
* [x] Keep M3B timeline state unchanged.
* [x] Do not add freshness state to `MapItemTimelineState`.
* [x] Do not annotate timeline rows as current semantic authority.
* [ ] Permit Android presentation to show a historical or stale-context label where configured.
* [x] Keep row ordering and pagination under the M3B controller.
* [x] Do not filter historical confirmation rows after material change.
* [x] Do not merge historical rows into the current verification summary.
* [x] Do not count visible historical rows.

## Refresh behavior

* [ ] Recompute freshness presentation after accepted detail refresh.
* [ ] Reflect remote `situation_changed` after current detail refresh.
* [ ] Reflect remote resolution after current detail refresh.
* [ ] Do not wait for timeline refresh to invalidate current evidence.
* [ ] Allow timeline refresh to fail while current freshness presentation remains correct.
* [ ] Allow current detail refresh to fail while existing timeline history remains visible.
* [ ] Do not mutate current evidence optimistically from the submitted update command.
* [ ] Do not infer material change from a closed interaction form.
* [ ] Reload persisted current state as authority.

## Suggested production contracts

* [x] `MapVerificationFreshnessPresentation`
* [x] `MapVerificationFreshnessState`
* [ ] `MapVerificationInvalidationReason`
* [ ] `MapSituationMaterialChangePresentation`
* [x] `ProjectMapVerificationFreshness`
* [x] `DefaultMapVerificationFreshnessProjector`

These contracts may extend M3C1 presentation models if responsibilities remain clear and Android-independent.

## M3C2 automated tests

### Freshness authority

* [x] Verify current confirmation count remains M2-provided.
* [x] Verify freshness is not derived from timeline timestamps.
* [x] Verify freshness is not derived from visible row count.
* [x] Verify freshness is not derived from wall-clock age.
* [x] Verify full ordering tuples are not exposed.
* [x] Verify M2 material-change ordering is not reimplemented.

### Situation changed

* [x] Verify current evidence becomes absent after accepted material change according to M2 state.
* [x] Verify stale historical confirmations remain visible in timeline state.
* [x] Verify stale history does not become current evidence.

### Resolution

* [x] Verify resolved Situation presents no current confirmation evidence.
* [x] Verify resolved Situation preserves historical confirmation activity.
* [x] Verify historical confirmation is not labeled current.
* [x] Verify resolution is distinct from deletion.
* [x] Verify timeline rows do not override resolved lifecycle.

### Ambiguous history

* [ ] Verify never-confirmed is not inferred from only the first timeline page.
* [ ] Verify stale-history is not inferred from a visible row alone.
* [x] Verify unavailable material-change metadata produces honest generic presentation.
* [x] Verify unresolved legacy semantics remain explicit.
* [x] Verify invalid persisted metadata does not become generic no-evidence state.

### Refresh and regression

* [ ] Verify detail refresh updates freshness independently from timeline refresh.
* [ ] Verify timeline refresh failure does not restore stale current evidence.
* [ ] Verify remote material change is reflected after current-state refresh.
* [ ] Verify no optimistic freshness mutation is introduced.
* [x] Verify no Room schema change.
* [x] Verify Room schema remains version 52.

## M3C2 non-goals

* [x] Do not create trust decay based on age.
* [x] Do not create automatic conflict detection.
* [x] Do not score confirmer quality.
* [x] Do not expose full confirmer lists.
* [x] Do not modify timeline pagination.
* [x] Do not filter historical actions.
* [x] Do not change resolution semantics.
* [x] Do not change confirmation invalidation.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

## M3C2 acceptance

* [x] Current and historical confirmation evidence remain clearly distinct.
* [x] Material Situation change cannot leave stale confirmations presented as current.
* [x] Resolved Situation cannot appear currently confirmed.
* [x] Historical confirmations remain visible.
* [x] Freshness is derived only from accepted current-state semantics.
* [x] Timeline timestamps and visible rows are not semantic authority.
* [x] Unknown freshness reasons are represented honestly.
* [x] No automatic conflict, trust score or time-decay semantics are introduced.
* [x] Room schema remains version 52.
* [x] M3C2 is reviewed and accepted before Coordination participation presentation begins.

---

# Phase M3C3 — Coordination Participation Presentation

## Goal

Present current Coordination participation separately from Situation verification by reusing:

* accepted M2 materialized acknowledgement state for current participation;
* already projected M3B applied Coordination-note history for optional historical preview.

M3C3 must preserve the authority split:

```text
M2 acknowledgementActorCount = current Coordination participation authority
M3B activity entries          = historical Coordination activity
M3C3 presentation             = read-only UI-safe interpretation
```

M3C3 must not apply Situation verification, lifecycle or freshness semantics to Coordination.

M3C3 is a read-only projection phase. Android integration, rendering and refresh orchestration remain assigned to M3C4.

---

## Architecture lock

M3C3 defines two independent pure projection boundaries:

```text
M3A current detail snapshot
        ↓
Coordination participation projector
        ↓
Current acknowledgement presentation
```

and:

```text
M3B already-loaded ordered activity entries
        ↓
Coordination note-preview projector
        ↓
Optional historical note preview
```

The ownership model is locked as follows:

* [x] Current acknowledgement presentation is derived only from accepted M3A/M2 current detail state.
* [x] Optional note preview is derived only from already projected M3B activity entries supplied to the projector.
* [x] Current participation projection must work when timeline history is:

  * not loaded;
  * loading;
  * empty;
  * partially loaded;
  * failed;
  * unavailable.
* [x] Note-preview availability must not affect current acknowledgement presentation.
* [x] Current acknowledgement state must not affect M3B ordering, paging or note-history ownership.
* [x] Do not create an M3C3 asynchronous controller.
* [x] Do not create a third presentation state owner.
* [x] Do not make M3C3 projectors depend on:

  * `MapItemTimelineController`;
  * `MapItemTimelineState`;
  * `MapItemDetailShellController`;
  * Android lifecycle classes.
* [x] Do not add M3C3 fields to `MapItemTimelineState`.
* [x] Do not add M3B cursor or paging fields to M3C3 contracts.
* [x] Do not add timeline dependencies to M3A detail or submission contracts.
* [x] Keep Android rendering and refresh coordination assigned to M3C4.
* [x] Keep all M3C3 projectors Android-independent and read-only.

---

## Application package boundary

* [x] Add a dedicated Coordination participation package outside:

  * Activity packages;
  * Fragment packages;
  * Compose packages;
  * Room implementation packages;
  * transport packages;
  * Situation verification packages where naming would imply acknowledgement is verification.
* [x] Prefer a package such as:

```text
app.peermesh.map.application.participation
```

* [x] Keep participation contracts independent from Android UI classes.
* [x] Keep participation contracts independent from Room entities and annotations.
* [x] Keep participation contracts independent from BLE, relay and transport classes.
* [x] Reuse the accepted M3A `MapItemIdentity`.
* [x] Reuse the accepted M3A detail/domain projection.
* [x] Reuse accepted M3B application-level activity entries only behind a narrow preview projection boundary.
* [x] Do not expose M3B presentation controller state through M3C3 public contracts.
* [x] Do not query M2 or M3B repositories from M3C3 projectors.
* [x] Do not create a second action-history engine.
* [x] Do not persist participation presentation.
* [x] Do not persist note-preview presentation.
* [x] Do not add a Room migration.

Boundary note: `MapItemDomainPresentation.categoryOrigin` still exposes the
M3A-owned `app.peermesh.data.db.MapItemCategoryOrigin`. M3C3 maps that
inherited enum into UI-safe vocabulary; it adds no DAO, Room entity,
repository, database operation, or persistence implementation dependency.

---

## Coordination applicability

* [x] Apply Coordination participation presentation only to recognized Coordination items.
* [x] Return explicit not-applicable presentation for recognized Situation items.
* [x] Preserve unresolved legacy category explicitly.
* [x] Preserve canonical-only detail with unavailable domain enrichment.
* [x] Preserve target-unavailable outcomes.
* [x] Preserve invalid persisted canonical or domain-state outcomes.
* [x] Do not infer Coordination from:

  * canonical kind;
  * acknowledgement history;
  * note history;
  * interaction controls;
  * legacy confidence;
  * legacy votes;
  * legacy contributions.
* [x] Keep recognized legacy Coordination distinguishable from explicit domain-v1 Coordination where relevant.
* [x] Do not convert unresolved category into Coordination merely because acknowledgement or note history exists.
* [x] Keep Coordination lifecycle absent.
* [x] Treat unexpected Coordination lifecycle as invalid persisted state rather than normal presentation.
* [x] Do not apply Situation lifecycle defaults to Coordination.
* [x] Do not attach active, changed, stale or resolved Situation states to Coordination participation.

---

## Current acknowledgement evidence

* [x] Reuse accepted M2 `acknowledgementActorCount`.
* [x] Treat the value as the current distinct actor participation count produced by M2 materialization.
* [x] Preserve the count exactly.
* [x] Do not recount acknowledgement timeline rows.
* [x] Do not count visible acknowledgement entries.
* [x] Do not count Coordination notes as acknowledgements.
* [x] Do not count Situation confirmations.
* [x] Do not count Situation updates.
* [x] Do not count legacy votes.
* [x] Do not count legacy contributions.
* [x] Do not recalculate distinct actors.
* [x] Do not derive actor count from actor labels or display names.
* [x] Do not add weights.
* [x] Do not add trust percentages.
* [x] Do not add quorum or threshold semantics.
* [x] Do not call acknowledgement verification.
* [x] Do not call acknowledgement confirmation.
* [x] Do not imply acknowledgement proves correctness.
* [x] Do not imply acknowledgement guarantees task completion.
* [x] Do not imply acknowledgement guarantees delivery to all peers.
* [x] Do not apply Situation material-change invalidation.
* [x] Do not apply Situation resolution invalidation.
* [x] Do not reset acknowledgement count using Situation lifecycle rules.
* [x] Treat a negative acknowledgement count as invalid persisted presentation state rather than coercing it to zero.

---

## Participation evidence model

Represent current acknowledgement evidence explicitly:

* [x] current participation evidence present;
* [x] current participation evidence absent;
* [x] not applicable;
* [x] unresolved;
* [x] unavailable;
* [x] invalid.

Requirements:

* [x] Define current participation evidence as present only when accepted `acknowledgementActorCount` is greater than zero.
* [x] Define current participation evidence as absent when accepted `acknowledgementActorCount` is exactly zero.
* [x] Do not convert unavailable domain state into zero acknowledgements.
* [x] Do not convert unresolved legacy category into no participation.
* [x] Do not convert invalid persisted state into no participation.
* [x] Do not treat zero current acknowledgements as proof that no historical acknowledgement ever existed.
* [x] Do not derive historical participation existence from the current count.
* [x] Keep evidence descriptive rather than authoritative.
* [x] Do not create verified, trusted, completed or fulfilled states.

---

## Participation vocabulary

* [x] Add stable application-level Coordination participation vocabulary.
* [x] Permit presentation concepts such as:

  * acknowledged by community members;
  * no current acknowledgements;
  * Coordination participation unavailable;
  * unresolved legacy Coordination semantics;
  * historical Coordination note preview available.
* [x] Keep localized user-facing sentences in Android resources.
* [x] Do not expose raw `MAP.ITEM.ACKNOWLEDGE`.
* [x] Do not expose raw `MAP.ITEM.NOTE`.
* [x] Do not expose persistence action enum names.
* [x] Do not expose processor rejection strings.
* [x] Do not expose Room, DAO or SQLite terminology.
* [x] Do not imply acknowledgements prove correctness.
* [x] Do not imply acknowledgements guarantee completion.
* [x] Do not imply acknowledgements guarantee peer delivery.
* [x] Do not introduce participation ranking.
* [x] Do not introduce top contributors.
* [x] Do not introduce trust or reputation language.

---

## Current participation projector

Add one pure projector for current Coordination participation.

Preferred dependency flow:

```text
M2 MapItemDomainStateRepository
        ↓
M3A DefaultMapItemDetailComposer
        ↓
M3A MapItemDetailLoadResult
        ↓
M3C3 Coordination participation projector
        ↓
M3C3 UI-safe current participation presentation
```

Requirements:

* [x] Accept the same accepted M3A detail result or equivalent UI-safe detail snapshot used by M3C1/M3C2.
* [x] Do not independently reload canonical or domain state.
* [x] Do not call repositories.
* [x] Do not call DAOs.
* [x] Do not call reducers.
* [x] Do not call processors.
* [x] Do not replay durable events.
* [x] Do not query action history.
* [x] Do not require timeline state.
* [x] Do not depend on note-preview availability.
* [x] Preserve exact marker or area identity.
* [x] Validate structural consistency of the supplied UI-safe detail projection.
* [x] Prefer a pure projector that does not normally throw.
* [x] Do not swallow fatal JVM `Error` subclasses.
* [x] Preserve ordinary invalid-state classification without exposing technical persistence details.

---

## Coordination note-preview audit

* [x] Audit whether an accepted non-paginated latest Coordination-note application read already exists.
* [x] Do not add a new latest-note repository merely for M3C3.
* [x] Prefer reusing already projected M3B applied-history entries supplied by presentation integration.
* [x] Do not query `MapItemActionQueryRepository` directly from M3C3.
* [x] Do not query `MapItemActionDao`.
* [x] Do not read durable events.
* [x] Do not replay events.
* [x] Do not scan all timeline pages merely to find a note.
* [x] Do not request hidden pages from inside the projector.
* [x] Do not introduce a second action-history engine.
* [x] Keep full Coordination note history under M3B.
* [x] Keep ordering, cursor ownership, refresh, append and deduplication under M3B.

---

## Note-preview source semantics

The note-preview projector may receive only an already ordered collection of M3B application activity entries.

Requirements:

* [x] Treat supplied entries as a partial historical window unless completeness is explicitly established by M3B state outside the projector.
* [x] Preserve the exact order supplied by M3B.
* [x] Do not sort entries again.
* [x] Do not reconstruct ordering from timestamps.
* [x] Do not use UI arrival order.
* [x] Do not use adapter position as semantic identity.
* [x] Select the first valid `COORDINATION_NOTE_ADDED` entry in the supplied newest-first order.
* [x] Do not call the result the globally latest persisted note unless the supplied history is known to establish that fact.
* [x] Prefer wording and contract naming such as:

  * note preview;
  * visible-history note preview;
  * first available Coordination note in supplied history.
* [x] Do not represent absence of a preview as proof that no Coordination note has ever existed.
* [x] Represent preview absence as no preview available in the supplied historical window.
* [x] Do not infer that history is complete from an empty supplied list.
* [x] Do not infer that no note exists from a partial first page containing only acknowledgements.
* [x] Do not infer current Coordination state from preview content.

---

## Note-preview identity and validation

If a note preview is included, expose only UI-safe historical fields such as:

* [x] stable action or entry identity;
* [x] exact map-item identity;
* [x] note content;
* [x] actor presentation;
* [x] action time.

Requirements:

* [x] Use accepted `MapItemActivityEntry.entryId` as stable preview identity.
* [x] Preserve exact group, item type and item ID.
* [x] Reject or return invalid preview result when entry identity conflicts with the requested target.
* [x] Do not select history for a different marker or area sharing the same raw item ID.
* [x] Require activity type to be Coordination note.
* [x] Require nonblank note content.
* [x] Preserve actor presentation from M3B.
* [x] Preserve action timestamp from M3B.
* [x] Do not expose Lamport time.
* [x] Do not expose ordering tuples.
* [x] Do not expose M2 cursor fields.
* [x] Do not expose Room row IDs.
* [x] Do not expose raw event names.
* [x] Do not expose materialization state.
* [x] Do not expose rejection reasons.
* [x] Do not attach information-source semantics to Coordination note preview.
* [x] Treat unexpected Situation update disposition on a Coordination note entry as invalid projected history.
* [x] Treat unexpected Situation lifecycle meaning on a Coordination note entry as invalid projected history.
* [x] Preserve long-content safety for Android rendering without mutating persisted content. — M3C4 bounds preview rendering without changing projected or persisted note content.

---

## Current participation and history separation

* [x] Present current acknowledgement actor count from M2 domain state.
* [x] Present note preview from accepted historical activity only.
* [x] Keep acknowledgement count independent from loaded timeline page size.
* [x] Keep acknowledgement count independent from timeline load success.
* [x] Keep note preview independent from semantic interaction availability.
* [x] Keep note preview independent from acknowledgement count.
* [x] Do not derive current participation from visible timeline rows.
* [x] Do not derive category from notes.
* [x] Do not derive acknowledgement evidence from note existence.
* [x] Do not derive note existence from acknowledgement evidence.
* [x] Do not add current participation fields to `MapItemTimelineState`.
* [x] Do not add timeline cursor fields to M3C3 participation contracts.
* [x] Do not add M3C3 participation state to M3B merge or pagination logic.
* [x] Preserve M3B ordering and pagination ownership.
* [x] Preserve M3A/M2 current-detail authority.
* [x] Ensure timeline failure cannot erase valid current acknowledgement count.
* [x] Ensure valid history cannot convert failed current-detail loading into current participation truth.

---

## Legacy coexistence

* [x] Keep legacy confidence visually and semantically separate from Coordination participation.
* [x] Keep legacy votes visually and semantically separate.
* [x] Keep legacy contributions visually and semantically separate.
* [x] Do not translate legacy contribution into Coordination note.
* [x] Do not translate legacy vote into acknowledgement.
* [x] Do not call legacy confidence participation.
* [x] Do not count legacy activity in `acknowledgementActorCount`.
* [x] Preserve unresolved legacy presentation.
* [x] Permit a compatibility explanation that older activity is displayed separately.
* [x] Keep global terminology migration assigned to M3F.
* [x] Do not rewrite, migrate or relabel legacy history.

---

## Refresh provenance and ownership

M3C3 defines refresh provenance but does not implement refresh orchestration.

Current acknowledgement refresh path:

```text
accepted or remote acknowledgement
        ↓
accepted M2 processing/materialization
        ↓
M3A current-detail reload
        ↓
M3C3 participation re-projection
```

Note-preview refresh path:

```text
accepted or remote Coordination note
        ↓
accepted M2 applied history
        ↓
M3B first-page refresh
        ↓
M3C3 note-preview re-projection
```

Requirements:

* [x] Define current acknowledgement count as refreshed only through accepted M3A detail reload.
* [x] Define note preview as refreshed only from accepted M3B activity snapshots.
* [x] Keep both refresh paths independent.
* [x] Assign actual refresh orchestration to M3C4 Android integration.
* [x] Do not add refresh methods to pure M3C3 projectors.
* [x] Do not make current participation projection trigger timeline refresh.
* [x] Do not make note-preview projection trigger detail refresh.
* [x] Do not increment acknowledgement count optimistically.
* [x] Do not create optimistic note previews.
* [x] Do not infer acknowledgement success from form closure.
* [x] Do not infer note success from form closure.
* [x] Do not infer accepted state from Toast or localized feedback text.
* [x] Preserve accepted local persistence feedback if timeline refresh fails. — accepted feedback and timeline refresh remain independently rendered by the existing controllers.
* [x] Permit current count refresh to succeed while note-preview refresh fails. — detail and timeline snapshots reproject independently.
* [x] Permit note-preview refresh to succeed while detail refresh fails. — timeline publication reprojects from retained detail without changing detail ownership.
* [x] Do not change `SubmitMapInteraction`.
* [x] Do not add timeline dependencies to submission contracts.
* [x] Do not add submission dependencies to M3C3 projectors.

---

## Result contracts

Define a typed current participation projection result distinguishing:

* [x] Coordination participation available;
* [x] Situation participation not applicable;
* [x] unresolved legacy category;
* [x] canonical-only detail with domain state unavailable;
* [x] target unavailable;
* [x] invalid current domain state;
* [x] invalid input identity where applicable;
* [x] internal projection failure only where an ordinary dependency failure exists.

Define a separate typed note-preview projection result distinguishing:

* [x] preview available;
* [x] no preview available in supplied history;
* [x] no target-applicability result is inferred from history-only input;
* [x] invalid supplied activity history;
* [x] invalid input identity where applicable.

Requirements:

* [x] Do not merge current participation failure and note-preview failure into one mandatory result.
* [x] Allow valid current participation presentation when preview projection fails.
* [x] Allow valid historical preview when current detail is temporarily unavailable, without treating preview as current-state authority.
* [x] Do not convert invalid persisted state into zero acknowledgements.
* [x] Do not convert unresolved state into no acknowledgements.
* [x] Do not convert target missing into empty Coordination participation.
* [x] Do not expose raw repository exceptions.
* [x] Do not expose Room, DAO or SQLite terminology.
* [x] Do not swallow fatal JVM `Error` subclasses.

---

## Suggested production contracts

Current participation:

* [x] `MapCoordinationParticipationPresentation`
* [x] Applicability represented by `MapCoordinationParticipationProjectionResult` variants
* [x] `MapCoordinationAcknowledgementEvidence`
* [x] `MapCoordinationParticipationLimitation`
* [x] `MapCoordinationParticipationProjectionResult`
* [x] `ProjectMapCoordinationParticipation`
* [x] `DefaultMapCoordinationParticipationProjector`

Optional historical note preview:

* [x] `MapCoordinationNotePreview`
* [x] `MapCoordinationNotePreviewProjectionResult`
* [x] `ProjectMapCoordinationNotePreview`
* [x] `DefaultMapCoordinationNotePreviewProjector`

Exact names may follow existing project conventions.

Responsibilities must remain narrow:

```text
Participation projector
    → current M2-backed acknowledgement presentation only

Note-preview projector
    → optional preview from already supplied M3B entries only
```

Do not combine both responsibilities into a mandatory timeline-dependent projector.

---

## Dependency flow

Accepted dependency flow:

```text
M2 MapItemDomainStateRepository
        ↓
M3A DefaultMapItemDetailComposer
        ↓
M3A MapItemDetailLoadResult
        ↓
M3C3 participation projector
        ↓
Current Coordination participation presentation
```

Optional historical flow:

```text
M2 MapItemActionQueryRepository
        ↓
M3B LoadMapItemActivity
        ↓
M3B MapItemActivityEntry list
        ↓
M3C3 note-preview projector
        ↓
Optional historical note preview
```

Forbidden dependency flow:

```text
M3C3 participation projector
        ✕→ MapItemTimelineController
        ✕→ MapItemTimelineState
        ✕→ MapItemActionQueryRepository
        ✕→ MapItemActionDao
        ✕→ Room entities
        ✕→ SubmitMapInteraction
```

Requirements:

* [x] Do not add `LoadMapItemActivity` as a dependency of current participation projection.
* [x] Do not add `MapItemTimelineController` as a dependency of either M3C3 projector.
* [x] Do not add `SubmitMapInteraction` as an M3C3 dependency.
* [x] Do not make M3A detail composer depend on M3C3.
* [x] Do not make M3B timeline controller depend on M3C3.
* [x] Keep M3A detail controller as current-state refresh owner.
* [x] Keep M3B timeline controller as history refresh and paging owner.
* [x] Keep M3C3 projectors pure and synchronous where practical.

---

## M3C3 automated tests

### Applicability

* [x] Verify recognized Coordination produces current participation presentation.
* [x] Verify recognized Situation returns not applicable.
* [x] Verify unresolved category remains unresolved.
* [x] Verify canonical-only detail remains unavailable rather than zero participation.
* [x] Verify target missing does not become no-acknowledgement Coordination.
* [x] Verify invalid persisted domain state remains invalid.
* [x] Verify Coordination category is not inferred from history.
* [x] Verify Coordination category is not inferred from canonical kind.
* [x] Verify Coordination category is not inferred from interaction controls.
* [x] Verify Coordination lifecycle remains absent.
* [x] Verify unexpected Coordination lifecycle is invalid.
* [x] Verify marker and area same-ID isolation remains preserved.

### Current acknowledgements

* [x] Verify M2 acknowledgement actor count is reused exactly.
* [x] Verify zero acknowledgements maps to current participation evidence absent.
* [x] Verify positive acknowledgement count maps to current participation evidence present.
* [x] Verify negative acknowledgement count is invalid rather than coerced.
* [x] Verify acknowledgement rows are not recounted.
* [x] Verify visible timeline page size does not affect current count.
* [x] Verify notes are not counted as acknowledgements.
* [x] Verify confirmations are not counted.
* [x] Verify Situation updates are not counted.
* [x] Verify legacy votes are not counted.
* [x] Verify legacy contributions are not counted.
* [x] Verify acknowledgement is not called verification.
* [x] Verify acknowledgement is not called confirmation.
* [x] Verify no weighted confidence is produced.
* [x] Verify no trust percentage is produced.
* [x] Verify no threshold or quorum is produced.
* [x] Verify Situation freshness rules are not applied.
* [x] Verify Situation material-change invalidation is not applied.
* [x] Verify Situation resolution semantics do not reset Coordination participation.

### Note-preview projection

* [x] Verify a supplied Coordination note may produce a preview.
* [x] Verify preview preserves stable entry identity.
* [x] Verify preview preserves exact item identity.
* [x] Verify preview preserves actor presentation.
* [x] Verify preview preserves action time.
* [x] Verify preview preserves note content.
* [x] Verify the first valid Coordination note in supplied newest-first order is selected.
* [x] Verify supplied M3B ordering is preserved without sorting.
* [x] Verify acknowledgement entries are skipped when selecting note preview.
* [x] Verify Situation activity entries are not selected.
* [x] Verify history for a different item identity is rejected or ignored according to the accepted projector contract.
* [x] Verify marker and area with the same raw item ID remain isolated.
* [x] Verify blank note content is invalid.
* [x] Verify unexpected Situation disposition on Coordination note is invalid.
* [x] Verify unexpected Situation lifecycle meaning on Coordination note is invalid.
* [x] Verify preview does not become current domain authority.
* [x] Verify no preview in supplied history does not mean no note has ever existed.
* [x] Verify empty supplied history is represented as no preview available in supplied history.
* [x] Verify a partial page containing no note does not produce a global no-note claim.
* [x] Verify full history remains under M3B.
* [x] Verify no DAO query is introduced.
* [x] Verify no repository query is introduced.
* [x] Verify no durable-event replay is introduced.
* [x] Verify no cursor or page-size field is exposed.
* [x] Verify Lamport and ordering internals are absent.

### Current state and history separation

* [x] Verify current participation works without timeline state.
* [x] Verify timeline failure does not erase valid current acknowledgement count.
* [x] Verify note-preview failure does not invalidate current participation.
* [x] Verify current-detail failure does not convert history into current participation truth.
* [x] Verify note preview existence does not alter acknowledgement evidence.
* [x] Verify acknowledgement count does not imply note existence.
* [x] Verify current participation fields are absent from `MapItemTimelineState`.
* [x] Verify timeline cursor fields are absent from M3C3 contracts.
* [x] Verify M3B ordering and pagination ownership remain unchanged.
* [x] Verify no third asynchronous controller is introduced.

### Refresh provenance

* [x] Verify current participation projection has no independent repository refresh path.
* [x] Verify note-preview projection has no independent history-loading path.
* [x] Verify acknowledgement refresh provenance remains M3A detail reload.
* [x] Verify note-preview refresh provenance remains M3B history refresh.
* [x] Verify no optimistic acknowledgement increment is introduced.
* [x] Verify no optimistic note preview is introduced.
* [x] Verify `SubmitMapInteraction` has no M3C3 dependency.
* [x] Verify M3C3 projectors have no submission dependency.
* [x] Verify current and history refresh operations remain contractually independent.

### Legacy and regression

* [x] Verify legacy contributions remain separate.
* [x] Verify legacy contributions are not translated into Coordination notes.
* [x] Verify legacy votes are not translated into acknowledgements.
* [x] Verify legacy confidence is not participation.
* [x] Verify unresolved legacy presentation remains explicit.
* [x] Verify M3C3 performs no writes.
* [x] Verify no Room entity is exposed.
* [x] Verify no DAO is exposed.
* [x] Verify no action-history engine is added.
* [x] Verify no Situation verification contract is reused as Coordination semantics.
* [x] Verify no Room schema change.
* [x] Verify Room schema remains version 52.

---

## M3C3 non-goals

* [x] Do not implement Situation verification.
* [x] Do not apply Situation freshness.
* [x] Do not apply material-change invalidation.
* [x] Do not attach lifecycle to Coordination.
* [x] Do not create task-completion semantics.
* [x] Do not create correctness or truth semantics.
* [x] Do not create participation ranking.
* [x] Do not expose full acknowledgement actor lists.
* [x] Do not expose full note author lists.
* [x] Do not merge legacy contributions.
* [x] Do not change timeline pagination.
* [x] Do not change timeline merge or deduplication.
* [x] Do not add a latest-note persistence query.
* [x] Do not scan all history pages for a note.
* [x] Do not add a controller.
* [x] Do not integrate M3C3 into `MapItemDetailActivity`.
* [x] Do not add Android resources or final rendering.
* [x] Do not implement refresh orchestration.
* [x] Do not change `SubmitMapInteraction`.
* [x] Do not change acknowledgement submission.
* [x] Do not change Coordination note submission.
* [x] Do not add optimistic participation state.
* [x] Do not add optimistic note previews.
* [x] Do not add persistence.
* [x] Do not add a Room migration.

---

## M3C3 acceptance

* [x] Coordination acknowledgement is presented as independent current participation.
* [x] Current acknowledgement count comes only from accepted M2 state.
* [x] Current acknowledgement count is reused without recalculation.
* [x] Current participation projection does not require timeline history.
* [x] Situation verification, lifecycle and freshness semantics are absent.
* [x] Coordination lifecycle remains absent.
* [x] Unexpected Coordination lifecycle is rejected as invalid persisted presentation state.
* [x] Optional Coordination note preview is derived only from already supplied M3B history.
* [x] Note preview remains historical and optional.
* [x] No preview in supplied history is not presented as proof that no note ever existed.
* [x] Note-preview availability does not affect current acknowledgement evidence.
* [x] Current participation does not affect M3B ordering, paging or cursor ownership.
* [x] Legacy votes, confidence and contributions remain separate.
* [x] No trust score, weighted confidence, quorum, ranking or completion guarantee is introduced.
* [x] No asynchronous controller or third state owner is introduced.
* [x] M3C3 performs no repository query, DAO access, write or replay.
* [x] Refresh provenance is defined while orchestration remains assigned to M3C4.
* [x] No protocol, reducer, replay, processor, materialization, persistence or transport semantics change.
* [x] Room schema remains version 52.
* [x] M3C3 is reviewed and accepted before verification interaction integration begins.

---

# Phase M3C4 — Verification Interaction Integration

## Goal

Integrate accepted M3C Situation verification, freshness and Coordination participation presentation into the M3A item-detail shell and existing interaction flow.

M3C4 must coordinate presentation and refresh behavior without changing M3A3 submission contracts or M2 semantic authority.

## Architecture lock

M3C4 integrates:

* the accepted M3A detail-shell controller;
* the accepted M3B timeline controller;
* pure M3C verification and participation projectors;
* the existing M3A interaction availability and submission boundaries.

The following ownership model is locked:

* [x] `MapItemDetailActivity` remains the Android lifecycle and presentation composition host.
* [x] `MapItemDetailShellController` remains current detail and interaction-state owner.
* [x] `MapItemTimelineController` remains timeline paging and history-state owner.
* [x] M3C projectors remain read-only and Android-independent.
* [x] Do not create a third asynchronous state owner unless independently justified.
* [x] Do not merge timeline state into detail state.
* [x] Do not merge detail state into timeline state.
* [x] Do not make the M3A and M3B controllers depend on each other.
* [x] Do not add timeline dependencies to `SubmitMapInteraction`.
* [x] Do not add submission dependencies to M3C projectors.
* [x] Do not make M3C presentation state authoritative for action acceptance.
* [x] Keep coordination of presentation events in Android integration code.
* [x] Close existing controllers exactly once according to their accepted lifecycle.
* [x] Ensure callbacks after Activity destruction cannot update views.

## Verification section integration

For recognized Situation items:

* [x] Render current distinct confirmer count.
* [x] Render current evidence presence.
* [x] Render latest current confirmation time where safely available.
* [x] Render creator information source where explicitly known.
* [x] Render legacy or unresolved limitations.
* [x] Render active versus resolved current lifecycle.
* [x] Render M3C2 material-change explanation where established.
* [x] Preserve historical confirmations in the M3B timeline.
* [x] Keep current verification visually more authoritative than historical rows.
* [x] Avoid unsupported trust scores, percentages or labels.
* [x] Preserve area reported-condition wording.

For recognized Coordination items:

* [x] Do not render Situation verification.
* [x] Render current acknowledgement participation.
* [x] Render optional note preview where available.
* [x] Keep full note history in the M3B timeline.
* [x] Do not attach Situation freshness or lifecycle language.

For unresolved items:

* [x] Show safe read-only limitation presentation.
* [x] Do not offer verification or participation claims.
* [x] Do not infer category from timeline history.
* [x] Preserve legacy UI separately where currently retained.

## Confirm interaction integration

* [x] Reuse the accepted M3A Confirm interaction.
* [ ] Launch only through accepted M3A availability.
* [x] Do not create a new Confirm command type.
* [x] Do not construct raw events in M3C.
* [x] Do not bypass `SubmitMapInteraction`.
* [x] Do not bypass `RoomMapEventProcessor`.
* [ ] Preserve optional information source and note behavior.
* [ ] Preserve area reported-condition explanation.
* [ ] Prevent duplicate in-flight submission through accepted M3A ownership.
* [x] Do not increment current confirmation count optimistically. — `MapItemDetailActivityAcceptedConfirmRuntimeTest` verifies the count changes only in the accepted fake persisted boundary, then renders after the detail reload.
* [x] Do not synthesize current evidence. — `MapItemDetailActivityAcceptedConfirmRuntimeTest` verifies the initial canonical absence and post-accepted `Current(null)` state are loaded from persisted test authority.
* [x] Do not synthesize latest current confirmation time. — `MapItemDetailActivityAcceptedConfirmRuntimeTest` verifies `Current(null)` renders no latest-confirmation label.
* [x] Reload persisted current state after accepted submission. — `MapItemDetailActivityAcceptedConfirmRuntimeTest` verifies exactly two detail loads and the reprojected count of one.
* [ ] Keep processor rejection authoritative for stale UI.

## Fresh-confirmation CTA behavior

* [x] Show a request-for-fresh-confirmation CTA only when:

  * item is a recognized Situation;
  * current lifecycle permits Confirm;
  * M3A availability permits or advisory-enables Confirm;
  * M3C presentation establishes no current evidence or stale prior evidence.
* [x] Do not show the CTA for Coordination.
* [x] Do not show the CTA for resolved Situation.
* [x] Do not show the CTA for deleted, expired or interaction-closed target.
* [x] Do not show the CTA for unresolved category.
* [x] Do not let the CTA override M3A availability.
* [x] Do not let M3C presentation enable an action blocked by M3A.
* [ ] Keep the processor as final authority.
* [ ] Do not automatically submit Confirm.
* [ ] Do not reuse an old historical confirmation as a new action.
* [x] Keep localized CTA wording in Android resources.

## Coordination interaction integration

* [ ] Reuse accepted M3A Acknowledge interaction.
* [ ] Reuse accepted M3A Add Coordination note interaction.
* [ ] Do not construct new command types.
* [ ] Do not request information source for acknowledgement.
* [ ] Do not request note content for acknowledgement.
* [ ] Require nonblank content for Coordination note through accepted M3A validation.
* [ ] Do not increment acknowledgement count optimistically.
* [ ] Do not insert optimistic note preview.
* [ ] Refresh current acknowledgement count from persisted state.
* [ ] Refresh note history through the timeline controller.
* [x] Keep participation language independent from verification.

## Submission feedback and refresh orchestration

After accepted persisted interaction:

* [ ] Keep accepted M3A submission feedback authoritative.
* [ ] Request current detail refresh.
* [ ] Request M3B first-page timeline refresh according to accepted M3B3 policy.
* [ ] Reproject M3C presentation from refreshed current detail.
* [ ] Do not create synthetic timeline rows.
* [ ] Do not create synthetic verification state.
* [ ] Preserve accepted local state if timeline refresh fails.
* [ ] Preserve accepted local state after post-commit delivery warning.
* [ ] Keep current detail refresh and timeline refresh independent.
* [ ] Ensure each accepted feedback event triggers automatic refresh at most once.

For exact duplicate:

* [ ] Preserve accepted M3A `AlreadyRecorded` behavior.
* [ ] Do not create a second timeline row.
* [ ] Do not increment counts.
* [ ] Do not automatically present the command as newly confirmed.
* [ ] Permit explicit user refresh independently.

For semantic rejection:

* [ ] Preserve processor rejection classification.
* [ ] Refresh current detail only where accepted M3A stale-state policy requires.
* [ ] Do not create timeline rows.
* [ ] Do not alter verification counts.
* [ ] Do not report compatibility warning as semantic rejection.
* [ ] Do not hide historical activity.

For persistence failure:

* [ ] Do not change current verification or participation state.
* [ ] Do not create timeline rows.
* [ ] Do not claim local acceptance.
* [ ] Preserve existing rendered current state.

For accepted-with-delivery-warning:

* [ ] Present local persistence as accepted.
* [ ] Refresh current detail.
* [ ] Refresh timeline according to accepted M3B3 policy.
* [ ] Keep delivery warning separate from verification state.
* [ ] Do not imply current verification failed.
* [ ] Do not imply all peers received the action.

## Remote-state behavior

* [ ] Reflect remote Confirm after accepted current-detail refresh.
* [ ] Reflect remote `situation_changed` after accepted current-detail refresh.
* [ ] Reflect remote resolution after accepted current-detail refresh.
* [ ] Reflect remote Acknowledge after accepted current-detail refresh.
* [ ] Reflect remote Coordination note after explicit or accepted timeline refresh.
* [ ] Do not derive current evidence merely because a new timeline row appears.
* [ ] Do not retain stale verification after current domain state changes.
* [ ] Preserve historical rows after remote material change.
* [ ] Preserve loaded history after target becomes unavailable according to M3B3 policy.
* [ ] Never present retained history as current target availability or verification.

## Current-state and history visual hierarchy

* [ ] Keep current verification or participation summary above or visually distinct from timeline history.
* [ ] Ensure current state does not rely only on color.
* [ ] Ensure historical confirmation rows do not appear as current badges.
* [ ] Ensure stale-history explanation is understandable without inspecting timestamps.
* [ ] Keep resolved state more authoritative than historical confirmation activity.
* [ ] Keep Coordination participation visually distinct from Situation verification.
* [ ] Preserve actor and timestamp readability.
* [ ] Preserve long note and source readability.
* [ ] Avoid dense technical metadata.
* [ ] Avoid raw enum or event names.

## Legacy coexistence

* [ ] Keep legacy confidence separate from M3C confirmation evidence.
* [ ] Keep legacy contributions separate from Coordination participation.
* [ ] Do not translate legacy votes into Confirm.
* [ ] Do not translate legacy contributions into Situation update or Coordination note.
* [ ] Preserve unresolved legacy detail as read-only.
* [ ] Show compatibility explanation where needed.
* [ ] Keep complete global terminology migration assigned to M3F.
* [ ] Do not rewrite or migrate legacy history.

## Dependency wiring

* [x] Construct M3C projectors at the accepted map composition boundary.
* [x] Reuse accepted M3A detail state.
* [x] Reuse accepted M3A availability.
* [x] Reuse accepted M3A submission.
* [x] Reuse accepted M3B timeline controller.
* [x] Do not construct repositories in Activity or adapter code.
* [x] Do not construct DAOs in UI.
* [x] Do not construct reducers in UI.
* [x] Do not construct another `RoomMapEventProcessor`.
* [x] Do not introduce a global mutable verification service.
* [x] Keep projectors testable with plain Kotlin inputs.
* [x] Preserve existing non-map dependency wiring.

## M3C4 automated tests

The original M3C4 automated tests below are pure Kotlin resource/view-instruction mapping, policy, composition, and source-boundary tests. A separate Android runtime instrumentation suite provides layout, resource, widget-state, and Confirm-button routing evidence. Runtime acceptance remains partial until the remaining submission, refresh, lifecycle, remote-state, and legacy scenarios are verified.

### Situation integration

* [x] Verify pure view-state mapping emits current confirmer count for active Situation evidence.
* [x] Verify pure view-state mapping consumes supplied M2 verification projection state.
* [x] Verify pure view-state mapping emits creator-source presentation.
* [x] Verify pure view-state mapping emits latest current confirmation time where supplied.
* [x] Verify pure view-state mapping does not label a resolved Situation as currently confirmed.
* [x] Verify composition preserves supplied M3B timeline provenance across `Available` and `RetainedAfterFailure` inputs, while Activity source wiring retains separate latest detail and timeline snapshots. — `MapItemCommunityComposerM3C4Test.latestInputsAreUsedWithoutRetainingAStaleMixedResult` and `MapItemCommunityM3C4BoundaryTest.activityReprojectsBothSnapshotsWithoutCollapsingTimelineProvenance`.
* [x] Verify pure view-state mapping keeps stale history distinct from current evidence.
* [x] Verify pure view-state mapping emits area reported-condition limitation wording.
* [x] Verify pure view-state mapping keeps unresolved Situation semantics read-only.

### Coordination integration

* [x] Verify pure view-state mapping emits Coordination acknowledgement participation.
* [x] Verify pure view-state mapping hides Situation verification for Coordination.
* [x] Verify pure view-state mapping omits Situation lifecycle for Coordination.
* [x] Verify composition projects optional note preview only from supplied M3B history.
* [x] Verify pure view-state mapping labels acknowledgement independently from verification.
* [x] Verify pure view-state mapping omits Situation freshness for Coordination.
* [ ] Verify legacy contributions remain separate.

### Interaction integration

* [x] Verify community CTA policy intersects freshness with M3A Confirm visibility and enablement.
* [x] Verify the CTA action adapter delegates only `Situation.Confirm`.
* [x] Verify the Android Confirm button delegates through the accepted M3A interaction path at runtime.
* [x] Verify M3C cannot enable Confirm when M3A blocks it.
* [x] Verify fresh-confirmation CTA is absent for resolved Situation.
* [x] Verify fresh-confirmation CTA is absent for Coordination.
* [ ] Verify Acknowledge uses accepted M3A command.
* [ ] Verify Coordination note uses accepted M3A command.
* [x] Verify UI does not construct raw events.
* [x] Verify UI does not call DAOs or reducers.
* [x] Verify the community Confirm CTA is disabled while submission is in flight.

### Refresh orchestration

* [x] Verify accepted Confirm refreshes current detail. — `MapItemDetailActivityAcceptedConfirmRuntimeTest`, Emergency Debug instrumentation, 3 isolated passes and full-flavor runs.
* [x] Verify accepted Confirm requests one timeline first-page refresh. — `MapItemDetailActivityAcceptedConfirmRuntimeTest` asserts one first-page refresh after Accepted.
* [x] Verify reloaded M2-compatible current-detail projection updates verification presentation. — `MapItemDetailActivityAcceptedConfirmRuntimeTest` asserts no evidence initially and current count one after refresh.
* [ ] Verify accepted Acknowledge refreshes current detail.
* [ ] Verify accepted note refreshes timeline.
* [ ] Verify accepted-with-delivery-warning preserves local acceptance.
* [ ] Verify exact duplicate creates no second visible effect.
* [ ] Verify semantic rejection creates no synthetic verification state.
* [ ] Verify persistence failure leaves current state unchanged.
* [ ] Verify timeline refresh failure does not change accepted current-state feedback.
* [ ] Verify detail refresh and timeline refresh remain independent.
* [ ] Verify repeated rendering cannot repeat automatic refresh for one feedback event.

### Remote and stale-state behavior

* [ ] Verify remote Confirm appears after current-state refresh.
* [ ] Verify remote material change invalidates current evidence after refresh.
* [ ] Verify remote resolution overrides historical confirmations.
* [ ] Verify remote acknowledgement updates participation after refresh.
* [ ] Verify new timeline row alone does not change current verification.
* [ ] Verify retained history after target loss does not keep interactions enabled.
* [ ] Verify retained history does not make target appear active or verified.

### Regression

* [ ] Verify all five M3A interactions remain usable.
* [ ] Verify M3B pagination remains unchanged.
* [x] Verify no verification state is added to timeline cursor ownership.
* [x] Verify no timeline dependency is added to submission contracts.
* [x] Verify pure view-state mapping retains marker and area same-ID identity isolation.
* [ ] Verify current marker and area creation remain available.
* [ ] Verify canonical owner edit and delete remain available.
* [ ] Verify no BLE, relay or envelope change.
* [ ] Verify no reducer or materialization change.
* [ ] Verify no Room migration.
* [ ] Verify Room schema remains version 52.

### M3C4 evidence — 2026-07-22

Production implementation: `MapItemDetailActivity`, `MapItemCommunityProjectionComposer`, `MapItemCommunityDetailViewStateMapper`, `MapItemCommunityInteractionPolicy`, `MapItemCommunityConfirmCtaAction`, `MapItemCommunityTimelineInput`, and `strings.xml` provide pure presentation composition and resource/view instructions. The Activity retains the existing M3A interaction entry; M3C4 does not construct commands or invoke submission, persistence, DAO, repository, processor, or writer dependencies.

Original automated test evidence is pure Kotlin, not Android runtime rendering:

* `MapItemCommunityDetailViewStateMapperM3C4Test` (8 tests): resource/view-instruction mapping, including retained preview and retained empty-history limitation resources, CTA visibility, and in-flight disablement.
* `MapItemCommunityInteractionPolicyM3C4Test` (4 tests): freshness/M3A intersection, exactly-one visible-and-enabled Confirm, and action-adapter delegation only to `Situation.Confirm`.
* `MapItemCommunityComposerM3C4Test` (5 tests): composition uses supplied entries for `Available` and `RetainedAfterFailure`, retains provenance, and never calls the note-preview projector for `NotReady` or `Unavailable`.
* `MapItemDetailProjectionInputAdapterM3C4Test` (2 tests): detail-shell input adaptation remains a projection boundary.
* `MapItemCommunityM3C4BoundaryTest` (3 tests): source dependency guards, two-controller ownership, and independent detail/timeline provenance wiring.

Completed Gradle reports:

* `./gradlew :app:testEmergencyDebugUnitTest --tests '*M3C4*' --no-parallel` — Emergency, 22 tests, 0 failed, 0 skipped, passed; report produced.
* `./gradlew :app:testOfficialDebugUnitTest --tests '*M3C4*' --no-parallel` — Official, 22 tests, 0 failed, 0 skipped, passed; report produced.
* `./gradlew :app:testEmergencyDebugUnitTest --tests '*M3C1*' --tests '*M3C2*' --tests '*M3C3*' --tests '*M3C4*' --no-parallel` — Emergency, 72 tests, 0 failed, 0 skipped, passed; report produced.
* `./gradlew :app:testOfficialDebugUnitTest --tests '*M3C1*' --tests '*M3C2*' --tests '*M3C3*' --tests '*M3C4*' --no-parallel` — Official, 72 tests, 0 failed, 0 skipped, passed; report produced.
* `./gradlew :app:testEmergencyDebugUnitTest --no-parallel` — Emergency full app unit suite, 337 tests, 0 failed, 0 skipped, passed; report produced.
* `./gradlew :app:testOfficialDebugUnitTest --no-parallel` — Official full app unit suite, 337 tests, 0 failed, 0 skipped, passed; report produced.

Android runtime instrumentation evidence on device `251fd3e9` (SM-J415F - 9): `MapItemDetailActivityM3C4RuntimeTest` renders supplied Situation and Coordination projections through `MapItemDetailActivity` and verifies resolved Android resources, section visibility, TextView/Button state, and Confirm CTA visibility/enablement. The suite also uses the real Confirm Button `performClick()` path with a recording existing M3A controller boundary: the Confirm CTA delegates only `MapInteractionType.Situation.Confirm` when visible and enabled, and performs no submission; Coordination projections hide the Confirm CTA, while in-flight disabled and hidden CTAs do not delegate. These are runtime renderer and click-routing tests, not end-to-end submission or refresh integration tests.

Completed runtime reports: `connectedEmergencyDebugAndroidTest` and `connectedOfficialDebugAndroidTest` each completed the original five-test class twice with 0 failures and 0 skipped. After the click-routing regression was added, each flavor completed the six-test class with 0 failures and 0 skipped. Latest XML reports are under `app/build/outputs/androidTest-results/connected/debug/flavors/emergency/` and `app/build/outputs/androidTest-results/connected/debug/flavors/official/`; HTML reports are under `app/build/reports/androidTests/connected/debug/flavors/`.

Remaining unchecked limitations: submission outcomes, refresh orchestration, lifecycle recreation, remote-state behavior, legacy coexistence, and manual device walkthrough are not covered by the completed evidence above.

## M3C4 manual checks

* [ ] Open an active Situation marker with no current confirmations.
* [ ] Open an active Situation area with no current confirmations.
* [ ] Verify area confirmation wording does not claim exact boundary accuracy.
* [ ] Confirm an active Situation and verify persisted current count after refresh.
* [ ] Verify latest current confirmation time where supported.
* [ ] Add `still_active` update and verify current evidence remains consistent with accepted M2 semantics.
* [ ] Add `situation_changed` update and verify previous confirmations become historical only.
* [ ] Verify historical confirmation rows remain visible after material change.
* [ ] Add a fresh confirmation after material change.
* [ ] Resolve a Situation and verify current verification is absent.
* [ ] Verify resolved Situation does not offer fresh-confirmation CTA.
* [ ] Verify historical confirmation rows remain visible after resolution.
* [ ] Open Coordination detail with no acknowledgements.
* [ ] Acknowledge Coordination item and verify persisted count after refresh.
* [ ] Add Coordination note and verify preview/history refresh.
* [ ] Verify acknowledgement is never called verification.
* [ ] Verify Coordination receives no Situation freshness wording.
* [ ] Verify unresolved legacy item remains safe and read-only.
* [ ] Verify legacy confidence and contributions remain separate.
* [ ] Verify accepted local state survives timeline refresh failure.
* [ ] Verify accepted local state survives post-commit delivery warning.
* [ ] Verify remote material change updates current verification after refresh.
* [ ] Verify long actor, source and note content remains readable.

## M3C4 non-goals

* [ ] Do not change interaction command contracts.
* [ ] Do not change M2 semantic rejection.
* [ ] Do not add optimistic current verification state.
* [ ] Do not add optimistic timeline rows.
* [ ] Do not expose full confirmer or acknowledger identity lists.
* [ ] Do not add reputation.
* [ ] Do not add moderator approval.
* [ ] Do not add trust percentages.
* [ ] Do not add voting thresholds.
* [ ] Do not add weighted confirmation.
* [ ] Do not automatically resolve conflicting information.
* [ ] Do not add per-peer delivery status.
* [ ] Do not redesign normal map rendering.
* [ ] Do not add persistence.
* [ ] Do not add a Room migration.

## M3C4 acceptance

* [ ] Current Situation verification is integrated into marker and area detail.
* [ ] Current distinct confirmer count comes from accepted M2 state.
* [ ] Materially changed or resolved Situation cannot appear currently confirmed from historical rows.
* [ ] Historical confirmations remain visible.
* [ ] Area confirmation concerns reported condition rather than exact geometry.
* [ ] Coordination acknowledgement remains independent participation.
* [ ] Coordination lifecycle and Situation freshness remain absent.
* [ ] All interaction entry points reuse accepted M3A availability and submission.
* [x] Accepted outcomes refresh persisted current state without optimistic truth. — Accepted Confirm instrumentation mutates only the fake persisted boundary and verifies reloaded authority.
* [x] Timeline refresh remains independent from current-state authority. — Accepted Confirm instrumentation separately asserts detail reload and a single timeline first-page refresh with no synthetic timeline entries.
* [ ] Legacy confidence and contributions remain separate.
* [ ] No trust score, threshold, reputation or moderation is introduced.
* [ ] No protocol, reducer, replay, processor, materialization, persistence or transport semantics change.
* [ ] Room schema remains version 52.
* [ ] M3C4 is reviewed and accepted before overall M3C acceptance.

---

# M3C cross-phase contract audit

## Current-state read contract

* [ ] M3C current verification reads accepted M3A/M2 domain projection.
* [ ] Current confirmation count is not derived from timeline.
* [ ] Current acknowledgement count is not derived from timeline.
* [ ] Current lifecycle is not derived from timeline.
* [ ] Current category is not inferred by M3C.
* [ ] Creator source is not inferred from history or legacy confidence.
* [ ] Room entities are not exposed.
* [ ] Full M2 ordering tuples are not exposed.
* [ ] Marker and area exact typed identity is preserved.

## Historical activity contract

* [ ] M3B timeline remains historical presentation.
* [ ] Historical confirmations remain visible after invalidation.
* [ ] Historical acknowledgements remain historical activity.
* [ ] Historical notes do not become current domain state.
* [ ] Timeline page size does not affect current counts.
* [ ] Visible rows do not determine verification or participation.
* [ ] Pending and rejected actions remain absent from normal timeline.
* [ ] Legacy votes and contributions remain separate.

## Authority contract

* [ ] M2 materialized domain state remains current-state authority.
* [ ] M2 processor remains semantic acceptance authority.
* [ ] M3A availability remains advisory.
* [ ] M3C presentation cannot enable a blocked action.
* [ ] M3C presentation cannot change confirmation or acknowledgement aggregates.
* [ ] M3C presentation cannot change lifecycle.
* [ ] M3C presentation cannot create semantic conflict.
* [ ] M3C presentation remains read-only.

## Refresh contract

* [ ] Current verification refreshes through accepted detail reload.
* [ ] Timeline refresh remains independent.
* [ ] Current-state refresh does not depend on timeline success.
* [ ] Timeline refresh does not determine current-state success.
* [ ] Accepted submission feedback is consumed at most once for automatic refresh.
* [ ] Exact duplicate creates no second visible effect.
* [ ] Semantic rejection creates no synthetic state.
* [ ] Post-commit delivery warning does not undo local acceptance.
* [ ] Remote state changes become visible only after accepted refresh or observation.

## Compatibility contract

* [ ] Legacy confidence is not Community Verification.
* [ ] Legacy vote is not Confirm.
* [ ] Legacy contribution is not Situation update or Coordination note.
* [ ] Coordination acknowledgement is not Situation verification.
* [ ] Mixed-version warning remains separate from semantic state.
* [ ] No peer delivery certainty is inferred.
* [ ] No BLE, relay or envelope changes.
* [ ] Room schema remains version 52.

---

# M3C overall non-goals

* [ ] Do not implement M3D map rendering redesign.
* [ ] Do not implement caching or delivery-state systems as part of this M3E UX
  remediation.
* [ ] Do not implement M3F global terminology migration.
* [ ] Do not add a Room migration.
* [ ] Do not add new tables.
* [ ] Do not change M2 reducers.
* [ ] Do not change action materialization.
* [ ] Do not change confirmation invalidation.
* [ ] Do not change acknowledgement independence.
* [ ] Do not change duplicate classification.
* [ ] Do not change action ordering.
* [ ] Do not change earliest-resolution behavior.
* [ ] Do not change pending-target recovery.
* [ ] Do not change replay.
* [ ] Do not change processor semantics.
* [ ] Do not change BLE or relay protocols.
* [ ] Do not add moderation.
* [ ] Do not add reputation.
* [ ] Do not add trust percentages.
* [ ] Do not add voting thresholds.
* [ ] Do not add weighted confirmations.
* [ ] Do not add automatic conflict resolution.
* [ ] Do not add per-peer delivery tracking.
* [ ] Do not merge legacy and dedicated histories.
* [ ] Do not derive current state from timeline.

---

# M3C overall acceptance

* [x] M3C1 verification presentation contract is accepted.
* [x] M3C2 freshness and material-change UX is accepted.
* [x] M3C3 Coordination participation presentation is accepted.
* [ ] M3C4 verification interaction integration is accepted.
* [x] Current Situation confirmation evidence comes only from accepted M2 domain state.
* [x] Current distinct confirmer count is reused without recalculation.
* [ ] Historical confirmation remains visible after material change or resolution.
* [ ] Historical confirmation is not presented as current evidence after invalidation.
* [x] Resolved Situation cannot appear currently confirmed.
* [x] Area confirmation does not imply exact geometry verification.
* [x] Creator information source limitations are represented honestly.
* [ ] Coordination acknowledgement remains independent participation.
* [ ] Coordination receives no Situation lifecycle or freshness semantics.
* [ ] Legacy confidence, votes and contributions remain separate.
* [x] M3A interaction availability and submission remain unchanged.
* [x] M3B timeline ordering and pagination remain unchanged.
* [ ] Current-state and timeline refresh remain independent.
* [ ] No optimistic current verification or participation state is introduced.
* [ ] No trust score, percentage, threshold, reputation or moderation is introduced.
* [ ] No protocol, reducer, replay, processor, materialization, persistence, transport or relay semantics have changed.
* [ ] Room schema remains version 52.
* [ ] No blocking findings remain.
* [ ] M3C is reviewed and accepted before Phase M3D begins.

---

# Phase M3D — Collaborative Map UX Redesign

## Goal

Redesign the normal map experience around collaborative Situation and Coordination intelligence while preserving all accepted M2 protocol, persistence, reducer, replay, transport and compatibility semantics and all accepted M3A–M3C application and presentation boundaries.

M3D must allow users to:

* understand whether a visible item is a Situation, Coordination item or unresolved legacy item;
* distinguish marker and area items without collapsing their identities;
* distinguish active, stale, resolved, expired, unavailable and unresolved presentation states;
* understand current Situation confirmation evidence without treating it as absolute truth;
* understand current Coordination acknowledgement participation without calling it verification;
* select one exact map item and open the accepted M3 detail surface;
* create Situation and Coordination items through typed creation intent;
* preserve marker and area drafting;
* receive accurate local creation feedback without optimistic persisted truth;
* use the map under dense-item conditions with accessible and legible visual hierarchy.

M3D must not create a second map-domain engine.

M3D must preserve the authority split:

```text
Canonical persisted map state
    = item existence, canonical content and geometry authority

M2 materialized domain state
    = category, lifecycle, temporal, verification and participation authority

M3A–M3C application projections
    = accepted UI-safe semantic inputs where dependency direction permits

M3D application projection
    = map-specific read composition and render-safe semantics only

Presentation state
    = viewport, selection, focus and creation-draft authority

Runtime map objects
    = rendered artifacts and SDK handles only
```

M3D is divided into:

* [ ] Phase M3D1 — Batch Map Read and Semantic Presentation Model;
* [x] Phase M3D2 — Visual Policy and Runtime Marker/Area Rendering;
* [ ] Phase M3D3 — Creation Draft and Canonical Submission Integration;
* [ ] Phase M3D4 — Map-to-Detail Integration, Accessibility and M3D Closure;
* [ ] Phase M3D5 — Manual UX Remediation and Runtime Closure.

---

## M3D protected baseline

M3D must preserve:

* [ ] Room schema version 52;
* [ ] all accepted canonical map event types;
* [ ] all accepted dedicated `MAP.ITEM.*` action types;
* [ ] immutable append-only durable event history;
* [ ] immutable append-only dedicated action history;
* [ ] deterministic canonical and action ordering;
* [ ] the earliest-resolution irreversible Situation lifecycle cutoff;
* [ ] confirmation-freshness invalidation after material Situation change;
* [ ] acknowledgement independence from Situation verification;
* [ ] legacy confidence, contribution and removal-state separation;
* [ ] pending-target recovery semantics;
* [ ] mixed-version compatibility;
* [ ] BLE batching, envelope and relay behavior;
* [ ] `RoomMapEventProcessor` as the sole Room transaction owner;
* [ ] the accepted shared local and remote processing path;
* [ ] the accepted M3A typed item identity;
* [ ] the accepted M3A detail-read and interaction boundaries;
* [ ] the accepted M3B timeline ordering, paging and history ownership;
* [ ] the accepted M3C current verification, freshness and participation semantics;
* [ ] existing canonical owner edit and delete behavior;
* [ ] existing marker and area geometry storage;
* [ ] existing canonical ADD factories and local writer path.

M3D must not:

* [ ] change the event protocol;
* [ ] change Room schema;
* [ ] introduce a Room migration;
* [ ] add a persistence table;
* [ ] change canonical or dedicated-action reducers;
* [ ] change duplicate or fingerprint semantics;
* [ ] change canonical or action ordering;
* [ ] change replay or pending-target recovery;
* [ ] change relay, BLE or envelope formats;
* [ ] reinterpret legacy votes or contributions;
* [ ] create a second persistence or materialization flow;
* [ ] expose Room entities to application or UI contracts;
* [ ] allow UI to call DAOs, reducers or processors directly;
* [ ] use timeline history as current-state authority;
* [ ] derive category from visual kind;
* [ ] recalculate confirmation or acknowledgement aggregates;
* [ ] create optimistic persisted truth;
* [ ] add reflection;
* [ ] add lifecycle workarounds;
* [ ] add test-only production behavior;
* [ ] catch or map fatal JVM `Error` subclasses as normal outcomes.

---

# Phase M3D1 — Batch Map Read and Semantic Presentation Model

## Goal

Create an Android-independent batch read and semantic projection boundary for currently relevant map items.

M3D1 must compose canonical marker/area content and accepted M2 domain state by exact typed identity without integrating the new projection into the production map renderer yet.

M3D1 must not include selection, focus, runtime map objects, Android resources or creation behavior.

---

## M3D1 architecture lock

The accepted read path is:

```text
Canonical marker/area repositories
            +
Accepted M2 domain-state repository
            ↓
M3D1 batch read composition
            ↓
M3D1 UI-safe semantic map presentation
            ↓
M3D2 presentation visual policy
```

The ownership model is locked:

* [x] canonical persistence remains authoritative for item existence, canonical content and geometry;
* [x] accepted M2 domain state remains authoritative for category, lifecycle, accepted temporal semantics, confirmation count and acknowledgement count;
* [x] M3D1 owns exact-identity batch composition and map-specific semantic projection only;
* [x] M3D1 does not own viewport, selection, focus, temporary emphasis or creation drafts;
* [x] M3D1 does not own Android drawable, color, string or map-SDK values;
* [x] M3D1 does not own runtime marker, circle, polygon or overlay handles;
* [x] M3D1 does not own navigation;
* [x] M3D1 does not perform writes;
* [x] M3D1 does not replay history;
* [x] M3D1 does not create an asynchronous presentation controller.

---

## Application package boundary

* [x] Add a dedicated M3D map-presentation application package outside:

  * Activity packages;
  * Fragment packages;
  * Compose packages;
  * map-SDK implementation packages;
  * Room implementation packages;
  * BLE, relay and transport packages.
* [x] Keep public M3D1 contracts independent from Android UI classes.
* [x] Keep public M3D1 contracts independent from Room entities and annotations.
* [x] Keep public M3D1 contracts independent from map-SDK classes.
* [x] Keep public M3D1 contracts independent from BLE, relay and transport classes.
* [x] Reuse the accepted M3A `MapItemIdentity`.
* [x] Reuse accepted M2/M3 UI-safe semantic values where dependency direction remains valid.
* [x] Do not depend on M3B timeline controllers or timeline state.
* [x] Do not depend on M3C Android rendering models.
* [x] Do not use M3C prelocalized or detail-specific rendered text as map-state authority.
* [x] Do not create a parallel category, lifecycle, freshness or participation engine.

---

## Exact item identity

* [x] Preserve exact:

  * group ID;
  * item type;
  * item ID.
* [x] Keep marker or area type explicit.
* [x] Preserve marker/area isolation when both use the same raw item ID.
* [x] Do not key joins by raw item ID alone.
* [x] Validate nonblank group ID.
* [x] Validate nonblank item ID.
* [x] Validate recognized marker or area type.
* [x] Validate that every requested identity belongs to the requested group.
* [x] Return a typed invalid-request result for malformed normal application input.
* [x] Do not throw for ordinary invalid request input.
* [x] Define deterministic duplicate-input handling.
* [x] Do not return duplicate projected items for repeated equal identities.
* [x] Do not silently merge marker and area identities.

---

## Visible-set input boundary

* [x] Define one application request for a bounded collection of exact map-item identities.
* [x] Keep viewport and map-SDK bounds outside the application contract.
* [x] Do not expose Android `LatLng`, map-SDK bounds or camera classes to M3D1.
* [ ] Permit presentation to determine the requested identity set through its existing canonical map query path.
* [x] Keep group scope explicit.
* [x] Define a positive maximum or explicit bounded-load policy where required by current product constraints.
* [x] Reject or safely bound pathological requests rather than issuing unbounded reads.
* [x] Preserve deterministic input-to-output identity mapping.
* [x] Do not treat input order as persisted ordering authority.

---

## Canonical batch read

* [x] Batch-load requested marker canonical state.
* [x] Batch-load requested area canonical state.
* [x] Query marker storage only for marker identities.
* [x] Query area storage only for area identities.
* [x] Do not query both canonical tables for each identity and choose whichever returns first.
* [x] Do not issue one canonical repository query per item.
* [x] Preserve exact group, type and item ID in every returned canonical result.
* [x] Detect identity mismatches between requested identity and returned canonical record.
* [x] Detect duplicate canonical records for one exact identity.
* [x] Keep Room entities inside the data implementation boundary.
* [x] Map canonical rows into strict UI-safe or application-safe values.
* [x] Preserve canonical marker coordinates.
* [x] Preserve canonical radius where applicable.
* [x] Preserve canonical area geometry without rewriting it.
* [x] Validate area geometry as strict closed GeoJSON linear rings with at least three distinct non-closing vertices.
* [x] Preserve each validated ring's closing coordinate and all polygon, ring and coordinate ordering.
* [x] Count area-summary `vertexCount` as geometric non-closing vertices per ring: triangle `3`, triangular hole plus outer triangle `6`, and two-triangle multipolygon `6`.
* [x] Preserve canonical label and note content without mutating persistence.
* [x] Preserve canonical deletion or availability state where accepted.
* [x] Preserve canonical expiry fields where already accepted.
* [x] Do not expose field clocks, Room row IDs or reducer ordering tuples.

---

## Domain-state batch read

* [ ] Batch-load accepted M2 domain state for all exact requested identities.
* [ ] Do not issue one domain-state repository query per item.
* [ ] Preserve exact group, type and item ID joins.
* [ ] Preserve recognized Situation category.
* [ ] Preserve recognized Coordination category.
* [ ] Preserve unresolved legacy category.
* [ ] Preserve category origin where needed for safe presentation.
* [ ] Preserve creator information source where applicable.
* [ ] Preserve Situation lifecycle.
* [ ] Keep Situation lifecycle absent for Coordination.
* [ ] Preserve accepted expiry and interaction temporal semantics needed for map presentation.
* [ ] Preserve current confirmation actor count exactly.
* [ ] Preserve current acknowledgement actor count exactly.
* [ ] Preserve accepted freshness or material-change metadata only through UI-safe values.
* [ ] Do not query action history.
* [ ] Do not replay durable events.
* [ ] Do not recalculate distinct actors.
* [ ] Do not infer category from canonical kind.
* [ ] Do not infer lifecycle from timeline or action history.
* [ ] Do not infer freshness from timestamps in visible history.

---

## True batch requirements

* [x] Implement an explicit logical batch boundary.
* [x] Do not implement the batch boundary as an unbounded sequence of public one-item repository calls.
* [x] Audit existing DAO and repository support before adding new data APIs.
* [x] Prefer explicit typed batch queries.
* [x] If SQLite or Room bind limits require chunking:

  * use explicit bounded chunking in the data layer;
  * keep chunking invisible to application and presentation contracts;
  * preserve exact identity matching;
  * preserve deterministic results;
  * test chunk boundaries;
  * avoid parallel uncontrolled query fan-out.
* [x] Use one accepted clock snapshot for the entire semantic projection batch.
* [x] Do not read the clock separately for every item.
* [x] Fatal clock `Error` subclasses must propagate.
* [x] Ordinary clock or repository `Exception` failures may be mapped only through explicit typed outcomes.

---

## Semantic map-item projection

Create one outer UI-safe semantic map-item projection containing only rendering-relevant facts.

It may include:

* [ ] exact item identity;
* [ ] explicit marker or area shape;
* [ ] normalized canonical kind;
* [ ] normalized Situation, Coordination or unresolved category;
* [ ] category origin limitation where presentation needs it;
* [ ] Situation lifecycle where applicable;
* [ ] accepted temporal state;
* [ ] current Situation confirmation evidence summary;
* [ ] current Coordination acknowledgement summary;
* [ ] creator information-source summary where applicable;
* [ ] safe label or title presentation input;
* [ ] safe owner-note presentation input;
* [ ] marker coordinates and radius;
* [ ] application-safe area geometry;
* [ ] safe geometry summary input;
* [ ] typed presentation limitations.

It must not include:

* [ ] selection;
* [ ] focus;
* [ ] hover;
* [ ] temporary emphasis;
* [ ] creation-draft state;
* [ ] drawable or resource IDs;
* [ ] concrete colors;
* [ ] Android spans;
* [ ] localized final sentences;
* [ ] map-SDK objects;
* [ ] runtime marker or polygon handles;
* [ ] Room entities;
* [ ] DAO values;
* [ ] field clocks;
* [ ] Lamport values;
* [ ] action-order tuples;
* [ ] timeline cursors;
* [ ] full activity history;
* [ ] interaction-submission state.

---

## Shape discrimination

* [ ] Represent marker and area content through a sealed or explicitly discriminated shape model.
* [ ] Do not create one nullable-field structure containing both marker coordinates and area geometry.
* [ ] Marker shape must not expose area geometry.
* [ ] Area shape must not expose marker radius as an area semantic.
* [ ] Preserve marker and area identity under same raw ID.
* [ ] Detect impossible shape and identity combinations.
* [ ] Do not coerce invalid area geometry into a marker.
* [ ] Do not coerce invalid marker coordinates into an area.
* [ ] Do not rewrite persisted geometry to make projection succeed.

---

## Category and lifecycle projection

* [ ] Represent recognized Situation.
* [ ] Represent recognized Coordination.
* [ ] Represent unresolved legacy category distinctly.
* [ ] Do not use one generic `Unknown` state for all failures.
* [ ] Keep unresolved legacy distinct from missing domain enrichment.
* [ ] Keep unresolved legacy distinct from invalid persisted domain state.
* [ ] Keep recognized legacy category origin distinguishable from explicit domain-v1 where required.
* [ ] Keep active Situation distinct from resolved Situation.
* [ ] Keep Coordination lifecycle not applicable.
* [ ] Treat unexpected Coordination lifecycle as invalid persisted state.
* [ ] Do not attach synthetic active lifecycle to Coordination.
* [ ] Do not attach Situation freshness semantics to Coordination.
* [ ] Do not normalize invalid combinations to guessed defaults.

---

## Verification and participation projection

Situation:

* [ ] Reuse current confirmation actor count exactly.
* [ ] Preserve current evidence-present versus current evidence-absent semantics.
* [ ] Preserve resolved-state behavior.
* [ ] Preserve material-change freshness limitation where accepted.
* [ ] Do not calculate trust percentages.
* [ ] Do not create thresholds or quorum.
* [ ] Do not call confirmation absolute truth.
* [ ] Do not imply area confirmation validates exact polygon boundaries.

Coordination:

* [ ] Reuse acknowledgement actor count exactly.
* [ ] Preserve current participation evidence-present versus evidence-absent semantics.
* [ ] Do not call acknowledgement verification.
* [ ] Do not call acknowledgement confirmation.
* [ ] Do not apply Situation material-change invalidation.
* [ ] Do not apply Situation lifecycle.
* [ ] Do not derive participation from visible timeline rows.
* [ ] Do not imply acknowledgement guarantees task completion or peer delivery.

---

## Temporal presentation

* [ ] Reuse accepted M2/M3 temporal semantics.
* [ ] Distinguish current, expired, interaction-closed and unresolved temporal states where relevant.
* [ ] Preserve exact accepted expiry boundary behavior.
* [ ] Preserve exact accepted interaction-cutoff behavior.
* [ ] Keep owner-edit cutoff separate from member-interaction timing.
* [ ] Do not invent a new map-only expiry policy.
* [ ] Do not derive expiry from visual age.
* [ ] Do not derive stale verification from wall-clock age.
* [ ] Do not collapse expired, resolved and deleted into one state.
* [ ] Do not collapse unresolved temporal state into active.
* [ ] Use one clock snapshot per batch.

---

## Presentation cleanup inputs

M3D1 may provide UI-safe inputs for later presentation-only cleanup:

* [ ] bounded label display input;
* [ ] bounded note-preview input;
* [ ] safe area geometry summary;
* [ ] marker coordinate summary input;
* [ ] legacy limitation flags.

Requirements:

* [ ] Do not mutate canonical label or note values in persistence.
* [ ] Do not rewrite persisted GeoJSON.
* [ ] Do not change event payload semantics.
* [ ] Do not add schema fields for formatting.
* [ ] Keep truncation and final localized formatting in presentation code where possible.
* [ ] Preserve full canonical values for detail and owner editing.
* [ ] Detect malformed canonical values rather than silently inventing replacements.

---

## M3D1 failure model

Define typed batch-level and per-item outcomes at UI-safe granularity:

* [x] loaded semantic items;
* [x] invalid batch request;
* [x] canonical item missing;
* [x] domain enrichment missing;
* [x] unresolved legacy category;
* [x] invalid persisted canonical state;
* [x] invalid persisted domain state;
* [x] internal canonical read failure;
* [x] internal domain-state read failure.

Internal validation additionally:

* [x] detects and fails safely on unsupported persisted values;
* [x] detects and fails safely on exact-identity mismatch;
* [x] detects and fails safely on duplicate or conflicting persisted identity.

Public batch-level failures distinguish canonical-read unavailability, domain-state-read
unavailability, clock unavailability and invalid repository results. Public per-item failures
remain intentionally coarse as `InvalidCanonical`, `InvalidDomain` and `Missing`; malformed
data, unsupported values, identity mismatches, duplicate rows and conflicting result buckets
are detected internally without leaking persistence-specific reasons to the renderer.

Requirements:

* [x] A valid canonical item with missing domain enrichment may remain renderable only through an explicit canonical-only/unresolved presentation policy.
* [x] Do not silently present invalid persisted domain state as unresolved legacy state.
* [x] Do not silently drop invalid rows.
* [x] One invalid item should not corrupt unrelated valid items where safe isolation is possible.
* [x] A total repository failure may fail the entire batch.
* [x] Keep technical persistence details out of UI-facing contracts.
* [x] Do not expose raw exceptions.
* [x] Catch ordinary recoverable `Exception` only where explicit mapping is required.
* [x] Do not catch fatal JVM `Error` subclasses.

---

## M3D1 composition boundary

* [x] Add a narrow factory for constructing the M3D1 batch loader.
* [x] Keep `AppDatabase`, DAOs and data adapters inside composition/data code.
* [x] Return only the application batch-load interface.
* [x] Use explicit constructor injection.
* [x] Do not use a global mutable service.
* [x] Do not construct runtime map renderers in M3D1 factory.
* [x] Do not construct creation writers in M3D1 factory.
* [x] Do not modify `GroupMapActivity` production rendering in M3D1.
* [x] Preserve test doubles at application interfaces.

---

## Suggested production contracts

Possible narrow contracts include:

* [ ] `MapItemMapBatchRequest`
* [ ] `MapItemMapBatchResult`
* [ ] `MapItemMapFailure`
* [ ] `MapItemMapPresentation`
* [ ] `MapItemMapShape`
* [ ] `MapItemMapCategory`
* [ ] `MapItemMapLifecycle`
* [ ] `MapItemMapTemporalState`
* [ ] `MapItemMapVerification`
* [ ] `MapItemMapParticipation`
* [ ] `MapItemMapDisplay`
* [ ] `LoadMapItemMapPresentations`
* [ ] `DefaultMapItemMapPresentationComposer`
* [ ] `MapItemMapReadFactory`

Exact names may follow existing conventions, but responsibilities must remain narrow and UI-safe.

---

## M3D1 automated tests

### Identity and batch scope

* [ ] Verify marker identity includes group, type and item ID.
* [ ] Verify area identity includes group, type and item ID.
* [x] Verify marker and area with the same raw ID remain isolated.
* [x] Verify blank group or item ID fails safely.
* [x] Verify mixed-group request fails safely.
* [x] Verify duplicate requested identities follow deterministic policy.
* [x] Verify raw item ID alone is never used as join authority.

### Canonical batch loading

* [ ] Verify marker identities use marker batch query.
* [ ] Verify area identities use area batch query.
* [x] Verify canonical reads are not issued once per item.
* [ ] Verify canonical DAO row order does not change identity mapping.
* [ ] Verify missing canonical item is explicit.
* [x] Verify duplicate canonical identity is invalid.
* [x] Verify canonical identity mismatch is invalid.
* [ ] Verify Room entities do not escape the data boundary.
* [x] Verify chunking boundaries where chunking is implemented.

### Domain-state batch loading

* [x] Verify one logical domain-state batch read.
* [ ] Verify domain state is joined by exact group, type and item ID.
* [ ] Verify domain-state row order does not alter result.
* [ ] Verify recognized Situation projection.
* [ ] Verify recognized Coordination projection.
* [x] Verify unresolved legacy category remains unresolved.
* [x] Verify missing domain enrichment follows explicit policy.
* [x] Verify invalid domain state is not treated as missing or unresolved.
* [ ] Verify category is not inferred from canonical kind.
* [ ] Verify lifecycle is not inferred from history.
* [ ] Verify no action-history query or replay is used.

### Semantic projection

* [ ] Verify marker shape projection.
* [ ] Verify area shape projection.
* [ ] Verify Coordination lifecycle remains not applicable.
* [ ] Verify unexpected Coordination lifecycle is invalid.
* [ ] Verify resolved Situation cannot expose current confirmation evidence.
* [ ] Verify current confirmation actor count is reused exactly.
* [ ] Verify acknowledgement actor count is reused exactly.
* [ ] Verify confirmation is not converted to percentage or trust score.
* [ ] Verify acknowledgement is not called verification.
* [ ] Verify area confirmation does not imply exact-boundary verification.
* [x] Verify expired, resolved and deleted states remain distinct.
* [x] Verify one batch uses one clock snapshot.
* [x] Verify fatal clock and repository `Error` subclasses propagate.

### Partial failure isolation

* [x] Verify one invalid item does not remove unrelated valid items.
* [x] Verify invalid rows are not silently discarded.
* [x] Verify top-level read failure remains distinguishable from per-item invalid state.
* [ ] Verify missing enrichment does not become zero verification or participation.
* [ ] Verify unsupported future persisted values do not map to guessed defaults.

### Boundary and regression

* [x] Verify M3D1 application contracts contain no Room, DAO, Android UI or map-SDK dependencies.
* [x] Verify M3D1 does not depend on M3B timeline controller or state.
* [x] Verify M3D1 does not depend on submission.
* [x] Verify M3D1 does not modify `GroupMapActivity`.
* [x] Verify no creation behavior is introduced.
* [x] Verify no protocol, reducer, replay, processor or transport change.
* [x] Verify no Room migration.
* [x] Verify Room schema remains version 52.

---

## M3D1 non-goals

* [ ] Do not integrate the semantic projection into production map rendering.
* [ ] Do not define Android drawables or colors.
* [ ] Do not add selection or focus state.
* [ ] Do not add a runtime renderer registry.
* [ ] Do not modify map-SDK marker or area creation.
* [ ] Do not change creation terminology.
* [ ] Do not submit canonical ADD events.
* [ ] Do not navigate to detail.
* [ ] Do not remove legacy map presentation.
* [ ] Do not implement accessibility rendering.
* [ ] Do not add persistence.
* [ ] Do not add a Room migration.

---

## M3D1 acceptance

* [x] One clean batch map-read boundary exists.
* [x] Canonical and domain state are loaded without unbounded N+1 reads.
* [x] Exact group, item type and item ID are preserved.
* [x] Marker and area same-ID isolation is preserved.
* [x] One UI-safe semantic map projection exists.
* [x] Selection, focus and creation-draft state are absent from the semantic projection.
* [x] Category, lifecycle, verification and participation reuse accepted authority.
* [x] Missing, unresolved and invalid states remain distinguishable.
* [x] Invalid rows are not silently discarded.
* [x] One batch uses one consistent clock snapshot.
* [x] Room entities, Android resources and map-SDK objects are not exposed.
* [x] No production renderer or creation integration is implemented early.
* [x] No protocol, reducer, replay, processor, persistence or transport semantics change.
* [x] Room schema remains version 52.
* [x] M3D1 is reviewed and accepted before runtime rendering integration begins.

---

# Phase M3D2 — Visual Policy and Runtime Marker/Area Rendering

## Goal

Integrate M3D1 semantic map projections into a deterministic runtime marker/area renderer while preserving semantic authority outside the map SDK.

M3D2 must establish a clear visual hierarchy for Situation, Coordination, lifecycle, temporal, verification and participation states.

M3D2 must not change creation behavior.

---

## M3D2 architecture lock

The accepted rendering path is:

```text
M3D1 semantic map presentation
            +
Ephemeral selection/focus state
            ↓
M3D2 visual policy
            ↓
Immutable render specifications
            ↓
Runtime map-SDK renderer
            ↓
SDK marker/area artifacts
```

Ownership is locked:

* [ ] M3D1 semantic projection remains the source for renderable domain facts.
* [ ] Presentation owns viewport, selection, focus and temporary emphasis.
* [ ] Visual policy owns mapping semantic facts plus presentation emphasis into platform-neutral render specifications.
* [ ] Android resource mapping owns drawable, color, dimension and localized accessibility resources.
* [ ] Runtime renderer owns SDK object handles and diff application only.
* [ ] Runtime renderer does not query repositories.
* [ ] Runtime renderer does not evaluate domain semantics.
* [ ] SDK object registry is a cache, not state authority.
* [ ] Renderer artifacts must never be used to reconstruct persisted or domain state.

---

## Presentation state owner

* [ ] Add one Android-independent or minimally Android-coupled map presentation state owner where needed.
* [ ] Keep this owner separate from M3A detail controller.
* [ ] Keep this owner separate from M3B timeline controller.
* [ ] Do not add map-visible items to `MapItemDetailShellState`.
* [ ] Do not add timeline state to map state.
* [ ] Do not make map state authoritative for detail or domain semantics.
* [ ] Keep viewport request state explicit.
* [ ] Keep selected identity explicit.
* [ ] Keep focused identity explicit where focus differs from selection.
* [ ] Keep load and render operation state separate from semantic item data.
* [ ] Keep selection and focus ephemeral.
* [ ] Do not persist selection or focus as domain state.
* [ ] Define behavior when selected item disappears from the accepted snapshot.
* [ ] Define behavior when selected item becomes unavailable or invalid.
* [ ] Define behavior when viewport changes while a batch load is in flight.
* [ ] Define controller close or lifecycle invalidation behavior.

---

## Selection and emphasis state

* [ ] Represent selected identity independently from each semantic item.
* [ ] Represent focused identity independently where needed.
* [ ] Do not store `selected` or `focused` booleans in persisted or M3D1 semantic projections.
* [ ] Do not duplicate selected identity in Activity fields, adapter rows and SDK tags as competing authorities.
* [ ] SDK marker/polygon selected style must be a rendered consequence.
* [ ] Define normal emphasis.
* [ ] Define selected emphasis.
* [ ] Define focused emphasis where required.
* [ ] Define creation-draft emphasis only in M3D3.
* [ ] Define deterministic priority when selected, focused, stale or unavailable states coexist.
* [ ] Do not let emphasis alter semantic category, lifecycle or verification state.

---

## Visual policy boundary

* [ ] Add one platform-neutral visual-policy boundary.
* [ ] Accept M3D1 semantic item plus ephemeral selection/focus state.
* [ ] Return immutable render specifications.
* [ ] Keep resource IDs outside application semantic contracts.
* [ ] Keep map-SDK classes outside visual-policy contracts.
* [ ] Do not let runtime renderer choose Situation versus Coordination.
* [ ] Do not let runtime renderer choose active versus resolved.
* [ ] Do not let runtime renderer derive stale versus current evidence.
* [ ] Do not let runtime renderer interpret confirmation or acknowledgement counts.
* [ ] Keep visual policy deterministic and pure where practical.
* [ ] Reject unsupported semantic combinations rather than silently choosing default active style.

---

## Render specification

A render specification may contain:

* [ ] exact item identity;
* [ ] marker or area render shape;
* [ ] category visual family;
* [ ] canonical-kind visual subtype;
* [ ] lifecycle visual treatment;
* [ ] temporal visual treatment;
* [ ] current verification or participation indicator;
* [ ] unresolved or unavailable limitation treatment;
* [ ] selection/focus emphasis;
* [ ] z-order group;
* [ ] label visibility policy;
* [ ] accessibility presentation key or typed description input.

It must not contain:

* [ ] repositories;
* [ ] DAOs;
* [ ] Room entities;
* [ ] domain-state mutation methods;
* [ ] timeline rows;
* [ ] interaction commands;
* [ ] event payloads;
* [ ] runtime SDK handles;
* [ ] raw persisted enum strings.

---

## Situation and Coordination hierarchy

* [ ] Distinguish Situation and Coordination using more than one subtle visual difference where practical.
* [ ] Avoid relying only on color.
* [ ] Permit shape, iconography, border, pattern or label hierarchy to reinforce category.
* [ ] Keep canonical kind as a subordinate visual subtype.
* [ ] Do not allow canonical kind to override category.
* [ ] Do not infer category from legacy marker icon.
* [ ] Keep unresolved category visually distinct from recognized Situation and Coordination.
* [ ] Do not present unresolved state as normal active state.
* [ ] Avoid alarming emergency semantics for Coordination unless canonical kind itself requires it.
* [ ] Preserve current canonical item meaning while introducing collaborative category hierarchy.

---

## Situation lifecycle and freshness visuals

* [ ] Distinguish active Situation.
* [ ] Distinguish resolved Situation.
* [ ] Distinguish expired Situation.
* [ ] Distinguish unresolved temporal state.
* [ ] Distinguish current confirmation evidence from no current evidence.
* [ ] Distinguish accepted stale-after-change presentation where available.
* [ ] Do not show historical confirmation rows as current map evidence.
* [ ] Do not calculate freshness from age or timeline timestamps.
* [ ] Do not imply a positive confirmation count proves truth or safety.
* [ ] Do not imply zero confirmation count proves falsehood.
* [ ] Do not let resolved style resemble active-confirmed style.
* [ ] Keep canonical deletion distinct from resolution.
* [ ] Define visual priority for resolved plus expired states.
* [ ] Define visual priority for invalid or unavailable projection.

---

## Coordination participation visuals

* [ ] Distinguish Coordination from Situation.
* [ ] Present acknowledgement only as participation.
* [ ] Do not call acknowledgement verification.
* [ ] Do not apply Situation lifecycle.
* [ ] Do not apply Situation freshness.
* [ ] Do not imply acknowledgement means completed.
* [ ] Do not imply acknowledgement means delivered to all peers.
* [ ] Do not turn acknowledgement count into a trust score.
* [ ] Define a calm participation indicator that remains legible under dense-map conditions.
* [ ] Keep optional note preview out of map-marker semantic authority unless explicitly and safely surfaced.

---

## Marker rendering

* [ ] Render marker items through immutable render specifications.
* [ ] Use exact typed identity as runtime registry key.
* [ ] Do not use raw item ID alone.
* [ ] Add new marker artifact when identity first appears.
* [ ] Update existing marker artifact when its render specification changes.
* [ ] Remove marker artifact when identity is absent from the latest accepted snapshot.
* [ ] Preserve marker coordinate authority from canonical state.
* [ ] Preserve radius rendering where currently supported.
* [ ] Keep radius artifact tied to exact marker identity.
* [ ] Do not create duplicate marker or radius objects for repeated equal snapshots.
* [ ] Define z-index and selected-state behavior deterministically.
* [ ] Preserve click hit-testing identity.
* [ ] Do not store domain-state objects in SDK tags.

---

## Area rendering

* [ ] Render area items through immutable render specifications.
* [ ] Use exact typed identity as runtime registry key.
* [ ] Do not use raw item ID alone.
* [ ] Add new area artifact when identity first appears.
* [ ] Update existing area artifact when its render specification changes.
* [ ] Remove area artifact when identity is absent from the latest accepted snapshot.
* [ ] Preserve canonical area geometry.
* [ ] Do not rewrite or simplify persisted geometry in the renderer.
* [ ] Keep visual simplification, if introduced, separate from canonical truth and explicitly reviewed.
* [ ] Do not imply confirmation validates exact area boundaries.
* [ ] Preserve area click selection.
* [ ] Avoid duplicate polygon, multipolygon or outline artifacts.
* [ ] Define fill, outline, z-order and selected-state policy.
* [ ] Do not store domain-state objects in SDK tags.

---

## Runtime registry

* [ ] Add one runtime registry owned by the renderer.
* [ ] Key all artifacts by exact typed `MapItemIdentity`.
* [ ] Keep marker and area registries logically isolated where SDK artifact types differ.
* [ ] Store SDK handles only.
* [ ] Do not store repositories.
* [ ] Do not store Room entities.
* [ ] Do not store mutable domain projections as authority.
* [ ] Do not reconstruct semantic state from artifact properties.
* [ ] Ensure renderer `clear` removes only renderer-owned artifacts.
* [ ] Ensure repeated `clear` is safe.
* [ ] Ensure rendering the same snapshot twice is idempotent.
* [ ] Ensure removed identities are cleaned deterministically.
* [ ] Ensure marker/area type changes for one identity fail safely rather than leaking old artifacts.

---

## Snapshot and diff behavior

* [ ] Apply one immutable accepted render snapshot at a time.
* [ ] Include a generation or request ownership token where asynchronous reads exist.
* [ ] Prevent obsolete viewport results from replacing newer accepted snapshots.
* [ ] Do not rely only on best-effort coroutine/task cancellation.
* [ ] Keep stale-result ownership checks authoritative.
* [ ] Diff by exact identity.
* [ ] Preserve deterministic add, update and remove behavior.
* [ ] Do not globally clear and recreate every artifact unless evidence proves it is required and acceptable.
* [ ] Avoid flicker during ordinary refresh.
* [ ] Preserve selected state across equivalent item updates.
* [ ] Clear selection when selected identity is no longer present according to explicit policy.
* [ ] Do not allow append-only timeline activity to trigger map duplication.
* [ ] Do not infer map item removal from transient read failure.

---

## Dense-map behavior

* [ ] Define bounded behavior for large visible-item sets.
* [ ] Preserve readable category distinction at normal zoom.
* [ ] Avoid rendering full labels for every item when density makes them unreadable.
* [ ] Define label suppression or prioritization as presentation policy.
* [ ] Keep selected and focused items visually discoverable.
* [ ] Avoid count badges that become visually misleading at small scale.
* [ ] Avoid per-item repository reads during pan or zoom.
* [ ] Avoid uncontrolled renderer recreation during camera movement.
* [ ] Coalesce or supersede obsolete viewport requests where appropriate.
* [ ] Do not add clustering semantics that merge marker and area identity unless independently designed and reviewed.
* [ ] Do not let dense-map optimization change domain meaning.

---

## Runtime error behavior

* [ ] Isolate failure to render one item where safe.
* [ ] Do not silently display an invalid item as normal.
* [ ] Preserve already accepted runtime artifacts during transient top-level refresh failure where policy permits.
* [ ] Do not clear the map merely because optional domain enrichment refresh fails.
* [ ] Distinguish read failure from empty accepted snapshot.
* [ ] Distinguish invalid render specification from unsupported SDK operation.
* [ ] Keep technical errors out of user-facing copy.
* [ ] Do not catch fatal JVM `Error` subclasses.
* [ ] Ensure renderer failure cannot mutate persisted state.

---

## Suggested production contracts

Possible contracts include:

* [ ] `GroupMapState`
* [ ] `GroupMapLoadState`
* [ ] `GroupMapSelectionState`
* [ ] `GroupMapController`
* [ ] `DefaultGroupMapController`
* [ ] `MapItemVisualPolicy`
* [ ] `MapItemRenderSpec`
* [ ] `MapItemRenderShape`
* [ ] `MapCategoryVisualStyle`
* [ ] `MapLifecycleVisualStyle`
* [ ] `MapTemporalVisualStyle`
* [ ] `MapEvidenceVisualStyle`
* [ ] `MapItemEmphasis`
* [ ] `MapRenderSnapshot`
* [ ] `MapRuntimeRenderer`
* [ ] `MapRuntimeRenderRegistry`
* [ ] `MapVisualResourceResolver`

Exact names may follow current project conventions.

---

## M3D2 automated tests

### Visual policy

* [ ] Verify Situation and Coordination produce distinct visual families.
* [ ] Verify canonical kind remains subordinate to category.
* [ ] Verify category is not inferred inside visual policy.
* [ ] Verify unresolved category uses explicit limitation treatment.
* [ ] Verify active and resolved Situation differ.
* [ ] Verify expired and resolved remain distinct.
* [ ] Verify current evidence and no current evidence differ without implying truth.
* [ ] Verify stale-after-change does not appear current.
* [ ] Verify Coordination acknowledgement is not mapped to verification style.
* [ ] Verify Coordination receives no Situation lifecycle style.
* [ ] Verify selected and focused emphasis do not change semantic category.
* [ ] Verify invalid semantic combinations fail safely.

### Renderer registry

* [ ] Verify initial marker add.
* [ ] Verify initial area add.
* [ ] Verify marker update reuses exact identity.
* [ ] Verify area update reuses exact identity.
* [ ] Verify absent marker is removed.
* [ ] Verify absent area is removed.
* [ ] Verify rendering the same snapshot twice creates no duplicate artifacts.
* [ ] Verify marker and area with the same raw ID remain isolated.
* [ ] Verify marker radius artifact follows marker identity.
* [ ] Verify clear removes owned artifacts exactly once.
* [ ] Verify SDK handles are not state authority.

### Snapshot concurrency

* [ ] Verify obsolete viewport result cannot overwrite newer state.
* [ ] Verify controller close prevents later publication.
* [ ] Verify best-effort cancellation is not the sole stale-result protection.
* [ ] Verify transient read failure does not become accepted empty snapshot.
* [ ] Verify selected identity persists across equivalent refresh.
* [ ] Verify selected identity clears according to policy when item disappears.
* [ ] Verify callbacks are not published after lifecycle close.
* [ ] Verify fatal execution `Error` subclasses propagate.

### Marker and area semantics

* [ ] Verify marker coordinates come only from canonical projection.
* [ ] Verify area geometry comes only from canonical projection.
* [ ] Verify area rendering does not claim exact-boundary verification.
* [ ] Verify renderer does not calculate confirmation or acknowledgement meaning.
* [ ] Verify renderer does not access repositories or DAOs.
* [ ] Verify runtime tags do not contain Room entities or mutable domain authority.

### Dense-map behavior

* [ ] Verify bounded label policy.
* [ ] Verify selected item remains emphasized under dense input.
* [ ] Verify obsolete camera requests are superseded.
* [ ] Verify no per-item repository reads occur during one batch refresh.
* [ ] Verify input ordering differences do not create duplicate artifacts.

### Boundary and regression

* [ ] Verify runtime renderer has no repository dependency.
* [ ] Verify visual policy has no map-SDK dependency.
* [ ] Verify selection state remains outside M3D1 semantic projection.
* [ ] Verify no creation behavior changes.
* [ ] Verify detail, timeline and M3C projectors remain independent.
* [ ] Verify no protocol, reducer, replay, processor or transport change.
* [ ] Verify no Room migration.
* [ ] Verify Room schema remains version 52.

---

## M3D2 instrumentation tests

* [ ] Open production group map.
* [ ] Render one Situation marker.
* [ ] Render one Coordination marker.
* [ ] Render one Situation area.
* [ ] Render one Coordination area.
* [ ] Verify marker and area same raw ID remain independently selectable.
* [ ] Verify selected item receives selected treatment.
* [ ] Verify resolved Situation differs from active Situation.
* [ ] Verify expired item differs from active item.
* [ ] Verify unresolved item uses safe limitation treatment.
* [ ] Verify map refresh updates an existing artifact without duplication.
* [ ] Verify removed item disappears.
* [ ] Verify Activity recreation does not resurrect obsolete render snapshot.
* [ ] Verify runtime renderer does not block detail interaction.

---

## M3D2 manual checks

* [ ] Verify Situation and Coordination are distinguishable without relying only on color.
* [ ] Verify active, resolved, expired, stale and unresolved states are understandable.
* [ ] Verify confirmation count is not presented as absolute truth.
* [ ] Verify acknowledgement is not called verification.
* [ ] Verify area styling does not imply exact-boundary verification.
* [ ] Verify selected marker remains visible under dense-map conditions.
* [ ] Verify selected area remains visible under dense-map conditions.
* [ ] Verify long labels do not make the map unusable.
* [ ] Verify small-screen rendering.
* [ ] Verify light and dark themes where supported.
* [ ] Verify low-contrast and color-vision accessibility.
* [ ] Verify pan and zoom do not cause obvious duplicate artifacts or stale flashes.

---

## M3D2 non-goals

* [ ] Do not redesign creation flow.
* [ ] Do not change canonical ADD submission.
* [ ] Do not add creation drafts.
* [ ] Do not modify detail interactions.
* [ ] Do not modify timeline paging.
* [ ] Do not add clustering unless separately reviewed.
* [ ] Do not add map-based moderation.
* [ ] Do not add per-peer delivery indicators.
* [ ] Do not add persistence.
* [ ] Do not add a Room migration.

---

## M3D2 acceptance

* [x] Production map consumes accepted M3D1 semantic projections.
* [x] Situation and Coordination are visually distinguishable.
* [x] Marker and area identities remain isolated.
* [x] Active, resolved, expired, stale and unresolved presentations remain distinguishable.
* [x] Selection and focus remain presentation state.
* [x] Runtime renderer contains no domain-semantic decisions.
* [x] Runtime renderer queries no repositories or DAOs.
* [x] Renderer updates are idempotent by exact identity.
* [x] Obsolete viewport results cannot overwrite newer accepted state.
* [x] Removed items are cleaned deterministically.
* [x] Dense-map behavior remains bounded and legible.
* [x] Area presentation does not imply exact-boundary verification.
* [x] No creation behavior is changed early.
* [x] No protocol, reducer, replay, processor, persistence or transport semantics change.
* [x] Room schema remains version 52.
* [x] M3D2 is reviewed and accepted before creation-flow integration begins.

### M3D2 closure verification (2026-07-27)

Review accepted the M3D2 closure. Focused JVM coverage passed for scope/retry
ownership and controller behavior in both Emergency and Official distributions.
Focused instrumentation passed on device `251fd3e9`, covering Activity dialog
scope transitions, retry cancellation, exact same-raw-ID trail isolation, and
runtime renderer update/remove/idempotency behavior.

Non-blocking follow-up:

* [ ] Move or replace `GroupMapActivityTestSeams` so release production code does
  not retain mutable test-only behavior switches; use a test-source or debug-only
  dependency seam instead.

---

# Phase M3D3 — Creation Draft and Canonical Submission Integration

## Goal

Replace legacy creation vocabulary with typed Situation and Coordination creation intent while preserving canonical marker/area ADD construction, local writer ownership, drafting behavior and authoritative persisted outcomes.

M3D3 must keep draft state separate from persisted map state and selected-item state.

---

## M3D3 architecture lock

The accepted creation path is:

```text
User creation intent
        ↓
Ephemeral typed creation draft
        ↓
Application creation validation and compatibility policy
        ↓
Existing accepted canonical ADD factory or narrow adapter
        ↓
Existing local map writer
        ↓
RoomMapEventProcessor
        ↓
Canonical persistence and M2 materialization
        ↓
Post-commit transport
        ↓
Persisted map reload
```

Ownership is locked:

* [x] presentation owns ephemeral creation draft state;
* [x] application creation policy owns valid category-kind-shape combinations;
* [x] accepted canonical factory owns canonical ADD event construction;
* [x] trusted application context owns event ID, actor, timestamp and Lamport values;
* [x] existing local writer owns local submission;
* [x] `RoomMapEventProcessor` remains sole Room transaction owner;
* [x] persisted reload remains map truth after submission;
* [x] temporary marker/area draft artifacts are renderer-owned consequences;
* [x] draft state is not canonical state;
* [x] selection state is not creation-draft state;
* [x] post-commit delivery warning does not roll back accepted local persistence.

---

## Creation entry vocabulary

* [x] Replace normal creation entry vocabulary with Situation and Coordination.
* [x] Keep final localized wording in Android resources.
* [ ] Do not expose raw domain or event enum names.
* [ ] Do not expose canonical event types.
* [ ] Do not expose wire payload fields.
* [ ] Clearly explain the distinction between:

  * reporting a Situation;
  * creating a Coordination item.
* [ ] Preserve safe legacy creation terminology only where temporary coexistence is required.
* [ ] Remove old terminology only after the new creation path is complete and verified.
* [ ] Do not relabel existing legacy persisted history.

---

## Typed creation intent

Add typed creation intent containing only user-supplied application input.

It may include:

* [x] group ID through trusted navigation or screen context;
* [x] explicit marker or area shape;
* [x] Situation or Coordination category;
* [x] compatible canonical kind;
* [x] label or title;
* [x] owner note where accepted;
* [x] marker coordinates;
* [x] radius where applicable;
* [x] area draft geometry;
* [x] expiry input where accepted;
* [x] creator information source only where required or permitted.

It must not include:

* [x] Room entities;
* [x] raw JSON payload maps;
* [x] arbitrary event ID;
* [x] arbitrary actor DID;
* [x] arbitrary Lamport time;
* [x] transport metadata;
* [x] reducer ordering fields;
* [x] domain-state aggregate counts;
* [x] optimistic verification or acknowledgement state;
* [x] runtime SDK handles.

---

## Category-kind compatibility policy

* [x] Add one explicit application compatibility policy.
* [x] Keep compatibility outside arbitrary UI dropdown combinations.
* [x] Define allowed Situation kinds.
* [x] Define allowed Coordination kinds.
* [x] Define allowed marker shapes per kind.
* [x] Define allowed area shapes per kind.
* [x] Define creator information-source requirements per category and accepted contract.
* [x] Reject invalid domain-v1 combinations before event construction.
* [x] Keep processor and structural event validation authoritative.
* [x] Do not infer category solely from selected canonical kind.
* [ ] Do not infer canonical kind solely from free-form label.
* [ ] Do not let UI create a recognized category with invalid source/lifecycle semantics.
* [ ] Do not attach Situation lifecycle to Coordination creation.
* [ ] Do not create initial confirmation or acknowledgement aggregates in UI.
* [ ] Do not add kind-specific collaborative interactions.

---

## Creation draft state

* [x] Add one typed ephemeral creation-draft state.
* [x] Keep marker and area drafts explicitly discriminated.
* [x] Keep draft state separate from persisted item projection.
* [x] Keep draft state separate from selected persisted-item state.
* [x] Keep form fields and draft geometry under one state owner.
* [x] Avoid duplicate draft authority across Activity fields, dialog fields and SDK artifacts.
* [x] Temporary SDK marker, circle, polyline or polygon objects must render the draft state only.
* [x] Do not treat temporary artifacts as canonical truth.
* [ ] Define initial draft state.
* [ ] Define editing draft state.
* [ ] Define validating state.
* [x] Define submitting state.
* [x] Recover an ordinarily rejected executor submission to a retryable draft failure.
* [x] Define input failure state.
* [x] Define accepted-local result.
* [x] Define semantic or structural rejection.
* [x] Define persistence failure.
* [x] Define accepted-with-delivery-warning.
* [x] Define cancel behavior.
* [ ] Define behavior under Activity recreation where supported.
* [ ] Define behavior when group membership changes while drafting.
* [ ] Define behavior when map camera changes during drafting.
* [ ] Do not persist draft as domain state merely for lifecycle convenience.

---

## Marker drafting

* [ ] Preserve current marker placement behavior unless explicitly improved.
* [ ] Preserve canonical coordinate precision.
* [ ] Preserve radius drafting where currently supported.
* [ ] Keep temporary marker and radius tied to one draft identity.
* [ ] Do not add duplicate temporary marker artifacts.
* [ ] Permit cancellation without writing an event.
* [ ] Validate required marker input before submission.
* [ ] Validate coordinate ranges.
* [ ] Validate radius according to existing accepted constraints.
* [ ] Do not allow marker draft to contain area geometry.
* [ ] Do not create persisted marker until accepted writer result.
* [ ] Do not render accepted marker as canonical truth until persisted reload.

---

## Area drafting

* [ ] Preserve current area drafting behavior unless explicitly improved.
* [ ] Preserve accepted Polygon or MultiPolygon support.
* [ ] Keep temporary vertices and geometry under one draft owner.
* [ ] Do not rewrite canonical geometry semantics.
* [ ] Validate minimum geometry requirements.
* [ ] Validate malformed geometry safely.
* [ ] Permit cancellation without writing an event.
* [ ] Do not allow area draft to contain marker radius semantics.
* [ ] Do not treat temporary polygon as persisted area.
* [ ] Do not claim area confirmation validates exact draft boundaries.
* [ ] Do not create persisted area until accepted writer result.
* [ ] Do not render accepted area as canonical truth until persisted reload.

---

## Creator information source

* [ ] Show creator-source input only for accepted Situation creation cases where required or permitted.
* [ ] Do not show creator-source input for Coordination when not applicable.
* [ ] Do not infer creator source from canonical kind.
* [ ] Do not infer creator source from owner note.
* [ ] Do not infer creator source from legacy confidence.
* [ ] Do not use latest confirmation source as creator source.
* [ ] Map UI selection explicitly to accepted domain values.
* [ ] Reject missing required source before event construction.
* [ ] Reject unsupported source values.
* [ ] Keep final localized source labels in Android resources.
* [ ] Preserve explicit legacy limitations for existing items without rewriting them.

---

## Input validation

* [x] Add lightweight creation-input validation.
* [ ] Validate nonblank required label or title.
* [ ] Validate accepted text lengths using existing constraints where available.
* [x] Validate marker coordinates.
* [x] Validate radius.
* [x] Validate area geometry.
* [x] Validate expiry input.
* [x] Validate category-kind-shape compatibility.
* [x] Validate creator-source applicability.
* [x] Return typed input failures.
* [x] Keep database-dependent semantic authority in the processor.
* [x] Do not duplicate reducer semantics.
* [x] Do not duplicate membership authority.
* [x] Do not duplicate event structural validation.
* [x] Ensure final factory output passes accepted event validation.

---

## Canonical ADD adapter

* [x] Reuse the accepted canonical marker ADD factory.
* [x] Reuse the accepted canonical area ADD factory.
* [x] Add only a narrow application adapter if current factory APIs are not presentation-safe.
* [x] Map typed creation intent to exactly one canonical ADD event.
* [x] Do not construct arbitrary payload maps in Activity, Fragment or ViewModel code.
* [x] Do not manually generate event JSON.
* [x] Resolve actor from trusted local identity context.
* [x] Resolve event ID through the accepted generator.
* [x] Resolve timestamp through the accepted clock.
* [x] Resolve Lamport value through the accepted ordering context.
* [x] Preserve generated event identity through post-commit transport warning.
* [x] Do not generate multiple semantic events for one creation intent.
* [x] Do not create a dedicated action as part of canonical creation unless already required by accepted M2 factory semantics.
* [x] Do not insert canonical or domain rows directly.

---

## Creation submission boundary

* [x] Add or reuse one app-facing creation submission boundary.
* [x] Accept typed creation intent.
* [x] Validate application input.
* [x] Resolve trusted actor and ordering context.
* [x] Construct one accepted canonical ADD event.
* [x] Submit through the existing local map writer.
* [x] Do not call DAOs.
* [x] Do not call reducers.
* [x] Do not call `RoomMapEventProcessor` directly from UI.
* [x] Do not open a Room transaction.
* [x] Do not directly enqueue BLE or relay traffic.
* [x] Preserve existing post-commit transport ownership.
* [x] Preserve shared local and remote processing path.
* [x] Ensure marker and area creation share the same architectural submission pattern.

---

## Creation result mapping

Define UI-safe outcomes distinguishing:

* [x] accepted and persisted;
* [x] exact duplicate where applicable;
* [x] invalid creation input;
* [x] incompatible category-kind-shape selection;
* [x] invalid trusted local context;
* [x] semantic or structural rejection;
* [x] persistence failure;
* [x] accepted locally with post-commit delivery warning;
* [x] internal application failure.

Requirements:

* [ ] Report accepted local persistence accurately.
* [ ] Do not claim peer delivery certainty.
* [x] Do not map post-commit transport warning to local rollback.
* [x] Keep missing-group-key feedback distinct from enqueue and transport warnings.
* [x] Do not report failed persistence as accepted.
* [x] Do not create synthetic canonical item after failure.
* [ ] Do not expose Room, DAO, SQLite, reducer or transaction terminology.
* [x] Preserve stable rejection categories where available.
* [x] Map unknown future rejection safely.
* [x] Do not map unknown rejection to success.
* [x] Do not swallow fatal JVM `Error` subclasses.

---

## No optimistic map truth

* [ ] Temporary draft artifacts remain visually distinguishable from persisted items.
* [x] Accepted submission triggers canonical/domain map reload.
* [x] Do not insert a synthetic semantic item into the M3D1 snapshot.
* [x] Do not increment verification or acknowledgement counts.
* [x] Do not assign active/resolved state optimistically.
* [x] Do not create a synthetic timeline row.
* [x] Do not assume post-commit delivery succeeded.
* [x] Preserve accepted local item after delivery warning through persisted reload.
* [x] Clear draft only according to explicit accepted-result policy.
* [x] Preserve or restore draft appropriately after recoverable input failure.
* [x] Do not clear a recoverable draft merely because optional transport failed after local acceptance.

---

## Creation and selection coexistence

* [ ] Starting creation follows an explicit policy for current persisted-item selection.
* [ ] Creation draft must not reuse selected persisted identity.
* [ ] Selecting a persisted item while drafting follows an explicit cancel, suspend or block policy.
* [ ] Do not maintain simultaneous ambiguous selection and draft emphasis.
* [ ] Draft click targets must not navigate to persisted detail.
* [ ] Accepted reload may select the newly persisted item only through an explicit presentation policy.
* [ ] Do not infer the new persisted identity from SDK artifact position.
* [ ] Use accepted generated item identity.
* [ ] Clear obsolete draft artifacts after accepted persisted item is loaded.
* [ ] Do not leave orphan temporary map artifacts after cancellation or Activity destruction.

---

## Suggested production contracts

Possible contracts include:

* [ ] `MapCreationIntent`
* [ ] `CreateSituationMapItem`
* [ ] `CreateCoordinationMapItem`
* [ ] `MapCreationShape`
* [ ] `MapCreationDraft`
* [ ] `MapCreationState`
* [ ] `MapCreationFailure`
* [ ] `MapCreationCompatibilityPolicy`
* [ ] `ValidateMapCreationIntent`
* [ ] `SubmitMapCreation`
* [ ] `MapCreationSubmissionResult`
* [ ] `MapCreationEventFactoryAdapter`
* [ ] `MapCreationController`
* [ ] `MapCreationDraftRenderer`

Exact names may follow current project conventions.

---

## M3D3 automated tests

### Compatibility policy

* [x] Verify allowed Situation marker combinations.
* [x] Verify allowed Situation area combinations.
* [x] Verify allowed Coordination marker combinations.
* [ ] Verify allowed Coordination area combinations.
* [x] Verify invalid category-kind combinations fail.
* [x] Verify invalid category-shape combinations fail.
* [ ] Verify Coordination cannot receive Situation lifecycle input.
* [x] Verify creator source is required only where accepted.
* [x] Verify creator source is absent where not applicable.
* [ ] Verify category is not guessed from label.
* [ ] Verify no kind-specific collaborative action is introduced.

### Draft state

* [x] Verify marker draft initial state.
* [ ] Verify area draft initial state.
* [ ] Verify draft and persisted selection remain separate.
* [x] Verify form fields and geometry share one draft authority.
* [ ] Verify cancellation writes no event.
* [x] Verify invalid input preserves recoverable draft.
* [x] Verify duplicate submission while in flight is guarded.
* [x] Verify retry keeps its draft ID while receiving a new submission request ID.
* [x] Verify stale same-group submission feedback cannot apply to newer pending UI ownership.
* [x] Verify submission feedback consumption requires the matching submission ID.
* [ ] Verify controller close clears owned temporary artifacts.
* [ ] Verify Activity recreation follows accepted draft policy.
* [ ] Verify temporary artifacts are not canonical state.

### Marker creation

* [x] Verify valid marker intent maps to one canonical ADD event.
* [x] Verify coordinates are preserved.
* [x] Verify radius is preserved.
* [x] Verify marker intent cannot contain area geometry.
* [x] Verify invalid coordinates fail before submission.
* [x] Verify invalid radius fails before submission.
* [ ] Verify accepted marker reloads persisted map state.
* [ ] Verify failed marker submission creates no synthetic item.

### Area creation

* [x] Verify valid area intent maps to one canonical ADD event.
* [x] Verify geometry is preserved.
* [x] Verify area intent cannot contain marker radius semantics.
* [x] Verify malformed geometry fails safely.
* [ ] Verify accepted area reloads persisted map state.
* [ ] Verify failed area submission creates no synthetic item.
* [ ] Verify area creation wording does not imply exact-boundary verification.

### Trusted event construction

* [x] Verify UI cannot supply arbitrary actor.
* [x] Verify UI cannot supply arbitrary event ID.
* [x] Verify UI cannot supply arbitrary timestamp.
* [x] Verify UI cannot supply arbitrary Lamport time.
* [x] Verify accepted canonical factory is reused.
* [x] Verify final event passes structural validation.
* [x] Verify creation submits through accepted local writer.
* [x] Verify UI does not call DAOs, reducers or processor.
* [x] Verify post-commit transport remains outside Room transaction.

### Result and reload behavior

* [x] Verify accepted local persistence result.
* [x] Verify accepted-with-delivery-warning retains local acceptance.
* [x] Verify executor rejection completes as retryable internal failure without invoking submission.
* [x] Verify missing-key, enqueue-rejected and transport-failure delivery outcomes remain distinct.
* [x] Verify persistence failure creates no item.
* [x] Verify semantic rejection creates no item.
* [x] Verify exact duplicate does not create duplicate visible artifacts.
* [ ] Verify accepted result triggers one persisted map refresh.
* [ ] Verify no optimistic semantic projection is inserted.
* [ ] Verify no optimistic verification or participation count.
* [x] Verify fatal JVM `Error` subclasses propagate.

### Regression

* [ ] Verify existing owner edit remains available.
* [ ] Verify existing owner delete remains available.
* [x] Verify M3A interactions remain unchanged.
* [x] Verify M3B timeline remains unchanged.
* [x] Verify M3C semantics remain unchanged.
* [x] Verify no protocol, reducer, replay, processor or transport change.
* [x] Verify no Room migration.
* [x] Verify Room schema remains version 52.

---

## M3D3 instrumentation tests

* [ ] Start Situation marker draft.
* [ ] Start Coordination marker draft.
* [ ] Start Situation area draft.
* [ ] Start Coordination area draft.
* [ ] Cancel marker draft without persistence.
* [ ] Cancel area draft without persistence.
* [ ] Reject invalid category-kind combination before submission.
* [ ] Submit valid marker creation through accepted writer path.
* [ ] Submit valid area creation through accepted writer path.
* [ ] Verify accepted persisted item appears only after reload.
* [ ] Verify failed persistence leaves no synthetic item.
* [ ] Verify accepted-with-delivery-warning still shows persisted local item.
* [ ] Verify duplicate in-flight submission is guarded.
* [ ] Verify draft artifacts are cleared on cancellation and destruction.
* [ ] Verify marker and area drafting survive accepted lifecycle behavior.

Device evidence on 2026-07-28: `:app:connectedEmergencyDebugAndroidTest` passed 14/14
tests on `251fd3e9` (`SM-J415F - 9`). Those existing runtime tests cover M3D2/M3C
surfaces only; no M3D3 creation instrumentation test exists yet, so the M3D3
instrumentation items above remain unchecked.

---

## M3D3 manual checks

* [ ] Create an active Situation marker.
* [ ] Create a Coordination marker.
* [ ] Create a Situation area.
* [ ] Create a Coordination area.
* [ ] Verify creation vocabulary is understandable.
* [ ] Verify creator-source input appears only where applicable.
* [ ] Verify invalid combinations cannot be selected or submitted.
* [ ] Verify marker placement and radius remain usable.
* [ ] Verify area drafting remains usable.
* [ ] Verify cancellation creates no persisted item.
* [ ] Verify accepted local item appears after reload.
* [ ] Verify delivery warning does not remove accepted local item.
* [ ] Verify selection and drafting do not conflict.
* [ ] Verify Activity recreation follows the accepted draft policy.
* [ ] Verify long labels and notes remain editable without raw-data leakage.

No M3D3 manual device walkthrough was performed in this run; manual closure remains deferred.

---

## M3D3 non-goals

* [ ] Do not change canonical owner edit semantics.
* [ ] Do not change canonical delete semantics.
* [ ] Do not change dedicated action commands.
* [ ] Do not create initial confirmation automatically.
* [ ] Do not create initial acknowledgement automatically.
* [ ] Do not add moderation.
* [ ] Do not add reputation.
* [ ] Do not add per-peer delivery state.
* [ ] Do not rewrite legacy items.
* [ ] Do not add persistence.
* [ ] Do not add a Room migration.

---

## M3D3 acceptance

* [x] Situation and Coordination creation use typed application intent.
* [x] Category-kind-shape compatibility is explicit and tested.
* [x] UI cannot submit invalid domain-v1 combinations.
* [x] Marker and area drafts remain distinct and usable.
* [x] Draft state is separate from persisted-item selection.
* [x] UI constructs no raw event payloads.
* [x] Accepted canonical ADD factories and local writer are reused.
* [x] `RoomMapEventProcessor` remains the sole Room transaction owner.
* [x] Persisted reload remains map truth.
* [x] No optimistic canonical, verification or participation state is created.
* [x] Accepted local persistence remains visible after post-commit delivery warning.
* [ ] Existing owner edit/delete and M3A–M3C behavior remain available.
* [x] No protocol, reducer, replay, processor, persistence or transport semantics change.
* [x] Room schema remains version 52.
* [ ] M3D3 is reviewed and accepted before final map-to-detail and accessibility closure begins.

---

# Phase M3D4 — Map-to-Detail Integration, Accessibility and M3D Closure

## Goal

Complete exact map-item selection and navigation into the accepted M3 detail surface, finalize accessible map presentation, complete safe presentation-only label and geometry cleanup, and close M3D with production evidence.

M3D4 must not merge map, detail or timeline state owners.

---

## M3D4 architecture lock

M3D4 integrates:

* [x] M3D map presentation state owner;
* [x] M3D runtime renderer;
* [x] M3D creation controller;
* [x] accepted M3A detail navigation;
* [x] accepted M3A detail controller;
* [x] accepted M3B timeline controller;
* [x] accepted M3C projectors and detail rendering.

Ownership remains:

```text
Map controller
    = viewport, map snapshot, selection, focus and map refresh

Creation controller
    = ephemeral creation draft and creation submission

Detail controller
    = current selected detail and interaction state after navigation

Timeline controller
    = selected detail history paging after navigation

M3C projectors
    = pure detail verification and participation interpretation

Runtime renderer
    = map-SDK artifacts only
```

Requirements:

* [x] Do not merge map state into `MapItemDetailShellState`.
* [x] Do not merge detail state into map state.
* [x] Do not merge timeline state into map state.
* [x] Do not make map controller depend on timeline controller.
* [x] Do not make detail controller depend on map controller.
* [x] Do not make runtime renderer navigate directly.
* [x] Keep navigation orchestration in Android presentation integration.
* [ ] Keep all repositories and DAOs inside accepted factories.
* [x] Close each state owner exactly once.
* [x] Prevent callbacks after Activity destruction from updating views.

---

## Exact map selection

* [x] Select marker by exact `MapItemIdentity`.
* [x] Select area by exact `MapItemIdentity`.
* [x] Preserve group ID.
* [x] Preserve explicit marker or area type.
* [x] Preserve item ID.
* [x] Do not select by raw item ID alone.
* [x] Do not reconstruct identity from label, coordinates or SDK object index.
* [x] Do not serialize Room entity as selection state.
* [x] Do not serialize full semantic projection as navigation authority.
* [x] Keep one selected persisted identity.
* [x] Clear selection according to explicit policy when item disappears.
* [x] Keep draft selection separate.
* [x] Ensure marker and area with same raw ID remain independently selectable.
* [x] Ensure runtime hit testing maps to exact typed identity.
* [ ] Ensure invalid SDK tags fail safely.

---

## Map-to-detail navigation

* [x] Navigate through accepted M3A detail entry point.
* [x] Pass exact group ID, item type and item ID.
* [x] Do not pass Room entities.
* [x] Do not pass canonical snapshots as truth.
* [x] Do not pass domain snapshots as truth.
* [x] Reload detail through accepted M3A repositories.
* [x] Preserve marker/area same-ID isolation.
* [x] Handle target missing after selection.
* [x] Handle target deleted between selection and navigation.
* [x] Handle invalid navigation identity safely.
* [x] Do not use timeline history to reconstruct missing detail.
* [x] Do not create a placeholder target.
* [ ] Preserve current map camera and selection state according to explicit back-navigation policy.
* [x] Avoid duplicate detail launches from repeated SDK callbacks.
* [x] Guard navigation while creation draft owns interaction where required.

---

## Return-to-map behavior

* [x] Define map refresh behavior after returning from detail.
* [x] Refresh map semantic state from persisted authority where relevant.
* [x] Do not copy detail state directly into map items.
* [x] Do not copy timeline rows into map state.
* [x] Preserve or clear selected identity according to explicit policy.
* [ ] Reflect remote or local resolution after accepted map refresh.
* [ ] Reflect remote or local deletion after accepted map refresh.
* [ ] Reflect confirmation or acknowledgement count changes after accepted map refresh.
* [ ] Reflect material-change freshness after accepted map refresh.
* [x] Keep map refresh independent from timeline refresh.
* [x] Keep detail refresh independent from map refresh.
* [x] Do not treat navigation result as persistence authority.
* [x] Do not mutate runtime artifacts optimistically from detail feedback.

---

## Selected-item invalidation

* [ ] Define behavior when selected item is removed remotely.
* [ ] Define behavior when selected item becomes canonical missing.
* [ ] Define behavior when selected item becomes invalid.
* [ ] Define behavior when selected Situation becomes resolved.
* [ ] Define behavior when selected item becomes expired.
* [ ] Define behavior when domain enrichment becomes unavailable.
* [ ] Preserve valid map rendering for unrelated items.
* [ ] Do not keep stale interaction affordances on unavailable selected item.
* [ ] Do not let retained SDK artifact imply current availability.
* [ ] Do not let retained timeline history keep map item selected as active truth.
* [ ] Do not crash when renderer removes selected artifact.

---

## Accessibility semantics

* [x] Provide content descriptions for marker and area items where supported.
* [x] Include category meaning without exposing raw enum names.
* [x] Include lifecycle or temporal limitation where useful.
* [x] Include verification or participation wording without claiming certainty.
* [x] Keep area wording clear that confirmation concerns reported condition, not exact boundary.
* [x] Do not rely only on color for Situation versus Coordination.
* [x] Do not rely only on color for active, resolved, expired or stale states.
* [ ] Preserve sufficient contrast.
* [ ] Preserve touch-target size.
* [x] Preserve readable selected-state indication.
* [ ] Preserve screen-reader focus order where supported.
* [ ] Announce loading and map-read failure states accessibly where practical.
* [x] Avoid excessively verbose technical descriptions.
* [x] Keep localized accessibility text in Android resources.
* [x] Do not expose coordinates or raw geometry unless user-relevant.

---

## Current-state visual hierarchy

* [ ] Keep category visually primary.
* [ ] Keep canonical kind visually subordinate.
* [ ] Keep lifecycle and temporal limitation understandable.
* [ ] Keep verification or participation evidence descriptive.
* [ ] Keep selected state visually clear.
* [ ] Keep unresolved and unavailable states honest.
* [ ] Keep resolved state more authoritative than historical confirmation existence.
* [ ] Keep Coordination participation distinct from Situation verification.
* [ ] Keep confirmation count from appearing as a truth score.
* [ ] Keep acknowledgement count from appearing as completion or delivery guarantee.
* [ ] Keep stale evidence distinguishable from current evidence.
* [ ] Keep current map state independent from detail timeline rows.

---

## Presentation-only raw-data cleanup

M3D4 may finalize safe display cleanup for:

* [ ] raw GeoJSON replacement with a human-readable geometry summary;
* [x] overly long map labels;
* [x] note previews;
* [ ] coordinate summaries;
* [ ] area-size or vertex-count summaries where already safely derivable;
* [ ] legacy limitation wording.

Requirements:

* [x] Do not mutate persisted canonical geometry.
* [x] Do not mutate persisted labels or notes.
* [x] Do not change event payloads.
* [x] Do not add schema fields for formatting.
* [x] Preserve full values for detail and owner editing.
* [x] Bound map rendering without changing stored data.
* [x] Avoid exposing raw JSON or persistence enum values.
* [ ] Avoid misleading geometry precision.
* [ ] Do not calculate authoritative hazard area from display simplification.
* [x] Keep localization in Android resources.
* [ ] Test long and malformed display inputs.

---

## Legacy coexistence

* [ ] Render recognized legacy Situation safely.
* [ ] Render recognized legacy Coordination safely.
* [ ] Render unresolved legacy category with explicit limitation.
* [ ] Do not infer category from visual kind.
* [ ] Keep legacy confidence separate from M3C confirmation evidence.
* [ ] Keep legacy votes separate from Confirm.
* [ ] Keep legacy contributions separate from Situation update and Coordination note.
* [ ] Do not translate legacy history.
* [ ] Do not rewrite legacy persistence.
* [ ] Do not attach explicit creator source where legacy source is unspecified.
* [ ] Preserve read-only behavior for unresolved legacy items.
* [ ] Keep global remaining terminology migration assigned to M3F where applicable.

---

## Runtime lifecycle behavior

* [x] Create map state owner once per accepted Activity lifecycle.
* [x] Create runtime renderer once per accepted map instance.
* [x] Do not recreate controller or renderer from ordinary render callbacks.
* [x] Close controller exactly once.
* [x] Clear renderer-owned artifacts exactly once where required.
* [ ] Cancel queued tasks where possible.
* [ ] Keep stale-result ownership tokens authoritative.
* [x] Prevent callbacks after destruction from updating views.
* [ ] Preserve map camera according to existing lifecycle behavior where supported.
* [ ] Reload persisted state after recreation.
* [x] Do not persist runtime SDK objects.
* [x] Do not treat Activity recreation as domain persistence.
* [x] Ensure draft, selection and map snapshot lifecycle rules remain distinct.

---

## Error and degraded-state behavior

* [ ] Map read failure must not corrupt persisted state.
* [ ] Map read failure must not become accepted empty map state.
* [ ] One invalid item must not prevent unrelated valid items from rendering.
* [ ] Invalid item must not be rendered as normal active state.
* [ ] Missing domain enrichment may use explicit safe limitation presentation.
* [ ] Invalid domain enrichment must not be shown as unresolved legacy.
* [ ] Runtime renderer failure must not alter semantic state.
* [x] Detail navigation failure must not corrupt map selection state.
* [ ] Creation failure must not corrupt existing map snapshot.
* [ ] Timeline failure in detail must not alter map state.
* [x] Keep technical errors out of user-facing copy.
* [x] Do not catch fatal JVM `Error` subclasses.

---

## M3D4 automated tests

### Selection and navigation

* [x] Verify marker selection uses exact identity.
* [x] Verify area selection uses exact identity.
* [x] Verify marker and area same raw ID remain isolated.
* [x] Verify selection does not use label, position or adapter index as identity.
* [x] Verify exact identity is passed to accepted detail navigation.
* [x] Verify Room entities are not navigation arguments.
* [x] Verify full semantic projections are not navigation truth.
* [x] Verify invalid identity does not launch detail.
* [x] Verify repeated SDK callback does not launch duplicate detail.
* [x] Verify draft artifact does not launch persisted detail.

### Return and refresh

* [x] Verify return from detail requests accepted map refresh where required.
* [x] Verify map refresh reloads persisted authority.
* [x] Verify detail state is not copied directly into map state.
* [x] Verify timeline rows are not copied into map state.
* [ ] Verify resolution appears after map refresh.
* [ ] Verify deletion removes item after map refresh.
* [ ] Verify verification and participation changes appear after map refresh.
* [x] Verify map and timeline refresh remain independent.
* [x] Verify accepted detail outcome does not mutate renderer optimistically.

### Invalidated selection

* [x] Verify selected item removal clears selection according to policy.
* [x] Verify selected invalid item does not crash renderer.
* [x] Verify selected resolved Situation updates visual treatment.
* [x] Verify selected expired item updates visual treatment.
* [x] Verify unavailable domain enrichment uses safe limitation state.
* [x] Verify unrelated items remain rendered after selected-item failure.

### Accessibility and formatting

* [x] Verify category does not rely only on color.
* [x] Verify lifecycle does not rely only on color.
* [x] Verify content descriptions use localized presentation.
* [x] Verify raw enum and event names are absent.
* [x] Verify area description does not imply exact-boundary verification.
* [x] Verify acknowledgement is not called verification.
* [x] Verify long labels are bounded safely.
* [x] Verify long notes are bounded safely.
* [x] Verify raw GeoJSON is absent from normal map presentation.
* [x] Verify persisted data is not mutated by display cleanup.
* [ ] Verify touch-target and contrast resources meet accepted requirements where testable.

### Lifecycle and concurrency

* [x] Verify one controller instance per Activity lifecycle.
* [x] Verify ordinary render callbacks do not recreate controller.
* [x] Verify controller closes exactly once.
* [x] Verify renderer clears owned artifacts according to lifecycle policy.
* [ ] Verify callbacks after destruction cannot update views.
* [x] Verify obsolete viewport result cannot restore old selection or artifacts.
* [ ] Verify recreation reloads persisted state.
* [x] Verify runtime SDK objects are not persisted.

### Cross-phase regression

* [x] Verify all five M3A interactions remain usable in detail.
* [x] Verify M3B timeline ordering and pagination remain unchanged.
* [x] Verify M3C current-state authority remains unchanged.
* [x] Verify current verification is not derived from map artifacts.
* [x] Verify current participation is not derived from map artifacts.
* [ ] Verify owner edit remains available.
* [ ] Verify owner delete remains available.
* [ ] Verify marker creation remains available through M3D3.
* [ ] Verify area creation remains available through M3D3.
* [x] Verify no BLE, relay or envelope change.
* [x] Verify no reducer or materialization change.
* [x] Verify no Room migration.
* [x] Verify Room schema remains version 52.

---

## M3D4 instrumentation tests

* [ ] Select Situation marker and open exact detail.
* [ ] Select Coordination marker and open exact detail.
* [ ] Select Situation area and open exact detail.
* [ ] Select Coordination area and open exact detail.
* [ ] Verify marker and area same raw ID open correct detail.
* [ ] Return from detail and preserve accepted camera/selection policy.
* [ ] Resolve item in detail and verify map refresh treatment.
* [ ] Delete item and verify map removal.
* [ ] Confirm Situation and verify map evidence refresh.
* [ ] Acknowledge Coordination and verify map participation refresh.
* [ ] Verify timeline refresh failure does not corrupt map state.
* [ ] Verify item removal while selected does not crash.
* [ ] Verify Activity recreation does not publish obsolete snapshot.
* [ ] Verify content descriptions and accessible states where instrumentation supports them.
* [ ] Verify long-label and raw-geometry cleanup in production resources.

---

## M3D4 manual checks

* [ ] Open marker detail from map.
* [ ] Open area detail from map.
* [ ] Verify marker and area same raw ID navigate correctly.
* [ ] Verify Situation and Coordination remain distinguishable without color alone.
* [ ] Verify active, resolved, expired, stale and unresolved states.
* [ ] Verify selected marker and area treatment.
* [ ] Verify current confirmation count is not shown as absolute truth.
* [ ] Verify area confirmation wording does not claim exact boundary accuracy.
* [ ] Verify acknowledgement is not called verification.
* [ ] Verify Coordination receives no Situation lifecycle or freshness language.
* [ ] Create each supported Situation and Coordination marker/area form.
* [ ] Cancel marker and area drafts.
* [ ] Verify accepted local creation after delivery warning.
* [ ] Verify owner edit and delete remain usable.
* [ ] Verify long labels, notes and geometry summaries.
* [ ] Verify raw GeoJSON is absent from normal user presentation.
* [ ] Verify dense-map usability.
* [ ] Verify small-screen usability.
* [ ] Verify light and dark theme behavior where supported.
* [ ] Verify screen-reader and contrast behavior.
* [ ] Verify remote resolution, deletion and participation changes after map refresh.
* [ ] Verify unresolved legacy items remain safe and read-only.

---

## M3D4 non-goals

* [ ] Do not merge map, detail or timeline controllers.
* [ ] Do not derive current state from timeline.
* [ ] Do not derive current state from runtime map artifacts.
* [ ] Do not add a global activity feed.
* [ ] Do not add moderation.
* [ ] Do not add reputation or trust scoring.
* [ ] Do not add weighted confirmations.
* [ ] Do not add voting thresholds.
* [ ] Do not add automatic conflict resolution.
* [ ] Do not add per-peer delivery state.
* [ ] Do not add new map event types.
* [ ] Do not change geometry persistence.
* [ ] Do not add persistence.
* [ ] Do not add a Room migration.

---

## M3D4 acceptance

* [x] Exact marker and area selection navigates through accepted typed identity.
* [x] Marker and area same-ID isolation is preserved end to end.
* [x] Map, creation, detail and timeline state owners remain separate.
* [x] Return-to-map refresh uses persisted authority.
* [x] Current map state is not copied from detail or timeline snapshots.
* [x] Selected-item invalidation is handled safely.
* [x] Accessibility does not rely only on color.
* [x] Area presentation does not imply exact-boundary verification.
* [ ] Long labels, notes and geometry summaries are rendered safely.
* [x] Raw GeoJSON is absent from normal user presentation.
* [x] Legacy state remains honest and read-only where unresolved.
* [x] Existing M3A interactions, M3B timeline and M3C semantics remain available.
* [x] Existing canonical owner edit and delete remain available.
* [ ] Runtime lifecycle and stale-result behavior are verified.
* [x] No protocol, reducer, replay, processor, materialization, persistence or transport semantics change.
* [x] Room schema remains version 52.
* [ ] M3D4 is reviewed and accepted before M3D5 remediation begins.

### M3D4 implementation and JVM verification (2026-07-28)

Review correction: the first M3D4 implementation called `GroupMapController.select` before
asking the coordinator to suppress a busy callback. A different stale callback could therefore
change persisted selection while the original detail screen remained open. The list marker/area
Details actions also bypassed the coordinator and used a direct Activity launch.

Production integration now uses `DefaultMapItemNavigationCoordinator` as the narrow
Activity-scoped owner for `Ready -> Launching -> AwaitingReturn -> Ready` navigation,
exact reservation, duplicate launch and duplicate selection-mutation suppression, stale launch
callbacks, terminal close, and a one-shot return refresh request. Every persisted marker/area
entry point (runtime renderer and list Details) calls `requestPersistedMapItemDetail`:

```text
entry point -> coordinator reserve/block -> GroupMapController.select(exact identity)
-> exact selectedIdentity verification -> coordinator selection acceptance -> detail launch
-> AwaitingReturn -> one-shot onResume persisted refresh
```

Selection rejection releases only the owned reservation back to `Ready`; a busy, wrong-group,
invalid, inactive, draft-owned, or closed callback never mutates persisted selection. Its normal
`onResume` persisted map load consumes and coalesces the pending return refresh so it does not
issue a second semantic load. The Android launch boundary catches ordinary `Exception` only.

Accessibility/resource closure adds human-readable category and marker/area shape wording,
community-confirmation wording for Situation evidence, distinct acknowledgement wording for
Coordination, explicit area-boundary limitation text, selected and lifecycle state cues,
48-character runtime label previews, and bounded list label/note previews. Canonical input
objects and persisted values remain unchanged.

Focused and regression JVM evidence passed in both Emergency and Official distributions:

* `DefaultMapItemNavigationCoordinatorM3D4Test`
* `GroupMapActivityM3D4BoundaryTest`
* `OsmdroidMapRuntimeRendererM3D4BoundaryTest`
* `AndroidMapVisualResourceResolverM3D4Test`
* `./gradlew :app:testEmergencyDebugUnitTest --tests '*M3D4*' --no-parallel --no-daemon`
* `./gradlew :app:testOfficialDebugUnitTest --tests '*M3D4*' --no-parallel --no-daemon`
* both-flavor `'*M3D1*'` through `'*M3D4*'` regression
* both-flavor `'*M3A*'` through `'*M3D*'` regression
* full Emergency and Official unit suites

Review-fix production files: `GroupMapActivity.kt`, `MapItemNavigationCoordinator.kt`,
`MapItemNavigationDecision.kt`, and `DefaultMapItemNavigationCoordinator.kt`. Review-fix JVM
tests: `DefaultMapItemNavigationCoordinatorM3D4Test.kt` and
`GroupMapActivityM3D4BoundaryTest.kt`. The existing `GroupMapActivityM3D4RuntimeTest.kt` is
the corresponding Android-test source. No renderer, Room, reducer, processor, protocol, or
schema file changed for this correction.

`GroupMapActivityM3D4RuntimeTest` ran successfully for both distributions on the connected
SM-J415F (Android 9): one test passed for Emergency and one for Official. It covers exact
marker/area identity, same-raw-ID isolation, duplicate selection-mutation suppression before
return, draft gating, return readiness, and destruction closure through the shared entry point.
The test temporarily grants only missing location permissions and restores permissions that it
granted during teardown, so the platform permission dialog cannot mask the Activity return
lifecycle. Commands executed:

* `./gradlew :app:compileEmergencyDebugAndroidTest :app:compileOfficialDebugAndroidTest --no-parallel --no-daemon --console=plain`
* `./gradlew :app:connectedEmergencyDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.peermesh.GroupMapActivityM3D4RuntimeTest --no-parallel --no-daemon --console=plain`
* `./gradlew :app:connectedOfficialDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.peermesh.GroupMapActivityM3D4RuntimeTest --no-parallel --no-daemon --console=plain`

Only this focused map-to-detail navigation instrumentation scenario ran; the broader M3D4
instrumentation and manual checklists remain unchecked.
Room remains schema version 52.

---

# Phase M3D5 — Manual UX Remediation and Runtime Closure

## Goal

Close the production UX gaps found during the 2026-07-29 `OfficialDebug` manual
walkthrough on a Samsung Galaxy A10s, without changing accepted M2 protocol,
persistence, reducer, ordering, replay, transport or compatibility semantics.

M3D5 is a remediation and runtime-evidence phase. It must not treat an existing
application seam, visual-policy test, or accepted roadmap item as proof that the
corresponding production Activity behavior is correct.

M3D5 must:

* make category-compatible Coordination kinds selectable at runtime;
* replace or isolate remaining legacy Report/Observe/Vote/Contribute vocabulary;
* render marker kind/category meaning instead of showing undifferentiated dots;
* ensure confirmation and lifecycle meaning are not communicated by color alone;
* remove invalid normal actions from resolved Situation detail;
* preserve correct dashed resolved styling, persisted verification refresh and
  deterministic activity history;
* complete both-flavor device evidence for the repaired paths.

---

## Manual evidence baseline

The following findings were reproduced on a real Samsung Galaxy A10s running
`OfficialDebug`.

### Creation and kind selection

* [x] `photo_2026-07-29_15-50-05 (2).jpg` shows a Situation marker form with the
  emoji kind `🚧 Blocked path`.
* [x] `photo_2026-07-29_15-50-05 (3).jpg` shows an empty Coordination kind
  selector.
* [x] Situation source choices still use `Observed` and `Reported`.
* [x] Marker creation/edit copy still exposes `Note (observed)`.

Affected target checklist criteria:

* `UX-CREATE-03`;
* `UX-CREATE-COORD-02`;
* `UX-VOCAB-04`;
* `UX-CREATE-SIT-04`;
* `UX-CREATE-SIT-06`.

### Runtime marker rendering

* [x] `photo_2026-07-29_15-58-03.jpg` shows persisted marker kinds rendered as
  plain circular map dots while the list row still shows the kind emoji.
* [x] `photo_2026-07-29_16-06-43.jpg` shows current confirmation using a green
  outline without a second marker-local non-color cue.
* [x] `photo_2026-07-29_16-09-53 (2).jpg` shows no-longer-active state using a
  dashed border; this lifecycle cue is correct and must be preserved.

Affected target checklist criteria:

* `UX-MAP-01`;
* `UX-MAP-10`;
* `UX-MAP-11`;
* `UX-A11Y-03`;
* the M3D visual-communication gate.

### Legacy edit and list presentation

* [x] `photo_2026-07-29_15-57-25.jpg` shows `Reported`, `Voting`,
  `Contributions`, and `Request Removal` in the owner edit surface.
* [x] The normal map list still uses vote/report wording and exposes legacy
  `Request removal` beside canonical owner edit.
* [x] Dedicated Situation actions remain separate in M3 detail, but the retained
  legacy controls are still user-visible and semantically confusing.

Affected target checklist criteria:

* `UX-VOCAB-04`;
* `UX-VOCAB-09`;
* `UX-ARCH-10`;
* `UX-ARCH-11`;
* `UX-REG-08`.

### Resolved Situation action gating

* [x] `photo_2026-07-29_16-09-53.jpg` shows a resolved Situation with no current
  confirmation evidence and retained historical activity.
* [x] The same resolved detail still displays `ADD SITUATION UPDATE`.
* [x] No lifecycle-reactivation action is offered.

Affected target checklist criteria:

* `UX-ACTION-SIT-05`;
* `UX-ACTION-04`;
* `UX-E2E-C8`.

---

## M3D5 architecture lock

* [ ] Reuse `MapItemKindCatalog` as the only kind/category compatibility
  authority.
* [ ] Reuse accepted M3A interaction availability; do not add Activity-local
  lifecycle policy.
* [ ] Keep `RoomMapEventProcessor` authoritative for semantic rejection.
* [ ] Keep persisted reload authoritative after creation and interaction.
* [ ] Keep canonical owner edit distinct from dedicated Situation update.
* [ ] Keep canonical delete distinct from no-longer-active lifecycle.
* [ ] Do not reinterpret legacy vote, contribution or removal-request state.
* [ ] Keep M2 materialized domain state authoritative for category, lifecycle,
  current confirmation and acknowledgement.
* [ ] Keep M3B activity history historical and append-only.
* [ ] Keep runtime map objects as disposable rendered artifacts.
* [ ] Preserve exact group, item type and item ID throughout rendering,
  selection, detail and refresh.
* [ ] Do not add a Room migration or change schema version 52.

---

## M3D5A — Category-Kind Creation Repair

### Coordination kind availability

* [x] Determine why the production Coordination kind selector receives no
  compatible marker kinds.
* [x] Load Coordination kinds through the centralized catalog rather than a
  duplicated UI list.
* [x] Ensure every displayed Coordination kind supports the active geometry
  type.
* [x] Ensure Situation kinds do not appear in the Coordination list.
* [x] Ensure Coordination kinds do not appear in the Situation list.
* [x] Render the accepted kind emoji and human-readable label in both lists.
* [x] Show an explicit safe unavailable state if no compatible kind exists;
  never present a blank unexplained selector.
* [x] Prevent submission while no compatible kind is selected.
* [x] Preserve marker and area draft geometry when changing category only where
  the accepted draft policy permits it.

### Source vocabulary

* [x] Replace `Observed` with `Seen firsthand` or the accepted localized
  equivalent.
* [x] Replace bare `Reported` with `Reported by others` or the accepted localized
  equivalent.
* [x] Add `Source uncertain` where required by the accepted Situation creation
  contract.
* [x] Do not preselect a source that claims firsthand knowledge.
* [x] Remove `observed` from generic note labels.
* [x] Show no source field for Coordination.
* [x] Map UI selections only to accepted typed information-source values.

### M3D5A tests

* [x] Verify Situation marker kinds are nonempty and category-compatible.
* [x] Verify Coordination marker kinds are nonempty and category-compatible.
* [x] Verify Situation area kinds are nonempty where supported.
* [x] Verify Coordination area kinds are nonempty where supported.
* [x] Verify unsupported kind/shape combinations remain absent.
* [x] Verify empty catalog result produces an explicit disabled state.
* [x] Verify source labels contain no `Observed`, bare `Reported`, or
  `Confidence`.
* [x] Verify Coordination intent contains no information source.
* [x] Verify invalid category/kind/source combinations never invoke submission.

### M3D5A follow-up routing

The prior cancellation concern from `C4.jpg` was reviewed again on 2026-07-29.
The accepted UX is that Cancel from the creation metadata form dismisses only
that form and retains the active map drawing draft, with its **Done** and
**Cancel** controls. M3D5A retains only the original category/kind/source
evidence above.

### M3D5A implementation evidence — 2026-07-29

* Production:
  * `MapKinds` now derives marker and area creation options from
    `MapItemKindCatalog`, with profile fallback that remains constrained by
    category and geometry.
  * Situation creation exposes `Seen firsthand`, `Reported by others`, and
    `Source uncertain`, with no preselected source.
  * Coordination marker creation exposes the catalog-backed `Campsite` and
    `Waypoint` options and omits the Situation-only source controls.
  * Because the centralized catalog currently defines no Coordination area
    kind, that combination now shows an explicit unavailable message and keeps
    Save disabled instead of presenting a blank selector.
  * Creation note copy is now `Note (optional)`. Legacy owner-edit wording is
    intentionally deferred to M3E8.
* Automated coverage:
  * `MapKindsCategoryContractTest`
  * `MapCreationFormM3D5ATest`
  * `ValidateMapCreationIntentM3D3Test`
  * existing M3D3 creation controller and event-factory coverage
* Verification:
  * focused M3D3/M3D5A unit tests passed for `EmergencyDebug` and
    `OfficialDebug`;
  * full app unit-test tasks passed for both debug flavors;
  * `assembleEmergencyDebug` and `assembleOfficialDebug` passed;
  * `git diff --check` passed.
* Galaxy A10s `OfficialDebug` runtime evidence for manual scenario A was
  reviewed on 2026-07-29 (`A1.jpg` through `A4.jpg`): Situation kind/source
  copy, required-source rejection, and persisted `Source uncertain` detail all
  passed. The target UX checklist now records the corresponding Situation
  criteria as verified.
* Galaxy A10s `OfficialDebug` runtime evidence for manual scenario B was also
  reviewed on 2026-07-29 (`B1.jpg` through `B4.jpg`): Coordination marker kinds
  are nonempty and category-compatible, source/lifecycle fields are absent, and
  the persisted Coordination detail presents acknowledgement as participation.
  The screenshot exposes an open M3E2 defect: the Coordination timeline header
  still says `Situation activity`.
* Galaxy A10s `OfficialDebug` runtime evidence for manual scenario C was
  partially reviewed on 2026-07-29 (`C1.jpg` through `C4.jpg`): Situation-area
  drafting and explicit unsupported Coordination-area presentation passed.
  C4 shows creation-form Cancel returning to the active drawing draft. The
  2026-07-29 UX decision accepts this behavior; it is not an M3E1 defect.
  C4 also exposed raw GeoJSON in the normal Area creation form, which was
  subsequently repaired in M3E3.
* Galaxy A10s `OfficialDebug` runtime evidence for manual scenario D was
  partially reviewed on 2026-07-29 (`D1a.jpg`, `D1b.jpg`, and `D2.jpg`). Owner
  edit remains reachable, but the Coordination `Waypoint` created in scenario B
  renders as the Situation kind `Blocked path` in owner edit. This M3E4
  category/kind-fidelity defect, and the absence of post-Cancel persistence
  evidence, keep scenario D open.

---

## M3D5B — Semantic Marker Rendering and Accessibility Repair

The runtime marker rendering and accessibility defects are moved to **M3E5 —
Semantic marker rendering** and **M3E6 — Verification and lifecycle cues**.
M3D5B remains the evidence/audit source only.

---

## M3D5C — Legacy Vocabulary and Control Cleanup

All vocabulary/control and owner-edit defects are moved to **M3E2 —
Coordination activity heading**, **M3E3 — Area geometry presentation**, **M3E4
— Owner-edit category/kind fidelity**, and **M3E8 — Legacy vocabulary and
control cleanup**. M3D5C remains the evidence/audit source only.

Implementation update (2026-07-29): M3E2 was already complete. The M3D5C code
pass now also implements M3E3, M3E4 and the code/test portion of M3E8. Manual
runtime evidence remains open in those M3E sections; use
`docs/map_refactor/M3D5C_Manual_Test_Script.md`.

---

## M3D5D — Resolved Interaction Gating Repair

The resolved-Situation interaction-gating defect is moved to **M3E7 — Resolved
Situation action gating**. M3D5D remains the evidence/audit source only.

---

## M3D5 instrumentation verification

Run on both `EmergencyDebug` and `OfficialDebug`:

* [ ] Open Situation marker creation and verify compatible emoji kinds.
* [ ] Open Coordination marker creation and verify compatible emoji kinds.
* [ ] Open Situation and Coordination area creation where supported.
* [ ] Verify source wording and absence of legacy confidence vocabulary.
* [ ] Create one Situation marker and one Coordination marker.
* [ ] Verify persisted markers use non-color semantic kind/category cues.
* [ ] Confirm a Situation and verify persisted current confirmation refresh.
* [ ] Verify confirmed marker meaning is not communicated by green alone.
* [ ] Add a Still-active update and a Situation-changed update.
* [ ] Mark the Situation no longer active.
* [ ] Verify the resolved marker retains dashed styling.
* [ ] Verify resolved detail offers no normal Situation action.
* [ ] Verify history remains visible and newest-first.
* [ ] Open owner edit and verify canonical controls remain available without
  legacy semantic duplication.

---

## M3D5 manual closure

Record device model, Android version, flavor, exact gesture and screenshot for
each applicable target criterion:

* [ ] `UX-CREATE-03`;
* [ ] `UX-CREATE-COORD-02`;
* [ ] `UX-VOCAB-04`;
* [ ] `UX-VOCAB-09`;
* [ ] `UX-CREATE-SIT-04`;
* [ ] `UX-CREATE-SIT-06`;
* [ ] `UX-MAP-01`;
* [ ] `UX-MAP-10`;
* [ ] `UX-MAP-11`;
* [ ] `UX-ACTION-SIT-05`;
* [ ] `UX-ACTION-04`;
* [ ] `UX-A11Y-03`;
* [ ] `UX-REG-08`;
* [ ] `UX-E2E-C8`.

---

## M3D5 non-goals

* [ ] Do not change map event types or payload contracts.
* [ ] Do not change canonical or dedicated-action reducers.
* [ ] Do not change lifecycle ordering or confirmation invalidation semantics.
* [ ] Do not change duplicate, replay or pending-target recovery.
* [ ] Do not change BLE, relay or envelope formats.
* [ ] Do not add trust scores, weighted confidence, moderation or reputation.
* [ ] Do not add per-peer delivery tracking.
* [ ] Do not migrate or rewrite legacy history.
* [ ] Do not introduce new persistence or a Room migration.

---

## M3D5 acceptance

* [ ] Coordination creation exposes at least one compatible kind wherever the
  centralized catalog supports the active geometry.
* [ ] Empty compatible-kind state is explicit and cannot submit.
* [ ] Situation source wording matches the accepted information-source contract.
* [ ] Coordination exposes no source, confidence or lifecycle creation field.
* [ ] New creation/detail flows expose no ambiguous legacy vote/report vocabulary.
* [ ] Persisted markers communicate kind/category with a non-color cue.
* [ ] Current confirmation does not rely only on a green outline.
* [ ] Dashed no-longer-active styling remains correct.
* [ ] Resolved Situation offers no normal Situation interactions.
* [ ] Resolved Situation offers no reactivation.
* [ ] Resolved Situation retains deterministic human-readable activity.
* [ ] Owner Edit/Delete remain separate from collaborative actions.
* [ ] Legacy votes, contributions and removal requests remain semantically
  separate.
* [ ] Exact typed identity, same-ID isolation and stale-result protection remain
  intact.
* [ ] Both Emergency and Official focused unit suites pass.
* [ ] Both Emergency and Official instrumentation scenarios pass.
* [ ] Manual evidence closes every criterion listed in M3D5 manual closure.
* [ ] No protocol, reducer, replay, processor, persistence, transport or relay
  semantics change.
* [ ] Room schema remains version 52.
* [ ] M3D5 is reviewed and accepted before overall M3D acceptance.

---

# M3D cross-phase contract audit

## Read-path contract

* [ ] Map semantic state is read through M3D application contracts.
* [ ] Canonical marker and area state is batch-loaded through accepted repository/data seams.
* [ ] Domain enrichment is batch-loaded through accepted M2 state seams.
* [ ] Map does not query one item at a time through public repository calls.
* [ ] UI does not call DAOs.
* [ ] UI does not expose Room entities.
* [ ] UI does not replay durable events.
* [ ] UI does not derive aggregates from action history.
* [ ] UI does not infer category from canonical kind.
* [ ] Exact group, item type and item ID are preserved.
* [ ] Marker and area same-ID isolation is preserved.
* [ ] One batch uses one accepted clock snapshot.

## Authority contract

* [ ] Canonical persistence remains item content and geometry authority.
* [ ] M2 materialized state remains category, lifecycle, verification and participation authority.
* [ ] Timeline remains historical presentation only.
* [ ] Presentation owns viewport, selection, focus and creation drafts.
* [ ] Runtime map objects remain rendered artifacts only.
* [ ] Runtime renderer does not evaluate domain semantics.
* [ ] Visual policy does not mutate domain state.
* [ ] Map presentation does not enable blocked M3A interactions.
* [ ] Invalid persisted state is not normalized to guessed state.
* [ ] Missing, unresolved and invalid states remain distinguishable.

## Rendering contract

* [ ] Situation and Coordination are visually distinguishable.
* [ ] Category does not rely only on color.
* [ ] Active, resolved, expired, stale and unresolved states remain distinguishable.
* [ ] Marker and area rendering remain explicitly separate.
* [ ] Area confirmation does not imply exact-boundary verification.
* [ ] Confirmation count is not presented as absolute truth.
* [ ] Acknowledgement is not presented as verification or completion.
* [ ] Selection and focus remain ephemeral.
* [ ] Renderer updates are idempotent by exact identity.
* [ ] Obsolete viewport results cannot overwrite newer accepted state.
* [ ] Runtime artifacts are not persisted or treated as authority.

## Creation contract

* [ ] UI submits typed Situation or Coordination creation intent.
* [ ] Category-kind-shape compatibility is explicit.
* [ ] UI cannot construct invalid domain-v1 combinations.
* [ ] Marker and area draft states remain explicitly separate.
* [ ] Draft state is not persisted domain state.
* [ ] UI does not construct raw event payloads.
* [ ] Accepted canonical ADD factories are reused.
* [ ] Accepted local writer is reused.
* [ ] `RoomMapEventProcessor` remains the sole Room transaction owner.
* [ ] Post-commit transport remains outside the Room transaction.
* [ ] Accepted persisted reload remains map truth.
* [ ] No optimistic canonical or domain state is created.

## Detail and timeline integration contract

* [ ] Map selection passes exact typed identity.
* [ ] Detail reloads through accepted M3A read contracts.
* [ ] Timeline remains owned by accepted M3B controller.
* [ ] M3C projectors remain read-only current-state interpretation.
* [ ] Map state is not copied from detail state.
* [ ] Map state is not copied from timeline rows.
* [ ] Map refresh remains independent from timeline refresh.
* [ ] Target missing or deleted is not reconstructed from history.
* [ ] Retained historical activity does not keep a map item active or verified.
* [ ] Map, detail and timeline controllers do not depend on each other.

## Compatibility contract

* [ ] Legacy confidence is not Community Verification.
* [ ] Legacy vote is not Confirm.
* [ ] Legacy contribution is not Situation update or Coordination note.
* [ ] Unresolved legacy category is not guessed from visual kind.
* [ ] Mixed-version limitation remains separate from semantic state.
* [ ] No peer delivery certainty is inferred.
* [ ] No BLE, relay or envelope behavior changes.
* [ ] Room schema remains version 52.

## Failure and exception contract

* [ ] Ordinary recoverable `Exception` failures are mapped explicitly where required.
* [ ] Fatal JVM `Error` subclasses propagate.
* [ ] One invalid item does not corrupt unrelated valid map items where safe isolation is possible.
* [ ] Invalid rows are not silently dropped.
* [ ] Read failure is not treated as accepted empty snapshot.
* [ ] Runtime renderer failure cannot mutate persisted state.
* [ ] Creation failure cannot create synthetic item state.
* [ ] Navigation failure cannot reconstruct missing targets.
* [ ] Technical persistence details remain outside user-facing copy.

---

# M3D overall test strategy

## M3D1

Required:

* [ ] pure unit tests;
* [ ] semantic projection tests;
* [ ] batch repository/data tests;
* [ ] identity and invalid-state tests;
* [ ] source/dependency boundary tests;
* [ ] Room schema regression tests.

Instrumentation:

* [ ] only where required to prove actual Room batch-query behavior or schema compatibility.

Manual:

* [ ] not required for acceptance beyond inspection of no production rendering change.

## M3D2

Required:

* [ ] visual-policy unit tests;
* [ ] runtime renderer tests using map-SDK adapters or test doubles;
* [ ] state-owner concurrency and lifecycle tests;
* [ ] exact-identity registry tests;
* [ ] boundary tests;
* [ ] regression tests.

Instrumentation:

* [ ] production marker and area rendering;
* [ ] selection;
* [ ] update/remove/idempotency;
* [ ] lifecycle recreation;
* [ ] dense-map smoke behavior.

Manual:

* [ ] visual hierarchy;
* [ ] dense-map legibility;
* [ ] contrast and non-color semantics;
* [ ] area verification wording.

## M3D3

Required:

* [x] creation compatibility-policy tests;
* [x] creation-input validation tests;
* [x] event-factory adapter tests;
* [x] submission result tests;
* [x] draft-state tests;
* [ ] no-optimistic-truth tests;
* [x] boundary and regression tests.

Instrumentation:

* [ ] marker drafting and creation;
* [ ] area drafting and creation;
* [ ] cancellation;
* [ ] persistence failure;
* [ ] accepted-with-delivery-warning;
* [ ] Activity lifecycle behavior.

Manual:

* [ ] creation vocabulary;
* [ ] creator source;
* [ ] invalid-combination prevention;
* [ ] marker and area drafting usability.

## M3D4

Required:

* [ ] exact selection and navigation tests;
* [ ] return-to-map refresh tests;
* [ ] invalidated-selection tests;
* [ ] accessibility resource and behavior tests;
* [ ] formatting and raw-data cleanup tests;
* [ ] lifecycle tests;
* [ ] full M3A–M3C regression tests;
* [ ] full data/schema regression tests.

Instrumentation:

* [ ] map-to-detail marker and area paths;
* [ ] same raw ID isolation;
* [ ] detail return and map refresh;
* [ ] resolution and deletion;
* [ ] verification and participation refresh;
* [ ] accessibility and long-content behavior.

Manual:

* [ ] full production map walkthrough;
* [ ] all category/lifecycle/temporal states;
* [ ] creation;
* [ ] selection and navigation;
* [ ] dense map;
* [ ] accessibility;
* [ ] unresolved legacy behavior.

## M3D5

Required:

* [ ] category-kind creation repair tests;
* [ ] semantic marker render-spec and non-color cue tests;
* [ ] forbidden legacy-vocabulary and control-separation tests;
* [ ] resolved interaction-gating and stale-state tests;
* [ ] full M3A–M3D regression tests for both flavors.

Instrumentation:

* [ ] Situation and Coordination marker/area creation paths;
* [ ] semantic marker rendering before and after confirmation;
* [ ] resolved styling and resolved detail action gating;
* [ ] owner edit and legacy-control separation;
* [ ] Activity recreation and stale callback behavior.

Manual:

* [ ] close every criterion listed in M3D5 manual closure on `EmergencyDebug`;
* [ ] close every criterion listed in M3D5 manual closure on `OfficialDebug`;
* [ ] retain exact screenshots, device metadata, gestures and visible outcomes.

---

# M3D overall non-goals

* [ ] Do not implement M3E caching or delivery-state systems.
* [ ] Do not implement M3F global terminology migration beyond terminology required to complete the accepted M3D map and creation flows.
* [ ] Do not add a Room migration.
* [ ] Do not add new tables.
* [ ] Do not change M2 reducers.
* [ ] Do not change action materialization.
* [ ] Do not change confirmation invalidation.
* [ ] Do not change acknowledgement independence.
* [ ] Do not change duplicate classification.
* [ ] Do not change canonical or action ordering.
* [ ] Do not change earliest-resolution behavior.
* [ ] Do not change pending-target recovery.
* [ ] Do not change replay.
* [ ] Do not change processor semantics.
* [ ] Do not change BLE or relay protocols.
* [ ] Do not add moderation.
* [ ] Do not add reputation.
* [ ] Do not add trust percentages.
* [ ] Do not add voting thresholds.
* [ ] Do not add weighted confirmations.
* [ ] Do not add automatic conflict resolution.
* [ ] Do not add per-peer delivery tracking.
* [ ] Do not add kind-specific collaborative interactions.
* [ ] Do not merge legacy and dedicated histories.
* [ ] Do not derive current state from timeline.
* [ ] Do not treat runtime map objects as domain state.
* [ ] Do not persist selection, focus or creation-draft presentation state as domain truth.

---

# M3D overall acceptance

* [x] M3D1 batch map read and semantic presentation model is accepted.
* [x] M3D2 visual policy and runtime marker/area rendering is accepted.
* [ ] M3D3 creation draft and canonical submission integration is accepted.
* [ ] M3D4 map-to-detail integration, accessibility and closure is accepted.
* [ ] M3D5 manual UX remediation and runtime closure is accepted.
* [ ] One clean M3D batch read boundary exists.
* [ ] Visible map state is loaded without unbounded N+1 repository reads.
* [ ] Exact group, item type and item ID are preserved throughout map rendering.
* [ ] Marker and area same-ID isolation is preserved.
* [ ] Canonical geometry remains persisted authority.
* [ ] M2 domain state remains category, lifecycle, verification and participation authority.
* [ ] Timeline remains historical presentation only.
* [ ] Situation and Coordination are visually distinguishable without relying only on color.
* [ ] Active, resolved, expired, stale, unavailable and unresolved states remain distinguishable.
* [ ] Confirmation count is not presented as absolute truth.
* [ ] Area confirmation does not imply exact-boundary verification.
* [ ] Coordination acknowledgement remains independent participation.
* [ ] Selection, focus and creation drafts remain ephemeral presentation state.
* [ ] Runtime SDK artifacts remain caches rather than state authority.
* [ ] Renderer updates are deterministic and idempotent by exact identity.
* [ ] Obsolete viewport results cannot overwrite newer accepted map state.
* [ ] Situation and Coordination creation use typed application intent.
* [ ] Invalid domain-v1 creation combinations cannot be submitted.
* [ ] Marker and area drafting remain available.
* [ ] All creation writes reuse accepted canonical factories and local writer.
* [ ] `RoomMapEventProcessor` remains the sole Room transaction owner.
* [ ] Persisted reload remains map truth after creation and detail changes.
* [ ] No optimistic canonical, verification, participation or timeline state is introduced.
* [ ] Map selection navigates through accepted M3A exact typed identity.
* [ ] Map, creation, detail and timeline state owners remain separate.
* [ ] Raw GeoJSON and unsafe raw persistence values are absent from normal user presentation.
* [ ] Long labels, notes and geometry summaries are presented safely without mutating persistence.
* [ ] Accessibility, contrast and touch-target requirements are verified.
* [ ] Recognized legacy items remain compatible.
* [ ] Unresolved legacy items remain honest and read-only.
* [ ] Existing M3A interactions remain usable.
* [ ] Existing M3B timeline ordering and pagination remain unchanged.
* [ ] Existing M3C verification, freshness and participation semantics remain unchanged.
* [ ] Existing canonical owner edit and delete behavior remains available.
* [ ] Ordinary failures are explicitly mapped and fatal JVM `Error` subclasses propagate.
* [ ] No protocol, event model, reducer, replay, processor, materialization, persistence, transport or relay semantics have changed.
* [ ] Room schema remains version 52.
* [ ] No blocking findings remain.
* [ ] M3D is reviewed and accepted before Phase M3E begins.

---

# M3E — Post-Manual-Test Map UX Remediation

## Purpose and scope

M3E is the single execution backlog for every user-visible defect found during
the Samsung Galaxy A10s `OfficialDebug` M3D5 walkthrough. M3D5 retains the
screenshots and audit history; implementation, automated verification and
repeat manual acceptance belong only to the M3E tasks below.

M3E must preserve the accepted M2/M3 protocol, persistence, reducer, replay,
transport, canonical writer, typed creation and domain-state contracts. It is a
presentation, UI-flow and binding remediation phase, not a semantics rewrite.

## M3E1 — Creation-form cancellation policy (accepted)

Source: `C4.jpg`; previously M3D5A1/M3D5A1A/M3D5A1B/M3D5A1C.

The former remediation is superseded by the accepted UX decision on
2026-07-29: Cancel from a new marker/area metadata form dismisses the form but
retains the active map draft and its **Done**/**Cancel** controls. This lets the
user revise the drawn marker/area rather than losing work.

* [x] Preserve the active creation draft after creation-form Cancel; do not
  restore the original map screen at this point.
* [x] Keep the draft ephemeral: creation-form Cancel must not persist an
  incomplete marker/area or mutate an accepted item.
* [x] Retain map-level draft Cancel as the explicit action that discards the
  overlay and returns to the original map state.
* [x] Manual behavior accepted on `OfficialDebug` by product UX review.

## M3E2 — Coordination activity-heading repair

Source: `B3.jpg`; previously M3D5C1.

* [x] Replace `Situation activity` with `Coordination activity` or an accepted
  neutral heading for Coordination detail.
* [x] Keep Situation's heading category-correct.
* [x] Do not change timeline data, M2/M3 state, action availability or
  persistence behavior.
* [x] Add presentation/resource coverage rejecting `Situation activity` for a
  Coordination detail.
* [ ] Manually verify on `OfficialDebug`.

Additional evidence (2026-07-29): both `coodination-detail.jpg` and
`ack-coodination-detail.jpg` still render the Coordination timeline under
`Situation activity`, including after acknowledgement. This reconfirms the
defect; acknowledgement count and history themselves update correctly.

Implementation evidence (2026-07-29): `MapItemDetailActivity` now derives the
timeline heading from the accepted domain category and uses the new localized
`Coordination activity` resource only for Coordination. `MapItemDetailActivityM3E2Test`
covers the category/resource binding. No timeline rows, interaction semantics,
or persisted state are changed.

### M3E2A — Acknowledgement CTA idempotency cue (2026-07-29)

Source: `ack-coodination-detail.jpg`. The acknowledgement count and activity
were correct, but the same user was still offered `ACKNOWLEDGE COORDINATION`
immediately after the accepted acknowledgement.

* [x] Hide the acknowledgement CTA after the current user receives an accepted
  or already-recorded outcome, without changing the persisted count.
* [x] Also recognize a current-user acknowledgement from loaded timeline
  entries, so a refreshed detail screen does not re-offer the action when that
  evidence is available.
* [x] Keep the CTA available after rejection, invalid input, unavailable or
  failed outcomes; do not optimistically increment the acknowledgement count.
* [x] Add focused presentation coverage for the heading and acknowledgement CTA
  gate.
* [ ] Manually verify: acknowledge once, refresh/reopen the detail screen, and
  confirm no duplicate acknowledgement CTA is offered for the same user.

## M3E3 — Area geometry presentation repair

Source: `C1.jpg` through `C3.jpg` and `D2.jpg`; previously M3D5C2.

* [x] Replace raw GeoJSON in normal Area creation/edit presentation with a
  human-readable drawn-area summary.
* [x] Preserve typed polygon drafts, canonical geometry and submission
  semantics; technical inspection, if necessary, stays outside normal UI.
* [x] Add presentation coverage preventing raw GeoJSON in normal Area forms.
* [x] Manually verify on `OfficialDebug`.

Implementation evidence (2026-07-29): `dialog_map_area.xml` keeps the technical
geometry transport field hidden and presents `area_geometry_summary`.
`GroupMapActivity.areaGeometrySummary()` renders Polygon/MultiPolygon counts
without changing the typed draft or canonical submit path.

Manual evidence (2026-07-29, Galaxy A10s `OfficialDebug`):
`area-creation.jpg` and `area-update.jpg` show `Drawn area · 3 vertices`
instead of raw GeoJSON; `area-detail.jpg` describes `polygon with 3 vertices`;
and `area-on-map.jpg` shows the saved polygon.

## M3E4 — Owner-edit category/kind fidelity repair

Source: `D1b.jpg`; previously M3D5C3.

* [x] Compose owner-edit presentation from canonical persisted category and
  kind; never replace Coordination `Waypoint` with a Situation fallback.
* [x] Make category explicit or unambiguous in owner edit without allowing the
  flow to reinterpret category semantics.
* [x] Preserve canonical owner writer, edit-window rules, geometry and persisted
  category/kind values.
* [x] Add Situation/Coordination marker and area edit tests, including a
  non-default Coordination kind.
* [x] Manually verify Cancel leaves persisted category, kind, label and note
  unchanged.

Implementation evidence (2026-07-29): owner edit loads `category` from
`map_item_domain_state` before opening the form and filters kind choices with
the accepted category. Unknown legacy category is shown explicitly and locks
kind rather than deriving category from kind or falling back to the first
Situation option. `MapLegacyCleanupM3D5CTest` and
`MapKindsCategoryContractTest` cover the boundary and the urban Coordination
`Waypoint` case.

Manual evidence (2026-07-30, Galaxy A10s `OfficialDebug`):
`edit-coodination-before-save.jpg`, `coodination-after-save.jpg` and
`edit-coodination-after-save.jpg` confirm that a newly saved Coordination
`📍 Waypoint` remains a Waypoint in owner edit and explicitly displays
`Category: Coordination`. The separate Cancel-persistence criterion remains
closed: `edit-coodination-cancel.jpg` changes the note without saving, while
`coodination-after-cancel-edit.jpg` retains the previously saved
`Test C2, edit note` value and the same `W1` / `📍 Waypoint` item.

## M3E5 — Semantic marker rendering and accessibility repair

Source: M3D5 baseline marker-dot evidence; previously M3D5B kind/category cue.

* [x] Render a non-color emoji/icon or equivalent kind/category cue on persisted
  markers.
* [ ] Keep kind and lifecycle cues visible under selection and dense input.
  Category-specific marker-frame shape is optional styling, not an acceptance
  requirement.
* [x] Provide localized content descriptions containing shape, category, kind
  and lifecycle where known.
* [x] Verify render specs, exact marker/area identity isolation, idempotent
  snapshot reapplication and renderer boundary isolation.
* [ ] Manually verify on both debug flavors.

### M3E5A — Selected-marker semantic-cue layering (deferred)

Discovered by `active-coodination-on-map.jpg` (Galaxy A10s, `OfficialDebug`,
2026-07-29). The selected Coordination marker label `W1` covers the central
`📍 Waypoint` cue, leaving no visible kind emoji/icon on the selected marker.

* [x] Reposition marker labels to the right of the icon frame so kind cue and
  selected emphasis remain simultaneously visible.
* [ ] Preserve exact identity and selection hit behavior; do not derive
  category/kind in the runtime renderer.
* [ ] Add selected Situation and Coordination render coverage and manually
  verify both on `OfficialDebug`.

Implementation note (2026-07-29): `ResolvedMapLabel` now carries an explicit
leading inset. The Android resolver assigns it only to marker labels; following
device evidence, it retains 30% of the initial full-frame offset (9dp standard,
10.8dp emphasized), moving the label 70% back toward the icon. The osmdroid
runtime anchors these labels at the item coordinate's left edge. Area labels
retain their centred placement. Runtime device evidence remains required.

Additional device evidence (2026-07-29): `coodination-on-map.jpg` and
`ack-coodination-on-map.jpg` confirm that `W1` no longer overlaps `📍 Waypoint`.
The acknowledgement badge appears in addition to the pin. Full selected
Situation/Coordination comparison coverage remains open.

### M3E5B — Detail kind emoji consistency (2026-07-29)

Source: detail-screen review. Persisted markers already render their kind emoji
on the map, but detail rendered only the raw normalized key (for example
`resource` or `waypoint`).

* [x] Render detail kind with the same catalog-backed `emoji + readable name`
  label used by map rows and creation selectors; retain the raw key only as a
  fallback for unknown/legacy kinds.
* [x] Add focused presentation coverage for catalog label lookup.
* [ ] Manually verify a Situation and a Coordination detail screen on
  `OfficialDebug`.

### M3E5C — Area centroid kind cue (2026-07-29)

Source: `area-on-map.jpg`. A persisted Area renders only its polygon boundary
and fill, so its kind cannot be identified from the map alone.

* [x] Render the Area's catalog emoji at the geometry centroid.
* [x] Surround the emoji with a dashed circular border slightly larger than the
  icon, visually distinguishing Area cues from point-marker artwork.
* [x] Render the same small current-confirmation, resolved `×`, or Coordination
  acknowledgement status cue used by marker artwork at the Area centroid.
* [x] Place an Area text label below the centroid icon with a small gap; do not
  change point-marker label placement.
* [x] Keep the centroid cue in the exact Area artifact bundle and route its tap
  to the same identity as the polygon.
* [x] Add resolver and runtime-registry coverage.
* [ ] Manually verify unselected/selected Area cue visibility, current/resolved
  status badge, and centroid tap behavior on `OfficialDebug`.

Implementation evidence (2026-07-30): `AreaKindDrawable` provides the dashed
circle, emoji and marker-parity status cue; `OsmdroidMapRuntimeRenderer` owns
the centroid `Marker` in `AreaArtifacts`, preserving exact identity and
deterministic overlay order. Runtime verification of active/confirmed/resolved
area cues remains open.

## M3E6 — Verification and lifecycle cue repair

Source: M3D5 baseline confirmed/resolved marker evidence; previously M3D5B
verification/lifecycle cue.

* [x] Add a non-color current-confirmation cue in addition to green outline.
* [x] Make the non-color `×` lifecycle cue authoritative over current or
  historical confirmation styling; dashed outline is optional decorative styling.
* [x] Preserve distinct active, stale, no-longer-active, expired, unavailable
  and unresolved presentation where data is available.
* [ ] Add visual-policy/runtime tests and manually verify selected confirmed and
  selected resolved items.

### M3E6A — Resolved dashed-cue legibility (superseded)

The 2026-07-29 UX decision makes the visible non-color `×` badge sufficient for
resolved/no-longer-active recognition. A dashed outline may remain as optional
styling, but its legibility is no longer an acceptance criterion or remediation
task.

### M3E5/M3E6 implementation evidence — 2026-07-29

* `AndroidMapVisualResourceResolver` now renders a runtime-only semantic marker
  drawable with a central kind emoji. Its category-specific frame shapes remain
  optional styling rather than an acceptance requirement.
* Current confirmation keeps its green outline but now also renders a `✓` badge.
  Resolved markers render an `×` badge; any dashed outline is optional styling;
  resolution styling
  takes priority over historical/current confirmation color.
* Marker content descriptions now include the readable kind in addition to the
  existing category, geometry and state tokens.
* `AndroidMapVisualResourceResolverM3D5BTest` covers semantic kind/category,
  non-color confirmation/resolution cues, resolved precedence and kind-bearing
  descriptions. Existing visual-policy and resolver boundary tests remain in
  the focused suite.
* Focused tests passed for `EmergencyDebug` and `OfficialDebug`; both debug APKs
  assembled successfully. Runtime manual acceptance remains pending
  `docs/map_refactor/M3D5B_Manual_Test_Script.md`.
* Galaxy A10s `OfficialDebug` evidence reviewed on 2026-07-29:
  `active-situation-on-map.jpg` shows an active Situation kind emoji plus `✓`;
  `active-situation-detail.jpg` confirms current confirmation separately from
  creator source. `active-coodination-on-map.jpg` exposes M3E5A because selected
  label `W1` obscures the Coordination kind cue. The later UX decision accepts
  the visible `×` as the required resolved cue and supersedes M3E6A's dashed
  border remediation.
  `no-longer-marker-detail.jpg` also reconfirms the open M3E7 defect: Add
  Situation Update remains visible after resolution.

## M3E7 — Resolved Situation action gating

Source: resolved-detail manual evidence; previously M3D5D.

* [ ] Hide Confirm, Add Situation Update and Mark no longer active for a
  resolved Situation; do not offer reactivation.
* [ ] Keep historical activity visible, current confirmation at none and
  processor validation authoritative for stale forms.
* [ ] Protect against stale callbacks or Activity recreation restoring active
  controls after resolution.
* [ ] Add active/resolved, stale-form and lifecycle recreation coverage; manually
  verify on both debug flavors.

## M3E8 — Legacy vocabulary and control cleanup

Source: M3D5 manual audit; previously M3D5C generic cleanup.

* [x] Audit normal list, creation, owner edit and detail for `Observed`, bare
  `Reported`, `Confidence`, `Vote`, `Voting`, `Contribute`, `Contributions`,
  `Request Removal` and `Note (observed)`.
* [x] Remove or explicitly isolate legacy controls without relabeling vote as
  Confirm, contribution as update/note, or removal request as lifecycle/Delete.
* [x] Keep canonical Edit/Delete separate from collaborative actions and retain
  readable, safe behavior for recognized and unresolved legacy items.
* [x] Add resource/source-boundary and regression tests.
* [ ] Manually verify the normal map, detail and owner-edit paths.

Implementation evidence (2026-07-29): normal rows no longer render vote badges
or Request Removal links; owners receive separate canonical Edit and Delete
buttons. Existing edit dialogs preserve stored confidence internally while
hiding legacy vote/contribution/removal controls. Recognized legacy removal
state remains read-only. Historical persistence and processor behavior are not
deleted or relabeled.

### M3E8A — Non-owner legacy contribution entry points (2026-07-29)

Discovered in `active-situation-contribution-viewed-other-user.jpg` and
`active-situation-on-map-viewed-other-user.jpg` on `OfficialDebug`: a
non-owner could still open the legacy `Observed`/`Reported` contribution form,
see `Contributions`, and use the `Contribute` map-row action. Those controls
overlap and conflict with the dedicated Situation/Coordination interactions.

* [x] Remove the non-owner `Contribute` map-row action and contribution preview;
  retain Details as the route to the dedicated item actions.
* [x] Make non-owner legacy marker/area dialogs read-only: hide legacy
  confidence/source and note fields, suppress Save, and do not load the legacy
  vote record into the dialog.
* [x] Hide the legacy contribution history section for non-owners without
  deleting stored contribution/vote data or changing protocol/persistence.
* [ ] Add focused regression coverage and manually verify marker and area as a
  different device/user on both debug flavors.

Implementation note (2026-07-29): this is deliberately a UI-surface removal.
Existing local records and their historical processor/protocol behavior remain
unchanged; canonical Situation confirmation/update and Coordination
acknowledgement/note actions stay on the detail screen.

## M3E9 — Immediate map/list refresh after marker and area mutation (2026-07-30)

Source: delete flow observed on Galaxy A10s `OfficialDebug`. After confirming
Delete, the marker/area can remain visible in the map and list until the user
opens **Details**, which incidentally refreshes the presentation. This is a
stale UI-state defect, not an asynchronous-delete latency acceptance.

* [x] After a successful owner edit/save, refresh the affected marker or area
  immediately in both the map overlay and its selected/list row without a
  follow-up navigation action.
* [x] After confirmed owner Delete, remove only the affected marker or area
  immediately from the map overlay, selection/card, and list; preserve every
  unrelated item.
* [x] Ensure the refresh handles marker and area mutations, including a
  currently selected item, without requiring **Details**, manual refresh, or
  reopening the map screen.
* [x] Add focused presenter/runtime coverage for edit and delete snapshot
  reconciliation, then manually verify on `OfficialDebug` and `EmergencyDebug`.

Implementation evidence (2026-07-30): Delete now returns its local
`storeAndBroadcast` outcome to the UI callback and calls `refreshMapUi` only
after an accepted write, matching the existing successful owner-edit flow.
`MapUiRefreshM3E9Test` verifies edit/delete refresh ordering for markers and
areas. Runtime evidence remains open in
`docs/map_refactor/M3E9_Manual_Test_Script.md`.

Manual regression: edit a visible marker and area, save each, then confirm
Delete for one of them while another remains visible. The map and list/card
must update immediately and the unrelated item must remain unchanged.

### M3E9A — Hide owner mutation actions when canonical mutation is unavailable (2026-07-30)

Source: `photo_2026-07-30_12-29-21.jpg` and
`photo_2026-07-30_14-28-03.jpg`. A resolved owner Situation still showed
**Edit**/**Delete**, but the canonical writer rejected both UPD and DEL events
and the UI only reported `Could not save map update.`

* [x] Derive marker/area owner-mutation availability from canonical owner, lifecycle,
  expiry and owner-edit-window state.
* [x] Hide **Edit** and **Delete** for `No longer active`, expired, owner-edit-window-closed,
  non-owner, missing/invalid canonical state, and locally removed-member cases.
* [x] Keep **Edit** and **Delete** visible for an eligible active owner item and
  retain **Details** for items whose mutation actions are unavailable.
* [x] Add focused policy coverage for each accepted/rejected state.
* [ ] Manually verify eligible owner, resolved owner and non-owner marker/area
  rows on both debug flavors using Test R4 in
  `docs/map_refactor/M3E9_Manual_Test_Script.md`.

Implementation evidence (2026-07-30): `MapOwnerDeleteAvailabilityPolicy`
mirrors the canonical UPD/DEL constraints and `MapListAdapter` renders Edit and
Delete only for item identities approved from loaded domain state. Focused
`MapOwnerDeleteAvailabilityPolicyTest`, M3D5C cleanup coverage and the
OfficialDebug/EmergencyDebug focused tests and builds pass.

## M3E10 — Canonical Coordination-area support (2026-07-30)

Source: Coordination Area creation showed **No compatible kinds are available**,
which prevented using a drawn area for real coordination work.

* [x] Add canonical, Area-compatible Coordination kinds: **Assembly zone**
  (general gathering), **Trail sector** (hiking), **Event zone** (outdoor
  events), and **Rescue staging** (rescue operations).
* [x] Preserve the existing `camp` and `waypoint` marker-only compatibility
  contract; do not reclassify any existing Situation kind.
* [x] Surface readable emoji-backed options in the Area creation selector:
  Assembly zone and Event zone for Urban; all four for Outdoor.
* [x] Keep Coordination Marker vocabulary profile-specific: Urban offers
  Meeting point and Waypoint; Outdoor offers Campsite and Waypoint, without
  falling back to Campsite for Urban.
* [x] Cover catalog category/shape compatibility, profile options, and
  Coordination-area acknowledge/note processing.
* [ ] Manually verify creation, map centroid cue, Details and Coordination
  actions using `docs/map_refactor/M3E10_Manual_Test_Script.md`.

Implementation evidence (2026-07-30): `MapItemKindCatalog` is the canonical
source for the four new Coordination-area kinds and the Urban marker-only
`meeting_point` kind, so protocol validation,
persistence and UI filtering agree. `MapKinds` supplies emoji-backed labels to
the Area form and all map/detail presentation. Existing `camp` and `waypoint`
remain marker-only; these additions do not silently broaden legacy kinds.

## M3E11 — Immediate map/list refresh after remote map events (2026-07-30)

Source: when user A changed a marker or area, user B received the BLE/cloud
**map update** or **map synced** banner, but the map overlay and marker/area
list remained stale until a later refresh. The event had already been accepted
and committed; only the active map presentation was not reconciled.

* [x] Use the shared post-commit `EventIngestor` notification boundary for
  accepted BLE and relay/cloud `MAP.*` events.
* [x] Coalesce accepted remote map-event bursts and immediately reload the
  current group's authoritative Room-backed overlays and marker/area list.
* [x] Preserve an in-progress marker/area draft; retain the pending remote
  refresh and apply it when the draft finishes or is cancelled.
* [x] Make the callback lifecycle- and group-scope-safe: cancel queued work
  while paused, rely on the full resume load for missed events, and reject an
  obsolete group generation.
* [x] Add focused regression coverage for BLE/relay ingress, post-commit
  notification, debounce, draft deferral and pause/resume behavior.
* [ ] Manually verify canonical add/edit/delete and dedicated Situation or
  Coordination updates over both BLE and cloud using
  `docs/map_refactor/M3E11_Manual_Test_Script.md`.

Implementation evidence (2026-07-30): `GroupMapActivity` now turns every
accepted remote `MAP.*` notification into a 500 ms coalesced map/list reload.
The normal `onResume` load covers events received while the Activity is
paused. `RemoteMapIngestRefreshM3E11Test` protects the common BLE/relay ingest
boundary and the lifecycle/draft reconciliation contract.

## M3E12 — Missing canonical map dependency recovery (defer → repair → replay)

Source: user A creates a marker/area, but user B misses its canonical
`MAP.*.ADD` because BLE delivery is weak or interrupted. If B later receives
an `UPD`, `DEL`, Situation action or Coordination action for the same item, B
cannot materialize the item because its canonical owner, creation metadata and
domain state were never established locally. Refreshing the UI cannot repair
this state because the missing item does not exist in Room.

Current failure mode:

1. The canonical `ADD` may be absent even though later mutations/actions carry
   its `ref.root`; weak BLE delivery can deliver a later action while the
   initial ADD remains in a prior outbound batch with no retryable target.
2. Dedicated actions with no local target are currently persisted as
   `pending_target` but classified as ordinary accepted events, so they do not
   activate the missing-root repair trigger.
3. BLE transport completion is not proof that every group member durably
   materialized the event; a normal application batch can complete after the
   first successful peer delivery and rely on later mesh forwarding.
4. Relay pull advances its cursor after processing the fetched page even when
   an individual event was not accepted, so a rejected orphan event is not a
   durable recovery queue.
5. A generic sync banner can therefore be visible while the canonical item is
   still absent from the map and list.

### Proposed processing contract

Use a bounded, durable **defer → repair → replay** flow:

1. **Defer**
   * Detect a structurally valid canonical `UPD`/`DEL` or dedicated map action
     whose marker/area canonical `ADD` is missing.
   * Do not apply it as an implicit `ADD`, and do not permanently reject and
     forget it.
   * Persist the exact event and fingerprint in a pending canonical inbox keyed
     by `(gid, item_type, item_id, event_id)`, with materialization state
     `pending_missing_canonical`.
   * Return an accepted-deferred result only after that pending state is
     committed, so BLE acknowledgement or cloud cursor advancement never
     discards the only recoverable copy.

2. **Repair**
   * Persist the canonical root event ID when an `ADD` is created/materialized.
     New `UPD`, `DEL` and dedicated actions must set `ref.root` to that `ADD`
     event ID while keeping `ref.target` as the marker/area ID.
   * On first receipt of an orphan, retain its root as a durable local WANT.
     Every subsequently encountered eligible BLE/DTN peer receives a targeted
     `WANT(ref.root)` until the root ADD materializes locally or the root moves
     to a terminal rejection state. The peer need not be the marker/area owner:
     any peer with durable event history may satisfy the request.
   * Per-peer suppression/backoff is allowed to avoid a retry storm, but must
     not globally suppress the WANT merely because one prior peer failed or was
     unavailable. A new peer is a new repair opportunity.
   * BLE/DTN responses fetch the missing root by event ID from durable event
     history, not only from a still-live outbound batch.
   * Cloud repair fetches the root by event ID independently of the normal
     forward-only relay cursor.
   * During compatibility rollout, events without `ref.root` may request repair
     by `(gid, item_type, item_id)` from a peer capable of resolving the
     canonical root.
   * A short-term BLE optimization may prepend the immutable root `ADD` to a
     mutation batch, but dependency bundling does not replace durable pending
     state or targeted cloud repair.

3. **Replay**
   * When the root `ADD` arrives, materialize it and its domain state first.
   * Replay pending canonical mutations for the exact target in deterministic
     total order, preserving existing owner, category, lifecycle, expiry and
     stale-event policies.
   * Replay pending Situation/Coordination actions only after the canonical
     mutation history is established.
   * Commit root materialization, pending-state transitions and resulting
     projections transactionally; duplicate delivery must remain idempotent.
   * Publish one post-commit map notification after reconciliation so map/list
     refresh sees the repaired final state rather than intermediate states.

### Safety, UX and observability requirements

* Pending rows must have per-group/per-target caps, retention/expiry, pruning
  and replay-attempt diagnostics to prevent unbounded storage or retry storms.
* Conflicting duplicates, target-type conflicts, removed-member events and
  invalid signatures remain hard rejections and must never enter repair.
* A generic transport-level **Map synced** banner is acceptable when the orphan
  is durably received. It must immediately trigger missing-root repair and must
  not be treated as proof that the marker/area is already materialized.
  Item-level success such as **Marker updated** is published only after replay.
* Repair requests require backoff, deduplication and one in-flight request per
  root/target. Offline operation must retain pending work for later BLE or
  cloud availability. A root is satisfied only after its ADD is durably
  materialized; receiving a later action or one unsuccessful peer exchange
  must not clear the request.
* Do not create a marker/area directly from `UPD`. Update payloads do not
  reliably contain immutable creation context, and treating them as `ADD`
  would weaken canonical ownership and lifecycle validation.
* M3E11 remains presentation reconciliation only; debounce or periodic UI
  reload changes are not a substitute for dependency recovery.

### Delivery tasks

* [x] Add protocol/root-reference compatibility and persist canonical
  `root_event_id` for marker and area domain state.
* [x] Add durable pending-canonical persistence, outcome classification,
  bounded recovery scheduling and deterministic replay.
* [x] Classify a dedicated action with an absent target as accepted-deferred
  after persisting its `pending_target` row, so it activates repair rather than
  emitting only an ordinary map-sync notification.
* [x] Add peer-agnostic BLE/DTN targeted WANT replay: retain missing
  `ref.root`, request it from every newly encountered eligible peer, apply
  per-peer backoff/deduplication, and stop only after ADD materialization or a
  terminal rejection.
* [x] Add cloud targeted event repair independent of the relay cursor and
  ensure cursor advancement is safe only after accept or durable defer.
* [x] Reconcile canonical mutations before existing pending dedicated actions,
  then publish one post-commit refresh notification.
* [x] Add diagnostics and correct deferred/success banner semantics.
* [x] Trigger a coalesced relay repair cycle immediately when a deferred event
  exposes a missing canonical `ref.root`; use the existing targeted
  `repairByEventIds` path and preserve the pending row while offline.
* [ ] Cover marker and area gaps for `ADD → UPD`, `ADD → DEL`, Situation
  actions and Coordination actions, including duplicates, reordering, process
  restart, offline recovery, expiry, caps and mixed BLE/cloud delivery.
* [ ] Manually verify on both debug flavors with two or more devices by
  deliberately suppressing `ADD`, delivering later mutations, restoring a
  repair path and confirming final map/list convergence.

Implementation evidence (2026-07-30): Room schema 53 adds the bounded
`pending_canonical_map_events` inbox. Remote canonical mutations are durably
classified as accepted-deferred, retain their exact event/fingerprint, and do
not emit the normal inserted notification. Canonical ADDs and newly authored
mutations/actions carry `ref.root`; ADD materialization replays canonical
mutations in total order before the existing dedicated-action replay in the
same Room transaction. BLE anti-entropy can now repair from the durable event
inventory, while relay sync performs a cursor-independent, capped event-ID
fetch for pending canonical and dedicated-action roots. Automated processor,
factory, submission and 52→53 migration coverage is supplemented by
`docs/map_refactor/M3E12_Manual_Test_Script.md`; mixed-transport, restart,
expiry/cap and both-flavor device evidence remains open.

Implementation evidence (2026-07-31): `AcceptedDeferred` now emits a dedicated
canonical-repair request. A running relay scheduler is pulled forward without
starting a parallel loop; otherwise a network-constrained unique one-time
worker is queued. The next cycle reads durable pending roots, fetches the
missing ADD by event ID, and lets the existing transactional processor replay
canonical mutations before Situation/Coordination actions. Activity-log
milestones cover defer, request, scheduling, targeted pull and completion.

Implementation evidence (2026-07-31, M3E12 completion patch): a dedicated
Situation/Coordination action whose target has not materialized now returns
`AcceptedDeferred` after its immutable `pending_target` row commits, whenever
it carries `ref.root`. This activates the existing repair notification and
cloud event-ID pull. The DTN inventory store also surfaces durable missing
roots from both canonical and dedicated-action pending stores. At every new
BLE/DTN peer session, `DtnSyncEngine` sends a targeted `INV_WANT` for those
roots even if the peer did not advertise them in its current inventory window;
the request is session-scoped, so a failed or disconnected peer does not
suppress the same WANT for a later peer. The WANT naturally disappears after
the ADD materializes and pending rows replay. Events from legacy peers without
`ref.root` retain the pre-existing pending-target behavior because no safe
event-ID repair target exists.

Test finding (2026-07-31): A missed `MAP.MARKER.ADD` followed by
`MAP.ITEM.CONFIRM` and `MAP.ITEM.SITUATION.UPDATE` still left the recipient
without the marker. Both dedicated actions were persisted as ordinary accepted
events, so no deferred/repair log was emitted. The original ADD was not retried
after the peer became available, and DTN produced no event-ID WANT. The two
open delivery tasks above are therefore required before M3E12 can be marked
complete.

## M3E13 — Human-readable map-item metadata and owner attribution

Source: `photo_2026-07-30_19-33-38.jpg` on the group map shows a marker card
with `Updated 1d ago · TTL -- · by 7elqLQ`. `TTL` is implementation vocabulary
and `--` provides no useful user information, while the shortened technical
actor identifier does not identify a person clearly.

### Presentation contract

* Remove the `TTL` segment from marker and area list/card metadata. For
  example, render `Updated 1d ago · by Mai` rather than
  `Updated 1d ago · TTL -- · by Mai`.
* This is presentation-only cleanup. Do not remove TTL/expiry from the event
  contract, Room state, validation or lifecycle behavior. Where expiration is
  relevant, the detail screen may continue to express it with user-facing
  wording such as an expiry date or remaining time rather than the acronym
  `TTL`.
* Resolve the displayed creator name in this strict order:
  1. If the canonical marker/area owner is the active local user, show the
     localized text **by you**.
  2. Otherwise, use that owner's nonblank nickname for the current group.
  3. Otherwise, use that owner's nonblank profile name configured in
     **Settings**.
  4. If neither name is available, use a localized neutral fallback such as
     **by a group member**; do not expose DID, device ID or another shortened
     technical identifier in the normal card UI.
* Apply the same resolver and vocabulary to both marker and area rows,
  regardless of Situation/Coordination category, lifecycle state, selection
  state or owner/non-owner viewer.
* Normalize blank/whitespace-only names, preserve Unicode/emoji names and
  safely ellipsize long names without changing the underlying stored profile.
* Name/profile updates and group nickname changes must be reflected after the
  next normal map/list reconciliation without rewriting map events.

### Delivery tasks

* [x] Add a presentation-level map owner display-name resolver that consumes
  the active actor plus group nickname/profile projections without leaking DAO
  or Settings access into the row adapter.
* [x] Replace technical actor ID formatting with the defined
  `you → group nickname → profile name → neutral fallback` precedence.
* [x] Remove the `TTL` token/value from marker and area card metadata while
  preserving all expiry domain behavior.
* [x] Add focused tests for local owner, nickname, profile fallback, missing
  names, Unicode/long names and marker/area parity.
* [ ] Manually verify owner and non-owner rows on both debug flavors, including
  a user with a group nickname and a user with only a Settings profile name.

## M3E completion gate

* [ ] Every M3E task has focused automated coverage and real-device runtime
  evidence.
* [ ] EmergencyDebug and OfficialDebug both pass the applicable regression and
  manual flows.
* [ ] `Map_Target_UX_Checklist.md` is updated only when the repaired runtime
  behavior is evidenced.
* [ ] No M2/M3 protected invariant, Room schema, protocol or persistence
  behavior regresses.
