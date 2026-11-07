## Phase M2 — Persistence, Reducer and Ingest Enforcement

### Objective

Implement the accepted Phase M1 marker/area domain contracts across:

- event models;
- serialization and deserialization;
- Room persistence;
- materialized domain state;
- reducers;
- local validation;
- verified remote ingest;
- legacy compatibility;
- BLE and relay-compatible event flow.

Phase M2 must preserve:

- offline-first local persistence;
- the durable append-only event log;
- transactional reducer/materialization;
- Room-based read state;
- deterministic event ordering;
- replay and dedupe protections;
- membership enforcement;
- BLE outbox and batching;
- relay bridging;
- TTL filtering;
- existing M1-compatible drafting and UI behavior until Phase M3.

### Locked architecture decisions

- [x] Keep existing canonical map event types:
  - `MAP.MARKER.ADD`
  - `MAP.MARKER.UPD`
  - `MAP.MARKER.DEL`
  - `MAP.AREA.ADD`
  - `MAP.AREA.UPD`
  - `MAP.AREA.DEL`
- [x] Use optional M1 domain extensions only for canonical ADD/UPD metadata.
- [x] Do not represent new member interactions as canonical UPD events.
- [x] Add dedicated interaction event types:
  - `MAP.ITEM.CONFIRM`
  - `MAP.ITEM.SITUATION.UPDATE`
  - `MAP.ITEM.SITUATION.STATUS`
  - `MAP.ITEM.ACKNOWLEDGE`
  - `MAP.ITEM.NOTE`
- [x] Use `EventCore.ref.target` as the canonical target item ID.
- [x] Use explicit `item_type = marker | area` discrimination.
- [x] Require `action_id` to equal the event ID.
- [x] Treat the event ID as the stable action identity and dedupe identity.
- [x] Keep new action history append-only.
- [x] Keep legacy contributions in `map_item_votes`.
- [x] Never reinterpret legacy contributions as new actions.
- [x] Store new domain materialization in a generic marker/area domain-state model.
- [x] Use a separate generic action-history table.
- [x] Permit new actions on recognized legacy items when category, membership and temporal rules can be established safely.
- [x] Treat legacy creator source as unspecified.
- [x] Do not automatically treat `SITUATION_CHANGED` as a conflict.
- [x] Invalidate current confirmation freshness after a material Situation change.
- [x] Preserve conflict inputs for a later reviewed conflict contract.
- [x] Accept that legacy peers may reject unknown dedicated action event types.
- [x] Never disguise a new action as a legacy canonical UPD merely to make an old peer accept it.

---

## Phase M2A — Event Contract and Serialization Foundation

### Goal

Define and verify the production wire contract for M1 domain metadata and dedicated map interaction events without changing Room schema, production reducers or user-facing UI behavior.

### Implemented production wire contract

All dedicated actions use event version `ev = 1`, payload version `map_domain_v = 1`,
`EventCore.ref.target` as the only target identity, and `item_type = marker | area`.
`action_id` is required and equals `EventCore.id`; `action_t` is required and equals
`EventCore.time.t`. Actor and Lamport identity exist only in `EventCore.actor.did` and
`EventCore.time.lt`. Payload actor/time/Lamport overrides and canonical mutation fields
are rejected. Unknown optional fields are tolerated for version 1, while unknown stable
semantic values and unsupported domain versions are rejected.

The event/action mapping is one-to-one:

| Event type | `map_action` | Action-specific payload |
|---|---|---|
| `MAP.ITEM.CONFIRM` | `confirm` | optional `information_source`, optional `action_note` |
| `MAP.ITEM.SITUATION.UPDATE` | `add_update` | required nonblank `action_note`, required `situation_update_type`, optional `information_source` |
| `MAP.ITEM.SITUATION.STATUS` | `update_status` | required `situation_lifecycle = no_longer_active`, optional `information_source`, optional `action_note` |
| `MAP.ITEM.ACKNOWLEDGE` | `acknowledge` | no source, note, lifecycle, update type, confidence, or canonical fields |
| `MAP.ITEM.NOTE` | `add_note` | required nonblank `action_note`; no source, lifecycle, update type, or canonical fields |

Domain-aware canonical ADD retains all legacy canonical fields and adds
`map_domain_v = 1`, `item_category`, and conditionally `information_source`.
The category must match the normalized kind in `MapItemKindCatalog`; Situation creation
requires source and Coordination creation forbids it. Legacy ADD without
`map_domain_v` remains valid and legacy `confidence` is not interpreted as source.
Canonical UPD remains a canonical mutation and is never decoded as a dedicated action.

### Event type contract

- [x] Add constants for:
  - `MAP.ITEM.CONFIRM`
  - `MAP.ITEM.SITUATION.UPDATE`
  - `MAP.ITEM.SITUATION.STATUS`
  - `MAP.ITEM.ACKNOWLEDGE`
  - `MAP.ITEM.NOTE`
- [x] Keep every dedicated action type under the existing `MAP.*` namespace.
- [x] Verify `EventTypes.isMap()` recognizes all dedicated action events.
- [x] Define an explicit mapping between each event type and `MapItemAction`.
- [x] Reject mismatches between event type and `map_action`.
- [x] Do not introduce marker-specific and area-specific duplicates of every action type.

### Target and identity contract

- [x] Require a nonblank target item ID in `EventCore.ref.target`.
- [x] Require `item_type` to be exactly `marker` or `area`.
- [x] Reject a payload target ID that conflicts with `EventCore.ref.target`.
- [x] Require nonblank `action_id`.
- [x] Require `action_id == EventCore.id`.
- [x] Require `action_t == EventCore.time.t` when `action_t` is present.
- [x] Take actor identity exclusively from `EventCore.actor.did`.
- [x] Take Lamport time exclusively from `EventCore.time.lt`.
- [x] Do not allow payload fields to override actor, timestamp or Lamport identity.

### Payload versioning

- [x] Use `map_domain_v = 1` for new domain-aware canonical events and all dedicated action events.
- [x] Preserve M1 reserved field names:
  - `map_domain_v`
  - `item_category`
  - `information_source`
  - `map_action`
  - `situation_lifecycle`
  - `situation_update_type`
  - `action_id`
  - `action_t`
  - `action_note`
- [x] Add only the minimum extra discriminator required for marker/area targeting, such as `item_type`.
- [x] Do not introduce a second action content field.
- [x] Use `action_note` for both optional notes and required update/note content.
- [x] Ignore unknown optional fields for a known supported payload version.
- [x] Fail safely on unknown domain versions when new semantics cannot be established.
- [x] Do not guess unknown category, source, action, lifecycle or update-type values.

### Dedicated action payloads

- [x] Define Confirm payload:
  - optional information source;
  - optional action note;
  - no coordinates or geometry;
  - no canonical item fields.
- [x] Define Situation Update payload:
  - required nonblank action note/content;
  - required update disposition;
  - optional information source;
  - no lifecycle mutation field.
- [x] Define Situation Status payload:
  - required lifecycle target;
  - optional action note;
  - optional information source;
  - user-created target limited to `NO_LONGER_ACTIVE`.
- [x] Define Acknowledge payload:
  - no information source;
  - no weighted confidence;
  - no canonical item fields.
- [x] Define Coordination Note payload:
  - required nonblank action note/content;
  - no information source;
  - no canonical item fields.

### Canonical ADD/UPD extensions

- [x] Allow domain-aware ADD events to carry category and creator information source.
- [x] Validate category against the normalized kind catalog.
- [x] Require creator source for domain-v1 Situation creation.
- [x] Forbid creator source for domain-v1 Coordination creation.
- [x] Continue accepting legacy ADD events without `map_domain_v` or source.
- [x] Do not apply domain-v1 source requirements retroactively to legacy events.
- [x] Keep existing canonical marker/area fields readable by legacy peers.
- [x] Do not make legacy confidence represent creator information source.

### Structural validation

- [x] Extend `EventValidator` for all dedicated event types.
- [x] Keep pure structural/wire validation separate from DB-dependent semantic validation.
- [x] Validate positive event/action timestamps.
- [x] Validate non-negative Lamport time.
- [x] Validate stable wire values through M1 parsers.
- [x] Validate category/action compatibility where payload information is sufficient.
- [x] Validate action-specific required and forbidden fields.
- [x] Reject coordinates or geometry in dedicated action payloads.
- [x] Verify area confirmation contains no exact-boundary confirmation semantics.
- [x] Reject malformed action payloads without affecting valid sibling events in the same APP batch.

### Event factories/codecs

- [x] Add typed factories or codecs for each dedicated action.
- [x] Map domain objects to stable wire values explicitly.
- [x] Map event ID directly to action ID.
- [x] Ensure factory output passes `EventValidator`.
- [x] Add round-trip serialization/deserialization tests.
- [x] Add unknown-field tolerance tests.
- [x] Add unknown-value and mismatched-event-type rejection tests.
- [x] Add marker/area target-discrimination tests.
- [x] Add action-ID/event-ID mismatch tests.

### M2A non-goals

- [x] Do not change Room schema.
- [x] Do not add action tables yet.
- [x] Do not implement production action reducers.
- [x] Do not enable new action UI.
- [x] Do not reinterpret legacy persistence.
- [x] Do not redesign BLE or relay transport.
- [x] Do not change existing user-facing Confidence/Contribute behavior.

### M2A acceptance

- [x] Wire contract is documented and stable.
- [x] All dedicated events have typed construction and parsing.
- [x] Structural validation rejects malformed action events.
- [x] Existing legacy canonical events continue passing their tests.
- [x] `core` tests pass.
- [x] Existing app compilation remains unaffected.
- [x] M2A is reviewed and accepted before starting Room migration work.

---

## Phase M2B — Persistence and Read-Model Foundation

### Goal

Add non-destructive Room persistence for normalized map-item domain state and append-only dedicated action history while preserving all legacy rows, existing map rendering queries and current drafting behavior.

Phase M2B is divided into:

- Phase M2B1 — Persistence Schema, Migration and Legacy Backfill;
- Phase M2B2 — Deterministic DAO and Read-Model Queries.

M2B must prepare transaction-safe persistence seams for M2C without enabling production action reduction, semantic enforcement or action generation.

---

## Phase M2B1 — Persistence Schema, Migration and Legacy Backfill

### Goal

Introduce the Room schema required for domain-state materialization and dedicated action history, migrate database version 51 non-destructively, and backfill only those legacy semantics that can be established safely from canonical marker/area rows.

### Generic persistence model

- [x] Add exactly two generic Room tables:
  - `map_item_domain_state`;
  - `map_item_actions`.
- [x] Do not add domain-state or action-history columns to `map_markers` or `map_areas`.
- [x] Do not alter or delete `map_item_votes`.
- [x] Do not rewrite the durable `events` table.
- [x] Do not backfill action rows from legacy votes, contributions, confidence, removal requests or historical canonical UPD events.
- [x] Do not add production reducer or ingest integration in M2B1.

### Domain-state identity and ownership

- [x] Add generic `map_item_domain_state`.
- [x] Use `(gid, item_type, item_id)` as its composite primary identity.
- [x] Require `item_type` to use the stable persisted values:
  - `marker`;
  - `area`.
- [x] Persist the canonical owner DID separately from latest canonical event metadata.
- [x] Persist canonical ADD identity/order inputs when known:
  - canonical ADD event ID;
  - canonical ADD timestamp;
  - canonical ADD Lamport time;
  - canonical ADD actor DID.
- [x] Leave canonical ADD identity/order inputs null for migrated legacy rows when the accepted ADD cannot be established from the canonical row alone.
- [x] Do not infer ownership from dedicated action history.

### Domain category and origin

- [x] Persist normalized category when safely recognized:
  - `situation`;
  - `coordination`.
- [x] Persist a stable category origin that distinguishes:
  - explicit domain-v1 category;
  - legacy kind-derived category;
  - unresolved category.
- [x] Derive legacy category only through `MapItemKindCatalog`.
- [x] Respect marker/area support from `MapItemKindCatalog`.
- [x] Leave unknown or unsupported legacy kinds unresolved.
- [x] Do not duplicate kind/category mapping rules in migration SQL.
- [x] Persist domain version only when explicitly established.
- [x] Persist a stable state origin that distinguishes explicit domain-v1 state from legacy-row backfill.

### Creator information source

- [x] Persist creator information source only when explicitly supplied by an accepted domain-aware canonical ADD.
- [x] Keep migrated legacy creator information source null/unspecified.
- [x] Do not map legacy confidence to creator information source.
- [x] Do not map legacy Reported to `REPORTED_BY_OTHERS`.
- [x] Do not map legacy Observed to `FIRSTHAND`.
- [x] Do not claim explicit source semantics from legacy marker/area state.

### Situation lifecycle

- [x] Persist nullable Situation lifecycle.
- [x] Keep Coordination lifecycle absent.
- [x] Represent active recognized legacy Situation rows using compatibility lifecycle `active` only when the canonical row is currently active.
- [x] Mark the lifecycle/state origin as legacy-derived for such rows.
- [x] Do not map deleted or tombstoned legacy rows to `no_longer_active`.
- [x] Leave lifecycle unresolved/null when safe lifecycle semantics cannot be established.
- [x] Do not infer lifecycle from legacy removal-request or cancellation state.

### Independent temporal windows

- [x] Persist independent:
  - `expires_at`;
  - `owner_edit_until`;
  - `interaction_until`.
- [x] Persist a stable temporal origin that distinguishes:
  - explicit domain-v1 windows;
  - legacy TTL-derived windows;
  - legacy no-expiry windows;
  - unresolved legacy temporal state.
- [x] Centralize legacy window derivation outside UI.
- [x] Do not use TTL as an implicit edit lock after migration.
- [x] For valid finite legacy expiry, derive owner edit cutoff from:
  - the existing 50% TTL rule;
  - minimum 10 minutes;
  - maximum 6 hours.
- [x] For valid finite legacy expiry, set interaction cutoff consistently with expiry.
- [x] For legacy no-expiry rows, keep:
  - `expires_at = null`;
  - `owner_edit_until = null`;
  - `interaction_until = null`.
- [x] Do not fail the whole migration because of malformed legacy temporal data.
- [x] Preserve malformed canonical marker/area rows unchanged.
- [x] Mark malformed or unsafe temporal derivation as unresolved in domain state.
- [x] Validate newly persisted finite timestamps as positive.
- [x] Treat expiry, owner-edit and interaction cutoffs as exclusive boundaries.
- [x] Reject finite interaction cutoff without finite expiry at the persistence boundary.
- [x] Reject interaction cutoff before expiry.
- [x] Allow owner-edit cutoff before or after expiry while preserving expiry precedence.

### Domain-state aggregate and ordering fields

- [x] Persist lifecycle-aware confirmation actor count.
- [x] Persist acknowledgement actor count.
- [x] Initialize migrated legacy aggregate counts to zero.
- [x] Do not convert legacy votes or contributions into either count.
- [x] Persist complete latest confirmation order/identity inputs:
  - event ID;
  - action timestamp;
  - Lamport time;
  - actor DID.
- [x] Persist complete latest Situation update order/identity inputs.
- [x] Persist complete latest lifecycle status order/identity inputs.
- [x] Persist complete latest material-change order/identity inputs.
- [x] Preserve conflict-relevant ordering inputs without inventing a conflict rule.
- [x] Keep all latest-action pointers null when no applied dedicated action exists.

### Action-history identity and immutable content

- [x] Add generic `map_item_actions`.
- [x] Use `(gid, action_id)` as its composite primary identity.
- [x] Add uniqueness for `(gid, event_id)`.
- [x] Persist:
  - group ID;
  - action ID;
  - event ID;
  - original dedicated event type;
  - map-domain version;
  - target item ID;
  - target item type;
  - action type;
  - actor DID;
  - action timestamp;
  - Lamport time;
  - optional information source;
  - optional action note;
  - optional Situation update disposition;
  - optional target lifecycle;
  - event fingerprint;
  - materialization state;
  - stable rejection reason.
- [x] Require persisted action timestamp to be positive.
- [x] Require persisted Lamport time to be non-negative.
- [x] Enforce `action_id == event_id` before persistence.
- [x] Store the exact wire ordering inputs from `EventCore`.
- [x] Do not store arrival order or an auto-generated row ID as semantic ordering input.
- [x] Treat dedicated action semantic content as immutable after insertion.

### Duplicate and fingerprint contract

- [x] Use a deterministic cryptographic fingerprint for the canonical serialized event.
- [x] Use SHA-256 or an equivalently stable digest.
- [x] Do not use Kotlin or Java object hash codes.
- [x] Insert action rows with ignore-on-conflict semantics.
- [x] Do not use `REPLACE` or unrestricted upsert for action history.
- [x] After an ignored duplicate insert, allow the caller to compare the stored fingerprint.
- [x] Treat matching identity and matching fingerprint as exact duplicate delivery.
- [x] Treat matching identity and different fingerprint as conflicting duplicate input.
- [x] Never overwrite the first persisted semantic row with a later duplicate.

### Materialization state storage

- [x] Support stable materialization values:
  - `applied`;
  - `pending_target`;
  - `rejected`.
- [x] Keep rejection reason null for applied and pending actions.
- [x] Require a stable rejection reason when an action becomes rejected.
- [x] Store stable machine-readable rejection values rather than localized UI text.
- [x] Allow only the following future state transitions:
  - `pending_target → applied`;
  - `pending_target → rejected`.
- [x] Do not allow applied or rejected rows to return to pending.
- [x] Do not implement production action classification or transitions in M2B1.

### Foreign-key strategy

- [x] Do not add a foreign key from `map_item_actions` to `map_markers`.
- [x] Do not add a foreign key from `map_item_actions` to `map_areas`.
- [x] Do not add a foreign key from `map_item_actions` to `map_item_domain_state`.
- [x] Preserve action-before-target storage.
- [x] Preserve action history after canonical target deletion or tombstoning.
- [x] Keep target existence and target-type validation as M2C transactional semantics.

### Database migration

- [x] Increment Room database version from 51 to 52.
- [x] Add explicit `MIGRATION_51_52`.
- [x] Register the migration in the existing migration chain.
- [x] Create both new tables without altering existing tables.
- [x] Backfill domain state from current canonical marker and area rows.
- [x] Use deterministic Kotlin migration logic where `MapItemKindCatalog` or temporal derivation is required.
- [x] Stream marker and area rows rather than loading the whole database into memory.
- [x] Use prepared insertion statements for backfill.
- [x] Make backfill retry-safe through deterministic derivation and ignore-on-conflict insertion.
- [x] Create large secondary indexes after bulk backfill where practical.
- [x] Keep migration complexity proportional to marker and area row counts.
- [x] Do not replay the durable event log during migration.
- [x] Export Room schema `52.json`.

### Legacy preservation

- [x] Preserve all existing:
  - durable events;
  - marker rows;
  - area rows;
  - confidence values 0/1/2;
  - owner confidence;
  - votes and contribution notes;
  - removal requests and cancellations;
  - deleted/tombstone-related state;
  - canonical field clocks;
  - existing UI query behavior.
- [x] Do not rewrite historical events.
- [x] Do not migrate votes to confirmations.
- [x] Do not migrate contributions to acknowledgements or notes.
- [x] Do not migrate removal requests to `no_longer_active`.
- [x] Do not reinterpret legacy confidence as domain source.
- [x] Do not change marker/area canonical row values during backfill.
- [x] Keep `map_item_actions` empty immediately after migration from schema 51.

### M2B1 migration tests

- [x] Add Room migration-test infrastructure if it is not already present.
- [x] Add migration tests from schema 51 to 52.
- [x] Verify all existing tables remain readable after migration.
- [x] Verify marker and area rows preserve their legacy values.
- [x] Verify confidence, votes, contribution notes and removal state are unchanged.
- [x] Verify recognized legacy category backfill.
- [x] Verify unresolved legacy kind behavior.
- [x] Verify active legacy Situation lifecycle compatibility backfill.
- [x] Verify deleted legacy Situation does not become `no_longer_active`.
- [x] Verify legacy source remains null.
- [x] Verify finite temporal-window derivation.
- [x] Verify no-expiry temporal-window preservation.
- [x] Verify malformed temporal rows do not abort migration.
- [x] Verify malformed canonical rows remain unchanged.
- [x] Verify migrated aggregate counts are zero.
- [x] Verify no legacy action rows are invented.
- [x] Verify exported schema and Room-generated schema match.

### M2B1 non-goals

- [x] Do not implement production action reducer behavior.
- [x] Do not modify `EventIngestor`.
- [x] Do not enable action generation.
- [x] Do not update domain aggregates from live events.
- [x] Do not transition pending actions.
- [x] Do not change map rendering queries.
- [x] Do not modify BLE, batching or relay behavior.
- [x] Do not remove or redesign legacy marker/area persistence.

### M2B1 acceptance

- [x] Room database version 52 opens successfully from schema 51.
- [x] Migration is non-destructive and retry-safe.
- [x] Existing marker/area UI queries compile and behave as before.
- [x] New tables match exported Room schema 52.
- [x] Legacy rows remain semantically readable.
- [x] Domain-state backfill does not claim unsupported domain semantics.
- [x] Action history remains empty after legacy migration.
- [x] No production reducer, ingest or UI behavior has been enabled.
- [x] M2B1 is reviewed and accepted before starting M2B2.

---

## Phase M2B2A — Persistence Contracts and DAO Mutation Foundation

### Persisted-value and validation contracts

- [x] Define explicit stable database mappings for item type, category, category origin, state origin, temporal origin, lifecycle, action type, update disposition, information source and materialization state.
- [x] Map typed values in both directions without `enum.name`, `Enum.valueOf` or `enumValueOf`.
- [x] Return typed missing-required, unknown-value and invalid-record-shape failures without throwing during normal parsing.
- [x] Preserve nullable and unresolved semantics without converting unknown values to null.
- [x] Validate nonblank persistence identities and require `action_id == event_id`.
- [x] Validate positive finite timestamps, nonnegative Lamport values and nonnegative aggregate counts.
- [x] Validate finite temporal-window invariants without enforcing permissions.
- [x] Require canonical ADD and all latest-order pointers to be complete tuples or all null.
- [x] Require every public DAO write path to validate before SQLite and return a typed write result.
- [x] Reject unsupported action/domain versions and validate origin/version consistency.
- [x] Share machine-readable rejection-reason validation between record and transition boundaries.
- [x] Require explicit domain-v1 state to carry explicit, non-null resolved category.

