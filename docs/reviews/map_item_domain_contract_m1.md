# Map Item Domain Contract — Phase M1

## Status and phase boundary

This document locks the corrected Phase M1 domain vocabulary and compatibility foundation for private-group map markers and areas.

Phase M1 does not change production event serialization, Room schema, reducers, ingest, BLE/relay behavior, or user-facing UI. Existing Reported, Observed, Confidence, Contribute, and removal-request behavior remains unchanged until its approved later phase.

## Kind inventory and category mapping

`MapItemKindCatalog` is the single source of truth for canonical categories and legacy-key normalization. Unknown kinds return `null`; no category is guessed.

| Kind key | Current label | Marker | Area | Category |
|---|---|---:|---:|---|
| `obstacle` | Blocked path | Yes | Yes | Situation |
| `crowded` | Crowded area | Yes | Yes | Situation |
| `risk` | Potential risk | Yes | Yes | Situation |
| `hot_zone` | Hot zone | Yes | Yes | Situation |
| `incident` | Ongoing disruption | Yes | Yes | Situation |
| `medical` | Medical help needed | Yes | Yes | Situation |
| `resource` | Utility point | Yes | Yes | Situation |
| `route_note` | Route observation | Yes | No | Situation |
| `environment` | Environmental conditions | Yes | Yes | Situation |
| `info` | Observation note | Yes | No | Situation |
| `checkpoint` | Checkpoint | Yes | No | Situation |
| `road_closed` | Road closed | Yes | Yes | Situation |
| `traffic_jam` | Traffic congestion | Yes | Yes | Situation |
| `power_outage` | Power outage | Yes | Yes | Situation |
| `connectivity` | Connectivity point | Yes | No | Situation |
| `steep` | Steep terrain | Yes | Yes | Situation |
| `water_crossing` | Water crossing | Yes | Yes | Situation |
| `wildlife` | Wildlife presence | Yes | Yes | Situation |
| `camp` | Campsite | Yes | No | Coordination |
| `rescue` | Rescue needed | Yes | Yes | Situation |
| `waypoint` | Waypoint | Yes | No | Coordination |

Classification decisions:

- `resource` is Situation because utility, food/water, or shelter availability can change and requires confirmation/update semantics.
- `connectivity` is Situation because connectivity availability is an observed field condition that can disappear or change.
- `checkpoint` is Situation because the current checkpoint label/icon represents an external field control point; `waypoint` already represents deliberate group navigation.
- `camp` and `waypoint` remain Coordination because they represent intentional group/navigation locations.
- `medical` and `rescue` remain Situation because their current labels describe a field need or condition.

Legacy normalization is centralized:

| Legacy key | Canonical key | Category |
|---|---|---|
| `danger` | `risk` | Situation |
| `blocked_path` | `obstacle` | Situation |
| `safe` | `info` | Situation |
| `food_water` | `resource` | Situation |
| `shelter` | `resource` | Situation |

`MapKinds.normalizeKey()` and `MapKinds.labelForKey()` delegate normalization to `MapItemKindCatalog`. The app does not keep a second alias map.

## Information source

Information source answers “How do you know about this?” It is not confidence.

| Domain value | Stable wire value | M3 wording contract |
|---|---|---|
| `FIRSTHAND` | `firsthand` | I saw it myself / Seen firsthand |
| `REPORTED_BY_OTHERS` | `reported_by_others` | Someone told me / Reported by others |
| `UNCERTAIN` | `uncertain` | I’m not sure / Source uncertain |

Unknown wire values parse to `null`. Enum names must not be serialized as wire values.

### Source requirement matrix

`MapInformationSourcePolicy` is the centralized enforcement seam.

| Category | Operation | Requirement |
|---|---|---|
| Situation | Create | Required |
| Situation | Confirm | Optional |
| Situation | Add update | Optional |
| Situation | Update status | Optional |
| Situation | Acknowledge | Invalid operation |
| Situation | Add note | Invalid operation |
| Coordination | Create | Forbidden |
| Coordination | Confirm | Invalid operation |
| Coordination | Add update | Invalid operation |
| Coordination | Update status | Invalid operation |
| Coordination | Acknowledge | Forbidden |
| Coordination | Add note | Forbidden |

“Forbidden” means source must be absent. “Invalid operation” means the category/action pairing itself is invalid, regardless of source.

Confirm, Add update, and Update status accept an optional source because the action may be operational/contextual or may not require a new factual-source claim. A No longer active status action does not require source or note at the M1 domain level. M2 may validate additional content rules only through a reviewed contract amendment; it must not silently make source mandatory.

## Actions and stable identity

Stable action wire values are:

