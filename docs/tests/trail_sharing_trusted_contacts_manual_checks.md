# Trail Sharing & Trusted Contacts Manual QA Checklist

## Purpose

This checklist verifies the Trail Sharing and Trusted Contacts redesign after the cleanup of the legacy Group Settings live trail configuration.

The key behavior to validate:

- Manual Trail uses a runtime audience selected at start time.
- Automatic Trail uses Trusted Contacts from the active group.
- Manual sharing targets are not reused as automatic safety recipients.
- Automatic Trail can record locally without broadcasting when no trusted contacts exist.
- Live trail start, point batch, and end events are emitted only when a live share session actually starts.

## Required Setup

Use at least two devices or emulators in the same PeerMesh group.

Recommended setup:

- Device A: trail owner / sender
- Device B: trusted contact / selected recipient
- Optional Device C: non-recipient group member

Before testing:

1. Install the latest debug build on all devices.
2. Confirm all devices are in the same group.
3. Confirm Device A can open the Trails screen.
4. Confirm Device A can open Trusted Contacts.
5. Confirm ActivityLog is available for inspection.

Useful build command:

```bash
cd android && ./gradlew :app:assembleDebug
```

Useful log search terms:

```text
Live trail share
manual share prepared
manual live share started
auto live share started
auto live share skipped
live trail first point broadcast
recording stopped activeShare
```

---

## Case 1 — Manual Trail: Entire Group

### Goal

Verify Manual Trail sharing to the entire group remains runtime/session-based.

### Steps

1. On Device A, open Trails.
2. Select Manual Trail.
3. Tap Start.
4. Choose Entire group.
5. Move or inject enough location updates to create at least one trail point.
6. Stop the trail.

### Expected Sender Logs

Device A should show logs similar to:

```text
manual share prepared gid=<gid> scope=group members=0
recording started mode=manual
manual live share starting scope=group targets=0
manual live share started scope=group targets=0
live trail first point broadcast trailId=<trail_id> batchSize=<n>
recording stopped activeShare=true
stop sendEnd=true trailId=<trail_id> gid=<gid>
```

### Expected Event Behavior

- `LIVE_TRAIL_START` is emitted.
- `share_scope=group`.
- `share_to` is empty.
- `LIVE_TRAIL_POINT_BATCH` is emitted after points are recorded.
- `LIVE_TRAIL_END` is emitted once when the trail stops.

### Expected Receiver Behavior

- Group members can see the live trail while it is active.
- The trail disappears or becomes ended after `LIVE_TRAIL_END` is processed.

---

## Case 2 — Manual Trail: Selected Members

### Goal

Verify Manual Trail selected audience does not persist as automatic safety recipients.

### Steps

1. On Device A, open Trails.
2. Select Manual Trail.
3. Tap Start.
4. Choose Selected members.
5. Select Device B only.
6. Start the trail.
7. Move or inject enough location updates to create at least one trail point.
8. Stop the trail.

### Expected Sender Logs

Device A should show logs similar to:

```text
manual share prepared gid=<gid> scope=members members=1
recording started mode=manual
manual live share starting scope=members targets=1
manual live share started scope=members targets=1
live trail first point broadcast trailId=<trail_id> batchSize=<n>
recording stopped activeShare=true
stop sendEnd=true trailId=<trail_id> gid=<gid>
```

### Expected Event Behavior

- `LIVE_TRAIL_START` is emitted.
- `share_scope=members`.
- `share_to` contains Device B DID only.
- `LIVE_TRAIL_POINT_BATCH` is emitted after points are recorded.
- `LIVE_TRAIL_END` is emitted once when the trail stops.

### Expected Receiver Behavior

- Device B can see the live trail.
- Device C, if present and not selected, should not see the live trail.

### Regression Check

After stopping the trail:

1. Open Trusted Contacts on Device A.
2. Confirm Device B was not automatically added as a trusted contact unless the user explicitly configured it there.

---

## Case 3 — Manual Trail: No Pending Audience Guard

### Goal

Verify Manual Trail does not accidentally start live sharing if recording starts without a prepared runtime audience.

### Steps

This is mostly a defensive/runtime check. Trigger Manual recording through any path that bypasses the normal audience picker, if such a debug path exists.

### Expected Sender Logs

```text
recording started mode=manual
manual live share skipped no pending audience
```

### Expected Event Behavior

- No `LIVE_TRAIL_START` is emitted.
- No `LIVE_TRAIL_POINT_BATCH` is emitted for live sharing.
- No `LIVE_TRAIL_END` is emitted because no live share session started.
- Local trail recording may still continue if the recorder supports that path.

---

## Case 4 — Automatic Trail: No Active Group

### Goal

Verify Automatic Trail does not broadcast when no active group is available.

### Steps

1. Clear or avoid setting the active group, if a debug path exists.
2. Trigger Automatic Trail recording.

### Expected Sender Logs

```text
recording started mode=auto
// or recording started mode=automatic depending on current constant value
auto live share skipped no active group
```

### Expected Event Behavior

- No `LIVE_TRAIL_START` is emitted.
- No live sharing point batches are emitted.
- Local trail recording may still continue.

---

## Case 5 — Automatic Trail: No Trusted Contacts

### Goal

Verify Automatic Trail records locally but does not broadcast live share when no trusted contacts are configured.

### Steps