### Deterministic action order

- [x] Define one reusable action order key using timestamp, Lamport, actor DID and event ID.
- [x] Define ascending comparison over the complete tuple.
- [x] Define descending comparison by reversing the complete tuple.
- [x] Verify every comparator tie level, equality and both directions with focused unit tests.

### Domain-state DAO mutation foundation

- [x] Add a dedicated generic domain-state DAO separate from `MapDao`.
- [x] Require group, item type and item ID for single lookup.
- [x] Constrain batch lookup by group and one item type.
- [x] Use insert-ignore for initial domain-state creation.
- [x] Expose only targeted atomic lifecycle, latest-pointer and aggregate-count mutations.
- [x] Use typed item type, lifecycle and complete order-key inputs for public targeted mutations.
- [x] Require lifecycle and lifecycle order together for lifecycle mutation; no public clear/reset operation is exposed.
- [x] Preserve immutable identity, ownership, category, origin, temporal and canonical ADD fields.
- [x] Return affected-row counts for targeted mutations.

### Action-history DAO mutation foundation

- [x] Add a dedicated generic action-history DAO.
- [x] Use insert-ignore and expose identity lookup by action ID and event ID.
- [x] Keep immutable semantic content and fingerprint visible to duplicate-policy callers.
- [x] Permit only guarded `pending_target` to `applied` or `rejected` transitions.
- [x] Clear rejection reason on applied and require a stable nonblank machine-readable reason on rejected.
- [x] Return affected-row counts so a future transaction owner can identify the transition winner.
- [x] Expose both DAOs from `AppDatabase` without connecting production flows.

### M2B2A tests and verification

- [x] Add focused unit tests for mappings, parsing failures, record shape, temporal invariants and total ordering.
- [x] Add Room instrumentation coverage for composite identity, item-type batch isolation, insert-ignore immutability, targeted mutations, fingerprint visibility and guarded transitions.
- [x] Add regression coverage for existing marker and area DAO behavior.
- [x] Verify focused unit tests pass.
- [x] Verify Room instrumentation test sources compile.
- [x] Verify Room instrumentation tests pass on a connected device or emulator.
- [x] Complete full M2B2A module verification and external review.

### M2B2A non-goals

- [x] Do not add latest, history, aggregate, pending-scan or pagination queries.
- [x] Do not implement reducer, ingest, replay, materialization or aggregate orchestration.
- [x] Do not connect UI, BLE, batching or relay behavior.
- [x] Keep Room schema version 52 and do not add a migration.

---

## Phase M2B2B — Deterministic Queries and Read-Model Projection

### Goal

Build deterministic pending, latest, aggregate and keyset-history queries over the accepted M2B2A persistence contracts, then expose safe domain read models and transaction-ready seams for M2C and M3.

M2B2B must reuse the single total-order contract accepted in M2B2A and must not introduce production reducer, replay, materialization or UI behavior.

### Action-history index verification

- [x] Retain the M2B1 unique index for `(gid, event_id)`.
- [x] Verify the M2B1 group/item/materialization/order index supports:
  - latest applied action;
  - applied action history;
  - pending actions for one target;
  - keyset pagination.
- [x] Verify the M2B1 group/item/materialization/action/actor index supports:
  - confirmation actor counts;
  - acknowledgement actor counts.
- [x] Verify the M2B1 pending-target-first index supports bounded deterministic pending scans.
- [x] Verify Room index declarations match exported schema 52.
- [x] Use query-plan tests to confirm normal item history and pending-target access do not require a full action-table scan.
- [x] Do not add a new index or bump the Room schema without measured query-plan evidence.
- [x] If an accepted M2B1 index is proven insufficient, document the evidence before proposing a schema change.

### Pending-target queries

- [x] Add deterministic pending-action lookup by:
  - group ID;
  - item type;
  - item ID.
- [x] Require item type in pending-target identity.
- [x] Order target-specific pending results by the accepted total comparator ascending.
- [x] Exclude applied actions.
- [x] Exclude rejected actions.
- [x] Add a bounded group-scoped pending-target scan.
- [x] Require the bounded pending scan to use:
  - group ID;
  - complete keyset cursor when present;
  - explicit positive limit.
- [x] Order bounded pending scans by the accepted total comparator ascending.
- [x] Do not add a global cross-group recovery scan in M2B2B.
- [x] Do not automatically transition, replay, reject or prune pending rows when queried.
- [x] Do not implement orphan retention or pruning policy.

### Latest-action queries

- [x] Add latest applied action query by:
  - group ID;
  - item type;
  - item ID.
- [x] Add latest applied action-of-type query.
- [x] Support latest applied confirmation.
- [x] Support latest applied Situation update.
- [x] Support latest applied lifecycle-status action.
- [x] Add latest applied material-change query or the minimum deterministic query needed to derive it.
- [x] Define material Situation change for this query contract as:
  - applied `update_status`; or
  - applied `add_update` with `situation_changed` disposition.
- [x] Do not treat `still_active` as a material change.
- [x] Use the accepted total comparator descending for every latest query.
- [x] Reverse the complete comparator tuple for descending order.
- [x] Exclude pending actions.
- [x] Exclude rejected actions.
- [x] Return null when no matching applied action exists.
- [x] Do not derive latest semantics from Room row ID or insertion order.

### Valid aggregate queries

- [x] Add distinct applied confirmation-actor count.
- [x] Add distinct applied acknowledgement-actor count.
- [x] Count repeated actions by the same actor once.
- [x] Exclude pending actions from all valid aggregates.
- [x] Exclude rejected actions from all valid aggregates.
- [x] Keep acknowledgements independent from confirmation and verification counts.
- [x] Keep legacy votes and contributions outside all new-action aggregates.
- [x] Add confirmation count without a material-change boundary.
- [x] Add confirmation count after a supplied latest material-change order boundary.
- [x] Compare confirmation actions against the complete boundary tuple:
  - action timestamp;
  - Lamport time;
  - actor DID;
  - event ID.
- [x] Do not compare a material boundary using timestamp alone.
- [x] Count only confirmations ordered strictly after the boundary.
- [x] Do not apply the material-change boundary to acknowledgement counts.

### Paginated applied-action history

- [x] Add M3-ready paginated applied-action history by:
  - group ID;
  - item type;
  - item ID.
- [x] Order normal history newest first.
- [x] Exclude pending rows from normal history.
- [x] Exclude rejected rows from normal history.
- [x] Define an explicit history cursor containing:
  - action timestamp;
  - Lamport time;
  - actor DID;
  - event ID.
- [x] Use keyset pagination based on the complete comparator tuple.
- [x] Reverse the complete tuple for newest-first history.
- [x] Use the last returned row as the next-page cursor.
- [x] Do not use `OFFSET` as the primary pagination contract.
- [x] Keep page boundaries stable when:
  - timestamps are equal;
  - timestamp and Lamport are equal;
  - timestamp, Lamport and actor DID are equal.
- [x] Require repository or query-boundary validation for positive page limits.

### Batch lookup strategy

- [x] Add a domain item identity model containing:
  - item type;
  - item ID.
- [x] Allow one batch request to contain marker and area identities.
- [x] Deduplicate requested identities before querying.
- [x] Partition requested identities by item type.
- [x] Query domain state at most once per item type per chunk.
- [x] Keep item type in every DAO batch query.
- [x] Chunk large item-ID collections to remain within Room/SQLite bind limits.
- [x] Return found, missing and invalid persisted records as distinguishable outcomes.
- [x] Do not issue one Room query per visible map item.
- [x] Do not require normal map rendering to replay the event log.

### Domain read-model projection

- [x] Add an M3-facing domain read model exposing:
  - group ID;
  - item type;
  - item ID;
  - normalized nullable category;
  - category origin;
  - nullable creator information source;
  - nullable Situation lifecycle;
  - independent temporal windows;
  - temporal origin;
  - confirmation actor count;
  - acknowledgement actor count.
- [x] Keep Room entities internal to the data layer.
- [x] Do not expose Room entities through repository interfaces.
- [x] Do not expose reducer-only ordering metadata through the M3-facing read model.
- [x] Do not expose canonical ownership metadata through the M3-facing read model unless a later reviewed UI contract requires it.
- [x] Preserve unresolved category, lifecycle or temporal state without guessing semantics.
- [x] Fail safely when a required persisted value is unknown.
- [x] Ensure one invalid row does not invalidate valid sibling rows in a batch result.
- [x] Do not modify M3 presentation vocabulary.

### Internal M2C materialization snapshot

- [x] Add a separate internal persistence projection for future M2C use.
- [x] Keep the internal snapshot separate from the M3-facing domain read model.
- [x] Allow the internal snapshot to expose:
  - owner DID;
  - domain version;
  - state origin;
  - canonical ADD identity and order;
  - latest confirmation order;
  - latest Situation update order;
  - latest lifecycle order;
  - latest material-change order;
  - current aggregate counts.
- [x] Represent each optional order pointer as a complete order key or null.
- [x] Reject partially populated order pointers during mapping.
- [x] Do not expose the internal snapshot to UI code.
- [x] Do not perform semantic enforcement through the mapper.

### Mapper contract

- [x] Add a domain-state entity mapper.
- [x] Add an action entity mapper or query projection mapper where needed.
- [x] Use only the strict M2B2A persistence parsers.
- [x] Do not duplicate persisted-value parsing inside repositories.
- [x] Validate nonnegative counts.
- [x] Validate record-shape completeness.
- [x] Preserve valid nullable semantics.
- [x] Return typed parse or mapping failures.
- [x] Do not silently drop unknown required semantic values.
- [x] Do not infer category, source, lifecycle, origin or materialization state from unrelated fields.

### Repository seam

- [x] Add a small read-only domain-state repository seam.
- [x] Support single domain-state lookup.
- [x] Support batch domain-state lookup.
- [x] Return domain projections rather than Room entities.
- [x] Distinguish:
  - found state;
  - missing state;
  - invalid persisted record.
- [x] Add a separate action-query repository or query seam for:
  - latest applied action;
  - latest typed action;
  - pending actions;
  - aggregate counts;
  - paginated applied history.
- [x] Do not combine domain-state and action-history responsibilities into one unrestricted repository.
- [x] Do not connect the repository to `GroupMapActivity` in M2B2B.

### Transaction-ready M2C seam

- [x] Expose the minimum persistence dependencies needed for a future Room transaction containing:
  - durable event insertion;
  - immutable action insertion;
  - action lookup for duplicate comparison;
  - pending-action lookup;
  - guarded materialization transition;
  - targeted aggregate or domain-state update.
- [x] Keep transaction orchestration outside DAO implementations.
- [x] Preserve `AppDatabase.runInTransaction` or the accepted database transaction mechanism as the future orchestration boundary.
- [x] Ensure future M2C can observe insert and transition affected-row results.
- [x] Ensure future M2C can update dependent aggregates only after owning the successful transition.
- [x] Do not implement the transaction workflow in M2B2B.
- [x] Do not add local or remote event processing.
- [x] Do not modify current reducer or ingest routing.
- [x] Do not update domain state from production events.

### M2B2B tests

- [x] Verify target-specific pending recovery order.
- [x] Verify bounded group-scoped pending scan order.
- [x] Verify pending scans exclude applied and rejected rows.
- [x] Verify latest applied action total order.
- [x] Verify latest typed action total order.
- [x] Verify latest material-change selection.
- [x] Verify `still_active` does not become a material-change boundary.
- [x] Verify distinct confirmation actors.
- [x] Verify repeated confirmation by one actor counts once.
- [x] Verify distinct acknowledgement actors.
- [x] Verify acknowledgements remain independent from confirmation counts.
- [x] Verify applied, pending and rejected mixtures.
- [x] Verify confirmation boundary comparison uses the complete tuple.
- [x] Verify confirmations at or before the boundary are excluded.
- [x] Verify keyset history pagination across every comparator tie level.
- [x] Verify no duplicate or skipped rows across page boundaries.
- [x] Verify batch marker lookup.
- [x] Verify batch area lookup.
- [x] Verify mixed marker/area batch lookup uses partitioned queries.
- [x] Verify missing and invalid rows remain distinguishable.
- [x] Verify unknown persisted values fail safely.
- [x] Verify Room entities are not exposed through repository contracts.
- [x] Verify critical query plans use accepted schema v52 indexes.
- [x] Verify existing marker/area DAO and UI query behavior remains unchanged.

### M2B2B non-goals

- [x] Do not enable action generation in production UI.
- [x] Do not implement production action reducer behavior.
- [x] Do not modify `EventIngestor`.
- [x] Do not enforce canonical ownership.
- [x] Do not enforce membership.
- [x] Do not enforce category/action compatibility.
- [x] Do not enforce lifecycle or temporal permissions.
- [x] Do not replay pending actions.
- [x] Do not transition pending actions from query code.
- [x] Do not update verification or acknowledgement aggregates from live events.
- [x] Do not materialize production actions.
- [x] Do not redesign normal map rendering.
- [x] Do not implement Community Verification presentation.
- [x] Do not change BLE, batching or relay behavior.

### M2B2B acceptance

- [x] Pending, latest, aggregate and history queries use the single M2B2A total-order contract.
- [x] Pending recovery is deterministic and ascending.
- [x] Latest and normal action history are deterministic and descending by the complete tuple.
- [x] Pending and rejected actions are excluded from valid aggregates and M3-visible history.
- [x] Confirmation counts respect the complete latest-material-change boundary.
- [x] Acknowledgements remain independent from verification counts.
- [x] Keyset pagination remains stable across comparator ties.
- [x] Domain state can be batch-queried without replaying the durable event log.
- [x] Room entities remain internal to the data layer.
- [x] M3-facing read models remain separate from M2C materialization snapshots.
- [x] Critical query plans use accepted schema v52 indexes.
- [x] Existing map UI queries still compile and behave as before.
- [x] No production reducer, ingest, replay, materialization, transport or UI behavior has been enabled.
- [x] M2B2B is reviewed and accepted before M2B2 is accepted.

---

### M2B2 overall non-goals

- [x] Do not enable action generation in production UI.
- [x] Do not implement production action reducer behavior.
- [x] Do not modify `EventIngestor`.
- [x] Do not enforce owner, membership, category, lifecycle or temporal permissions.
- [x] Do not replay pending actions.
- [x] Do not automatically transition pending rows.
- [x] Do not update verification or acknowledgement aggregates from live events.
- [x] Do not materialize production actions.
- [x] Do not redesign normal map rendering.
- [x] Do not implement Community Verification presentation.
- [x] Do not change BLE, batching or relay behavior.
- [x] Do not implement M2C orchestration early.

### M2B2 overall acceptance

- [x] M2B2A persisted-value, parser, comparator and DAO mutation contracts are accepted.
- [x] M2B2B deterministic query, cursor, mapper and read-model contracts are accepted.
- [x] DAO insert behavior preserves immutable append-only action semantics.
- [x] Duplicate action delivery cannot overwrite the first stored row.
- [x] Pending state transitions are guarded and monotonic.
- [x] All pending, latest, replay-ready and pagination contracts use one total comparator.
- [x] Pending and rejected actions are excluded from valid aggregates and M3-visible history.
- [x] Confirmation counts can apply the complete latest-material-change boundary.
- [x] Domain state can be batch-queried without replaying the durable event log.
- [x] Required indexes and unique constraints match exported Room schema 52.
- [x] Critical query plans avoid full-table scans for accepted normal access patterns.
- [x] Existing map UI queries still compile and behave as before.
- [x] No production reducer, ingest, action replay, aggregate update, transport or UI behavior has changed.
- [x] M2B2 is reviewed and accepted as the deterministic DAO/read-model baseline before reducer enforcement begins.

---

### M2B overall non-goals

- [x] Do not enable action generation in production UI.
- [x] Do not complete semantic action enforcement.
- [x] Do not delete `map_item_votes`.
- [x] Do not remove legacy fields from marker/area entities.
- [x] Do not redesign map rendering.
- [x] Do not implement Community Verification presentation.
- [x] Do not implement M2C, M2D or M3 behavior early.

### M2B overall acceptance

- [x] M2B1 migration is non-destructive.
- [x] New entities and DAOs match exported Room schema 52.
- [x] Legacy compatibility tests pass.
- [x] Required indexes and uniqueness constraints exist.
- [x] Append-only action content cannot be overwritten.
- [x] Pending materialization state can only transition deterministically.
- [x] Domain state can be queried without replaying the full event log.
- [x] Existing map UI queries compile and behave as before.
- [x] No production reducer, ingest, transport or UI semantic behavior changed.
- [x] M2B1 and M2B2 are both reviewed with no blocking findings.
- [x] M2B is accepted as the persistence/read-model baseline before Phase M2C begins.

---

## Phase M2C — Reducer and Ingest Enforcement

### Goal

Apply the accepted M1 and M2B2 contracts transactionally to canonical map events and dedicated interaction events through one shared local/remote processing path.

Phase M2C is divided into:

* Phase M2C1 — Transactional Processing Seam and Canonical Enforcement;
* Phase M2C2A — Dedicated Action Persistence and Semantic Classification;
* Phase M2C2B — Lifecycle, Latest Pointers and Aggregate Materialization;
* Phase M2C3 — Pending-Target Recovery and Deterministic Replay;
* Phase M2C4 — Shared Local/Remote Integration and Convergence Closure.

M2C must preserve:

* the accepted M2A wire contract;
* Room schema version 52;
* append-only durable event history;
* immutable dedicated action history;
* existing legacy contribution storage;
* existing BLE and relay transport contracts;
* current M1-compatible UI behavior until Phase M3.

M2C must not implement:

* new marker/area interaction UI;
* Community Verification presentation;
* BLE protocol changes;
* relay protocol changes;
* mixed-version transport closure assigned to M2D;
* M3 presentation behavior.

---

## Phase M2C1 — Transactional Processing Seam and Canonical Enforcement

### Goal

Introduce the shared DB-aware map-event processing boundary, split canonical reduction from dedicated action reduction, and enforce canonical ownership, membership and temporal rules transactionally without yet enabling production dedicated-action materialization.

### Processing boundary

* [x] Add one DB-aware map-event processing/acceptance seam.
* [x] Make the processing seam the transaction owner for map events.
* [x] Keep transaction orchestration outside DAO implementations.
* [x] Keep the processing seam independent from UI and transport code.
* [x] Return a typed processing result distinguishing:

  * accepted and inserted;
  * exact duplicate;
  * conflicting duplicate;
  * semantically rejected;
  * invalid persisted state;
  * internal persistence failure.
* [x] Use stable machine-readable rejection reasons.
* [x] Do not use localized UI strings as processing outcomes.
* [x] Do not enqueue or broadcast events from inside the Room transaction.
* [x] Preserve a successfully committed local event when later transport enqueue fails.

### Reducer architecture

* [x] Separate canonical map reduction from dedicated action reduction.
* [x] Keep a small reducer facade or dispatcher where needed to preserve integration seams.
* [x] Route canonical ADD/UPD/DEL only to the canonical reducer.
* [x] Route dedicated interaction events only to the action reducer.
* [x] Do not let the action reducer mutate:

  * marker coordinates;
  * marker radius;
  * area geometry;
  * canonical kind;
  * canonical label;
  * canonical owner note;
  * canonical layer;
  * canonical TTL or temporal windows;
  * legacy removal state.
* [x] Keep existing non-map reducers outside the new map processing seam unless required by direct integration.
* [x] Do not enable dedicated-action production routing in M2C1.

### Transaction boundary

* [x] Keep durable event insertion and canonical map reduction in one Room transaction.
* [x] Recheck membership inside the transaction.
* [x] Recheck target state, ownership and temporal permissions inside the transaction.
* [x] Ensure semantic rejection causes no canonical row or domain-state mutation.
* [x] Define whether rejected structurally valid map events remain outside the durable accepted event log.
* [x] Ensure transaction rollback leaves no partial event or canonical state.
* [x] Observe affected-row results from all guarded writes.
* [x] Do not use Room row ID or arrival order as semantic input.

### Canonical ADD enforcement

* [x] Establish canonical owner from the accepted ADD actor.
* [x] Establish canonical ADD identity and complete order tuple for new domain-aware items.
* [x] Materialize initial domain state for accepted domain-aware ADD.
* [x] Materialize initial Situation lifecycle as `active`.
* [x] Keep Coordination lifecycle null.
* [x] Persist creator information source only when accepted by the M2A/M1 contract.
* [x] Reject conflicting ADD for an already established item identity.
* [x] Treat exact duplicate ADD delivery idempotently.
* [x] Do not overwrite an existing marker with an area of the same target identity.
* [x] Do not overwrite an existing area with a marker of the same target identity.
* [x] Preserve accepted legacy ADD behavior where domain semantics remain unresolved.
* [x] Replay of pending dedicated actions remains an M2C3 responsibility.

### Canonical UPD enforcement

* [x] Permit canonical owner-field mutation only from the established owner.
* [x] Enforce owner edit cutoff outside UI.
* [x] Enforce expiry before evaluating owner edit capability.
* [x] Treat expiry and owner-edit boundaries as exclusive.
* [x] Ensure owner edit cutoff does not become an interaction cutoff.
* [x] Reject owner mutation after the applicable owner edit cutoff.
* [x] Reject owner mutation at or after expiry.
* [x] Ensure an owner canonical UPD cannot reactivate a resolved Situation.
* [x] Preserve category compatibility with the canonical kind catalog.
* [x] Do not allow canonical UPD to masquerade as a dedicated interaction action.

### Legacy non-owner UPD compatibility

* [x] Reject or ignore canonical owner fields from a non-owner legacy UPD.
* [x] Continue extracting only valid legacy contribution fields.
* [x] Keep legacy contribution storage in `map_item_votes`.
* [x] Prevent a legacy contribution from advancing the canonical item clock.
* [x] Prevent a non-owner legacy UPD from changing:

  * marker coordinates;
  * marker radius;
  * area geometry;
  * kind;
  * label;
  * owner note;
  * layer;
  * TTL or temporal windows;
  * domain lifecycle;
  * legacy removal fields.
* [x] Keep legacy removal state separate from new Situation lifecycle.
* [x] Do not reinterpret a legacy contribution as a dedicated action.
* [x] Preserve existing contribution behavior for recognized legacy traffic.

### Canonical DEL enforcement

