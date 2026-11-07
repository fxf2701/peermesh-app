# Group Member Removal V1 Test Scenarios

Last updated: 2026-06-16

## Scope

This test plan covers the currently implemented `member_removed` V1 behavior on synced clients.

This plan does **not** cover:
- join-by-request
- invitation bootstrap changes
- key rotation / rekey
- multi-epoch history decryption

The current implementation under test is limited to:
- owner removes a member
- synced clients store `removed_members`
- synced clients reject new participation from the removed peer
- removed peer becomes read-only in chat after syncing removal

## Devices

Recommended setup:
- Device A: group owner
- Device B: normal member to be removed
- Device C: normal member who stays in group

Optional:
- Device D: extra observer

## Preconditions

- All devices run the same test build.
- Devices A, B, and C are already in the same group.
- All devices have synced enough to see each other in the group member list.
- Chat, map, and trail features are working normally before removal.

## Expected V1 Behavior Summary

After removal has synced:
- Device A and C should mark B as removed
- Device B should no longer be able to send new chat activity
- Device B should no longer be able to create new map activity
- Device B should no longer be able to create new live trail activity
- Devices A and C should reject new content from B for that group
- transport/relay behavior is not part of this manual UI test

## Scenario 1: Baseline before removal

### Steps

1. On Device B, send a chat message.
2. Verify Device A and C receive it.
3. On Device B, create a map marker or map update.
4. Verify Device A and C receive it.
5. On Device B, start or update a shared trail if trail sharing is available in the build.
6. Verify Device A and C receive it.

### Expected

- All features behave normally before removal.
- B is still treated as an active member.

## Scenario 2: Owner can remove a member

### Steps

1. On Device A, open `Group settings`.
2. Open `Members`.
3. Find Device B.
4. Tap `Remove`.
5. Review the confirmation dialog text.
6. Confirm removal.

### Expected

- Remove button is visible for owner.
- Confirmation dialog explains that synced clients will block new participation and that old secrets are not fully revoked yet.
- Success toast appears after confirmation.
- Device B is shown as `Removed` in the members list on Device A after refresh.

## Scenario 3: Non-owner cannot remove a member

### Steps

1. On Device C, open `Group settings`.
2. Open `Members`.
3. Inspect the member rows.

### Expected

- Remove buttons are not available to non-owner users.

## Scenario 4: Removed status syncs to another valid member

### Steps

1. After Scenario 2, wait for Device C to sync.
2. On Device C, open `Group settings`.
3. Open `Members`.
4. Inspect Device B's row.

### Expected

- Device B appears with `Removed` status on Device C after sync.
- Remove button remains unavailable on Device C because C is not owner.

## Scenario 5: Removed peer becomes read-only in chat after sync

### Steps

1. After Scenario 2, wait for Device B to sync the removal event.
2. On Device B, open the group chat.
3. Inspect the composer/input state.
4. Try to type and send a message.

### Expected

- Chat composer becomes disabled or read-only after sync.
- Status text explains that the user was removed and cannot send new group activity.
- Send action does not create a new outgoing message.

## Scenario 6: Synced clients reject new chat from removed peer

### Steps

1. If possible, attempt to make Device B send a new message after removal sync.
2. Observe Device A and Device C.

### Expected

- New chat from B should not appear on A or C after removal sync.
- If B's UI tries to send, it should show failure or read-only status and not persist normal participation.

## Scenario 7: Removed peer cannot create new map content after sync

### Steps

1. On Device B, open the group map after removal sync.
2. Try to add or edit a marker.
3. Try to add or edit an area if available.
4. Observe Device A and C.

### Expected

- Device B is blocked from creating new map activity.
- A toast or similar UI feedback indicates participation is no longer allowed.
- Devices A and C do not receive new map content from B after sync.

## Scenario 8: Removed peer cannot create new live trail content after sync

### Steps

1. On Device B, try to start or update live trail sharing after removal sync.
2. Observe Device A and C.

### Expected

- Device B cannot create new trail activity for the group.
- Devices A and C do not receive new trail activity from B after sync.

## Scenario 9: Existing history remains visible

### Steps

1. On Device A, C, and B, scroll through earlier chat content from before removal.
2. Inspect older map/trail content created before removal if available.

### Expected

- Previously synced historical content remains visible.
- Removal only blocks new participation in current V1 scope.

## Scenario 10: Removal does not delete the group immediately from removed device

### Steps

1. On Device B, inspect the group after removal sync.

### Expected

- Group may still remain locally visible depending on current UI flow.
- The important requirement is that B cannot continue normal participation.

## Scenario 11: Unsynced limitation check

This scenario documents a known V1 limitation rather than a pass/fail defect.

### Steps

1. Put Device C offline before Device A removes Device B.
2. Remove Device B from Device A.
3. Keep Device C offline long enough that it has not learned the removal yet.
4. Compare behavior on C before and after it reconnects and syncs.

### Expected

- Before sync, C may still operate on stale state.
- After sync, C should mark B as removed and reject new participation from B.

### Notes

- This limitation is expected in the current local-first delayed-sync design.
- It should not be filed as a bug unless behavior remains wrong after sync convergence.

## Scenario 12: Group deletion / leave cleanup

### Steps

1. On any device with test data, leave or delete the group using current supported flow.
2. Re-open the app and inspect whether stale removal state causes obvious UI issues.

### Expected

- Group-local removal state should be cleaned up with group-local data.

## Logging Suggestions

When testing, capture:
- exact device roles
- whether each device was online or offline
- time when removal was triggered
- time when each device reflected removed status
- whether blocked actions failed locally
- whether any rejected content still appeared on other synced devices

## Pass Criteria

V1 can be considered acceptable for this scope if all of the following are true:

- owner can remove a member
- non-owner cannot remove a member
- removed status appears on synced clients
- removed peer cannot continue new chat participation after sync
- removed peer cannot continue new map participation after sync
- removed peer cannot continue new trail participation after sync
- other synced clients do not continue accepting new participation from the removed peer

## Known V1 Limitations

- no join-by-request yet
- no key rotation yet
- no cryptographic rejoin prevention yet
- stale clients may temporarily operate on old state until sync completes
- historical content is not revoked by this V1 implementation