1. On Device A, open Trusted Contacts.
2. Ensure no trusted contacts are selected for the active group.
3. Return to Trails.
4. Select Automatic Trail.
5. Trigger automatic recording start.
6. Move or inject location updates.
7. Stop the trail.

### Expected Sender Logs

```text
recording started mode=auto
// or recording started mode=automatic depending on current constant value
auto live share skipped no trusted contacts
recording stopped activeShare=false
```

### Expected Event Behavior

- No `LIVE_TRAIL_START` is emitted.
- No `LIVE_TRAIL_POINT_BATCH` is emitted for live sharing.
- No `LIVE_TRAIL_END` is emitted because no live share session started.
- Local trail recording still exists after completion.

### Expected Receiver Behavior

- No other device sees a live trail from Device A.

---

## Case 6 — Automatic Trail: Trusted Contacts Configured

### Goal

Verify Automatic Trail automatically shares live trail to trusted contacts from trail start until trail end.

### Steps

1. On Device A, open Trusted Contacts.
2. Select Device B as a trusted contact.
3. Leave Device C unselected, if available.
4. Return to Trails.
5. Select Automatic Trail.
6. Trigger automatic recording start.
7. Move or inject enough location updates to create at least one trail point.
8. Stop the trail.

### Expected Sender Logs

```text
recording started mode=auto
// or recording started mode=automatic depending on current constant value
auto live share starting trusted_contacts=1
auto live share started trusted_contacts=1
live trail first point broadcast trailId=<trail_id> batchSize=<n>
recording stopped activeShare=true
stop sendEnd=true trailId=<trail_id> gid=<gid>
```

### Expected Event Behavior

- `LIVE_TRAIL_START` is emitted automatically when recording starts.
- `share_scope=members`.
- `share_to` contains trusted contact DIDs only.
- `LIVE_TRAIL_POINT_BATCH` is emitted after points are recorded.
- `LIVE_TRAIL_END` is emitted once when recording stops.

### Expected Receiver Behavior

- Device B can see the live trail from start until end.
- Device C, if present and not trusted, should not see the live trail.

---

## Case 7 — Stop Trail Emits End Only When Live Share Started

### Goal

Verify stop behavior does not emit fake live trail end events.

### Steps

Run both scenarios:

1. Automatic Trail with no trusted contacts.
2. Automatic Trail with one trusted contact.

### Expected Behavior

For no trusted contacts:

```text
recording stopped activeShare=false
```

Expected:

- No `LIVE_TRAIL_END`.

For one trusted contact:

```text
recording stopped activeShare=true
stop sendEnd=true trailId=<trail_id> gid=<gid>
```

Expected:

- Exactly one `LIVE_TRAIL_END`.

---

## Case 8 — Group Switching Safety Check

### Goal

Verify Automatic Trail uses the current `ActiveGroupManager` group and does not use stale legacy live trail settings.

### Steps

1. Create or use two groups: Group A and Group B.
2. In Group A, configure Device B as trusted contact.
3. In Group B, configure no trusted contacts or a different trusted contact.
4. Switch Device A to Group B.
5. Trigger Automatic Trail recording.

### Expected Behavior

- Automatic sharing uses Group B trusted contacts only.
- Group A trusted contacts do not receive Group B auto trail.
- There is no dependency on old `live_trail_share_*` preferences.

### Expected Logs

If Group B has no trusted contacts:

```text
auto live share skipped no trusted contacts
recording stopped activeShare=false
```

If Group B has trusted contacts:

```text
auto live share starting trusted_contacts=<group_b_count>
auto live share started trusted_contacts=<group_b_count>
```

---

## Case 9 — Capability Gate Check

### Goal

Verify live sharing does not start when `FEATURE_SHARED_LIVE_TRAIL` is unavailable, while local trail behavior remains understandable.

### Steps

1. Disable or simulate disabled `PeerMeshCapabilities.canUseSharedLiveTrail`.
2. Try Manual Trail sharing.
3. Try Automatic Trail with trusted contacts configured.

### Expected Logs

```text
live share skipped capability disabled kind=manual
```

or:

```text
live share skipped capability disabled kind=automatic
```

### Expected Behavior

- No `LIVE_TRAIL_START` is emitted.
- No live trail point batch is emitted.
- No live trail end is emitted.
- Local trail recording behavior should match product policy.

### Product Follow-Up

If this blocks Manual Trail recording completely in the current UI, handle under the Pro Gates/Product Policy step.

---

## Final Verification Commands

Run after any related patch:

```bash
cd android && ./gradlew :app:assembleDebug
```

Search for removed legacy APIs:

```bash
rg -n "LiveTrailShareManager\\.(getConfig|setEnabled|setScope|setMembers|onConfigChanged|setActiveGroup|getActiveGroup)" android/app/src/main
```

Expected:

- no matches

Search for removed legacy Group Settings UI:

```bash
rg -n "GroupSettingsActivity|activity_group_settings|item_map_live_trail_share|map_live_trail_share|page_title_group_settings" android/app/src/main
```

Expected:

- no matches

---

## Pass Criteria

Step 12B is considered passed when:

- Manual Trail entire group works.
- Manual Trail selected members works.
- Automatic Trail with no trusted contacts records locally and does not broadcast.
- Automatic Trail with trusted contacts broadcasts only to trusted contacts.
- Stop emits live trail end only when a live share session started.
- Group switching does not leak or reuse stale recipients.
- Build passes.