* [x] Permit canonical DEL only from the established owner.
* [x] Reject non-owner canonical DEL.
* [x] Apply deterministic canonical ordering before deletion.
* [x] Preserve accepted tombstone behavior.
* [x] Do not delete dedicated action history when a target is deleted.
* [x] Do not convert canonical deletion into `no_longer_active`.
* [x] Prevent later canonical UPD from reviving a deleted target.

### Membership enforcement

* [x] Require current group membership for canonical member-generated map events.
* [x] Reject events from removed actors.
* [x] Reject local canonical participation after the local actor has been removed.
* [x] Keep membership-control event exceptions outside map-event semantics.
* [x] Recheck membership in the same transaction that accepts the map event.
* [x] Verify the transaction membership recheck prevents acceptance after removal; no separately injectable concurrent scheduler seam exists.

### Clock normalization decision

* [x] Resolve the discrepancy between adjusted ingest time and serialized original event time.
* [x] Define one accepted ordering-time contract for map processing.
* [x] Preserve original signed/wire event content unchanged.
* [x] Do not rewrite `EventCore.time.t` only for reducer use while persisting another ordering value.
* [x] Ensure stored event ordering fields and reducer ordering inputs represent the same accepted contract.
* [x] Include event ID as the final canonical ordering tie-break.
* [x] Do not change the accepted M2B2 action-order comparator.

### M2C1 tests

* [x] Verify accepted canonical ADD establishes owner and domain state atomically.
* [x] Verify conflicting ADD is rejected without partial writes.
* [x] Verify owner canonical UPD succeeds inside its temporal window.
* [x] Verify non-owner canonical fields cannot mutate marker state.
* [x] Verify non-owner canonical fields cannot mutate area state.
* [x] Verify valid legacy contribution extraction remains available.
* [x] Verify legacy contribution does not advance the canonical item clock.
* [x] Verify non-owner canonical DEL is rejected.
* [x] Verify owner DEL remains deterministic.
* [x] Verify expired owner mutation is rejected.
* [x] Verify owner edit cutoff is exclusive.
* [x] Verify removed-member canonical events are rejected transactionally.
* [x] Verify rollback leaves no partial event, canonical row or domain state.
* [x] Verify schema version remains 52.

### M2C1 non-goals

* [x] Do not connect the new processing seam to `EventIngestor` yet.
* [x] Do not connect the new processing seam to the local UI writer yet.
* [x] Do not materialize dedicated actions.
* [x] Do not replay pending actions.
* [x] Do not update action-derived aggregates.
* [x] Do not change BLE, relay or UI behavior.

### M2C1 acceptance

* [x] One transaction-ready map processing seam exists.
* [x] Canonical and dedicated-action reducer responsibilities are separated.
* [x] Canonical owner, membership and temporal enforcement is deterministic.
* [x] Legacy non-owner contribution compatibility remains available.
* [x] Canonical event and domain-state writes are atomic.
* [x] Ordering-time semantics are explicitly resolved.
* [x] No production local or remote integration has changed.
* [x] M2C1 is reviewed and accepted before dedicated-action materialization begins.

---

## Phase M2C2 — Dedicated Action Reduction and Materialization

### Goal

Implement transactional semantic validation and materialization for dedicated map interaction events whose canonical target and domain state already exist.

## Phase M2C2A — Dedicated Action Persistence and Semantic Classification

### Goal

Implement transactional parsing, semantic validation, duplicate handling and immutable persistence for dedicated map interaction events whose canonical target and domain state already exist.

M2C2A establishes accepted dedicated actions as deterministic append-only history.
It does not yet materialize lifecycle, latest-action pointers or aggregate counts.

### Dedicated action processing boundary

* [x] Enable dedicated action routing through the accepted M2C1 map processing seam.
* [x] Keep `RoomMapEventProcessor` as the sole Room transaction owner.
* [x] Keep canonical reduction and dedicated action reduction responsibility-separated.
* [x] Parse dedicated events only through the accepted M2A codec and wire contract.
* [x] Do not duplicate dedicated payload parsing inside reducers or repositories.
* [x] Pass target, membership and persisted-domain context explicitly to the action reducer.
* [x] Keep the action reducer independent from UI, BLE, relay and event-source metadata.

### Action identity and fingerprint

* [x] Require `action_id == event_id`.
* [x] Preserve exact wire ordering inputs:

  * event timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Compute a deterministic cryptographic fingerprint from canonical serialized event content.
* [x] Use SHA-256 or an equivalently stable digest.
* [x] Do not use Kotlin or Java object hash codes.
* [x] Ensure equivalent canonical serialized content produces the same fingerprint.
* [x] Ensure semantically different event content produces a different fingerprint.

### Immutable action persistence

* [x] Persist dedicated action semantics using insert-ignore.
* [x] Never use `REPLACE` or unrestricted upsert for action history.
* [x] Never overwrite the first persisted action row.
* [x] Persist accepted existing-target actions directly as `applied`.
* [x] Do not use `pending_target` for an existing target.
* [x] Preserve exact semantic payload and ordering values from the wire event.
* [x] Keep durable event dedupe and action dedupe independent but consistent.
* [x] Allow only the action-insertion winner to continue to later materialization.

### Duplicate classification

* [x] Treat matching action identity and matching fingerprint as exact duplicate delivery.
* [x] Treat matching action identity and different fingerprint as conflicting duplicate input.
* [x] Treat matching event identity and inconsistent action identity as invalid persisted state.
* [x] Ensure exact duplicate delivery does not insert or mutate action history.
* [x] Ensure a conflicting duplicate cannot alter the original action row.
* [x] Return typed outcomes for:

  * accepted and inserted;
  * exact duplicate;
  * conflicting duplicate;
  * semantically rejected;
  * invalid persisted state;
  * internal persistence failure.

### Target and category enforcement

* [x] Require target identity from `EventCore.ref.target`.
* [x] Require explicit `item_type`.
* [x] Verify `item_type` against the actual canonical target table.
* [x] Reject marker/area target-type mismatch.
* [x] Require the canonical target and domain state to already exist.
* [x] Load category only from persisted domain state.
* [x] Reject dedicated action semantics when category is unresolved.
* [x] Reject dedicated action semantics for unsupported legacy kinds.
* [x] Do not infer category from action type.
* [x] Leave action-before-target classification and recovery to M2C3.

### Membership and temporal enforcement

* [x] Require current group membership for every dedicated action.
* [x] Reject actions from removed actors.
* [x] Reject local actions after the local actor has been removed.
* [x] Recheck membership inside the same transaction that accepts the action.
* [x] Reject actions at or after exclusive expiry.
* [x] Reject actions at or after exclusive interaction cutoff.
* [x] Do not use owner-edit cutoff to block valid member interaction.
* [x] Reject action acceptance for deleted targets.
* [x] Preserve historical applied actions after later target deletion.

### Situation action semantic enforcement

* [x] Permit only:

  * Confirm;
  * Add update;
  * Update status.
* [x] Enforce Confirm information-source policy.
* [x] Enforce optional Confirm note policy.
* [x] Ensure area confirmation confirms the reported condition, not exact geometry.
* [x] Require nonblank Situation update content.
* [x] Require a recognized Situation update disposition.
* [x] Enforce optional Situation update information-source policy.
* [x] Permit user-created status target only as `no_longer_active`.
* [x] Reject lifecycle reactivation.
* [x] Do not automatically classify `situation_changed` as conflict.

### Coordination action semantic enforcement

* [x] Permit only:

  * Acknowledge;
  * Add note.
* [x] Keep acknowledgement unweighted.
* [x] Forbid information source on acknowledgement.
* [x] Forbid lifecycle and update disposition on acknowledgement.
* [x] Require nonblank Coordination note content.
* [x] Forbid information source, lifecycle and update disposition on Coordination note.
* [x] Never reinterpret an acknowledgement as Situation verification.

### Lifecycle-order classification

* [x] Use the accepted complete action-order tuple:

  * event timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Compare normal Situation actions against the latest accepted resolution order.
* [x] Accept a historically valid action ordered before resolution even if delivered later.
* [x] Reject a normal Situation action ordered after resolution.
* [x] Prevent a stale pre-resolution action from reopening lifecycle.
* [x] Allow repeated `no_longer_active` actions as immutable applied history.
* [x] Defer latest lifecycle-pointer selection and lifecycle projection to M2C2B.

### Transaction contract

* [x] Keep durable event insertion, immutable action insertion and semantic classification in one Room transaction.
* [x] Ensure semantic rejection leaves no durable event or action row.
* [x] Keep live existing-target semantic rejection outside accepted action history.
* [x] Reserve stored `rejected` action state primarily for later pending-action reclassification in M2C3.
* [x] Ensure rollback leaves no partial durable event or action row.
* [x] Observe affected-row results from action insertion.
* [x] Do not update lifecycle, latest-action pointers or aggregate counts in M2C2A.

### M2C2A tests

* [x] Verify Situation Confirm acceptance and immutable persistence.
* [x] Verify Situation Add update acceptance.
* [x] Verify Situation Update status acceptance.
* [x] Verify Coordination Acknowledge acceptance.
* [x] Verify Coordination Add note acceptance.
* [x] Verify wrong-category actions are rejected.
* [x] Verify wrong item type is rejected.
* [x] Verify unresolved legacy category is rejected safely.
* [x] Verify deleted-target actions are rejected.
* [x] Verify exact expiry boundary is rejected.
* [x] Verify exact interaction-cutoff boundary is rejected.
* [x] Verify owner-edit cutoff does not block member interaction.
* [x] Verify removed-member actions are rejected.
* [x] Verify normal action ordered before resolution is preserved as historical applied action.
* [x] Verify normal action ordered after resolution is rejected.
* [x] Verify repeated resolution actions are accepted as immutable history.
* [x] Verify exact duplicate action delivery is idempotent.
* [x] Verify conflicting duplicate fingerprint is rejected.
* [x] Verify action insertion cannot overwrite immutable semantics.
* [x] Verify transaction rollback leaves no partial durable event or action row.
* [x] Verify lifecycle, pointers and aggregates remain unchanged in M2C2A.

### M2C2A non-goals

* [x] Do not materialize lifecycle.
* [x] Do not update latest-action pointers.
* [x] Do not update confirmation or acknowledgement aggregates.
* [x] Do not replay action-before-target rows.
* [x] Do not scan or prune orphaned pending actions.
* [x] Do not connect production local or remote event paths.
* [x] Do not change BLE, relay or UI behavior.
* [x] Keep Room schema version 52.

### M2C2A acceptance

* [x] Existing-target dedicated actions are parsed and validated transactionally.
* [x] Category, membership, temporal and lifecycle-order policies are enforced outside UI.
* [x] Accepted actions are stored as immutable append-only history.
* [x] Exact and conflicting duplicates are distinguished deterministically.
* [x] Historically valid stale actions remain available without reopening lifecycle.
* [x] No lifecycle, pointer or aggregate materialization has been implemented early.
* [x] No production ingest or local writer integration has changed.
* [x] M2C2A is reviewed and accepted before M2C2B begins.

---

## Phase M2C2B — Lifecycle, Latest Pointers and Aggregate Materialization

### Goal

Materialize deterministic lifecycle, latest-action pointers and category-correct aggregates from accepted applied dedicated-action history.

The append-only action table is the source of truth. Domain-state projections must be derivable from applied action history and must converge regardless of event arrival order.

### Projection architecture

* [x] Keep `map_item_actions` as the source of truth for action-derived state.
* [x] Derive lifecycle, latest pointers and aggregate counts from applied action history.
* [x] Do not increment or decrement aggregate counts manually.
* [x] Do not use arrival order, Room row ID or insertion order as semantic input.
* [x] Reuse the accepted M2B2 complete action comparator.
* [x] Keep projection logic reusable by M2C3 pending replay.
* [x] Keep projection logic independent from local or remote event source.

### Atomic action projection mutation

* [x] Add one targeted atomic domain-state mutation for action-derived projection.
* [x] Allow the mutation to update only:

  * Situation lifecycle;
  * latest confirmation pointer;
  * latest Situation update pointer;
  * latest lifecycle pointer;
  * latest material-change pointer;
  * confirmation actor count;
  * acknowledgement actor count.
* [x] Preserve immutable identity, owner, category, origin, temporal and canonical ADD fields.
* [x] Require every latest-action pointer to be a complete tuple or null.
* [x] Validate nonnegative aggregate counts.
* [x] Return affected-row count.
* [x] Require exactly one target domain-state row to be updated.
* [x] Keep Room schema version 52.

### Deterministic lifecycle reduction

* [x] Materialize Situation lifecycle from applied lifecycle-status actions.
* [x] Use the accepted complete action-order tuple:

  * event timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Select the latest applied lifecycle-status action by the complete comparator.
* [x] Preserve lifecycle `active` when no applied resolution action exists.
* [x] Materialize `no_longer_active` when an applied resolution action exists.
* [x] Never materialize lifecycle reactivation.
* [x] Prevent an older-delivered status event from replacing a newer lifecycle pointer.
* [x] Preserve repeated resolution actions as history while selecting only the latest pointer.
* [x] Prevent stale pre-resolution actions from reopening the item.

### Latest-action pointers

* [x] Derive latest applied confirmation using the complete comparator.
* [x] Derive latest applied Situation update using the complete comparator.
* [x] Derive latest applied lifecycle-status action using the complete comparator.
* [x] Derive latest applied material Situation change using the complete comparator.
* [x] Define material Situation change as:

  * applied `update_status`; or
  * applied `add_update` with `situation_changed`.
* [x] Do not treat `still_active` as a material change.
* [x] Keep every latest pointer complete or null.
* [x] Preserve historical actions regardless of whether they become a latest pointer.

### Confirmation aggregation

* [x] Count distinct applied confirmation actors.
* [x] Count repeated confirmations by one actor once.
* [x] If no material-change boundary exists, count all applied confirmation actors.
* [x] If a material-change boundary exists, count only confirmations ordered strictly after it.
* [x] Compare confirmations against the complete material-change order tuple.
* [x] Invalidate current confirmation freshness after:

  * accepted `update_status`; or
  * accepted `add_update` with `situation_changed`.
* [x] Do not invalidate confirmation freshness after `still_active`.
* [x] Keep resolved Situation current verification from appearing confirmed.
* [x] Do not delete historical confirmations when freshness is invalidated.

### Acknowledgement aggregation

* [x] Count distinct applied acknowledgement actors.
* [x] Count repeated acknowledgements by one actor once.
* [x] Keep acknowledgement counts independent from confirmation counts.
* [x] Do not apply the Situation material-change boundary to acknowledgements.
* [x] Never include acknowledgements in Situation verification counts.
* [x] Keep legacy contributions outside acknowledgement aggregates.

### Materialization ownership

* [x] Materialize action-derived state only after winning immutable action insertion.
* [x] Ensure exact duplicate delivery does not increment or otherwise change aggregates.
* [x] Ensure conflicting duplicate input cannot change projections.
* [x] Recompute deterministic projection from applied history after an accepted insertion.
* [x] Keep durable event insert, action insert and projection update in one Room transaction.
* [x] Ensure projection failure rolls back the durable event and action insertion.
* [x] Observe affected-row results from the projection mutation.
* [x] Treat missing or multiply affected target state as persistence invariant failure.

### Arrival-order convergence

* [x] Ensure opposite delivery orders produce the same lifecycle.
* [x] Ensure opposite delivery orders produce the same latest pointers.
* [x] Ensure opposite delivery orders produce the same confirmation count.
* [x] Ensure opposite delivery orders produce the same acknowledgement count.
* [x] Ensure stale historical actions remain stored without replacing newer pointers.
* [x] Ensure action order, not delivery order, determines post-resolution rejection.
* [x] Ensure equal timestamps resolve using Lamport time, actor DID and event ID.

### M2C2B tests

* [x] Verify Situation Confirm materialization.
* [x] Verify repeated Confirm by one actor counts once.
* [x] Verify latest confirmation uses complete action order.
* [x] Verify Situation update latest-pointer ordering.
* [x] Verify `still_active` does not invalidate existing confirmation freshness.
* [x] Verify `situation_changed` establishes a material-change boundary.
* [x] Verify confirmation boundary comparison uses the complete tuple.
* [x] Verify confirmations at or before the boundary are excluded.
* [x] Verify confirmations after the boundary are counted.
* [x] Verify resolution invalidates current verification without deleting history.
* [x] Verify latest lifecycle pointer uses complete action order.
* [x] Verify older-delivered resolution cannot replace a newer lifecycle pointer.
* [x] Verify repeated resolution remains deterministic and replay-safe.
* [x] Verify lifecycle reactivation is impossible.
* [x] Verify Coordination Acknowledge materialization.
* [x] Verify repeated acknowledgement by one actor counts once.
* [x] Verify acknowledgements remain outside confirmation counts.
* [x] Verify Coordination Add note does not affect aggregates.
* [x] Verify stale historical actions do not replace latest pointers.
* [x] Verify opposite action arrival orders converge.
* [x] Verify exact duplicate delivery does not recompute incorrect counts.
* [x] Verify conflicting duplicate cannot alter projection.
* [x] Verify action-derived projection updates atomically.
* [x] Verify rollback leaves no partial durable event, action, lifecycle, pointer or aggregate state.
* [x] Verify schema version remains 52.

### M2C2B non-goals

* [x] Do not change action parsing or immutable action identity contracts accepted in M2C2A.
* [x] Do not replay action-before-target rows.
* [x] Do not implement pending-target transitions.
* [x] Do not scan or prune orphaned pending actions.
* [x] Do not connect production local or remote event paths.
* [x] Do not change BLE, relay or UI behavior.
* [x] Do not add a Room migration.

### M2C2B acceptance

* [x] Existing-target applied actions materialize lifecycle and aggregates transactionally.
* [x] Action history remains the source of truth for action-derived state.
* [x] Lifecycle and latest pointers use the accepted complete comparator.
* [x] Confirmation counts respect the complete latest-material-change boundary.
* [x] Acknowledgement counts remain independent from confirmation counts.
* [x] Duplicate delivery cannot change action history or materialized state.
* [x] Historical actions remain append-only.
* [x] Opposite action arrival orders converge to the same projection.
* [x] No production ingest or local writer integration has changed.
* [x] M2C2B is reviewed and accepted before overall M2C2 acceptance.


* [x] Do not replay action-before-target rows.
* [x] Do not transition pending actions.
* [x] Do not scan or prune orphaned pending actions.
* [x] Do not connect production local or remote event paths.
* [x] Do not change BLE, relay or UI behavior.
* [x] Do not change Room schema version 52.

### M2C2 overall acceptance

* [x] M2C2A action persistence and semantic-classification contracts are accepted.
* [x] M2C2B lifecycle, pointer and aggregate-materialization contracts are accepted.
* [x] Existing-target dedicated actions are validated and materialized transactionally.
* [x] Category, membership, lifecycle and temporal policies are enforced outside UI.
* [x] Dedicated action history is immutable and append-only.
* [x] Lifecycle and aggregate state use the accepted deterministic comparator.
* [x] Duplicate delivery cannot change action history or counts.
* [x] Historical stale actions remain available without overriding newer projection state.
* [x] Opposite delivery orders converge to the same lifecycle, pointers and aggregates.
* [x] No pending-target recovery has been implemented early.
* [x] No production ingest or local writer integration has changed.
* [x] M2C2 is reviewed and accepted before pending-target recovery begins.

---

## Phase M2C3 — Pending-Target Recovery and Deterministic Replay

### Goal

Support dedicated actions arriving before their canonical targets, persist them as immutable pending history, and deterministically recover them when the corresponding canonical target becomes available.

Phase M2C3 is divided into:

* Phase M2C3A — Pending Action Persistence and Duplicate Classification;
* Phase M2C3B — Target-Triggered Deterministic Replay;
* Phase M2C3C — Bounded Recovery and Convergence Closure.

M2C3 must preserve:

* Room schema version 52;
* immutable append-only dedicated action semantics;
* guarded monotonic materialization transitions;
* the accepted M2B2 complete action comparator;
* the accepted M2C2B history-derived projection architecture;
* the earliest-resolution irreversible lifecycle cutoff;
* `RoomMapEventProcessor` as the Room transaction owner.

M2C3 must not implement:

* `EventIngestor` production integration;
* local map writer integration;
* BLE or relay changes;
* UI behavior;
* an unbounded global pending scan;
* orphan pruning or retention policy without a separately accepted contract;
* full durable-log rebuild assigned to M2D.

---

## Phase M2C3A — Pending Action Persistence and Duplicate Classification

### Goal

Persist structurally valid dedicated actions whose canonical targets have not yet arrived, while preserving immutable action identity, deterministic duplicate handling and zero projection contribution.

### Missing-target classification

* [x] Distinguish among:

  * target exists with the requested item type;
  * target exists only with the opposite item type;
  * neither marker nor area target exists.
* [x] Continue routing an existing correctly typed target through the accepted M2C2 processing path.
* [x] Reject an opposite-type existing target using a stable target-type conflict reason.
* [x] Do not store an opposite-type target action as pending.
* [x] Classify an action as `pending_target` only when neither canonical target type exists.
* [x] Require group ID, item type and item ID for pending identity.
* [x] Keep category and target-dependent semantic enforcement deferred until target recovery.
* [x] Do not infer target category from the action type.

### Pending action persistence

* [x] Parse pending candidates only through the accepted M2A dedicated-action codec.
* [x] Require current membership before accepting a pending action.
* [x] Recheck membership inside the same Room transaction that persists the pending action.
* [x] Preserve exact wire ordering inputs:

  * event timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Compute the same canonical SHA-256 event fingerprint used by M2C2A.
* [x] Persist the durable event and immutable pending action in one Room transaction.
* [x] Persist pending actions using insert-ignore.
* [x] Persist:

  * `materialization_state = pending_target`;
  * `rejection_reason = null`.
* [x] Preserve all immutable semantic content while pending.
* [x] Do not update lifecycle, latest pointers or aggregate counts from pending actions.
* [x] Do not create placeholder canonical rows or placeholder domain-state rows.
* [x] Do not add foreign keys that prevent action-before-target persistence.
* [x] Keep Room schema version 52.

### Entity construction contract

* [x] Make the requested initial materialization state explicit at the action-entity construction boundary.
* [x] Support only explicitly requested initial states needed by live processing:

  * `applied`;
  * `pending_target`.
* [x] Do not infer the initial state implicitly from nullable target context.
* [x] Continue validating persisted action identity, timestamps, Lamport values and semantic fields through the accepted persistence contracts.
* [x] Keep semantic action content immutable after insertion.