| Action | Wire value | Category |
|---|---|---|
| `CONFIRM` | `confirm` | Situation |
| `ADD_UPDATE` | `add_update` | Situation |
| `UPDATE_STATUS` | `update_status` | Situation |
| `ACKNOWLEDGE` | `acknowledge` | Coordination |
| `ADD_NOTE` | `add_note` | Coordination |

Unknown action values parse to `null`. `MapItemActionPolicy` owns category/action compatibility.

Every action record contains `MapActionMetadata`:

- nonblank `actionId`;
- nonblank `itemId`;
- nonblank `actorDid`;
- positive `timestampSeconds`;
- non-negative `eventLamport`.

`actionId` is a stable identifier and is never derived from actor/item/timestamp. If M2 represents every map action with its own event, it should map event ID directly to `actionId`.

Action records are:

- `MapConfirmation`
- `MapSituationUpdate`
- `MapSituationStatusUpdate`
- `MapCoordinationAcknowledgement`
- `MapCoordinationNote`

Add update content and Coordination note content must not be blank. Pure `MapActionInvariantPolicy.isStructurallyValid()` exposes metadata/content shape requirements for M2. Its name is deliberately narrow: it does not establish category/action, source, temporal, membership, or event-order validity.

M2 may accept an action only when structural, category/action, source, temporal, membership, and event-order policies all pass.

Owner Edit remains an ownership capability rather than a category interaction action.

## Situation lifecycle and update disposition

Lifecycle and update disposition are separate concepts.

### Lifecycle

`MapSituationLifecycleStatus` answers whether the Situation is active:

| Value | Wire value |
|---|---|
| `ACTIVE` | `active` |
| `NO_LONGER_ACTIVE` | `no_longer_active` |

Allowed lifecycle transitions:

- Active → Active, reducer/materialization idempotence only;
- Active → No longer active;
- No longer active → No longer active, replay-safe;
- No longer active → Active is rejected.

No longer active preserves history. M1 does not implement its persistence or tombstone behavior.

### Update disposition

`MapSituationUpdateType` describes an Add update action:

| Value | Wire value | Meaning |
|---|---|---|
| `STILL_ACTIVE` | `still_active` | the described condition still exists |
| `SITUATION_CHANGED` | `situation_changed` | the condition changed but is not resolved |

Situation changed is never persisted as lifecycle state and does not resolve the item. A later member can confirm that the changed Situation remains active because lifecycle stays Active.

`MapSituationUpdate` contains update content, optional source, and required update type. It cannot contain lifecycle status.

`MapSituationStatusUpdate` is the first-class Update status action and contains action metadata, target lifecycle status, optional note, and optional source.

User-created Update status accepts only `NO_LONGER_ACTIVE`. `ACTIVE → ACTIVE` exists for reducer/materialization replay safety, not as a user-created status action. Reporting that a condition remains active uses Add update with `STILL_ACTIVE`; reporting material change uses Add update with `SITUATION_CHANGED`.

For an area, confirmation confirms the described condition, not the exact polygon boundary.

## Community verification

Community verification is derived presentation state, not creator input or persisted confidence.

Initial M1 derivation:

- zero distinct confirming actors → `UNVERIFIED`;
- one distinct confirming actor → `PARTIALLY_VERIFIED`;
- two or more distinct confirming actors → `CONFIRMED`;
- conflict signal → `CONFLICTING_UPDATES`, with deterministic precedence over actor count.

`confirmationActorCount` must not be negative; invalid negative input is rejected.

The count represents distinct actors. Repeated confirmations by one actor count once. Creator source alone does not count. M2 remains responsible for confirmation validity, freshness relative to material updates, actor dedupe, and deterministic conflict materialization. A resolved item must not be presented as currently Confirmed merely because historical confirmations remain; lifecycle-aware presentation/materialization is an M2/M3 requirement.

## TTL, owner edit window, interaction window, and lifecycle

`MapItemWindows` has independent fields:

- `expiresAtSeconds` — activity/TTL boundary;
- `ownerEditUntilSeconds` — canonical owner-edit boundary;
- `interactionUntilSeconds` — member-action boundary.

Time-only evaluation is named `isWithinActiveTime`; it does not imply lifecycle status.

Window validity rules:

- every finite expiry, owner-edit cutoff, and interaction cutoff must be positive;
- a finite interaction cutoff cannot precede finite expiry;
- an interaction cutoff equal to expiry is valid;
- an interaction cutoff after expiry is valid but cannot permit post-expiry interaction;
- an indefinitely active item cannot have a finite interaction cutoff;
- no expiry plus no interaction cutoff is valid;
- owner edit may close before expiry.
- owner edit may also be configured after expiry, but expiry still closes edit permission first, so it cannot grant post-expiry mutation.

Situation permission helpers require both time and lifecycle:

