# M3E11 Manual Test — Remote map-event refresh

## Scope

Verify that user B's visible map overlays and marker/area list reconcile
automatically after user A changes the same group map over BLE or cloud. No
tap on **Details**, manual refresh, screen reopen, or 60-second timer wait is
allowed as part of a passing result.

Record the build flavor, devices/users, group, transport used and screenshots
for each test.

## R1 — BLE canonical add, edit and delete

Preconditions:

* User A and user B are different users in the same group.
* Both devices have cloud/relay sync disabled or unavailable, and BLE delivery
  between them is working.
* User B keeps the group map screen open.
* Keep one unrelated marker or area visible as a control item.

Steps:

1. User A creates and saves a marker.
2. Without interacting with user B's screen, wait for its map-update banner.
3. Confirm that the new marker appears both on the map and in the marker list.
4. User A edits that marker's note or kind and saves.
5. Confirm that user B's existing overlay and list row update automatically.
6. User A deletes the marker and confirms deletion.
7. Confirm that it disappears automatically from user B's map and list.
8. Repeat the create/edit/delete flow for an area.

Pass criteria:

* [ ] Each accepted BLE update refreshes user B's map and list automatically.
* [ ] Add, edit and delete are visible without opening **Details** or reopening
  the map.
* [ ] Marker and area behavior is consistent.
* [ ] The unrelated control item remains unchanged.
* [ ] No active creation draft or unexpected selection is introduced.

## R2 — Cloud canonical and dedicated map events

Preconditions:

* User A and user B are different users in the same group.
* Prevent direct BLE delivery between the devices and enable cloud/relay sync.
* User B keeps the group map screen open.

Steps:

1. User A creates and saves a marker or area.
2. Wait for user B's cloud/map-synced banner without touching its screen.
3. Confirm that the item appears automatically on user B's map and list.
4. User A edits the item, then deletes it; verify the corresponding automatic
   update and removal on user B.
5. Create a Situation item. From user A, confirm it, add an update, or mark it
   no longer active.
6. Confirm that user B's map status cue and loaded list/card reconcile after
   each received event.
7. Create a Coordination item and acknowledge it or add a coordination note;
   confirm that user B refreshes after the received event.

Pass criteria:

* [ ] Cloud add, edit and delete refresh user B's map and list automatically.
* [ ] Accepted dedicated Situation events trigger the same refresh path.
* [ ] Accepted dedicated Coordination events trigger the same refresh path.
* [ ] No stale overlay/list remains after the sync banner.
* [ ] No manual refresh, **Details** navigation, or screen reopen is required.

## R3 — Remote update while user B is drafting

Preconditions:

* Use either a proven BLE or cloud path.
* User B starts drawing a new area but does not save it.

Steps:

1. While user B's area draft and **Done**/**Cancel** controls are visible, user
   A creates or edits another item.
2. Wait for user B to receive the update notification.
3. Verify that user B's draft vertices and controls are not cleared or moved.
4. User B presses **Cancel**, or completes and saves the draft.
5. Without another navigation action, verify that user A's remote change now
   appears in user B's map and list.

Pass criteria:

* [ ] A received remote event does not destroy or mutate the active draft.
* [ ] The pending refresh is applied automatically after draft exit.
* [ ] The draft result follows the selected Save/Cancel action.

## R4 — Pause/resume and group isolation

Steps:

1. Put user B's map Activity in the background.
2. User A creates or edits an item in the shared group.
3. Return user B to the same map screen.
4. Confirm that the full resume load shows the change.
5. If another group is available, switch user B to it and receive an event for
   the original group.

Pass criteria:

* [ ] Events received while paused are reflected when the map resumes.
* [ ] No delayed callback from the old group mutates the currently displayed
  group's map or list.

## Completion

* [ ] R1 passed on `OfficialDebug`.
* [ ] R1 passed on `EmergencyDebug`.
* [ ] R2 passed on at least one debug flavor with cloud/relay transport proven.
* [ ] R3 passed.
* [ ] R4 passed.