### Pending duplicate classification

* [x] Treat matching action identity and matching fingerprint as an exact duplicate regardless of whether the stored row is pending or applied.
* [x] Treat matching action identity and different fingerprint as conflicting duplicate input.
* [x] Never overwrite the original pending row.
* [x] Ensure exact duplicate pending delivery does not create a second durable event or action row.
* [x] Ensure conflicting duplicate delivery does not alter:

  * semantic content;
  * fingerprint;
  * materialization state;
  * rejection reason;
  * domain projection.
* [x] Detect inconsistent event/action identity as invalid persisted state.
* [x] Keep durable-event duplicate policy and action duplicate policy independent but consistent.

### Processing results

* [x] Preserve the accepted public map-event processing result contract unless an extension is required for diagnostics.
* [x] Use an internal pending-persistence result for deterministic classification:

  * pending action inserted;
  * exact duplicate;
  * conflicting duplicate;
  * invalid persisted state.
* [x] Let unexpected Room/SQLite persistence exceptions propagate to `RoomMapEventProcessor`, which returns the accepted public `PersistenceFailure` result and rolls back the active transaction.
* [x] Do not expose Room entities through processing results.
* [x] Do not use localized UI text as a pending-processing result.

### M2C3A transaction contract

* [x] Keep `RoomMapEventProcessor` as the sole Room transaction owner.
* [x] Ensure rollback leaves no partial durable event or pending action row.
* [x] Do not open transactions inside DAOs, reducers or entity factories.
* [x] Observe affected-row results from durable-event and action insertion.
* [x] Allow only the action-insertion winner to report a newly accepted pending action.
* [x] Do not trigger target recovery in M2C3A.

### M2C3A tests

* [x] Verify action-before-marker storage as `pending_target`.
* [x] Verify action-before-area storage as `pending_target`.
* [x] Verify pending action persistence preserves complete wire ordering inputs.
* [x] Verify pending action persistence preserves immutable semantic content and fingerprint.
* [x] Verify pending actions do not affect lifecycle.
* [x] Verify pending actions do not affect latest pointers.
* [x] Verify pending actions do not affect confirmation counts.
* [x] Verify pending actions do not affect acknowledgement counts.
* [x] Verify opposite-type existing target is rejected rather than stored pending.
* [x] Verify exact duplicate pending delivery is idempotent.
* [x] Verify conflicting duplicate pending delivery cannot overwrite the first row.
* [x] Verify removed-member pending actions are rejected transactionally.
* [x] Verify rollback leaves no partial durable event or pending action row.
* [x] Verify schema version remains 52.

### M2C3A non-goals

* [x] Do not replay pending actions.
* [x] Do not transition pending rows.
* [x] Do not refresh projections.
* [x] Do not add bounded recovery.
* [x] Do not implement orphan maintenance.
* [x] Do not connect production local or remote paths.
* [x] Do not change BLE, relay or UI behavior.

### M2C3A acceptance

* [x] Missing-target dedicated actions are persisted as immutable pending history.
* [x] Opposite-type target conflicts are not misclassified as pending.
* [x] Pending duplicate handling is deterministic and non-destructive.
* [x] Pending actions contribute nothing to materialized domain state.
* [x] Durable event and pending action persistence is atomic.
* [x] Room schema remains version 52.
* [x] M2C3A is reviewed and accepted before target-triggered replay begins.

---

## Phase M2C3B — Target-Triggered Deterministic Replay

### Goal

Recover all pending actions for one exact target when its canonical ADD becomes accepted, using the same semantic contracts and history-derived projection architecture as live existing-target processing.

### Replay coordinator architecture

* [x] Add one target-specific pending replay coordinator.
* [x] Keep the replay coordinator independent from UI, BLE, relay and event-source metadata.
* [x] Require the caller to own the Room transaction.
* [x] Do not open a nested or independent transaction inside the replay coordinator.
* [x] Require exact target identity:

  * group ID;
  * item type;
  * item ID.
* [x] Load pending rows only for that exact target.
* [x] Return a typed replay result containing:

  * examined count;
  * applied-transition count;
  * rejected-transition count;
  * already-completed count;
  * projection-refresh outcome.

### Canonical ADD replay trigger

* [x] Trigger target-specific pending replay after an accepted canonical ADD.
* [x] Trigger replay only after both:

  * the canonical marker or area row exists;
  * the target domain-state row exists.
* [x] Keep durable ADD insertion, canonical reduction, domain-state creation, pending transitions and projection refresh in one Room transaction.
* [x] Ensure an accepted canonical ADD cannot commit with replayable pending rows for that exact target left unclassified.
* [x] Do not trigger replay for conflicting or duplicate ADD outcomes that did not establish a new target.
* [x] Preserve canonical ADD duplicate behavior accepted in M2C1.

### Existing-target backlog trigger

* [x] Before applying a newly inserted existing-target dedicated action, recover pending backlog for the same target.
* [x] Ensure older pending actions are classified before a later live action is materialized.
* [x] Keep the pending backlog replay and the new live action inside the same Room transaction.
* [x] Do not replay unrelated targets.
* [x] Ensure an empty pending backlog is a deterministic no-op.

### Shared semantic evaluation

* [x] Extract or expose the minimum target-dependent semantic evaluator shared by:

  * live existing-target actions;
  * pending replay.
* [x] Reuse the accepted category/action compatibility policy.
* [x] Reuse the accepted expiry and interaction-window policy.
* [x] Reuse the accepted deleted-target policy.
* [x] Reuse the accepted lifecycle-order policy.
* [x] Do not duplicate dedicated payload parsing.
* [x] Do not duplicate category mappings.
* [x] Do not re-evaluate current membership during replay after membership was accepted when the pending event was initially persisted.
* [x] Evaluate target-dependent temporal semantics using the immutable action event timestamp.
* [x] Preserve action semantic behavior independently of local or remote source.

### Replay ordering

* [x] Load target-specific pending rows in complete action-order ascending order:

  * action timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Include event ID as the final tie-break.
* [x] Do not use insertion order.
* [x] Do not use Room row ID.
* [x] Do not use transport arrival order.
* [x] Do not use replay invocation time.
* [x] Use the same comparator contract as M2B2, M2C2 and deterministic rebuild tests.

### Irreversible lifecycle cutoff

* [x] Use the earliest accepted resolution action as the irreversible Situation lifecycle cutoff.
* [x] Keep the replay cutoff contract aligned with the accepted M2C2B projection derivation.
* [x] Accept normal Situation actions strictly ordered before the earliest resolution cutoff when otherwise valid.
* [x] Reject normal Situation actions ordered at or after the earliest resolution cutoff.
* [x] Permit repeated `no_longer_active` actions as immutable applied history.
* [x] Prevent pending replay from reopening a resolved Situation.
* [x] Do not use the latest resolution as the replay cutoff.
* [x] Keep Coordination action behavior independent from Situation lifecycle reduction.

### Guarded pending transitions

* [x] Permit only:

  * `pending_target → applied`;
  * `pending_target → rejected`.
* [x] Keep applied and rejected rows terminal.
* [x] Apply a stable machine-readable reason when transitioning to rejected.
* [x] Reuse existing semantic rejection reasons where applicable.
* [x] Transition semantically valid pending actions to applied.
* [x] Transition semantically incompatible pending actions to rejected.
* [x] Update dependent projection only after winning at least one guarded transition.
* [x] Ensure a transition loser cannot double-apply projection effects.
* [x] When a guarded transition affects zero rows:

  * reload the row;
  * treat a terminal row as already completed;
  * treat a still-pending row as a persistence invariant failure.

### Stable replay rejection reasons

* [x] Reuse stable reasons for:

  * target-type conflict;
  * target deleted;
  * unresolved target category;
  * action not allowed for category;
  * target expired;
  * interaction window closed;
  * target resolved;
  * lifecycle reactivation.
* [x] Keep rejection reason null for applied rows.
* [x] Do not use localized UI text.
* [x] Do not introduce orphan-retention rejection reasons in M2C3B.

### Projection refresh

* [x] Keep immutable applied action history as the source of truth.
* [x] After replay transitions complete, derive the full action projection from all applied target history.
* [x] Reuse the accepted M2C2B projection deriver.
* [x] Do not patch lifecycle incrementally.
* [x] Do not increment or decrement aggregate counts manually.
* [x] Do not patch latest pointers action by action.
* [x] Refresh the projection once after the target replay batch.
* [x] Require exactly one target domain-state row to be updated.
* [x] Treat missing or multiply affected domain-state rows as persistence invariant failure.
* [x] Ensure pending and rejected rows remain excluded from the derived projection.

### Replay rollback semantics

* [x] Roll back canonical ADD and its pending recovery together for:

  * invalid persisted pending shape;
  * action/event identity contradiction;
  * unsupported persisted semantic values;
  * projection derivation failure;
  * invalid guarded-write state;
  * canonical/domain-state inconsistency;
  * projection update affecting anything other than exactly one row.
* [x] Do not roll back the batch merely because one pending action is semantically incompatible.
* [x] Transition a semantically incompatible pending row to rejected and continue with valid sibling rows.
* [x] Ensure rollback restores all affected pending rows to their previous materialization state.
* [x] Ensure rollback leaves no partial canonical ADD, action transition or projection update.

### Replay idempotency

* [x] Replaying a target with no pending actions is a no-op.
* [x] Replaying an already completed target does not alter terminal action rows.
* [x] Replaying an already completed target produces the same projection.
* [x] Exact duplicate delivery after pending completion remains idempotent.
* [x] Conflicting duplicate delivery after pending completion cannot alter history or projection.
* [x] Use monotonic row state and absence of remaining target pending rows as replay completion.
* [x] Do not add a replay-completion table or schema field.

### M2C3B tests

* [x] Verify canonical marker ADD triggers target-specific replay.
* [x] Verify canonical area ADD triggers target-specific replay.
* [x] Verify replay occurs in complete total-order ascending order.
* [x] Verify valid pending actions become applied.
* [x] Verify incompatible pending actions become rejected.
* [x] Verify one incompatible pending action does not block valid siblings.
* [x] Verify marker/area target-type conflict is rejected during recovery.
* [x] Verify target deletion and temporal restrictions are enforced during replay.
* [x] Verify earliest resolution is the irreversible lifecycle cutoff.
* [x] Verify an action ordered before resolution remains valid historical action.
* [x] Verify an action ordered at or after resolution is rejected.
* [x] Verify repeated resolution actions remain deterministic.
* [x] Verify equal timestamps use Lamport time, actor DID and event ID.
* [x] Verify pending backlog is drained before a new existing-target live action.
* [x] Verify transition ownership prevents double materialization.
* [x] Verify projection is refreshed from applied history rather than patched.
* [x] Verify replayed confirmation counts match ordered live processing.
* [x] Verify replayed acknowledgement counts match ordered live processing.
* [x] Verify replayed lifecycle and latest pointers match ordered live processing.
* [x] Verify repeated replay is idempotent.
* [x] Verify rollback leaves no partial ADD, transition or projection state.
* [x] Verify schema version remains 52.

### M2C3B non-goals

* [x] Do not add group-scoped bounded recovery.
* [x] Do not implement orphan retention or pruning.
* [x] Do not connect `EventIngestor`.
* [x] Do not replace the local map writer.
* [x] Do not change BLE, relay or UI behavior.
* [x] Do not redesign full durable-log rebuild.

### M2C3B acceptance

* [x] Canonical ADD and target-specific pending recovery are atomic.
* [x] Pending replay reuses live target-dependent semantic contracts.
* [x] Replay ordering uses the complete accepted comparator.
* [x] Earliest resolution remains the irreversible lifecycle cutoff.
* [x] Pending transitions are guarded, monotonic and winner-owned.
* [x] Projection is derived from immutable applied history.
* [x] Repeated replay is idempotent.
* [x] Action-before-target and target-before-action delivery converge.
* [x] M2C3B is reviewed and accepted before bounded recovery begins.

---

## Phase M2C3C — Bounded Recovery and Convergence Closure

### Goal

Provide a deterministic group-scoped maintenance operation for recovering pending targets that were not completed by the primary target-arrival path, and close replay convergence and isolation guarantees.

### Bounded recovery operation

* [x] Add a bounded group-scoped pending recovery service.
* [x] Keep recovery invocation independent from UI.
* [x] Require:

  * group ID;
  * explicit positive batch limit;
  * complete keyset cursor when present.
* [x] Use the accepted bounded pending scan query.
* [x] Preserve deterministic complete action-order ascending scan order.
* [x] Do not add a global cross-group recovery scan.
* [x] Do not add an unbounded pending query.
* [x] Process groups independently.
* [x] Return a typed recovery result containing:

  * scanned row count;
  * discovered target count;
  * completed target count;
  * skipped missing-target count;
  * failed target count;
  * next complete keyset cursor.

### Target grouping and transaction ownership

* [x] Deduplicate exact target identities discovered in one bounded scan page.
* [x] Keep item type in every target identity.
* [x] Process one exact target per Room transaction.
* [x] Recheck canonical target and domain-state existence inside each target transaction.
* [x] Invoke the accepted target replay coordinator rather than duplicating replay logic.
* [x] Ensure one target failure does not roll back successfully recovered sibling targets.
* [x] Ensure one invalid target does not prevent later valid targets in the batch.
* [x] Keep transaction orchestration outside DAOs.

### Missing-target scan behavior

* [x] Leave still-missing target rows as `pending_target`.
* [x] Do not reject a row merely because its target is still absent during a bounded maintenance scan.
* [x] Do not prune recently pending actions.
* [x] Do not infer permanent orphan status from scan count.
* [x] Do not use Room row ID as retention or ordering input.
* [x] Preserve pending rows for future offline target arrival.

### Cursor contract

* [x] Use the complete accepted action-order tuple as the bounded recovery cursor:

  * action timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Require complete cursor values or no cursor.
* [x] Use the final scanned row as the continuation cursor.
* [x] Keep cursor progression deterministic across equal timestamps.
* [x] Verify no duplicate or skipped scan rows across page boundaries.
* [x] Do not use `OFFSET` as the recovery pagination contract.

### Recovery idempotency

* [x] Re-running the same bounded recovery page is safe.
* [x] Already completed rows do not return to pending.
* [x] A target recovered concurrently by the canonical ADD path is treated as already completed or no longer pending.
* [x] A stale bounded-scan result cannot double-apply projection effects.
* [x] Only guarded transition winners can change materialization state.
* [x] Projection remains a deterministic function of current applied history.

### Convergence closure

* [x] Verify action-before-target and target-before-action paths produce equivalent semantic action outcomes.
* [x] Verify opposite pending arrival orders produce identical lifecycle.
* [x] Verify opposite pending arrival orders produce identical latest pointers.
* [x] Verify opposite pending arrival orders produce identical confirmation counts.
* [x] Verify opposite pending arrival orders produce identical acknowledgement counts.
* [x] Verify target-triggered replay and bounded recovery produce identical target projection.
* [x] Verify replayed historical actions remain stored without replacing newer pointers.
* [x] Verify pending actions ordered before resolution cannot reopen lifecycle.
* [x] Verify actions ordered at or after resolution are rejected consistently.
* [x] Verify replay produces the same projection as equivalent ordered live processing.

### Recovery failure isolation

* [x] Treat semantic incompatibility as a terminal rejected row, not a recovery-service failure.
* [x] Treat invalid persisted target history as a target-scoped failure.
* [x] Continue processing valid sibling targets after one target-scoped failure.
* [x] Preserve the continuation cursor even when one target fails.
* [x] Return stable machine-readable failure classification for diagnostics.
* [x] Do not expose Room entities through recovery results.

### Orphan retention deferral

* [x] Do not implement orphan pruning in M2C3.
* [x] Do not transition still-missing targets to rejected solely because of age.
* [x] Do not delete pending action history.
* [x] Document that orphan retention requires a separate accepted policy covering:

  * retention duration;
  * offline synchronization delay;
  * clock skew;
  * persisted arrival-time authority;
  * rejection-versus-deletion behavior;
  * history and forensic requirements.
* [x] Defer orphan maintenance to M2D or a separately reviewed maintenance phase.

### M2C3C tests

* [x] Verify bounded pending scan requires a positive limit.
* [x] Verify incomplete recovery cursor is rejected.
* [x] Verify bounded recovery preserves complete comparator order.
* [x] Verify cursor stability across timestamp ties.
* [x] Verify cursor stability across timestamp and Lamport ties.
* [x] Verify cursor stability across timestamp, Lamport and actor ties.
* [x] Verify no duplicate or skipped pending rows across pages.
* [x] Verify mixed marker and area pending targets are isolated correctly.
* [x] Verify one target failure does not block valid sibling targets.
* [x] Verify still-missing targets remain pending.
* [x] Verify concurrently completed targets do not double materialize.
* [x] Verify bounded recovery is idempotent.
* [x] Verify bounded recovery projection matches target-triggered replay.
* [x] Verify opposite arrival orders converge.
* [x] Verify replayed projection matches ordered live processing.
* [x] Verify no orphan pruning occurs.
* [x] Verify schema version remains 52.

### M2C3C non-goals

* [x] Do not implement orphan pruning or retention.
* [x] Do not connect the recovery operation to app startup.
* [x] Do not connect `EventIngestor`.
* [x] Do not replace the local map writer.
* [x] Do not change BLE, relay or UI behavior.
* [x] Do not implement full durable-event-log rebuild.

### M2C3C acceptance

* [x] Bounded recovery is deterministic, group-scoped and cursor-based.
* [x] One target failure does not block valid sibling recovery.
* [x] Still-missing targets remain safely pending.
* [x] Guarded transition ownership prevents double materialization.
* [x] Target-triggered and bounded recovery produce equivalent projection state.
* [x] Action-before-target delivery converges for markers and areas.
* [x] No orphan retention policy has been implemented prematurely.
* [x] M2C3C is reviewed and accepted before overall M2C3 acceptance.

---

### M2C3 overall non-goals

* [x] Do not connect `EventIngestor`.
* [x] Do not replace the local map writer.
* [x] Do not implement BLE or relay changes.
* [x] Do not implement UI behavior.
* [x] Do not add a Room migration.
* [x] Do not implement an unbounded global pending scan.
* [x] Do not implement orphan pruning without a separately accepted retention contract.
* [x] Do not redesign full durable-log replay or rebuild assigned to M2D.

### M2C3 overall acceptance

* [x] M2C3A pending persistence and duplicate-classification contracts are accepted.
* [x] M2C3B target-triggered replay and projection-refresh contracts are accepted.
* [x] M2C3C bounded recovery and convergence contracts are accepted.
* [x] Missing-target actions are persisted as immutable `pending_target` history.
* [x] Opposite-type target conflicts are not misclassified as pending.
* [x] Pending actions do not affect lifecycle, pointers or aggregates.
* [x] Canonical ADD and target-specific recovery are atomic.
* [x] Existing-target live actions drain target pending backlog first.
* [x] Pending recovery reuses the accepted target-dependent semantic evaluator.
* [x] Pending transitions are guarded, monotonic and winner-owned.
* [x] Replay ordering uses the complete M2B2 comparator.
* [x] Event ID remains the final ordering tie-break.
* [x] Earliest resolution remains the irreversible lifecycle cutoff.
* [x] Projection is always derived from immutable applied action history.
* [x] Replay is idempotent and arrival-order independent.
* [x] Bounded recovery is deterministic and target-isolated.
* [x] Still-missing targets remain pending for future offline synchronization.
* [x] No orphan retention policy has been implemented prematurely.
* [x] Room schema remains version 52.
* [x] No production local, remote, BLE, relay or UI integration has changed.
* [x] M2C3 is reviewed and accepted before production path integration begins.

---

## Phase M2C4 — Shared Local/Remote Integration and Convergence Closure

### Goal

Route existing remote ingest and local map writing through the accepted shared map-processing seam while preserving current UI, non-map ingest and transport behavior.

Phase M2C4 is divided into:

* Phase M2C4A — Remote EventIngestor Integration;
* Phase M2C4B — Local Map Writer Integration;
* Phase M2C4C — Local/Remote Convergence and Regression Closure.

M2C4 must preserve:

* Room schema version 52;
* the accepted M2A wire contract;
* the accepted M2B persistence and read-model contracts;
* the accepted M2C1 canonical enforcement contract;
* the accepted M2C2 dedicated-action semantic and projection contracts;
* the accepted M2C3 pending-target recovery and deterministic replay contracts;
* `RoomMapEventProcessor` as the sole Room transaction owner for map-event processing;
* existing non-map ingest behavior;
* existing BLE and relay transport contracts;
* current M1-compatible UI behavior until Phase M3.

M2C4 must not:

* redesign accepted M2C reducers or replay components;
* introduce a second map-event transaction owner;
* change duplicate classification;
* change deterministic action ordering;
* change pending-target recovery semantics;
* change public wire or persistence contracts;
* add a Room migration;
* implement M2D transport or durable-log rebuild closure early.

---

## Phase M2C4A — Remote EventIngestor Integration

### Goal

Route every remotely ingested map event through the accepted shared map-processing seam while preserving all existing non-map ingest behavior, batch isolation, source attribution, dedupe coordination and post-commit notification behavior.

### Remote map routing

* [x] Inject or otherwise wire the accepted map-event processor into `EventIngestor`.
* [x] Route every event recognized by `EventTypes.isMap()` through the shared map-processing seam.
* [x] Stop invoking the legacy `MapReducer` directly from `EventIngestor`.
* [x] Do not insert a map event into the durable event table before invoking the shared processor.
* [x] Do not open a competing map-event transaction in `EventIngestor`.
* [x] Keep `RoomMapEventProcessor` as the sole Room transaction owner for remote map events.
* [x] Preserve exact remote event content passed to structural validation and semantic processing.
* [x] Preserve original event ID, actor DID, event timestamp and Lamport time.
* [x] Preserve existing event-source attribution outside reducer semantics.
* [x] Ensure remote dedicated actions cannot bypass category, lifecycle, temporal, membership or pending-target enforcement.
* [x] Ensure remote canonical events cannot bypass owner, membership or temporal enforcement.

### Non-map ingest preservation