- expired Situation cannot be edited or normally interacted with;
- No longer active Situation cannot receive normal Confirm/Add update interaction even if TTL remains;
- owner-edit expiry does not close member interaction while lifecycle is Active;
- read-only/history presentation after resolution does not grant mutation permission.

Coordination does not use Situation lifecycle. Coordination edit/interaction depends on valid windows and expiry.

M1 does not replace current production edit-lock or interaction behavior. M2 must implement these policies in persistence/reducer/ingest without relying only on UI checks.

## Legacy compatibility

### Legacy confidence and source

Legacy stored confidence remains readable:

- `0` — legacy uncertain;
- `1` — legacy reported;
- `2` — legacy observed.

Every legacy confidence value maps to `UNSPECIFIED_LEGACY_SOURCE` for source presentation. Legacy Reported must not become `REPORTED_BY_OTHERS`, because the old creation UI selected Reported by default.

### Legacy contributions

Legacy actor/item confidence and note records remain `LegacyMapContribution`. They are not silently converted into confirmations, acknowledgements, or updates because original intent cannot be established reliably.

### Legacy removal request

`LegacyMapRemovalState` preserves request timestamp, actor, reason, and cancellation timestamp. M1 does not rewrite removal requests into lifecycle events. M2 must preserve legacy history while adding new lifecycle materialization; M3 must use safe legacy wording where lifecycle cannot be established.

### Unknown values

Unknown kinds, sources, actions, lifecycle values, and update types return no guessed semantic. M2 must safely reject malformed new actions while preserving mixed-version tolerance for unknown optional fields and legacy events.

## Payload compatibility seam reserved for M2

`MapDomainPayloadContract` reserves optional names only; M1 does not write them:

- `map_domain_v`
- `item_category`
- `information_source`
- `map_action`
- `situation_lifecycle`
- `situation_update_type`
- `action_id`
- `action_t`
- `action_note`

Reserved extension version is `1`.

`action_note` is the single reserved wire-level action content field. It carries `MapConfirmation.note`, `MapSituationUpdate.content`, `MapSituationStatusUpdate.note`, or `MapCoordinationNote.content` according to action type. Domain records retain `note` versus `content` naming to express whether text is optional supporting context or required action content. M2 must not introduce a second content field or reuse the legacy canonical item `note` field without a reviewed contract amendment.

M2 may map event ID to `action_id` when each action is represented by one event. It must not derive action identity from actor/item/timestamp.

If M2 requires new event types instead of safe optional extensions, that is a targeted contract amendment requiring review before implementation.

## Requirements locked for M2

M2 must:

- persist/materialize category, source, actions, lifecycle, update type, and stable action identity without destructive migration;
- enforce canonical-field ownership outside UI;
- enforce category/action and source/category/operation compatibility;
- validate metadata, content, and stable wire values;
- combine structural, category/action, source, temporal, membership, and event-order policies before accepting an action;
- keep lifecycle separate from update disposition;
- reject invalid lifecycle reactivation;
- accept only No longer active as a user-created Update status target;
- block normal Situation interactions after resolution or expiry;
- keep active member interaction open after owner edit cutoff;
- count distinct confirmation/acknowledgement actors;
- define confirmation freshness and conflicting-update materialization deterministically;
- prevent resolved items from appearing currently Confirmed based only on history;
- retain event clock, replay, dedupe, membership, TTL, durable transaction, BLE outbox, batching, and relay protections;
- retain legacy confidence/contribution/removal parsing without inventing new meaning.

## Requirements locked for M3

M3 must consume these centralized category/action/source/temporal policies rather than create a UI-only parallel model.

M3 must:

- require source for Situation creation;
- omit source and confidence from Coordination creation;
- expose the category-specific action sets;
- keep Situation changed separate from No longer active;
- use lifecycle-aware current verification presentation;
- remove user-facing Reported, Observed, Confidence, Contribute, and confidence weights from the new flow;
- present legacy items without claiming an unverifiable source;
- preserve current drafting, focus, visibility, offline persistence, and viewport behavior.

## M1 implementation references

- `android/core/src/main/java/app/peermesh/core/map/MapItemDomain.kt`
- `android/core/src/main/java/app/peermesh/core/map/MapItemKindCatalog.kt`
- `android/core/src/main/java/app/peermesh/core/map/MapItemTemporalPolicy.kt`
- `android/core/src/main/java/app/peermesh/core/map/MapItemCompatibilityContract.kt`
- `android/app/src/main/java/app/peermesh/MapKinds.kt`
- `android/core/src/test/java/app/peermesh/core/map/MapItemDomainContractTest.kt`
- `android/app/src/test/java/app/peermesh/MapKindsCategoryContractTest.kt`
