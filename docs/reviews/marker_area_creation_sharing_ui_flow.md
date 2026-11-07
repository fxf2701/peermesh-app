# Marker and Area Creation and Sharing UI Flow

## Purpose and scope

This document records the approved, currently implemented UI flow for creating and sharing markers and areas on the private group map.

It describes existing behavior only. It does not define new product behavior, change transport rules, or propose a redesign.

The primary implementation is `GroupMapActivity`. The screen does not currently use a map-specific ViewModel; draft and presentation state are held by the activity.

## User-visible UI flow

### Opening the private group map

The screen displays:

- the group map title;
- the temporary Map Activity Banner when recent map activity exists;
- the map instruction, `Tap to place a marker. Long-press to draw an area`;
- the map;
- Marker, Area, and Shared Live Trail sections.

Marker and area creation do not require the user's current location. Location permission and provider state affect current-position rendering, not the ability to browse the map or place map items.

### Creating a marker

1. The user taps an unhandled position on the map.
2. A draft marker appears at that position.
3. The bottom action bar appears with Done and Cancel. Done is enabled immediately.
4. While the marker draft is active, another map tap moves the draft marker.
5. The user chooses one of the following:
   - **Cancel:** remove the draft and return to normal map interaction.
   - **Done:** open the marker details dialog with latitude and longitude prefilled from the draft position.
6. In the details dialog, the user can enter or select:
   - latitude and longitude;
   - optional radius;
   - marker kind;
   - optional TTL in minutes;
   - optional short label;
   - optional note;
   - confidence.
7. For a newly created marker, the UI selects reported confidence and does not show the observed option.
8. The user chooses:
   - **Cancel:** close the dialog without creating the marker;
   - **Save:** validate and submit the marker.
9. After successful local storage, the dialog and draft close. The marker list and map overlays refresh.

The label is limited to the first five characters during submission. The UI does not show a separate truncation confirmation.

### Creating an area

1. The user long-presses the map.
2. The first draft vertex appears and the screen enters area drawing mode.
3. The bottom action bar appears. Done remains disabled until at least three points exist.
4. Each subsequent tap adds another vertex.
5. Existing draft vertices can be dragged to new positions.
6. The screen displays a draft polyline and, once there are at least three points, a polygon preview.
7. If the draft fails the implemented area constraints, the preview uses the invalid fill treatment.
8. The user chooses one of the following:
   - **Cancel:** remove all draft geometry and return to normal map interaction.
   - **Done:** serialize the points as a closed GeoJSON Polygon and open the area details dialog.
9. In the details dialog, the user can enter or select:
   - geometry;
   - area kind;
   - optional TTL in minutes;
   - optional short label;
   - optional note;
   - confidence.
10. For a newly created area, the UI selects reported confidence and does not show the observed option.
11. After successful local storage, the dialog and draft close. The area list and map overlays refresh.

The geometry field contains the generated GeoJSON and is editable in the current UI.

### Viewing and controlling map items

The Marker and Area sections show active, non-expired items. Each section has a visibility switch, and each item has an individual visibility control. These controls affect local presentation only; they do not update or share the item.

Marker rows show the available kind, coordinates, optional radius, note, age, TTL, author, confidence totals, contribution preview, and removal state.

Area rows show the available kind, area metadata, note, age, TTL, author, confidence totals, contribution preview, and removal state.

Selecting a marker focus action centers the map on that marker. Selecting an area focus action zooms the map to its bounds.

### Editing and contributing

The original creator is treated as the item owner.

For an owner before the edit lock:

- the action is presented as editing the marker or area;
- the owner may update the available core fields and note;
- the owner's original confidence cannot be changed.

For a non-owner before the edit lock:

- the action is presented as contributing;
- core fields are read-only;
- the user may submit a confidence contribution and note;
- an existing contribution can change confidence once, tracked locally for that actor and item.

After the edit lock:

- core fields are displayed read-only;
- a non-owner with no prior contribution cannot submit one;
- a non-owner with a prior contribution may view it, but its confidence controls are disabled;
- the item remains available through the see-more/read-only presentation while it is active.

The edit lock is calculated from the item's TTL. Items without a meaningful expiry do not receive this lock.

### Removal presentation

The active UI uses a removal-request flow rather than immediate hard deletion.

1. The owner chooses the removal action.
2. A reason is required.
3. Saving the request creates an update to the marker or area.
4. The item remains visible with removal-request status and reduced overlay emphasis.
5. The owner can cancel an active removal request when the current UI enables that action.

## System behavior

### Event creation

Saving creates one of these event types:

- `MAP.MARKER.ADD` or `MAP.MARKER.UPD`;
- `MAP.AREA.ADD` or `MAP.AREA.UPD`.

Each event contains an event ID, actor DID, event time, Lamport value, entity target ID, map data, and optional TTL metadata. New items use the default map layer.