* [x] Keep non-map reducer behavior unchanged.
* [x] Preserve chat event processing.
* [x] Preserve chat moderation behavior.
* [x] Preserve SYS event processing.
* [x] Preserve live-trail processing.
* [x] Preserve any existing live-trail buffering or deferred replay behavior.
* [x] Preserve existing tombstone handling outside the accepted map-processing boundary.
* [x] Do not route unrelated non-map events through the map processor.
* [x] Preserve the existing per-event `EventIngestor` contract without modifying APP-batch iteration or sibling isolation.

End-to-end malformed-sibling APP-batch verification is assigned to M2D BLE batching and
compatibility closure; it is not an M2C4A transaction-boundary blocker.

### Remote processing-result mapping

* [x] Define one explicit mapping from `MapEventProcessingResult` to the existing remote-ingest result contract.
* [x] Map accepted newly inserted map events to remote-ingest success.
* [x] Map accepted canonical no-op outcomes consistently with the existing ingest contract.
* [x] Map exact duplicate delivery to an idempotent non-new-insert outcome.
* [x] Map conflicting duplicate delivery to a deterministic rejected outcome.
* [x] Map semantic rejection to a rejected ingest outcome rather than a transport failure.
* [x] Map invalid persisted state to a stable internal ingest failure classification.
* [x] Map persistence failure to the existing persistence/internal failure behavior.
* [x] Keep stable semantic rejection reasons available for tests and diagnostics.
* [x] Do not expose Room entities through remote-ingest results.
* [x] Do not expose reducer or replay internals through `EventIngestor`.

### Remote dedupe and replay-cache coordination

* [x] Bypass the legacy ID-only replay-cache fast path for map events so `RoomMapEventProcessor` remains the authoritative exact-versus-conflicting duplicate classifier.
* [x] Preserve the legacy replay-cache optimization for non-map events only.
* [x] Keep persistent dedupe behavior consistent with the shared processor outcome.
* [x] Do not mark a semantically rejected map event as durably accepted.
* [x] Do not mark a conflicting duplicate as a newly accepted event.
* [x] Do not create a second duplicate policy in `EventIngestor`.
* [x] Treat the shared processor as authoritative for map-event durable insertion and duplicate classification.
* [x] Ensure conflicting duplicate payload cannot alter the first persisted event, action or projection.
* [x] Preserve existing dedupe behavior for non-map events.

### Remote notification behavior

* [x] Preserve ingest notifications only for accepted newly inserted events.
* [x] Do not emit a new-ingest notification for exact duplicate map delivery.
* [x] Do not emit an accepted-event notification for semantic rejection.
* [x] Do not emit an accepted-event notification for conflicting duplicate input.
* [x] Do not emit an accepted-event notification when the map transaction rolls back.
* [x] Preserve existing notification behavior for non-map events.
* [x] Ensure notification happens only after successful transaction commit.

### Remote transaction and rollback contract

* [x] Recheck membership inside the map processor transaction.
* [x] Recheck target state, ownership and temporal permissions inside the map processor transaction.
* [x] Ensure remote semantic rejection leaves no partial durable event, action row or domain-state mutation.
* [x] Ensure processor persistence failure rolls back the full map-event transaction.
* [x] Ensure pending replay or projection failure rolls back the originating remote map event.
* [x] Do not rely on pre-transaction membership or temporal checks as semantic authority.
* [x] Preserve prechecks only where needed for routing efficiency or existing diagnostics.
* [x] Do not use transport arrival order as a semantic input.

### M2C4A tests

* [x] Verify remote canonical ADD is routed through the shared processor.
* [x] Verify remote canonical UPD is routed through the shared processor.
* [x] Verify remote canonical DEL is routed through the shared processor.
* [x] Verify remote dedicated actions are routed through the shared processor.
* [x] Verify `EventIngestor` no longer invokes `MapReducer` directly for map events.
* [x] Verify remote non-owner canonical marker mutation is rejected.
* [x] Verify remote non-owner canonical area mutation is rejected.
* [x] Verify remote non-owner canonical DEL is rejected.
* [x] Verify remote removed-member map event is rejected transactionally.
* [x] Verify remote expired canonical mutation is rejected.
* [x] Verify remote expired dedicated action is rejected.
* [x] Verify remote resolved-target restriction is enforced.
* [x] Verify remote action-before-target is persisted and recovered through the accepted M2C3 path.
* [x] Verify exact duplicate remote delivery is idempotent.
* [x] Verify conflicting duplicate remote delivery cannot alter persisted state.
* [x] Verify semantic rejection is not reported as a transport failure.
* [x] Verify accepted inserted map events notify only after commit.
* [x] Verify exact duplicates do not produce a new-ingest notification.
* [x] Verify rejected map events do not produce an accepted-event notification.
* [x] Verify M2C4A does not move APP-batch orchestration; M2D owns end-to-end malformed-sibling verification.
* [x] Verify chat behavior remains unchanged.
* [x] Verify chat moderation behavior remains unchanged.
* [x] Verify SYS processing remains unchanged.
* [x] Verify live-trail processing remains unchanged.
* [x] Verify non-map dedupe behavior remains unchanged.
* [x] Verify rollback leaves no partial durable event, action row or domain state.
* [x] Verify Room schema remains version 52.

### M2C4A non-goals

* [x] Do not modify the local map writer.
* [x] Do not modify `GroupMapActivity`.
* [x] Do not add local dedicated-action generation.
* [x] Do not change BLE batching rules.
* [x] Do not change relay bridging rules.
* [x] Do not redesign `EventIngestor` beyond the minimum map-routing boundary.
* [x] Do not redesign non-map reducer ownership.
* [x] Do not implement full durable-log replay or rebuild.
* [x] Do not add a Room migration.

### M2C4A acceptance

* [x] Every remote map event uses the shared map-processing seam.
* [x] `EventIngestor` no longer directly reduces map events.
* [x] `RoomMapEventProcessor` is the sole Room transaction owner for remote map events.
* [x] Remote map events cannot bypass accepted semantic restrictions.
* [x] Remote duplicate classification remains deterministic and non-destructive.
* [x] Remote accepted-event notifications occur only after successful commit.
* [x] Exact duplicates and semantic rejections are not reported as newly accepted events.
* [x] Non-map ingest behavior remains unchanged.
* [x] M2C4A preserves per-event ingest behavior; M2D owns APP-batch sibling-isolation verification.
* [x] Room schema remains version 52.
* [x] M2C4A is reviewed and accepted before local writer integration begins.

---

## Phase M2C4B — Local Map Writer Integration

### Goal

Replace direct local map-event persistence and reduction with a small compatibility writer backed by the accepted shared map processor, while preserving existing `GroupMapActivity` behavior and post-commit transport handling.

### Local writer boundary

* [x] Add one small app-facing local map writer or compatibility adapter.
* [x] Keep the writer interface independent from Room entities.
* [x] Keep the writer interface independent from reducer internals.
* [x] Keep the writer interface independent from BLE and relay implementation details.
* [x] Pass the exact local event and local actor identity to the accepted map processor.
* [x] Route all existing production local canonical map writes through the shared processor.
* [x] Do not introduce local dedicated-action UI generation.
* [x] Do not add a second local semantic validation implementation.
* [x] Do not duplicate membership, ownership, temporal or category policy in the writer.
* [x] Keep `RoomMapEventProcessor` as the sole Room transaction owner for local map events.

### GroupMapActivity compatibility

* [x] Keep the existing `GroupMapActivity` call site compiling through the compatibility writer.
* [x] Remove direct local `eventsDao().insert` use for map-event acceptance.
* [x] Remove direct local `MapReducer.apply` use.
* [x] Remove local Room transaction ownership for map-event reduction.
* [x] Move authoritative semantic enforcement out of `GroupMapActivity`.
* [x] Keep UI prechecks only as early user feedback.
* [x] Do not treat UI prechecks as semantic authority.
* [x] Keep the existing drafting, creation-flow and M1 vocabulary contracts; manual presentation smoke remains listed under M2C4B.
* [x] Avoid exposing processing implementation details to activity code.

### Local processing-result mapping

* [x] Define one explicit mapping from `MapEventProcessingResult` to local writer outcomes.
* [x] Distinguish:

  * accepted and newly inserted;
  * accepted canonical no-op where applicable;
  * exact duplicate;
  * conflicting duplicate;
  * semantic rejection;
  * invalid persisted state;
  * persistence failure.
* [x] Keep stable semantic rejection reasons available for diagnostics and tests.
* [x] Map semantic rejection to local user feedback rather than transport failure.
* [x] Do not expose Room entities through local writer outcomes.
* [x] Do not expose reducer, replay or projection internals to UI code.
* [x] Log exact duplicate and conflicting duplicate outcomes distinctly.
* [x] Do not report an exact duplicate as a newly created local event.

### Local transaction and authority contract

* [x] Recheck local group membership inside the map processor transaction.
* [x] Recheck target state, ownership and temporal permissions inside the transaction.
* [x] Reject local participation after the local actor has been removed.
* [x] Ensure local semantic rejection leaves no partial durable event, action row or domain-state mutation.
* [x] Ensure processor failure rolls back the entire local map-event transaction.
* [x] Ensure pending replay or projection failure rolls back the originating local event.
* [x] Do not rely on UI state or cached membership as semantic authority.
* [x] Preserve exact signed/wire event content and ordering inputs.
* [x] Do not use UI invocation order as semantic ordering input.

### Post-commit transport boundary

* [x] Perform group-key lookup only after successful local map-event commit.
* [x] Perform BLE permission checks only after successful local map-event commit where compatible with current behavior.
* [x] Enqueue the accepted event to transport only after successful local commit.
* [x] Do not enqueue a semantically rejected local event.
* [x] Do not enqueue a conflicting duplicate.
* [x] Do not enqueue an exact duplicate as a newly accepted event.
* [x] Preserve a successfully committed local event if group-key lookup fails.
* [x] Preserve a successfully committed local event if BLE permission is missing.
* [x] Preserve a successfully committed local event if transport enqueue fails.
* [x] Do not reopen or roll back the map transaction because of a post-commit transport failure.
* [x] Preserve current outbox event identity and serialized content.
* [x] Do not create a transport-specific copy with altered semantic fields.
* [x] Keep BLE and relay source metadata outside reducer semantics.

### Local user feedback preservation

* [x] Preserve current user feedback mapping for a rejected local event; manual presentation smoke remains listed under M2C4C.
* [x] Preserve current user feedback for missing group key.
* [x] Preserve current user feedback for missing BLE permission.
* [x] Preserve current user feedback mapping for transport enqueue failure where currently observable; manual presentation smoke remains listed under M2C4C.
* [x] Distinguish semantic rejection from transport failure.
* [x] Distinguish persistence failure from post-commit enqueue failure.
* [x] Keep UI-facing outcomes free of reducer or persistence terminology and retain success feedback unless a deterministic correction is required.
* [x] Do not add new Situation or Coordination interaction UI.

### Dependency wiring

* [x] Expose only the minimum app-facing writer interface required by `GroupMapActivity`.
* [x] Keep the concrete Room processor implementation inside the data layer.
* [x] Add dependency construction at the existing application composition boundary.
* [x] Avoid direct construction of Room DAOs or reducers inside `GroupMapActivity`.
* [x] Avoid service-locator expansion beyond the existing project architecture.
* [x] Reuse accepted processor dependencies rather than constructing a second processing graph.
* [x] Keep local and remote paths wired to the same semantic processor implementation.
* [x] Do not introduce a second transaction coordinator.

### M2C4B tests

* [x] Verify local canonical ADD uses the compatibility writer.
* [x] Verify local canonical UPD uses the compatibility writer.
* [x] Verify local canonical DEL uses the compatibility writer.
* [x] Verify `GroupMapActivity` no longer directly inserts map events.
* [x] Verify `GroupMapActivity` no longer directly invokes `MapReducer`.
* [x] Verify local non-owner canonical marker mutation is rejected.
* [x] Verify local non-owner canonical area mutation is rejected.
* [x] Verify local non-owner canonical DEL is rejected.
* [x] Verify local participation after membership removal is rejected.
* [x] Verify local expired canonical mutation is rejected.
* [x] Verify local semantic rejection creates no durable event or canonical mutation.
* [x] Verify accepted local event is committed before group-key lookup.
* [x] Verify accepted local event survives missing group key.
* [x] Verify accepted local event survives missing BLE permission.
* [x] Verify accepted local event survives transport enqueue failure.
* [x] Verify rejected local event is not enqueued.
* [x] Verify conflicting duplicate local event is not enqueued.
* [x] Verify exact duplicate local event is not enqueued as newly accepted.
* [x] Verify exact duplicate and conflicting duplicate produce distinct diagnostics.
* [x] Verify transaction rollback leaves no partial event, action or domain state.
* [x] Verify current marker creation UI compiles.
* [x] Verify current area creation UI compiles.
* [ ] Verify current map drafting behavior remains unchanged (manual non-blocking smoke check).
* [x] Verify no dedicated-action UI generation has been introduced.
* [x] Verify Room schema remains version 52.

### M2C4B non-goals

* [x] Do not redesign `GroupMapActivity`.
* [x] Do not redesign normal map rendering.
* [x] Do not add Situation or Coordination interaction controls.
* [x] Do not implement Community Verification presentation.
* [x] Do not change BLE batching rules.
* [x] Do not change relay bridging rules.
* [x] Do not redesign user outbox semantics.
* [x] Do not implement mixed-version transport closure.
* [x] Do not implement full durable-log replay or rebuild.
* [x] Do not add a Room migration.

### M2C4B acceptance

* [x] Every production local map write uses the shared map-processing seam.
* [x] `GroupMapActivity` no longer owns map-event persistence or reduction.
* [x] Local map events cannot bypass accepted semantic restrictions.
* [x] Local and remote map paths use the same processor implementation.
* [x] Local exact duplicates are not enqueued as newly accepted events.
* [x] Local semantic rejection is distinct from transport failure.
* [x] A successfully committed local event survives later key lookup or enqueue failure.
* [x] Technical compatibility is closed; manual presentation/drafting smoke is retained below.
* [x] No dedicated-action UI has been introduced.
* [x] Room schema remains version 52.
* [x] M2C4B is reviewed and accepted before convergence closure begins.

Technical M2C4B closure is accepted; the remaining presentation and feedback checks are optional manual smoke verification and do not establish M2C4C convergence or M2D transport compatibility.

---

## Phase M2C4C — Local/Remote Convergence and Regression Closure

### Goal

Prove that equivalent local and remote map events use one semantic and transactional contract, produce equivalent persisted state, and preserve all accepted non-map, legacy, UI and transport behavior.

M2C4C must primarily add integration verification and targeted wiring fixes. It must not introduce new domain semantics.

### Shared semantic equivalence

* [x] Process equivalent local and remote map events through the same structural contract. Evidence: `LocalRemoteCanonicalConvergenceInstrumentationTest.markerAndAreaAddUpdateDelete_produceEquivalentPersistedState`.
* [x] Process equivalent local and remote map events through the same membership policy.
* [x] Process equivalent local and remote canonical events through the same ownership policy.
* [x] Process equivalent local and remote map events through the same temporal policy.
* [x] Process equivalent local and remote dedicated actions through the same category/action compatibility policy.
* [x] Process equivalent local and remote Situation actions through the same lifecycle policy.
* [x] Process equivalent local and remote actions through the same aggregate projection logic.
* [x] Process equivalent local and remote events through the same duplicate policy. Evidence: `MapCrossSourceDuplicateAndFailureRegressionInstrumentationTest` source-order tests.
* [x] Process equivalent local and remote missing-target actions through the same pending-target recovery path. Evidence: `LocalRemotePendingConvergenceInstrumentationTest`.
* [x] Process equivalent local and remote map events through the same Room transaction owner. Evidence: production `LocalMapEventWriter`/`EventIngestor` convergence fixture and passing snapshots.
* [x] Ensure event source does not alter semantic acceptance. Evidence: 25-test M2C4C device run.
* [x] Ensure transport source metadata remains outside reducer semantics. Evidence: normalized snapshots plus mesh/relay duplicate test.

### Persistence equivalence

* [x] Ensure equivalent local and remote canonical ADD events persist the same canonical row values.
* [x] Ensure equivalent local and remote canonical ADD events persist the same domain-state identity and owner values.
* [x] Ensure equivalent local and remote dedicated actions persist the same immutable action semantics.
* [x] Ensure local and remote paths persist the same event timestamp.
* [x] Ensure local and remote paths persist the same Lamport time.
* [x] Ensure local and remote paths persist the same actor DID.
* [x] Ensure local and remote paths persist the same event ID and action ID.
* [x] Ensure local and remote paths compute the same canonical fingerprint.
* [x] Ensure local and remote paths produce the same lifecycle.
* [x] Ensure local and remote paths produce the same latest pointers.
* [x] Ensure local and remote paths produce the same confirmation count.
* [x] Ensure local and remote paths produce the same acknowledgement count.
* [x] Ensure equivalent semantic rejection leaves equivalent zero-partial-write state.

### Duplicate convergence

* [x] Verify a combined receive sequence across the non-map replay-cache path and the map durable duplicate-classifier path remains idempotent (closed by M2D APP-batch/replay-cache evidence).
* [x] Verify local-first then remote exact duplicate delivery is idempotent.
* [x] Verify remote-first then local exact duplicate delivery is idempotent.
* [x] Verify duplicate delivery through multiple remote sources is idempotent.
* [x] Verify duplicate delivery through local, BLE and relay-equivalent source metadata does not alter semantics (closed by M2D mixed-transport evidence).
* [x] Verify exact duplicate delivery cannot increment aggregate counts.
* [x] Verify exact duplicate delivery cannot refresh projection incorrectly.
* [x] Verify conflicting duplicate payload is rejected regardless of source order.
* [x] Verify conflicting duplicate payload cannot overwrite the original durable event.
* [x] Verify conflicting duplicate payload cannot overwrite the original action row.
* [x] Verify conflicting duplicate payload cannot alter lifecycle, pointers or aggregates.
* [x] Verify exact and conflicting duplicates remain distinctly logged.

### Pending-target convergence

* [x] Verify remote action-before-marker followed by local marker ADD converges.
* [x] Verify local-equivalent pending action followed by remote marker ADD converges where supported by the production event path.
* [x] Verify remote action-before-area followed by local area ADD converges.
* [x] Verify opposite pending action arrival orders produce identical terminal action states.
* [x] Verify target-triggered replay produces the same projection regardless of event source.
* [x] Verify bounded recovery produces the same projection regardless of original event source.
* [x] Verify existing-target live action drains target pending backlog before materialization.
* [x] Verify pending actions remain excluded from projection until applied.
* [x] Verify rejected pending actions remain excluded from projection.
* [x] Verify still-missing targets remain pending.
* [x] Verify action-before-target and target-before-action produce equivalent semantic outcomes.

### Lifecycle and ordering convergence

* [x] Verify status/update delivery in opposite arrival orders converges.
* [x] Verify earliest resolution remains the irreversible lifecycle cutoff.
* [x] Verify a normal action ordered before resolution remains valid historical applied action.
* [x] Verify a normal action ordered at or after resolution is rejected consistently.
* [x] Verify repeated resolution actions remain immutable history.
* [x] Verify latest lifecycle selection uses the complete accepted comparator.
* [x] Verify latest confirmation selection uses the complete accepted comparator.
* [x] Verify latest Situation update selection uses the complete accepted comparator.
* [x] Verify latest material-change selection uses the complete accepted comparator.
* [x] Verify equal timestamps resolve by Lamport time, actor DID and event ID.
* [x] Verify event ID remains the final ordering tie-break.
* [x] Verify source and arrival order never become semantic ordering inputs.

### Aggregate convergence

* [x] Verify repeated confirmation by one actor produces the same count through local and remote delivery.
* [x] Verify repeated acknowledgement by one actor produces the same count through local and remote delivery.
* [x] Verify multiple distinct confirmation actors produce equivalent counts across paths.
* [x] Verify multiple distinct acknowledgement actors produce equivalent counts across paths.
* [x] Verify `situation_changed` invalidates confirmation freshness identically across paths.
* [x] Verify `still_active` does not invalidate confirmation freshness.
* [x] Verify resolution invalidates current verification without deleting historical confirmations.
* [x] Verify acknowledgements remain independent from confirmation counts.
* [x] Verify legacy contributions remain outside new confirmation counts.
* [x] Verify legacy contributions remain outside acknowledgement counts.
* [x] Verify exact duplicate delivery does not change aggregate counts.

### Canonical enforcement regression

* [x] Verify remote non-owner canonical marker mutation is rejected.
* [x] Verify local non-owner canonical marker mutation is rejected.
* [x] Verify remote non-owner canonical area mutation is rejected.
* [x] Verify local non-owner canonical area mutation is rejected.
* [x] Verify remote non-owner canonical DEL is rejected.
* [x] Verify local non-owner canonical DEL is rejected.
* [x] Verify owner canonical UPD succeeds inside the accepted temporal window.
* [x] Verify owner canonical UPD at the exclusive cutoff is rejected.
* [x] Verify canonical mutation at or after expiry is rejected.
* [x] Verify removed-member remote event is rejected.
* [x] Verify local participation after removal is rejected.
* [x] Verify canonical ADD owner establishment remains atomic.
* [x] Verify canonical ADD target-type conflict remains rejected.
* [x] Verify later canonical UPD cannot revive a deleted target.
* [x] Verify canonical UPD cannot reactivate a resolved Situation.

### Dedicated-action regression

* [x] Verify Situation Confirm behaves equivalently through production paths.
* [x] Verify Situation Add update behaves equivalently through production paths.
* [x] Verify Situation Update status behaves equivalently through production paths.
* [x] Verify Coordination Acknowledge behaves equivalently through production paths.
* [x] Verify Coordination Add note behaves equivalently through production paths.
* [x] Verify wrong-category actions are rejected consistently.
* [x] Verify wrong item type is rejected consistently.
* [x] Verify unresolved legacy category is rejected safely.
* [x] Verify deleted-target actions are rejected consistently.
* [x] Verify exact expiry boundary is rejected consistently.
* [x] Verify exact interaction-cutoff boundary is rejected consistently.
* [x] Verify owner-edit cutoff does not block valid member interaction.
* [x] Verify resolved-target restrictions apply equally to both paths.
* [x] Verify target semantic rejection does not leave an accepted action row for live existing-target processing.

### Legacy compatibility regression

