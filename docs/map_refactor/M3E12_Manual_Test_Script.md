# M3E12 Manual Test Script — Missing canonical dependency recovery

## Test environment

- Two devices, A and B, in the same group.
- Run once on `OfficialDebug` and once on `EmergencyDebug`.
- Keep both the map canvas and the marker/area list in the screenshots.
- Use the activity log only as supporting evidence; final map/list convergence
  is the pass condition.

## Test R1 — Marker update arrives before its ADD

1. Temporarily prevent B from receiving A's new marker `ADD`.
2. On A, create a Situation marker and then edit its label or note.
3. Restore a BLE or cloud repair path and let B receive the later update.
4. Wait for synchronization without reopening the map screen.

Pass criteria:

- [ ] B does not create a synthetic marker from the update alone.
- [ ] A generic transport banner such as `Map synced` may appear, but B does
      not show a false item-level success such as `Marker updated` before the
      missing ADD is repaired.
- [ ] Receiving the orphan automatically requests the missing root ADD; no map
      reopen, Details tap, or manual refresh is needed to start repair.
- [ ] B repairs the missing ADD and then displays exactly one marker.
- [ ] The marker contains A's latest edited label/note on both map and list.
- [ ] Repeating sync does not duplicate the marker or visible update.

## Test R2 — Marker delete arrives before its ADD

1. Repeat the delivery gap with a new marker.
2. Delete the marker on A before B receives its ADD.
3. Restore BLE/cloud connectivity.

Pass criteria:

- [ ] B never exposes an editable phantom marker.
- [ ] After repair and replay, the deleted marker is absent from map and list.
- [ ] Another marker already visible on B is unchanged.

## Test R3 — Area update/delete arrives before its ADD

1. Repeat R1 with a Situation area and change its note/kind.
2. Repeat R2 with another area.

Pass criteria:

- [ ] The repaired area retains its original polygon geometry.
- [ ] The latest valid update is visible after repair.
- [ ] A delete received before ADD leaves no area on map/list after repair.
- [ ] No duplicate polygon or center icon is created.

## Test R4 — Dedicated Situation/Coordination action before ADD

1. Prevent B from receiving a new Situation or Coordination item's ADD.
2. From another member, submit a valid Confirm/Acknowledge or note action.
3. Restore a repair path.

Pass criteria:

- [ ] The action remains pending while the canonical item is absent.
- [ ] Receiving the action automatically schedules a targeted repair cycle.
- [ ] The missing root stays pending after an unsuccessful peer exchange and
      is requested again from each newly encountered BLE/DTN peer; that peer
      does not need to be the item owner.
- [ ] The missing root is repaired by event ID.
- [ ] Canonical updates are replayed before the dedicated action.
- [ ] Details show the correct final lifecycle/count/activity exactly once.

## Test R5 — Process restart and offline retention

1. Create an orphan-update condition on B.
2. Force-stop and restart B before restoring connectivity.
3. Keep B offline briefly, then restore BLE or cloud.

Pass criteria:

- [ ] No orphan work is lost across restart.
- [ ] Offline retries do not create repeated banners or duplicate rows.
- [ ] The item converges after connectivity returns without manual editing.

## Test R6 — Mixed BLE/cloud repair

Run R1 once with the orphan arriving by BLE and root repair by cloud, then once
with the orphan arriving by cloud and root repair by BLE.

Pass criteria:

- [ ] Both transport combinations converge to the same final map/list state.
- [ ] Relay cursor progress does not discard a durably deferred orphan.
- [ ] BLE repair works from durable history, not only a live outbound batch.
- [ ] A failed or unavailable peer does not clear the WANT; a later eligible
      peer can satisfy the same root ADD request.

## Supporting activity-log sequence

For an orphan with a valid `ref.root`, the receiving device should contain the
following sequence (the final map/list state remains the actual pass result):

1. `Map event deferred`
2. `Map canonical repair requested`
3. Either `DTN inv_want sent` with the missing root ID after the next eligible
   peer session, or `Map canonical repair scheduled` for cloud repair.
4. `Map canonical repair pull started` (when cloud repair is available)
5. `Relay map dependency repair` (when cloud repair is available)
6. `Map canonical repair pull finished` (when cloud repair is available)

When cloud is unavailable, step 3 may remain queued until the network
constraint is met; the pending event must survive and later replay.