Removal request and cancellation fields are carried by marker/area update events.

Hard-delete event types exist in the event model, but the current item actions are connected to removal requests rather than the hard-delete confirmation functions.

### Local persistence

The save operation runs off the main thread.

Within one database transaction, the system:

1. inserts the event into the durable events table;
2. applies `MapReducer`;
3. upserts the materialized marker or area row;
4. upserts the referenced map layer.

The UI reads from the materialized `map_markers` and `map_areas` tables rather than reconstructing the map directly from the event log.

### TTL and active items

If the user supplies a positive TTL in minutes, it is converted to seconds and stored as event metadata. A new map event without an explicit TTL uses the shared default map TTL of 24 hours.

Expired rows remain persisted but are excluded from active marker and area queries. They disappear from the UI after a refresh.

### Conflict handling and contributions

The reducer compares event clocks before applying an update. An event that is not newer than the materialized item is ignored by the reducer.

Owner note and confidence live on the marker or area entity. A non-owner's note and confidence are stored as an item contribution/vote and are included in the displayed consensus totals.

Observed contributions count twice and reported contributions count once when the activity sorts items by consensus weight. Items with equal weight are ordered by most recent update time.

### Receiving and rendering

For a valid received marker or area event, the receive path:

1. parses and verifies the envelope or app batch;
2. loads the group key and decrypts the payload;
3. validates the event envelope and supported payload shape;
4. applies replay, dedupe, TTL, deletion-tombstone, and membership checks;
5. inserts the event and runs `MapReducer` in a transaction;
6. notifies active ingest listeners;
7. refreshes the map screen when it is able to do so.

The map also performs a periodic refresh every 60 seconds while active and not drafting.

Markers render as map marker overlays. Optional labels appear at the configured zoom threshold. A selected marker's radius appears at the configured radius zoom threshold.

Areas render as polygons. Kind emoji and labels are placed using the calculated polygon centroid when available.

Only items within the current viewport are attached as marker/area overlays. Section and per-item visibility settings are also applied before rendering.

## State and transport behavior

### Activity state

The activity uses these interaction states:

```text
FREE
  single tap  -> MARKER_DRAFT
  long press  -> AREA_DRAFT

MARKER_DRAFT
  tap         -> move marker
  Done        -> marker dialog
  Cancel      -> FREE

AREA_DRAFT
  tap         -> add vertex
  drag        -> move vertex
  Done        -> area dialog when at least three points exist
  Cancel      -> FREE
```

Draft state, local visibility choices, and selected items are held in activity memory. The marker/area entities and outbound batches are persisted in Room.

Map refresh is paused while drafting and while a marker/area dialog is open. Incoming events are not rendered over an active draft. Refresh resumes after cancel, dismiss, or successful completion.

### BLE sharing

After successful local persistence and group-key lookup, the event is submitted to `UserOutbox` and `OutboundBatchDispatcher`.

Map events are placed in a persistent outbound batch queue. The dispatcher may combine map events during the current map batching window before handing the payload to BLE transport.

If nearby peers exist, dispatch is started. If no nearby peer exists, the event remains queued and the dispatcher schedules retry behavior. Detection of a nearby peer later kicks the queue again.

Per-peer delivery state is tracked below the map UI. The map screen does not display per-peer delivery confirmation.

### Offline behavior

Internet connectivity is not required for local marker or area creation.

Bluetooth being off or having no nearby peer does not roll back the locally stored item. The item remains visible locally, and its outbound batch remains available for later BLE dispatch according to dispatcher retry behavior.

Missing BLE permission also does not roll back local creation. The screen shows the existing BLE-permission-required message.

### Cloud/relay sharing

Cloud/relay sharing is handled by the relay synchronization subsystem, not directly by the map save dialog.

When relay sync is available, configured, and running:

- locally stored map events are eligible for relay push;
- map events received from BLE are eligible to be bridged to cloud;
- relay- and DTN-sourced events are not pushed back to relay;
- pulled relay events use the same verified ingest and reducer path as other received events.

The map UI does not show map-item-specific cloud pending, success, or failure state.

## Error and recovery paths

### Draft and dialog validation

Validation failures keep the details dialog open and show the existing user-facing message.

Marker validation covers:

- required, numeric latitude and longitude;
- required kind;
- required confidence;
- TTL conversion when the field parses as a positive whole number of minutes; blank, non-positive, or non-numeric input is treated as no explicit TTL rather than producing a validation error;
- same-kind proximity and recent-activity cooldown checks for new markers;
- owner confidence immutability;
- edit-window and contribution-switch restrictions.

Area validation covers:

- required, parseable geometry;
- supported Polygon or first MultiPolygon ring for point extraction;
- at least three normalized points;
- minimum approximate area of 10,000 square metres;
- minimum vertex angle of 45 degrees;
- required kind and confidence;
- TTL conversion when the field parses as a positive whole number of minutes; blank, non-positive, or non-numeric input is treated as no explicit TTL rather than producing a validation error;
- same-kind proximity and recent-activity cooldown checks for new areas;
- owner confidence immutability;
- edit-window and contribution-switch restrictions.

The draft area preview may show invalid fill before Done is selected. Final validation is repeated in the details dialog before submission.

### Capability and membership errors

Local map events are rejected when map capability is unavailable. The existing Pro unlock message is shown.

Creation and updates are rejected when the local actor has been removed from the group. Received events from removed members, or events rejected by local membership policy, are also discarded before materialization.

### Persistence errors

If the event cannot be inserted, the save operation reports failure, re-enables the Save button, and leaves the dialog open.

Successful insertion is immediately reflected in local materialized state before transport delivery is known.

### Group-key errors

If the group key is missing after local persistence:

- the locally inserted item remains stored;
- the existing missing-group-key message is shown;
- the map save flow currently reports local success and does not enqueue that event through `UserOutbox` in this branch.

### BLE errors and retry

When BLE permission is missing, the existing permission-required message is shown after local creation. The map flow does not request BLE permission itself.

No nearby peer and transient BLE dispatch failures are handled by the persistent outbound queue and retry scheduler. Terminal BLE delivery failure is recorded below the UI but is not presented as a marker/area-specific error on the map screen.

### Receive errors

Malformed payloads, invalid signatures, missing decryption keys, unsupported or invalid events, expired events, replayed or duplicate events, deleted-target conflicts, and membership-rejected events are discarded by the receive path.

These receive failures are logged but are not shown as item-specific map errors.

### Refresh recovery

An incoming event received while the user is drafting is not immediately applied to the visible map state. It becomes visible after the draft/dialog flow ends and the activity refreshes, or during a later periodic refresh.

## Known limitations

The following limitations describe verified current behavior; they are not new product decisions.

- The screen has no map-specific ViewModel. Activity recreation does not preserve an active marker/area draft or local in-memory presentation choices.
- Local save success is distinct from confirmed sharing success. The dialog closes after local persistence, not after BLE or cloud delivery.
- The result of outbound queue enqueueing is not surfaced by `storeAndBroadcast`; local persistence can be reported as successful even if enqueueing fails.
- A missing group key after persistence prevents the normal outbox enqueue for that save attempt while leaving the item stored locally.
- The map UI does not show per-peer BLE delivery state or map-item-specific relay delivery state.
- UI ownership restrictions are not independently enforced by `MapReducer`. The reducer can accept newer non-owner core-field values if such an event reaches validated ingest.
- Receive-side map payload validation is less strict than local UI validation. Marker validation requires numeric coordinates but does not verify geographic ranges. Area validation requires geometry but does not repeat the UI's polygon size and angle constraints.
- Local marker validation does not establish latitude/longitude bounds or positive radius bounds.
- Local area validation does not establish self-intersection, coordinate-bound, hole, all-MultiPolygon-ring, or maximum-vertex rules.
- Same-kind proximity and cooldown checks use the activity's currently loaded item lists, which may not represent a database update that has not yet refreshed into activity state.
- Active marker and area queries each return at most 200 items.
- Expired items are filtered from active queries rather than immediately removed from storage.
- Hard-delete functions and delete event types exist, but the current user flow is wired to removal requests.
- The area geometry field exposes editable raw GeoJSON.
- The map screen contains an online-connectivity helper that is not used by marker/area creation or sharing.

## Implementation references

- `android/app/src/main/java/app/peermesh/GroupMapActivity.kt`
- `android/app/src/main/java/app/peermesh/MapListAdapter.kt`
- `android/app/src/main/java/app/peermesh/UserOutbox.kt`
- `android/app/src/main/java/app/peermesh/OutboundBatchDispatcher.kt`
- `android/app/src/main/java/app/peermesh/PeerMeshEventPolicy.kt`
- `android/core/src/main/java/app/peermesh/core/factory/EventFactory.kt`
- `android/core/src/main/java/app/peermesh/core/types/EventTypes.kt`
- `android/core/src/main/java/app/peermesh/core/validation/EventValidator.kt`
- `android/core/src/main/java/app/peermesh/core/ttl/TtlHelper.kt`
- `android/data/src/main/java/app/peermesh/data/reducer/MapReducer.kt`
- `android/data/src/main/java/app/peermesh/data/db/dao/MapDao.kt`
- `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`
- `android/data/src/main/java/app/peermesh/data/ingest/EventIngestor.kt`
- `android/data/src/main/java/app/peermesh/data/ingest/IngestNotifier.kt`
- `android/data/src/main/java/app/peermesh/data/relay/RelayPushManager.kt`
- `android/app/src/main/java/app/peermesh/relay/RelaySyncRunner.kt`