* [x] Verify recognized legacy non-owner contribution behavior remains available.
* [x] Verify legacy contribution does not mutate canonical owner fields.
* [x] Verify legacy contribution does not advance the canonical item clock.
* [x] Verify legacy contribution remains stored in `map_item_votes`.
* [x] Verify legacy contribution is not reinterpreted as a dedicated action.
* [x] Verify legacy confidence remains unchanged.
* [x] Verify legacy removal state remains separate from Situation lifecycle.
* [x] Verify recognized active legacy items can still accept allowed new actions.
* [x] Verify unresolved legacy kinds reject new actions safely.
* [x] Verify canonical legacy marker and area UI queries remain unchanged.

### Transaction and failure regression

* [x] Verify semantic rejection leaves no partial durable event.
* [x] Verify semantic rejection leaves no partial action row.
* [x] Verify semantic rejection leaves no partial domain-state mutation.
* [x] Verify action projection failure rolls back durable event and action insertion.
* [x] Verify pending replay failure rolls back the triggering canonical ADD.
* [x] Verify guarded-transition invariant failure rolls back the target transaction.
* [x] Verify one bounded-recovery target failure does not roll back successful siblings.
* [x] Verify local persistence survives later group-key lookup failure.
* [x] Verify local persistence survives later BLE permission failure.
* [x] Verify local persistence survives later transport enqueue failure.
* [x] Verify remote notification occurs only after successful commit.
* [x] Verify transaction rollback leaves no partial event, action, lifecycle, pointer or aggregate state.

### UI, module and transport compatibility

* [x] Verify current map UI compiles without adopting M3 interaction UI.
* [x] Verify current marker creation behavior remains available.
* [x] Verify current area creation behavior remains available.
* [x] Covered by the retained manual drafting and presentation/feedback smoke checks.
* [x] Verify no Community Verification presentation has been added.
* [x] Verify no new Situation or Coordination interaction controls have been added.
* [x] Verify no normal map rendering redesign has been introduced.
* [x] Verify existing BLE enqueue contract remains unchanged.
* [x] Verify existing map batch classification remains unchanged.
* [x] Verify existing relay bridging contract remains unchanged.
* [x] Verify no mixed-version transport closure has been implemented early.
* [x] Verify no full durable-event-log rebuild has been implemented early.
* [x] Verify Room schema remains version 52. Evidence: in-memory schema assertion and `AppDatabase` static audit.
* [x] Verify exported Room schema remains unchanged.

### Roadmap and contract closure

* [x] Audit all M2C1 acceptance items against the accepted implementation and verification evidence.
* [x] Tick completed M2C1 items that were previously left unchecked only when supported by implementation and tests.
* [x] Audit all M2C3 overall acceptance items against the accepted M2C3A, M2C3B and M2C3C evidence.
* [x] Tick completed M2C3 overall acceptance items that were previously left unchecked.
* [x] Verify M2C4A acceptance is complete.
* [x] Verify M2C4B technical acceptance is complete; manual presentation/drafting smoke remains non-blocking.
* [x] Verify M2C4C technical acceptance is complete.
* [x] Audit M2C overall acceptance against M2C1 through M2C4 evidence.
* [x] Keep roadmap wording aligned with actual implementation.
* [x] Do not tick an item based only on architecture intent or skeleton presence.
* [x] Record any intentionally deferred item under M2D or another explicit later phase.
* [x] Confirm no accepted M2C3 contract was changed during production integration.

### M2C4C non-goals

* [x] Do not introduce new domain semantics.
* [x] Do not redesign the shared processor.
* [x] Do not change duplicate classification.
* [x] Do not change pending replay ordering.
* [x] Do not change earliest-resolution cutoff behavior.
* [x] Do not change action-derived projection rules.
* [x] Do not add Situation or Coordination interaction UI.
* [x] Do not implement Community Verification presentation.
* [x] Do not redesign normal map rendering.
* [x] Do not change BLE batching rules.
* [x] Do not change relay bridging rules.
* [x] Historical M2C4C boundary: mixed-version transport verification was deferred to, and is now accepted in, M2D.
* [x] Historical M2C4C boundary: durable-log replay/rebuild verification was deferred to, and is now accepted in, M2D4.
* [x] Do not add a Room migration.

### M2C4C acceptance

* [x] Equivalent local and remote map events use the same semantic processor.
* [x] Equivalent local and remote map events use the same Room transaction owner.
* [x] Event source does not alter semantic acceptance.
* [x] Local and remote paths persist the same ordering inputs.
* [x] Local and remote paths produce equivalent canonical and domain state.
* [x] Local and remote dedicated actions produce equivalent immutable history and projection.
* [x] Duplicate delivery remains idempotent across sources.
* [x] Conflicting duplicate delivery remains non-destructive across sources.
* [x] Action-before-target recovery converges across production paths.
* [x] Opposite action arrival orders converge.
* [x] Repeated actor counts remain deterministic.
* [x] Resolved and expired restrictions apply equally to local and remote paths.
* [x] Legacy contribution behavior remains available and separate.
* [x] Transaction rollback leaves no partial event, action or domain state.
* [x] Successfully committed local events survive later transport failures.
* [x] Existing non-map ingest and transport behavior remain compatible.
* [ ] Current UI presentation and local-feedback behavior remains compatible (manual non-blocking smoke check).
* [x] Room schema remains version 52.
* [x] M2C4C is reviewed and accepted with non-blocking manual presentation checks outstanding.

### M2C4C final evidence audit (2026-07-16)

This audit assigns every remaining M2C4C checklist theme to one evidence owner. A listed
automated owner is exact test evidence; a `manual non-blocking` owner is intentionally not
treated as automated semantic proof; M2D ownership remains deferred.

| Checklist theme | Evidence owner |
| --- | --- |
| Canonical ownership, area mutation, non-owner delete, durable/canonical/domain rollback | `LocalRemoteCanonicalConvergenceInstrumentationTest.nonOwnerAreaUpdate_isRejectedWithoutPartialWritesAcrossBothPaths`, `.nonOwnerDelete_isRejectedWithoutPartialWritesAcrossBothPaths`, `.semanticRejection_leavesBothDatabasesWithoutRejectedDurableEvent` |
| Canonical temporal boundaries | `LocalRemoteCanonicalConvergenceInstrumentationTest.ownerCanonicalTemporalBoundaries_areEquivalentAcrossBothPaths` (inside window; exact owner cutoff; exact expiry; after expiry) |
| Removed event actor | `EventIngestorM2C4AIntegrationTest.remoteNonOwnerAndRemovedMemberMutations_areRejectedWithoutDurableEvents` |
| Removed local participant with a distinct valid event actor | existing automated `RoomLocalMapEventWriterInstrumentationTest.removedLocalActorRejectsDistinctValidEventActor` plus shared-processor static evidence in `RoomMapEventProcessor.localRemovedActorIsRejectedWhenDistinctFromValidEventActor`; remote receiving context has no local participant in the accepted fixture, so no artificial parity seam was added |
| Canonical ordering tuple: timestamp, Lamport, actor DID, event ID | `MapEventOrderingPolicyTest.completeTupleUsesEveryTieBreakInOrder` |
| Earliest resolution | `RoomTargetPendingReplayCoordinatorTest.earliest resolution is an irreversible complete-order cutoff` and `RoomMapEventProcessorTest.replayUsesEarliestResolutionAsIrreversibleCutoff` |
| Latest confirmation | `LocalRemoteDedicatedActionConvergenceInstrumentationTest.confirmationComparatorRepresentativeCase_convergesAcrossPaths` |
| Latest lifecycle and latest material change | `RoomMapEventProcessorTest.repeatedResolutionIsAcceptedAndTieUsesLamportActorAndEventId` and `.materialBoundaryAndLatestPointersConvergeAcrossOppositeArrivalOrders` |
| Cross-source duplicates, exact/conflicting immutability, mutable-state duplicates | `MapCrossSourceDuplicateAndFailureRegressionInstrumentationTest`, `LocalRemoteDedicatedActionConvergenceInstrumentationTest.dedicatedConfirmation_exactAndConflictingDuplicates_areImmutableAcrossBothSourceDirections`, and its three `exactDedicatedDuplicateAfter…` tests |
| Pending ordering, replay, target isolation and bounded recovery | `LocalRemotePendingConvergenceInstrumentationTest`, `RoomBoundedPendingRecoveryInstrumentationTest`, and `RoomBoundedPendingRecoveryClosureInstrumentationTest` |
| Semantic-rejection rollback | `LocalRemoteCanonicalConvergenceInstrumentationTest.semanticRejection_leavesBothDatabasesWithoutRejectedDurableEvent` |
| Projection failure rollback | `RoomMapEventProcessorTest.projectionAffectedRowInvariantRollsBackEventActionAndDomainMutation` |
| Replay failure rollback and guarded transition | `RoomMapEventProcessorTest.canonicalAddRollsBackWhenPendingActionHasNoDurableEvent` and `.invalidPendingPairRollsBackEarlierSiblingTransition` |
| Bounded-recovery sibling isolation | `RoomBoundedPendingRecoveryInstrumentationTest.realTargetTransactionsIsolateRollbackAndPreserveFinalCursor` and `RoomBoundedPendingRecoveryServiceTest.one replay failure rolls back only its target and later sibling continues` |
| Invalid persisted event/action pair | `RoomMapEventProcessorTest.duplicateAndReplaySharePersistedPairInvariant`, `.incomingConflictCannotMaskMissingStoredActionPair`, and `.incomingConflictCannotMaskCorruptStoredActionPair` |
| Notification suppression | `EventIngestorM2C4AContractTest.exactAndConflictingDuplicates_areFalse_unmarked_andDistinctlyLogged` and `.rejection_invalidState_andPersistenceFailure_areFalse_unmarked_andDistinct` |
| Post-commit transport | `LocalMapPostCommitTransportTest` (`acceptedNew_ordersCallbacks_andPassesExactCommittedCore`, missing-key, permission, enqueue-false and enqueue-exception cases) |
| Existing UI presentation/drafting feedback | manual non-blocking: current marker/area creation compile tests are automated; presentation/drafting smoke checks remain intentionally unticked |
| Mixed-version transport and full durable-log rebuild | M2D |

The evidence above also closes the corresponding M2C1/M2C3/M2C4A/M2C4B technical audit items;
it does not treat architecture intent, a skeleton, or a manual check as automated proof.

---

### M2C4 overall non-goals

* [x] Verify the M2C4 boundary: no M3 interaction/presentation UI, rendering redesign, BLE/relay protocol redesign, ordering/duplicate/semantic contract change, or Room migration was introduced.
* [x] Historical M2C4 boundary: mixed-version verification and durable replay/rebuild were deferred to, and are now accepted in, M2D/M2D4.
* [x] Verify accepted M2C3 replay and recovery contracts remain unchanged.

### M2C4 overall acceptance

* [x] M2C4A remote EventIngestor integration is accepted.
* [x] M2C4B local map writer integration is accepted.
* [x] M2C4C local/remote convergence and regression closure is accepted with non-blocking manual presentation checks outstanding.
* [x] Remote events cannot bypass accepted map-domain restrictions.
* [x] Local events cannot bypass accepted map-domain restrictions.
* [x] Local and remote map paths share one transaction owner and semantic processor.
* [x] Non-owner canonical mutation tests pass for markers and areas.
* [x] Removed-member restrictions pass for local and remote paths.
* [x] Action-before-item recovery converges.
* [x] Repeated actor behavior is deterministic.
* [x] Resolved and expired interaction restrictions pass.
* [x] Legacy contribution behavior remains available.
* [x] Duplicate delivery remains idempotent across sources.
* [x] Conflicting duplicate delivery remains non-destructive.
* [x] Transaction rollback leaves no partial event, action or domain state.
* [x] Successfully persisted local events survive later transport failure.
* [x] Overall technical compatibility is closed; the two explicit manual smoke checks remain non-blocking.
* [x] Existing non-map ingest behavior remains compatible.
* [x] Existing BLE and relay contracts remain compatible.
* [x] Room schema remains version 52.
* [x] No M2D transport or durable-log rebuild work has been implemented early.
* [x] M2C4 is technically reviewed and accepted with non-blocking manual presentation checks outstanding.

---

### M2C overall acceptance

* [x] M2C1 canonical enforcement and transaction contracts are accepted.
* [x] M2C2A dedicated-action persistence and semantic-classification contracts are accepted.
* [x] M2C2B lifecycle, latest-pointer and aggregate-materialization contracts are accepted.
* [x] M2C2 overall dedicated-action reduction and materialization contracts are accepted.
* [x] M2C3 pending-target recovery and deterministic replay contracts are accepted.
* [x] M2C4 shared local/remote integration contracts are accepted with non-blocking manual presentation checks outstanding.
* [x] Canonical and action reducers remain responsibility-separated.
* [x] One shared DB-aware map processing seam owns semantic enforcement.
* [x] Canonical owner, membership and temporal rules are enforced transactionally.
* [x] Dedicated actions are immutable, append-only and category-correct.
* [x] Lifecycle and aggregate materialization use the complete accepted comparator.
* [x] Pending actions converge deterministically after target arrival.
* [x] Local and remote processing produce equivalent materialized state.
* [x] Legacy contributions remain separate and available.
* [x] Room schema remains version 52.
* [x] No UI, BLE or relay redesign has been pulled into M2C.
* [x] No blocking technical findings remain.
* [x] M2C is technically accepted as the reducer and ingest-enforcement baseline before Phase M2D, with non-blocking manual presentation checks outstanding.

---

## Phase M2D — Transport, Replay and Compatibility Closure

### Goal

Verify that the accepted M2 event, persistence and processing model survives BLE batching, relay bridging, mixed-version traffic, durable replay, dedupe and cross-boundary failure scenarios without corrupting canonical, domain or legacy state.

Phase M2D is divided into:

* Phase M2D1 — BLE Batch and Envelope Compatibility;
* Phase M2D2 — Relay Bridging and Mixed-Transport Deduplication;
* Phase M2D3 — Mixed-Version and Legacy/New Compatibility;
* Phase M2D4 — Durable Replay, Failure Closure and M2 Final Acceptance.

M2D must preserve:

* the accepted M2A wire contract;
* Room schema version 52;
* append-only durable event history;
* immutable dedicated action history;
* deterministic canonical and action ordering;
* `RoomMapEventProcessor` as the authoritative semantic and duplicate-classification boundary for map events;
* the accepted pending-target recovery and projection contracts;
* existing BLE batching and envelope formats;
* existing relay payload and bridge policies;
* legacy marker, area, confidence, contribution and removal semantics;
* current M1-compatible UI behavior until Phase M3.

M2D must not:

* introduce new map-domain semantics;
* redesign canonical or dedicated-action reducers;
* introduce a second map-event transaction owner;
* change duplicate classification;
* change the accepted complete action comparator;
* change the earliest-resolution irreversible lifecycle cutoff;
* disguise dedicated actions as legacy canonical UPD events;
* introduce a separate BLE or relay payload protocol for dedicated actions;
* introduce orphan rejection, pruning or retention-by-age without a separately accepted retention contract;
* add a Room migration unless independently reviewed and justified by measured evidence;
* implement M3 interaction UI or Community Verification presentation.

---

## Phase M2D1 — BLE Batch and Envelope Compatibility

### Goal

Verify that canonical map events and dedicated `MAP.ITEM.*` actions use the existing BLE APP-batch and envelope paths without identity mutation, protocol redesign or sibling-event failure propagation.

M2D1 must establish transport compatibility only. Semantic acceptance, duplicate classification and persistence remain owned by the accepted shared map-processing path.

### Map batch classification

* [x] Verify every canonical map event enters the existing map batch lane:

  * `MAP.MARKER.ADD`;
  * `MAP.MARKER.UPD`;
  * `MAP.MARKER.DEL`;
  * `MAP.AREA.ADD`;
  * `MAP.AREA.UPD`;
  * `MAP.AREA.DEL`.
* [x] Verify every dedicated map action enters the existing map batch lane:

  * `MAP.ITEM.CONFIRM`;
  * `MAP.ITEM.SITUATION.UPDATE`;
  * `MAP.ITEM.SITUATION.STATUS`;
  * `MAP.ITEM.ACKNOWLEDGE`;
  * `MAP.ITEM.NOTE`.
* [x] Verify batch classification continues to rely on the accepted map-event classification contract.
* [x] Do not introduce a separate batch lane for dedicated actions.
* [x] Do not classify dedicated actions as chat, SYS, trail or generic unknown traffic.
* [x] Preserve existing batch priority behavior unless measured evidence requires a separately reviewed change.

### Batch window and size compatibility

* [x] Preserve the existing map batch window.
* [x] Preserve existing APP-batch payload-size limits.
* [x] Verify multiple dedicated action events can share one APP batch.
* [x] Verify canonical and dedicated map events can coexist in one APP batch where currently permitted.
* [x] Verify dedicated action payload size remains bounded by the accepted M2A wire contract.
* [x] Verify dedicated action payloads do not include unnecessary:

  * marker coordinates;
  * marker radius;
  * area geometry;
  * canonical item snapshots;
  * legacy confidence snapshots;
  * projection snapshots.
* [x] Do not increase batch size or release delay without measured evidence.

### Per-event sibling isolation

* [x] Verify one malformed dedicated action does not reject valid sibling events in the same APP batch.
* [x] Verify one malformed canonical map event does not reject valid sibling events in the same APP batch.
* [x] Verify one structurally valid but semantically rejected map event does not reject valid siblings.
* [x] Verify one unknown dedicated action event type does not corrupt or suppress valid siblings.
* [x] Verify one unsupported map-domain version does not suppress valid siblings.
* [x] Verify one wrong-item-type action does not suppress valid siblings.
* [x] Verify one conflicting duplicate does not suppress valid siblings.
* [x] Verify one exact duplicate does not prevent new valid sibling events from being accepted.
* [x] Preserve per-event ingest result isolation.
* [x] Do not move semantic batch ownership into BLE decode or batching code.

### Envelope v1/v2 compatibility

* [x] Test canonical map events across every currently supported APP envelope version.
* [x] Test dedicated action events across every currently supported APP envelope version.
* [x] Verify envelope encode/decode preserves:

  * event ID;
  * event type;
  * action ID;
  * target item ID;
  * item type;
  * actor DID;
  * event timestamp;
  * Lamport time;
  * map-domain version;
  * action semantic payload.
* [x] Verify envelope mapping does not regenerate event IDs.
* [x] Verify envelope mapping does not regenerate action IDs.
* [x] Verify envelope mapping does not normalize or replace actor identity.
* [x] Verify envelope mapping does not replace event timestamp with receive time.
* [x] Verify envelope mapping does not use transport sequence as semantic Lamport time.
* [x] Verify unknown optional payload fields survive where the accepted envelope path is lossless.
* [x] Verify unsupported stable semantic values still fail safely after envelope decode.

### Deferred release and queue identity

* [x] Verify deferred batch release preserves the original event object or semantically identical serialized event.
* [x] Verify a queued event retains the same event ID and action ID after deferred release.
* [x] Verify queue retry does not generate a new event identity.
* [x] Verify queue retry does not alter target identity.
* [x] Verify queue retry does not alter timestamp or Lamport ordering inputs.
* [x] Verify queue coalescing does not merge two distinct dedicated actions.
* [x] Verify queue dedupe does not use payload-only or object-hash identity.
* [x] Verify transport queue metadata remains outside map reducer semantics.

### Duplicate coordination across APP batches

* [x] Verify duplicate action delivery in separate APP batches is idempotent.
* [x] Verify duplicate canonical delivery in separate APP batches is idempotent.
* [x] Verify exact map duplicates reach the authoritative persistent map duplicate classifier.
* [x] Verify conflicting map duplicates reach the authoritative persistent map duplicate classifier.
* [x] Verify the non-map replay cache does not mask map conflicting-duplicate detection.
* [x] Verify a combined receive sequence across:

  * the non-map replay-cache path;
  * the map durable duplicate-classifier path;
    remains idempotent and correctly classified.
* [x] Do not introduce a second map duplicate policy in the batch decoder.
* [x] Do not mark a semantically rejected map event as replay-cache accepted.

### M2D1 tests

* [x] Add focused map batch-classification tests for all canonical and dedicated event types.
* [x] Add multiple-dedicated-action APP-batch tests.
* [x] Add mixed canonical/action APP-batch tests.
* [x] Add malformed-action sibling-isolation tests.
* [x] Add semantic-rejection sibling-isolation tests.
* [x] Add unsupported-version sibling-isolation tests.
* [x] Add exact-duplicate sibling-isolation tests.
* [x] Add conflicting-duplicate sibling-isolation tests.
* [x] Add envelope v1 dedicated-action round-trip tests.
* [x] Add envelope v2 dedicated-action round-trip tests where supported.
* [x] Add deferred-release identity-preservation tests.
* [x] Add batch retry identity-preservation tests.
* [x] Add replay-cache versus durable-map-dedupe coordination tests.
* [x] Verify existing non-map APP-batch behavior remains unchanged.
* [x] Verify Room schema remains version 52.

### M2D1 non-goals

* [x] Do not change relay push or pull behavior.
* [x] Do not implement cloud-to-BLE bridge changes.
* [x] Do not implement mixed-version old-peer compatibility closure.
* [x] Do not implement durable-log replay or rebuild.
* [x] Do not introduce orphan retention or pruning.
* [x] Do not change map reducer semantics.
* [x] Do not change the shared map-processing result contract.
* [x] Do not add UI capability gating.
* [x] Do not add a Room migration.

### M2D1 acceptance

* [x] Canonical and dedicated map events use the existing map batch lane.
* [x] Dedicated actions do not require a new BLE payload protocol.
* [x] Existing batch window and payload-size limits remain compatible.
* [x] One malformed or rejected map event cannot suppress valid siblings.
* [x] Envelope v1/v2 paths preserve complete map event identity and ordering inputs.
* [x] Deferred release and retry preserve event/action identity.
* [x] Exact and conflicting duplicates remain authoritatively classified by the shared map processor.
* [x] Non-map replay-cache behavior remains unchanged.
* [x] No reducer, persistence, relay or UI semantics have changed.
* [x] Room schema remains version 52.
* [x] M2D1 is reviewed and accepted before relay bridging closure begins.

---

## Phase M2D2 — Relay Bridging and Mixed-Transport Deduplication

### Goal

Verify that canonical map events and dedicated actions can traverse local, BLE, relay and bridge paths while preserving exact identity, preventing relay echo amplification and converging through the accepted persistent duplicate policy.

M2D2 must preserve relay as a delivery mechanism only. Relay source, retry count, bridge direction and transport arrival order must not become semantic inputs.

### Relay eligibility

* [x] Verify locally accepted canonical map events remain eligible for relay push according to existing policy.
* [x] Verify locally accepted dedicated action events are eligible for relay push through the accepted map classification.
* [x] Verify mesh-received canonical map events can bridge to relay according to existing policy.
* [x] Verify mesh-received dedicated actions can bridge to relay according to existing policy.
* [x] Verify relay-received canonical map events can bridge to BLE according to existing policy.
* [x] Verify relay-received dedicated actions can bridge to BLE according to existing policy.
* [x] Verify rejected or rolled-back map events are not relayed as accepted events.
* [x] Verify exact duplicates are not treated as new relay-originated events.
* [x] Verify conflicting duplicates are not relayed as accepted mutations.
* [x] Do not add a dedicated-action-specific relay protocol.

### Relay identity preservation

* [x] Preserve event ID through local-to-relay mapping.
* [x] Preserve action ID through local-to-relay mapping.
* [x] Preserve event ID through mesh-to-relay bridging.
* [x] Preserve action ID through mesh-to-relay bridging.
* [x] Preserve event ID through relay-to-BLE bridging.
* [x] Preserve action ID through relay-to-BLE bridging.
* [x] Preserve original event type.
* [x] Preserve target item ID and item type.
* [x] Preserve actor DID.
* [x] Preserve event timestamp.
* [x] Preserve Lamport time.
* [x] Preserve action semantic payload.
* [x] Preserve canonical serialized content or an equivalent canonical representation producing the same fingerprint.
* [x] Do not generate transport-specific copies with altered semantic fields.

### Relay retry behavior

* [x] Verify relay push retry preserves event identity.
* [x] Verify relay pull retry preserves event identity.
* [x] Verify relay retry does not create a second action row.
* [x] Verify relay retry does not create a second durable event.
* [x] Verify relay retry does not increment confirmation counts.
* [x] Verify relay retry does not increment acknowledgement counts.
* [x] Verify relay retry does not refresh latest pointers incorrectly.
* [x] Verify relay retry cannot overwrite an existing immutable action.
* [x] Verify conflicting retry payload is rejected non-destructively.

### Relay echo prevention

* [x] Verify local → relay → local-equivalent echo is idempotent.
* [x] Verify local → BLE → relay → BLE echo is idempotent.
* [x] Verify relay → BLE → relay echo is idempotent.
* [x] Verify mesh-received action bridged to relay and returned through relay does not duplicate action history.
* [x] Verify relay-received action bridged to BLE and returned through mesh does not duplicate action history.
* [x] Verify relay echo does not alter lifecycle.
* [x] Verify relay echo does not alter latest pointers.
* [x] Verify relay echo does not alter confirmation counts.
* [x] Verify relay echo does not alter acknowledgement counts.
* [x] Verify relay echo does not change pending/applied/rejected terminal state.
* [x] Do not rely only on volatile source metadata to prevent semantic duplication.

### Cross-source duplicate convergence

* [x] Verify local-first then BLE exact duplicate is idempotent.
* [x] Verify BLE-first then local exact duplicate is idempotent.
* [x] Verify local-first then relay exact duplicate is idempotent.
* [x] Verify relay-first then local exact duplicate is idempotent.
* [x] Verify BLE-first then relay exact duplicate is idempotent.
* [x] Verify relay-first then BLE exact duplicate is idempotent.
* [x] Verify duplicate delivery through local, BLE and relay-equivalent source metadata does not alter semantics.
* [x] Verify conflicting duplicate payload is rejected regardless of source order.
* [x] Verify conflicting duplicate payload cannot overwrite:

  * durable event content;
  * action semantic content;
  * action fingerprint;
  * materialization state;
  * lifecycle;
  * latest pointers;
  * aggregate counts.
* [x] Verify exact and conflicting cross-source duplicates remain diagnostically distinct.

### Relay priority and TTL boundary

* [x] Verify existing relay priority behavior for canonical map events.
* [x] Verify existing relay priority behavior for dedicated actions.
* [x] Verify transport TTL does not overwrite map-domain expiry.
* [x] Verify transport TTL does not overwrite owner-edit cutoff.
* [x] Verify transport TTL does not overwrite interaction cutoff.
* [x] Verify relay receive time does not replace event timestamp.
* [x] Verify relay retry count does not become an ordering input.
* [x] Verify bridge direction does not become an ordering input.
* [x] Preserve the accepted expiry and interaction decisions inside the shared processor.

### Relay failure isolation

* [x] Verify relay push failure does not roll back an already committed local event.
* [x] Verify relay retry after local success remains idempotent.
* [x] Verify malformed relay event does not corrupt valid sibling relay events.
* [x] Verify relay bridge failure does not mutate canonical state.
* [x] Verify relay bridge failure does not mutate action history.
* [x] Verify relay bridge failure does not mutate domain projection.
* [x] Verify relay-attributed semantic rejection for one target/group does not corrupt accepted
  unrelated target/group state. M2D4 evidence:
  `M2D4DurableReplayInstrumentationTest.invalidTargetAndGroupDoNotCorruptValidSiblings` uses
  relay-attributed fixtures, typed per-event results and independent target/group snapshots. This
  is not claimed as relay bridge or target-scoped persistence failure evidence.
* [x] Preserve stable public processing diagnostics without exposing reducer internals. The same
  M2D4 case asserts public `MapEventProcessingResult` classifications; snapshots expose semantic
  rows only.

### M2D2 tests

* [x] Add targeted local-to-relay canonical tests.
* [x] Add targeted local-to-relay dedicated-action tests.
* [x] Add mesh-to-relay bridge tests.
* [x] Add relay-to-BLE bridge tests.
* [x] Add relay retry idempotency tests.
* [x] Add relay echo idempotency tests.
* [x] Add cross-source exact-duplicate matrix tests.
* [x] Add cross-source conflicting-duplicate matrix tests.
* [x] Add relay priority and transport-TTL boundary tests.
* [x] Add relay push-failure post-commit tests.
* [x] Add malformed relay sibling-isolation tests.
* [x] Verify existing non-map relay behavior remains unchanged.
* [x] Verify Room schema remains version 52.

### M2D2 final evidence audit

The following closes only the detailed claims proven by the existing M2D2 seams and the
accepted M2C processor suite.  It does not turn transport metadata into semantic input.

| Checklist theme | Evidence owner |
| --- | --- |
| Relay retry identity and immutable history | `RelayMapIdentityM2D2ContractTest.relayRetryProducesTheSameSemanticEventIdentity`; `MapMixedTransportDeduplicationM2D2InstrumentationTest.exactDuplicateMatrixAcrossLocalMeshAndRelayIsIdempotent`; `RoomMapEventProcessor.processInTransaction` / `classifyExistingDedicatedPair` classify a matching persisted event before reduction. |
| Acknowledgement aggregate idempotency | The processor's pre-reduction exact-duplicate boundary above; accepted M2C `RoomMapEventProcessorTest.repeatedAcknowledgementsCountDistinctActorsAndNotesDoNotChangeProjection`, which establishes the acknowledgement aggregate owner.  Therefore a relay-attributed exact retry or echo cannot reach that aggregate. |
| Conflicting retry non-destructiveness | `MapMixedTransportDeduplicationM2D2InstrumentationTest.conflictingDuplicateMatrixAcrossLocalMeshAndRelayIsNonDestructive`, including the relay-to-relay pair, plus the processor's immutable wire/fingerprint comparison. |
| Echo identity preservation | `RelayMapIdentityM2D2ContractTest.canonicalAndDedicatedMapEventsPreserveCompleteIdentityThroughRelayMapping`, `RelayPullManagerM2D2ContractTest.relayPullUsesRelaySourceAndPreservesMapIdentity`, and `CloudToBleBridgeM2D2ContractTest.bridgeReusesPersistedEventCoreWithoutSemanticMutation`; their returned cores enter the duplicate boundary above. |
| Action-history echo convergence | `MapMixedTransportDeduplicationM2D2InstrumentationTest.relayEchoDoesNotChangeActionHistoryOrProjection` and the identity/bridge owners above.  The local/mesh/relay source-pair matrix supplies each return-source attribution without re-proving reducer semantics. |
| TTL/domain temporal separation | `RelayMapIdentityM2D2ContractTest.relayTransportExpiryDoesNotMutateMapDomainTemporalFields`; accepted M2C `RoomMapEventProcessorTest.ownerAreaDeleteAtOwnerEditCutoffIsRejected`; and `RoomMapEventProcessor` as the sole request-to-reduction boundary.  Relay `exp` is transport-only while the preserved core supplies expiry, owner-edit, and interaction inputs. |
| Bridge failure isolation for the persisted map record | `CloudToBleBridge.bridgeableCore` is read/decode-only and `CloudToBleBridge.onEventInserted` only queues or logs after reading the persisted event; `CloudToBleBridgeM2D2ContractTest.bridgeEligibilityAndDecodeFailureCannotMutateCommittedMapState` and `RelayPushManagerM2D2ContractTest.pushFailureDoesNotAdvanceSemanticStateOrMutateCommittedEvents` cover the respective bridge/push failure seams. |
| Relay-attributed semantic rejection and public diagnostic stability | M2D4 `M2D4DurableReplayInstrumentationTest.invalidTargetAndGroupDoNotCorruptValidSiblings`: relay-attributed invalid target/group events retain typed public results while accepted unrelated target/group state remains intact. This does not claim relay bridge or target-scoped persistence failure isolation. |

M2D4 narrows the formerly deferred claim to the verified relay-attributed semantic-processing
boundary; it does not add relay-specific semantic behavior or claim a bridge failure boundary.

### M2D2 non-goals

* [x] Do not redesign relay payload formats.
* [x] Do not add a dedicated map-action relay endpoint.
* [x] Do not change map-event duplicate semantics.
* [x] Do not change action fingerprint generation.
* [x] Do not change canonical or action ordering.
* [x] Do not implement old-peer compatibility fixtures.
* [x] Do not implement durable-log rebuild.
* [x] Do not add per-peer delivery UI.
* [x] Do not add a Room migration.

### M2D2 acceptance

* [x] Canonical and dedicated map events remain eligible for existing relay policies.
* [x] Relay mapping preserves complete event/action identity and semantic payload.
* [x] Retry and echo cannot duplicate durable history or materialized state.
* [x] Cross-source exact duplicate delivery remains idempotent.
* [x] Cross-source conflicting duplicate delivery remains non-destructive.
* [x] Relay priority and transport TTL remain separate from map-domain semantics.
* [x] Relay failure cannot roll back already committed local persistence.
* [x] Existing non-map relay behavior remains unchanged.
* [x] No new relay protocol or semantic fork has been introduced.
* [x] Room schema remains version 52.
* [x] M2D2 is accepted with the M2D4 relay-attributed semantic-rejection and public-diagnostic
  isolation evidence described above; relay bridge/persistence target-failure isolation is not claimed.

---

## Phase M2D3 — Mixed-Version and Legacy/New Compatibility

### Goal

Verify that new and legacy peers can coexist without reinterpretation, canonical-state corruption or accidental conversion between legacy contributions and dedicated map actions.

M2D3 must verify compatibility through the existing wire, batching, persistence and processing paths. It must not introduce a new compatibility translation layer that changes event meaning.

### New peer receiving legacy canonical traffic

* [x] Verify a new peer accepts legacy canonical marker ADD.
* [x] Verify a new peer accepts legacy canonical marker UPD.
* [x] Verify a new peer accepts legacy canonical marker DEL.
* [x] Verify a new peer accepts legacy canonical area ADD.
* [x] Verify a new peer accepts legacy canonical area UPD.
* [x] Verify a new peer accepts legacy canonical area DEL.
* [x] Verify legacy canonical events without `map_domain_v` remain readable.
* [x] Verify legacy canonical events without explicit category remain readable.
* [x] Verify legacy canonical events without creator source remain readable.
* [x] Preserve existing confidence values.
* [x] Preserve existing owner confidence.
* [x] Preserve legacy contribution notes.
* [x] Preserve legacy votes.
* [x] Preserve legacy removal requests and cancellations.
* [x] Preserve legacy canonical clocks and tombstone state.
* [x] Do not reinterpret legacy confidence as information source.
* [x] Do not reinterpret legacy contribution as confirmation.
* [x] Do not reinterpret legacy contribution as acknowledgement.
* [x] Do not reinterpret legacy removal state as `no_longer_active`.

### Old peer receiving domain-aware canonical traffic

* [x] Verify an old peer can read known canonical marker fields from domain-aware marker ADD.
* [x] Verify an old peer can read known canonical area fields from domain-aware area ADD.
* [x] Verify an old peer can read known canonical fields from domain-aware canonical UPD.
* [x] Verify optional domain extensions do not replace required legacy canonical fields.
* [x] Verify old peers may ignore unknown optional canonical extensions safely.
* [x] Verify domain-aware canonical ADD does not require old-peer understanding of:

  * `map_domain_v`;
  * `item_category`;
  * `information_source`.
* [x] Verify an old peer does not need to materialize new domain state to retain canonical marker/area state.
* [x] Verify domain-aware canonical traffic does not corrupt legacy confidence storage.
* [x] Verify domain-aware canonical traffic does not create legacy contribution rows implicitly.

### Old peer receiving dedicated action traffic

* [x] Verify an old peer rejects or ignores unknown dedicated action event types safely according to existing behavior.
* [x] Verify unknown dedicated action rejection does not corrupt canonical marker state.
* [x] Verify unknown dedicated action rejection does not corrupt canonical area state.
* [x] Verify unknown dedicated action rejection does not alter confidence.
* [x] Verify unknown dedicated action rejection does not alter legacy contribution history.
* [x] Verify unknown dedicated action rejection does not alter legacy removal state.
* [x] Verify an unknown dedicated action does not cause accepted canonical siblings in the same batch to be lost.
* [x] Verify an unknown dedicated action does not cause accepted non-map siblings in the same batch to be lost.
* [x] Verify a new peer does not disguise dedicated actions as canonical UPD for old-peer acceptance.
* [x] Document that an old peer may not forward an action event it cannot decode or classify.
* [x] Keep capability-gated action UI rollout assigned to M3.

### Legacy and new history coexistence

* [x] Verify through production persistence paths that one item may contain:

  * legacy rows in `map_item_votes`;
  * new rows in `map_item_actions`.
* [x] Keep legacy contribution history separate from dedicated action history.
* [x] Keep legacy contribution notes separate from Coordination notes.
* [x] Keep legacy contribution confidence separate from Situation confirmations.
* [x] Exclude legacy contributions from confirmation actor counts.
* [x] Exclude legacy contributions from acknowledgement actor counts.
* [x] Exclude acknowledgements from Situation verification counts.
* [x] Exclude Situation confirmations from Coordination acknowledgement counts.
* [x] Keep creator legacy confidence independent from new creator information source.
* [x] Keep legacy removal state independent from Situation lifecycle.
* [x] Do not backfill new actions from legacy history.
* [x] Do not rewrite legacy rows when a new action is accepted.

### Recognized legacy item behavior

* [x] Verify recognized active legacy Situation markers can accept allowed dedicated actions.
* [x] Verify recognized active legacy Situation areas can accept allowed dedicated actions.
* [x] Verify recognized active legacy Coordination markers can accept allowed dedicated actions.
* [x] N/A: the accepted kind catalog has no supported legacy Coordination-area kind; unsupported legacy kinds remain unresolved and reject new actions safely.
* [x] Verify category is derived only through the accepted `MapItemKindCatalog`.
* [x] Verify unsupported or unknown legacy kinds remain unresolved.
* [x] Verify new actions on unresolved legacy category are rejected safely.
* [x] Verify creator information source remains unspecified for legacy-created items.
* [x] Verify legacy TTL-derived temporal windows continue to govern accepted actions.
* [x] Verify legacy no-expiry items preserve no-expiry compatibility.
* [x] Verify deleted legacy items cannot receive new applied actions.

### Mixed-version batch behavior

* [x] Verify legacy canonical and new canonical events can coexist in one supported APP batch.
* [x] Verify legacy canonical and dedicated action events can coexist in one supported APP batch.
* [x] Verify an old-peer rejection of one dedicated action does not corrupt valid legacy siblings.
* [x] Verify a new peer accepts valid legacy siblings when another dedicated action is malformed.
* [x] Verify mixed-version duplicate delivery remains idempotent.
* [x] Verify mixed-version conflicting duplicate delivery remains non-destructive.
* [x] Verify event ID is preserved across old/new peer transport paths.
* [x] Verify action ID remains equal to event ID on every new peer that persists the action.

### Compatibility documentation

* [x] Document which canonical extensions old peers may ignore.
* [x] Document that dedicated action event types may be unknown to old peers.
* [x] Document that an old peer may reject, drop or fail to forward an unknown action.
* [x] Document that dedicated actions are never translated into legacy UPD contributions.
* [x] Document that legacy contribution history remains visible through legacy behavior only.
* [x] Document that mixed-version peers may have different action-history visibility while retaining canonical-state safety.
* [x] Document that capability-gated interaction controls belong to M3.
* [x] Document that mixed-version limitations do not justify changing append-only identity semantics.

### M2D3 tests

* [x] Add legacy canonical marker compatibility tests.
* [x] Add legacy canonical area compatibility tests.
* [x] Add legacy confidence preservation tests.
* [x] Add legacy contribution preservation tests.
* [x] Add legacy removal preservation tests.
* [x] Add domain-aware canonical old-peer fixture tests.
* [x] Add unknown dedicated-action old-peer failure-safety tests.
* [x] Add mixed legacy/new APP-batch sibling-isolation tests.
* [x] Add legacy/new history coexistence tests.
* [x] Add recognized legacy Situation action tests.
* [x] Add recognized legacy Coordination action tests.
* [x] Add unresolved legacy category rejection tests.
* [x] Add deleted legacy target action rejection tests.
* [x] Verify existing canonical UI queries remain unchanged.
* [x] Verify Room schema remains version 52.

### M2D3 non-goals

* [x] Do not add a dedicated compatibility translation table.
* [x] Do not convert legacy contributions into dedicated actions.
* [x] Do not convert dedicated actions into legacy contributions.
* [x] Do not convert legacy confidence into information source.
* [x] Do not convert removal requests into Situation lifecycle.
* [x] Do not force old peers to understand dedicated action types.
* [x] Do not change old-peer forwarding behavior unless an existing bug corrupts accepted traffic.
* [x] Do not add capability-gated UI in M2D.
* [x] Do not implement durable-log rebuild.
* [x] Do not add a Room migration.

### M2D3 acceptance

* [x] New peers accept and preserve legacy canonical state.
* [x] New peers do not reinterpret legacy confidence, contributions or removal state.
* [x] Old peers retain canonical marker/area safety when receiving domain-aware canonical events.
* [x] Unknown dedicated actions fail safely on old-peer paths.
* [x] One rejected unknown action cannot corrupt valid siblings.
* [x] Legacy and dedicated action histories coexist without semantic merging.
* [x] Recognized active legacy items can accept category-correct new actions.
* [x] Unresolved legacy categories reject new actions safely.
* [x] Mixed-version limitations are documented explicitly.
* [x] Capability-gated UI remains assigned to M3.
* [x] Room schema remains version 52.
* [x] M2D3 is reviewed and accepted before durable replay and final closure begins.

---

## Phase M2D4 — Durable Replay, Failure Closure and M2 Final Acceptance

### Goal

Verify deterministic reconstruction from durable event history, close remaining cross-boundary failure gaps, audit all M2 evidence and accept Phase M2 as the stable persistence, reducer, ingest and transport baseline for Phase M3.

M2D4 must first audit whether the project already contains a production replay or rebuild framework.

Unless source inspection proves that an existing production rebuild framework must be extended, M2D4 should implement:

* deterministic replay and rebuild verification harnesses;
* reusable replay seams required by those tests;
* no user-facing rebuild API;
* no automatic startup rebuild;
* no destructive production-state reset workflow;
* no new maintenance subsystem.

If a production rebuild subsystem is required, its scope and safety contract must be reviewed separately before implementation.

### Replay authority contract

* [x] Define the durable event log as the replay input authority.
* [x] Define canonical serialized event content as immutable replay input.
* [x] Preserve original event ID.
* [x] Preserve original actor DID.
* [x] Preserve original event timestamp.
* [x] Preserve original Lamport time.
* [x] Preserve original target identity.
* [x] Preserve original dedicated action semantic content.
* [x] Do not use Room row ID as replay ordering.
* [x] Do not use current table insertion order as replay ordering.
* [x] Do not use transport arrival order as replay ordering.
* [x] Do not use replay invocation time as semantic event time.
* [x] Define replay-time authority explicitly.
* [x] Ensure temporal validation during rebuild uses the accepted event-time and target-window contract rather than wall-clock rebuild time.
* [x] Do not mutate signed or canonical event content during replay.

### Replay scope and isolation

* [x] Define whether replay operates:

  * per group;
  * per exact target;
  * through a deterministic test fixture;
  * through an existing production replay service.
* [x] Keep groups isolated.
* [x] Keep marker and area target identity explicit.
* [x] Prevent replay of one invalid target from corrupting unrelated target state.
* [x] Prevent replay of one invalid group from corrupting unrelated groups.
* [x] Preserve per-event failure classification.
* [x] Preserve append-only event and action identity.
* [x] Do not invent missing legacy action history.
* [x] Do not replay `map_item_votes` as dedicated actions.

### Canonical state rebuild

* [x] Add deterministic canonical-state replay tests from durable events.
* [x] Verify canonical marker state rebuild uses the accepted canonical ordering contract.
* [x] Verify canonical area state rebuild uses the accepted canonical ordering contract.
* [x] Verify canonical state rebuild does not depend on Room row ID.
* [x] Verify canonical state rebuild does not depend on durable row insertion order.
* [x] Verify deterministic rebuild produces equivalent canonical state for tested permutations containing the same accepted event set.
* [x] Verify ownership is re-established from accepted canonical ADD.
* [x] Verify non-owner canonical mutation cannot become accepted during rebuild.
* [x] Verify canonical tombstones remain effective after rebuild.
* [x] Verify later canonical UPD cannot revive a deleted target.
* [x] Verify marker/area target-type conflict remains rejected.
* [x] Verify legacy canonical event compatibility remains intact during rebuild.

### Dedicated action rebuild

* [x] Add deterministic action-state replay tests from durable events.
* [x] Verify action identity rebuild uses event ID as action ID.
* [x] Verify action fingerprint rebuild is deterministic.
* [x] Verify action state rebuild does not depend on Room row ID.
* [x] Verify action state rebuild does not depend on action insertion order.
* [x] Verify applied action history remains append-only.
* [x] Verify pending action history remains immutable until guarded transition.
* [x] Verify rejected action history remains terminal.
* [x] Verify exact duplicate replay is idempotent.
* [x] Verify conflicting duplicate replay cannot overwrite the first action.
* [x] Verify same ordering clock with different event IDs uses event ID as final tie-break.
  Evidence: `M2D4DurableReplayInstrumentationTest.sameCompleteOrderingClockUsesEventIdAsFinalTieBreak`
  uses the same timestamp, Lamport time and actor DID, reverses replay order, and selects
  `confirm-z` solely by event ID.
* [x] Verify repeated actions by the same actor remain historical actions while aggregates count distinct actors.

### Pending-target replay during rebuild

* [x] Verify action-before-marker replay recovers after marker creation.
* [x] Verify action-before-area replay recovers after area creation.
* [x] Verify target-before-action replay produces equivalent semantic outcome.
* [x] Verify opposite pending arrival orders converge.
* [x] Verify replay uses the complete accepted action comparator.
* [x] Verify event ID remains the final action-order tie-break. Evidence:
  `M2D4DurableReplayInstrumentationTest.sameCompleteOrderingClockUsesEventIdAsFinalTieBreak`.
* [x] Verify pending rows contribute nothing before transition to applied.
* [x] Verify rejected pending rows contribute nothing to projection.
* [x] Verify still-missing target actions remain pending in verification fixtures.
* [x] Do not reject still-missing targets solely because rebuild is running.
* [x] Do not infer orphan permanence from replay count or age.

### Lifecycle rebuild

* [x] Verify earliest accepted resolution remains the irreversible lifecycle cutoff after replay.
* [x] Verify a normal Situation action ordered before resolution remains valid historical action.
* [x] Verify a normal Situation action ordered at or after resolution remains rejected.
* [x] Verify repeated resolution actions remain immutable history.
* [x] Verify latest lifecycle pointer uses the complete accepted comparator.
* [x] Verify replay cannot reactivate a resolved Situation.
* [x] Verify canonical UPD cannot reactivate a resolved Situation.
* [x] Verify deleted Situation items do not regain interaction state.
* [x] Verify Coordination items never acquire Situation lifecycle.

### Latest-pointer rebuild

* [x] Verify latest confirmation pointer rebuild.
* [x] Verify latest Situation update pointer rebuild.
* [x] Verify latest lifecycle pointer rebuild.
* [x] Verify latest material-change pointer rebuild.
* [x] Verify every pointer uses:

  * action timestamp;
  * Lamport time;
  * actor DID;
  * event ID.
* [x] Verify partially populated pointer state fails safely.
* [x] Verify stale historical actions remain stored without replacing newer pointers.
* [x] Verify opposite replay orders produce identical pointers.

### Aggregate rebuild

* [x] Verify current confirmation actor count rebuilds from applied history.
* [x] Verify repeated confirmations by one actor count once.
* [x] Verify current confirmation count respects the latest material-change boundary.
* [x] Verify `situation_changed` invalidates prior confirmation freshness.
* [x] Verify `still_active` does not invalidate confirmation freshness.
* [x] Verify resolution invalidates current verification without deleting historical confirmations.
* [x] Verify resolved items cannot appear currently verified solely from historical confirmations.
* [x] Verify acknowledgement actor count rebuilds from applied history.
* [x] Verify repeated acknowledgements by one actor count once.
* [x] Verify acknowledgement counts remain independent from confirmation counts.
* [x] Verify legacy contributions remain excluded from both new aggregate types.
* [x] Verify exact duplicate replay does not change aggregate counts.
* [x] Verify opposite replay orders produce identical aggregates.

### Expiry and temporal rebuild behavior

* [x] Define temporal evaluation authority for replay explicitly.
* [x] Verify rebuild invocation time is not substituted for immutable event time.
* [x] Verify actions accepted historically under the contract are not rejected merely because rebuild occurs later.
* [x] Verify target expiry remains authoritative for current materialized availability.
* [x] Verify expired targets do not regain active interaction state.
* [x] Verify owner-edit cutoff remains separate from interaction cutoff.
* [x] Verify exact expiry boundaries remain exclusive.
* [x] Verify exact interaction-cutoff boundaries remain exclusive.
* [x] Verify an action ordered before and delivered after resolution remains classified by action order.
* [x] Verify an action ordered after resolution but delivered first remains rejected after complete replay.

### Cross-boundary failure evidence and regression gaps

* [x] Audit accepted M2C evidence before adding duplicate regression tests.
* [x] Reuse accepted M2C evidence where the same production boundary is already exercised.
* [x] Reuse accepted M2D1 evidence for APP-batch and envelope failure paths.
* [x] Reuse accepted M2D2 evidence for relay retry, echo and bridge failures.
* [x] Reuse accepted M2D3 evidence for mixed-version decoding failures.
* [x] Add new tests only where durable replay or a distinct cross-boundary path introduces new behavior.

### Required failure matrix

* [x] Verify local persistence success followed by BLE enqueue failure.
* [x] Verify local persistence success followed by relay push failure.
* [x] Verify relay retry remains idempotent.
* [x] Verify relay echo remains idempotent.
* [x] Verify crash or rollback during durable event plus action materialization leaves no partial state.
* [x] Verify malformed known action payload fails safely.
* [x] Verify unknown dedicated action type fails safely.
* [x] Verify unknown map-domain version fails safely.
* [x] Verify unknown optional fields remain tolerated for supported versions.
* [x] Verify wrong item type for an existing target fails safely.
* [x] Verify duplicate action ID with different payload is non-destructive.
* [x] Verify same actor repeating the same action remains deterministic.
* [x] Verify same ordering clock with different event IDs remains deterministic.
* [x] Verify removed-member action fails transactionally.
* [x] Verify action at exact expiry boundary is rejected.
* [x] Verify action at exact interaction-cutoff boundary is rejected.
* [x] Verify action ordered before and delivered after resolution remains historical and valid where otherwise allowed.
* [x] Verify action ordered after resolution but delivered first remains rejected after complete ordering.
* [x] Verify actions targeting deleted items fail safely.
* [x] Verify actions targeting expired items fail safely.
* [x] Verify normal actions targeting resolved Situations fail safely.
* [x] Verify actions targeting unknown items remain pending according to the accepted contract.
* [x] Verify one target replay failure does not corrupt valid sibling targets.
* [x] Verify one group replay failure does not corrupt valid sibling groups.

### Orphan retention deferral

* [x] Do not introduce orphan pruning in M2D.
* [x] Do not reject still-missing targets solely because of age.
* [x] Do not delete pending action history.
* [x] Do not use Room row ID as an arrival-time authority.
* [x] Do not use event timestamp as a transport-arrival authority.
* [x] Document that any future orphan-retention policy requires separate review covering:

  * retention duration;
  * offline synchronization delay;
  * clock skew;
  * persisted arrival-time authority;
  * device clock trust;
  * rejection-versus-deletion behavior;
  * forensic and history requirements;
  * cross-device convergence.

### M2 final compatibility review

* [x] Confirm existing UI compiles without adopting M3 interaction UI.
* [x] Confirm current marker creation flow remains available.
* [x] Confirm current area creation flow remains available.
* [x] Confirm no user-facing vocabulary migration was pulled into M2.
* [x] Confirm no Situation or Coordination interaction controls were added.
* [x] Confirm no Community Verification presentation was added.
* [x] Confirm no normal map rendering redesign was introduced.
* [x] Confirm no destructive durable-event-history rewrite occurred.
* [x] Confirm Room migration 51→52 remains accepted.
* [x] Confirm exported Room schema 52 remains accepted.
* [x] Confirm canonical owner protection is enforced locally and remotely.
* [x] Confirm membership restrictions are enforced locally and remotely.
* [x] Confirm canonical temporal restrictions are enforced locally and remotely.
* [x] Confirm action history remains append-only and deterministic.
* [x] Confirm duplicate delivery remains idempotent across local, BLE and relay paths.
* [x] Confirm conflicting duplicate delivery remains non-destructive across all tested paths.
* [x] Confirm verification aggregates remain Situation-only and freshness-aware.
* [x] Confirm acknowledgement aggregates remain Coordination-only.
* [x] Confirm legacy contributions remain separate from all new aggregates.
* [x] Confirm resolved items cannot appear currently verified from historical confirmations.
* [x] Confirm pending-target recovery remains deterministic.
* [x] Confirm BLE paths preserve dedicated action identity.
* [x] Confirm relay paths preserve dedicated action identity.
* [x] Confirm mixed-version limitations are documented.
* [x] Confirm orphan retention has not been implemented prematurely.
* [x] Confirm no blocking technical findings remain.
* [x] Accept Phase M2 as the stable persistence, reducer, ingest and transport baseline for Phase M3.

### Roadmap evidence audit

* [x] Audit M2A acceptance against current implementation and tests.
* [x] Audit M2B1 acceptance against current implementation, migration tests and schema 52.
* [x] Audit M2B2A acceptance against current persistence contracts and DAO tests.
* [x] Audit M2B2B acceptance against current deterministic query and projection tests.
* [x] Audit M2C1 acceptance against canonical enforcement evidence.
* [x] Audit M2C2 acceptance against action persistence and projection evidence.
* [x] Audit M2C3 acceptance against pending recovery and convergence evidence.
* [x] Audit M2C4 acceptance against production local/remote integration evidence.
* [x] Audit M2D1 acceptance against BLE batch and envelope evidence.
* [x] Audit M2D2 acceptance against relay and mixed-transport evidence.
* [x] Audit M2D3 acceptance against mixed-version compatibility evidence.
* [x] Audit M2D4 replay and failure evidence.
* [x] Tick roadmap items only when implementation and test evidence exist.
* [x] Do not tick an item based only on architecture intent or skeleton presence.
* [x] Record all remaining manual presentation checks as non-blocking where appropriate.
* [x] Record all intentionally deferred work under M3 or a separately accepted maintenance phase.

### M2D4 implementation evidence audit (2026-07-18)

M2D4 uses a fresh-database, test-only replay graph and never introduces a production rebuild
subsystem. `RoomMapEventProcessor` remains the sole transaction owner. Snapshot equality excludes
Room row IDs, insertion/source metadata and wall-clock invocation time. Detailed architecture and
the exact fixture-to-test map are recorded in `M2D4_ARCHITECTURE_SKELETON.md`.

| M2D4 evidence theme | Evidence owner |
| --- | --- |
| Durable adapter and identity/content preservation | `M2D4DurableReplayInstrumentationTest.durableFixtureAdapterPreservesExactSerializedAuthority` and `DurableMapReplayFixture.from` |
| Marker/area ADD, UPD, DEL, stale ordering and permutation convergence | `.canonicalMarkerAndAreaReplayIsDeterministicAcrossDurableRowPermutations` |
| All dedicated action families, append-only history, pointers and distinct actor aggregates | `.dedicatedActionsAndProjectionRebuildAcrossPermittedOppositeOrders` |
| Pending/target convergence, repeated resolution and earliest irreversible cutoff | `.actionBeforeTargetAndTargetBeforeActionConvergeWithEarliestResolutionCutoff` |
| Material-change freshness | `.materialChangeFreshnessRebuildsDeterministically` |
| Complete final event-ID tie-break | `.sameCompleteOrderingClockUsesEventIdAsFinalTieBreak` uses competing actions with the same timestamp, Lamport, and actor DID; only event ID differs. |
| Temporal authority and exclusive expiry | `.replayUsesImmutableEventTimeNotInvocationOrInsertionWallClock`; accepted `RoomMapEventProcessorTest` exact interaction cutoff and owner-edit/expiry cases |
| Exact/conflicting duplicate immutability | `.exactAndConflictingDuplicateReplayIsIdempotentAndNonDestructive` |
| Marker/area same raw ID isolation | `.markerAndAreaWithSameRawIdRemainIsolated` proves the opposite-type ADD/action are rejected without colliding with the first target |
| Target/group and relay-attributed diagnostic isolation | `.invalidTargetAndGroupDoNotCorruptValidSiblings` captures per-event typed results and independent group snapshots |
| Failure after durable insertion/canonical mutation / before domain completion | `.domainInsertFailureRollsBackDurableEventAndCanonicalMutation` directly asserts the durable event lookup is null, alongside zero canonical/domain/action state. |
| Failure after durable/action insertion / before projection completion | `.actionOrProjectionFailureRollsBackDurableAndActionWrites` |
| Pending retention and legacy-vote exclusion | `.stillMissingTargetRemainsPendingAndLegacyVotesAreNotDedicatedActions` |
| Pending replay corruption and guarded transition rollback | accepted `RoomMapEventProcessorTest.canonicalAddRollsBackWhenPendingActionHasNoDurableEvent`, `.invalidPendingPairRollsBackEarlierSiblingTransition`, and projection invariant tests |
| Pre-processor failure | `.failureBeforeProcessorInvocationDoesNotEnterProductionPath` and `M2D4FailureHook.beforeProcessor`; the isolated DB is closed without entering production processing |
| BLE/batch and post-commit failure boundaries | accepted M2D1 suite plus `LocalMapPostCommitTransportTest` |
| Relay retry, echo, bridge and post-commit failure boundaries | accepted M2D2 relay contract/instrumentation suites plus the relay-attributed M2D4 isolation case |
| Mixed-version malformed/legacy isolation | accepted M2D3 compatibility suite and `M2D3_MIXED_VERSION_COMPATIBILITY.md` |
| Schema | `.schemaRemainsVersion52`, `AppDatabase` version audit and unchanged exported `52.json` |

Still-missing targets remain pending. No arrival-time authority exists for safe age-based orphan
classification, so retention/pruning remains deferred to a separately reviewed policy. External
architecture/evidence review and its acceptance checkbox remain intentionally open.

### M2D4 tests and verification

* [x] Add deterministic canonical replay fixtures.
* [x] Add deterministic action replay fixtures.
* [x] Add action-before-target replay fixtures.
* [x] Add opposite-order replay convergence fixtures.
* [x] Add lifecycle and earliest-resolution replay fixtures.
* [x] Add latest-pointer rebuild fixtures.
* [x] Add aggregate rebuild fixtures.
* [x] Add temporal-authority replay fixtures.
* [x] Add deleted/expired/resolved target replay fixtures. Evidence combines the focused expiry,
  resolution and canonical tombstone fixtures with accepted `RoomMapEventProcessorTest` deleted,
  expired and resolved-target cases; the same production processor owns both.
* [x] Add cross-boundary failure-gap tests.
* [x] Run focused core tests.
* [x] Run focused data-layer unit tests.
* [x] Run Room instrumentation tests.
* [x] Run transport batch tests.
* [x] Run relay bridge tests.
* [x] Run mixed-version compatibility tests.
* [x] Run full connected device or emulator verification where required.
* [x] Verify Room schema remains version 52.
* [x] Complete external architecture and evidence review.

### M2D4 non-goals

* [x] Do not create a production rebuild subsystem unless separately reviewed after source inspection.
* [x] Do not automatically rebuild state at app startup.
* [x] Do not expose a user-facing rebuild action.
* [x] Do not destructively reset production state for verification.
* [x] Do not introduce orphan pruning or retention.
* [x] Do not change accepted reducer semantics.
* [x] Do not change duplicate classification.
* [x] Do not change action ordering.
* [x] Do not change earliest-resolution behavior.
* [x] Do not add M3 UI.
* [x] Do not add a Room migration without separate approval.

### M2D4 acceptance

* [x] Durable canonical replay is deterministic.
* [x] Durable action replay is deterministic.
* [x] Rebuild does not depend on Room row ID or insertion order.
* [x] Replay invocation time is not used as semantic event time.
* [x] Action-before-target and target-before-action histories converge for the same accepted durable
  history; live semantic rejection remains intentionally outside accepted durable history.
* [x] Earliest resolution remains irreversible after replay.
* [x] Lifecycle, latest pointers and aggregates rebuild from immutable applied history.
* [x] Deleted and expired targets do not regain active interaction state.
* [x] Cross-boundary failures leave no partial canonical, action or domain state.
* [x] Accepted M2C evidence is reused rather than duplicated unnecessarily.
* [x] BLE, relay and mixed-version limitations are documented.
* [x] No orphan-retention policy has been implemented prematurely.
* [x] No production rebuild subsystem has been introduced without separate review.
* [x] Room schema remains version 52.
* [x] No blocking findings remain.
* [x] M2D4 is reviewed and accepted before overall M2D and M2 acceptance.

---

### M2D overall non-goals

* [x] Do not introduce new map-domain semantics.
* [x] Do not redesign canonical or action reducers.
* [x] Do not introduce a second map-event transaction owner.
* [x] Do not change duplicate classification.
* [x] Do not change deterministic canonical or action ordering.
* [x] Do not change earliest-resolution cutoff behavior.
* [x] Do not change pending-target replay semantics.
* [x] Do not redesign BLE payload protocols.
* [x] Do not redesign relay payload protocols.
* [x] Do not translate dedicated actions into legacy canonical UPD events.
* [x] Do not reinterpret legacy contributions as dedicated actions.
* [x] Do not implement orphan retention or pruning.
* [x] Do not implement M3 interaction UI.
* [x] Do not implement Community Verification presentation.
* [x] Do not redesign normal map rendering.
* [x] Do not add a Room migration without separately accepted evidence.

### M2D overall acceptance

* [x] M2D1 BLE batch and envelope compatibility is accepted.
* [x] M2D2 relay bridging and mixed-transport deduplication is accepted.
* [x] M2D3 mixed-version and legacy/new compatibility is accepted.
* [x] M2D4 durable replay and failure closure is accepted.
* [x] Dedicated map actions use the existing BLE batch lane.
* [x] APP-batch sibling isolation is proven.
* [x] Envelope paths preserve complete event/action identity.
* [x] Relay bridge paths preserve complete event/action identity.
* [x] Relay retry and echo remain idempotent.
* [x] Exact duplicates remain idempotent across local, BLE and relay sources.
* [x] Conflicting duplicates remain non-destructive across all tested sources.
* [x] New peers preserve legacy canonical and contribution semantics.
* [x] Old-peer unknown-action failure remains canonical-state safe.
* [x] Legacy and new action histories coexist without reinterpretation.
* [x] Durable replay is deterministic and independent from Room row ID.
* [x] Replay-time authority is explicit and does not use rebuild invocation time as semantic event time.
* [x] Pending-target recovery converges during replay.
* [x] Lifecycle, latest pointers and aggregates rebuild correctly.
* [x] Resolved items cannot appear currently verified from stale historical confirmations.
* [x] Deleted and expired targets cannot regain active interaction state.
* [x] Cross-boundary failure scenarios leave no partial state.
* [x] Mixed-version limitations are documented.
* [x] Orphan retention remains deferred.
* [x] Room schema remains version 52.
* [x] No blocking technical findings remain.
* [x] M2D is accepted before final Phase M2 acceptance.

---

### Roadmap closure audit (2026-07-18)

* [x] Technical checklist items are closed only where the accepted implementation, test evidence, or phase evidence audit supplies an owner.
* [x] Historical M2C deferrals to M2D/M2D4 are closed by the accepted M2D transport and durable-replay evidence, not by M2C intent alone.
* [x] Umbrella non-goals are recorded as verified boundaries rather than open work.
* [x] The only intentionally unticked items are the two explicit, non-blocking manual smoke checks for drafting and UI presentation/local feedback.

## Phase M2 final acceptance

* [x] M2A wire contracts are accepted.
* [x] M2B persistence, migration, DAO and read-model contracts are accepted.
* [x] M2C reducer, ingest, pending-recovery and local/remote integration contracts are accepted.
* [x] M2D BLE, relay, mixed-version and replay compatibility contracts are accepted.
* [x] Canonical map event types remain backward-compatible.
* [x] Dedicated actions retain stable event/action identity.
* [x] Durable event history remains append-only.
* [x] Dedicated action history remains immutable and append-only.
* [x] Canonical state remains deterministic.
* [x] Action-derived state remains deterministic.
* [x] Pending-target recovery remains deterministic.
* [x] Duplicate delivery remains idempotent.
* [x] Conflicting duplicate delivery remains non-destructive.
* [x] Canonical owner and membership enforcement are authoritative outside UI.
* [x] Expiry, owner-edit and interaction windows remain independently enforced.
* [x] Situation lifecycle remains irreversible after accepted resolution.
* [x] Confirmation freshness is invalidated after material Situation change.
* [x] Acknowledgements remain independent from verification.
* [x] Legacy confidence, contributions and removal state remain preserved.
* [x] BLE and relay paths preserve complete dedicated-action identity.
* [x] Mixed-version limitations are documented.
* [x] Deterministic replay does not depend on Room row ID, insertion order or rebuild invocation time.
* [x] Room schema version remains 52.
* [x] No M3 presentation or interaction behavior has been pulled into M2.
* [x] No blocking findings remain.
* [x] Phase M2 is accepted as the stable persistence, reducer, ingest and transport baseline for Phase M3.

---

## Phase M2 out of scope

- [x] N/A for Phase M2: Situation/Coordination creation UI.
- [x] N/A for Phase M2: Creator source selector UI.
- [x] N/A for Phase M2: Confirm dialog.
- [x] N/A for Phase M2: Add update dialog.
- [x] N/A for Phase M2: Update status dialog.
- [x] N/A for Phase M2: Acknowledge UI.
- [x] N/A for Phase M2: Coordination note UI.
- [x] N/A for Phase M2: Community verification presentation.
- [x] N/A for Phase M2: Removal of old Confidence/Contribute terminology.
- [x] N/A for Phase M2: General map screen redesign.
- [x] N/A for Phase M2: Marker creation toolbar redesign.
- [x] N/A for Phase M2: Raw GeoJSON cleanup.
- [x] N/A for Phase M2: Label-length cleanup.
- [x] N/A for Phase M2: Per-peer delivery UI.
- [x] N/A for Phase M2: Moderation subsystem.
- [x] N/A for Phase M2: Kind-specific actions such as “I’m here” or “I can help”.
- [x] N/A for Phase M2: Unrelated BLE traffic-governance refactor.
