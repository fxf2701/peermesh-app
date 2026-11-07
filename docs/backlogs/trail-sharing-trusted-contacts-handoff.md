# Trail Sharing & Trusted Contacts Redesign - Handoff Summary

## Context

This handoff is for continuing the UX redesign described in:

- `docs/backlogs/trail-sharing-trusted-contacts-redesign.md`
- `docs/backlogs/trail-sharing-trusted-contacts-step-1-audit.md`

Goal:

- Manual Trail is a temporary trip-sharing session.
- Automatic Trail is persistent safety tracking.
- Trusted Contacts are the persistent safety recipients for Automatic Trail.
- Manual sharing targets must not be reused as automatic safety recipients.

Current working branch at time of writing:

- `improve/trail-sharing-trusted-contacts-step-9`

Important branch note:

- Step 1 through Step 8 were merged into `improve/trail-sharing-trusted-contacts-ux`.
- Step 9 exists as commit `91e6989 feat: auto share trails with trusted contacts` on `improve/trail-sharing-trusted-contacts-step-9`. If it has not been merged yet, merge Step 9 before starting the next branch.

Known untracked local folders to ignore unless explicitly needed:

- `export/`
- `logs/`
- `scripts/debug-kit/__pycache__/`

## Steps Already Completed

### Step 1 - Audit And Backlog Docs

Commit:

- `99586eb docs: audit trail sharing trusted contacts redesign`

Added:

- `docs/backlogs/trail-sharing-trusted-contacts-redesign.md`
- `docs/backlogs/trail-sharing-trusted-contacts-step-1-audit.md`

Summary:

- Documented target UX split between Manual Trail and Auto Trail.
- Audited current entry points, persistent live trail config, and recorder/share coupling.
- Identified old `GroupSettingsActivity` as a misleading live-sharing settings screen.

### Step 2 - Remove Old Live Trail Settings Entry Points

Commit:

- `53a53e8 refactor: remove live trail settings entry points`

Modified:

- `android/app/src/main/java/app/peermesh/GroupChatActivity.kt`
- `android/app/src/main/java/app/peermesh/GroupMapActivity.kt`
- `android/app/src/main/java/app/peermesh/PeerMeshFeatureUi.kt`
- `android/app/src/main/res/menu/group_chat_menu.xml`
- `android/app/src/main/res/menu/group_map_menu.xml`

Summary:

- Removed `Group settings` menu items from chat/map menus.
- Removed routes that opened `GroupSettingsActivity`.
- Kept `GroupSettingsActivity` registered temporarily so later steps could reuse logic or remove it safely.

### Step 3 - Clarify Trail Recording Modes

Commit:

- `0866537 refactor: clarify trail recording modes`

Added:

- `android/app/src/main/java/app/peermesh/TrailRecordingMode.kt`

Modified:

- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/java/app/peermesh/TrailAdapter.kt`
- `android/app/src/main/java/app/peermesh/TrailForegroundService.kt`
- `android/app/src/main/java/app/peermesh/TrailRecorder.kt`
- `android/app/src/main/res/layout/activity_trails.xml`
- `android/app/src/main/res/values/strings.xml`

Summary:

- Centralized `manual` / `auto` constants in `TrailRecordingMode`.
- Updated Trails UI copy to clearly say `Manual Trail` and `Automatic Trail`.
- Added explanatory copy under each mode.

### Step 4 - Manual Trail Runtime Audience

Commit:

- `1794aa1 feat: choose manual trail sharing audience`

Modified:

- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt`
- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/res/values/strings.xml`

Summary:

- Manual `Start` now opens audience selection.
- Options:
  - Entire group
  - Selected members
- Selected members opens a member picker from active group profiles.
- Added runtime manual share preparation so manual audience is not saved as persistent config.

### Step 5 - Split Manual And Automatic Sharing Paths

Commit:

- `fb6bc60 refactor: split manual and automatic trail sharing`

Modified:

- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt`
- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/java/app/peermesh/TrailRecorder.kt`

Summary:

- `TrailRecorder` now passes recording mode into `LiveTrailShareManager`.
- Manual sharing only starts if a pending manual audience exists.
- Automatic sharing is handled by a separate branch.
- `onConfigChanged` no longer affects manual sharing sessions.

### Step 6 - Trusted Contacts Subsystem

Commit:

- `8027355 feat: add trusted contacts management`

Added:

- `android/app/src/main/java/app/peermesh/TrustedContactsActivity.kt`
- `android/app/src/main/java/app/peermesh/TrustedContactsStore.kt`
- `android/app/src/main/res/layout/activity_trusted_contacts.xml`

Modified:

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/res/layout/activity_trails.xml`
- `android/app/src/main/res/values/strings.xml`

Summary:

- Added `TrustedContactsStore`, backed by SharedPreferences.
- Added `TrustedContactsActivity` to manage trusted contacts per group.
- Added `Manage Trusted Contacts` button on Trails screen.

### Step 7 - Automatic Trail Trusted Contacts Indicator

Commit:

- `5c8cc2d feat: show automatic trail trusted contacts status`

Modified:

- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/res/layout/activity_trails.xml`
- `android/app/src/main/res/values/strings.xml`

Summary:

- Added indicator under Automatic Trail.
- Displays active group/trusted contact count.
- Refreshes on create, resume, and periodic refresh loop.

### Step 8 - First-Time Automatic Trail Sharing Onboarding

Commit:

- `ce8ab5a feat: onboard automatic trail sharing`

Modified:

- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/res/values/strings.xml`

Summary:

- First time selecting Automatic Trail shows onboarding dialog.
- Dialog explains automatic live sharing to trusted contacts.
- Stores one-time flag in `PeerMeshPrefs.NAME`.

### Step 9 - Auto Share To Trusted Contacts

Commit:

- `91e6989 feat: auto share trails with trusted contacts`

Modified:

- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt`

Summary:

- Automatic Trail now uses `TrustedContactsStore`.
- Automatic sharing uses the active group from `ActiveGroupManager`, falling back to live trail active group.
- If no trusted contacts exist, automatic recording still records locally but does not broadcast live share.
- Legacy `live_trail_share` prefs are no longer used for Automatic Trail recipients.

## Current Important Files

Core Trail UX:

- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/res/layout/activity_trails.xml`
- `android/app/src/main/res/values/strings.xml`

Trail recording and foreground state:

- `android/app/src/main/java/app/peermesh/TrailRecorder.kt`
- `android/app/src/main/java/app/peermesh/TrailRecordingMode.kt`
- `android/app/src/main/java/app/peermesh/TrailForegroundService.kt`
- `android/app/src/main/java/app/peermesh/TrailAdapter.kt`

Live trail sharing:

- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt`
- `android/app/src/main/java/app/peermesh/LiveTrailEventWriter.kt`
- `android/app/src/main/java/app/peermesh/LiveTrailPendingStore.kt`
- `android/app/src/main/java/app/peermesh/LiveTrailBackfillManager.kt`
- `android/data/src/main/java/app/peermesh/data/reducer/LiveTrailReducer.kt`

Trusted Contacts:

- `android/app/src/main/java/app/peermesh/TrustedContactsActivity.kt`
- `android/app/src/main/java/app/peermesh/TrustedContactsStore.kt`
- `android/app/src/main/res/layout/activity_trusted_contacts.xml`
- `android/app/src/main/AndroidManifest.xml`

Old/legacy live trail settings:

- `android/app/src/main/java/app/peermesh/GroupSettingsActivity.kt`
- `android/app/src/main/res/layout/activity_group_settings.xml`
- `android/app/src/main/res/layout/item_map_live_trail_share.xml`
- strings with prefix `map_live_trail_share_*`

## Remaining Recommended Steps

### Step 10 - Remove Or Retire Legacy Group Settings Live Trail UI

Purpose:

- The old screen is no longer reachable from chat/map menus.
- It still exists in code and still writes legacy live trail share prefs.
- After Step 9, Automatic Trail no longer uses those prefs, and Manual Trail uses runtime audience.

Recommended work:

- Delete `GroupSettingsActivity` if no hidden entry point remains.
- Remove manifest registration for `GroupSettingsActivity`.
- Delete old layout and unused item layout if no references remain.
- Remove stale strings if unused.

Files likely to delete:

- `android/app/src/main/java/app/peermesh/GroupSettingsActivity.kt`
- `android/app/src/main/res/layout/activity_group_settings.xml`
- `android/app/src/main/res/layout/item_map_live_trail_share.xml`

Files likely to edit:

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/values/strings.xml`

Checks:

- Run `rg -n "GroupSettingsActivity|activity_group_settings|item_map_live_trail_share|map_live_trail_share" android/app/src/main`.
- Run `./gradlew :app:assembleDebug`.

### Step 11 - Clean Up Legacy Live Trail Share Pref API

Purpose:

- `LiveTrailShareManager` still contains APIs for the removed settings screen:
  - `getConfig`
  - `setEnabled`
  - `setScope`
  - `setMembers`
  - `onConfigChanged`
  - legacy pref keys
- After deleting `GroupSettingsActivity`, most of these should become unused.

Recommended work:

- Remove unused legacy setter/config APIs from `LiveTrailShareManager`.
- Keep only APIs still needed:
  - `SCOPE_GROUP`
  - `SCOPE_MEMBERS`
  - `ShareConfig`
  - `SharedTrailPoint`
  - `prepareManualTrailShare`
  - `onRecordingStarted`
  - `onRecordingStopped`
  - `onPoint`
- Remove legacy pref keys if no longer used.
- Consider whether `setActiveGroup/getActiveGroup` are still needed after automatic sharing uses `ActiveGroupManager`.

Files likely to edit:

- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt`
- potentially `android/app/src/main/java/app/peermesh/GroupMapActivity.kt` if it still calls `LiveTrailShareManager.setActiveGroup`

Checks:

- Run `rg -n "LiveTrailShareManager\\.(getConfig|setEnabled|setScope|setMembers|onConfigChanged|setActiveGroup|getActiveGroup)" android/app/src/main`.
- Run `./gradlew :app:assembleDebug`.

### Step 12 - Add Focused Tests Or Debug Verification Hooks

Purpose:

- Most work so far is Android UI/runtime logic.
- There are no dedicated tests for trusted contacts sharing behavior yet.

Recommended work options:

- Add unit tests for pure stores/parsing where practical.
- Add instrumentation/manual QA checklist if Android UI test infra is not present.
- At minimum document manual test cases in `docs/tests/` or `docs/backlogs/`.

Files likely to add:

- `android/app/src/test/java/app/peermesh/TrustedContactsStoreTest.kt` if app unit tests can access Android prefs via Robolectric or equivalent.
- Or `docs/tests/trail_sharing_trusted_contacts_manual_checks.md`.

Manual cases to cover:

- Manual Trail -> Entire group emits `share_scope=group`.
- Manual Trail -> Selected members emits `share_scope=members` with selected DIDs.
- Automatic Trail with zero trusted contacts records locally and emits no live share start.
- Automatic Trail with trusted contacts emits `share_scope=members` and `share_to` trusted contact DIDs.
- Stop trail sends live trail end only when a live share started.

### Step 13 - Improve Trusted Contacts UX Polish

Purpose:

- `TrustedContactsActivity` is functional but simple.
- It currently uses dynamic CheckBox rows; no RecyclerView adapter.

Recommended work:

- Add clearer empty state and disabled Save state when no contacts are available.
- Consider showing group name in title/subtitle.
- Consider moving row UI to XML if consistency matters.
- Consider adding a count to the Trails screen button or subtitle.

Files likely to edit:

- `android/app/src/main/java/app/peermesh/TrustedContactsActivity.kt`
- `android/app/src/main/res/layout/activity_trusted_contacts.xml`
- `android/app/src/main/res/values/strings.xml`
- optionally add an item layout such as `android/app/src/main/res/layout/item_trusted_contact.xml`

Checks:

- Build and inspect on a device/emulator if available.

### Step 14 - Reconcile Pro Gates And Product Policy

Purpose:

- Manual Trail sharing now requires `FEATURE_SHARED_LIVE_TRAIL`.
- Trusted Contacts management also requires `FEATURE_SHARED_LIVE_TRAIL`.
- Trail recording itself requires `FEATURE_TRAIL`.
- Product policy may want Trusted Contacts visible but gated differently.

Recommended work:

- Review `PeerMeshProGate` and `PeerMeshCapabilities` usage.
- Decide whether Trusted Contacts should be visible in free mode, disabled, or paywalled.
- Ensure copy does not promise auto-sharing when capability blocks live trail broadcast.

Files likely to edit:

- `android/app/src/main/java/app/peermesh/PeerMeshProGate.kt`
- `android/app/src/main/java/app/peermesh/PeerMeshCapabilities.kt`
- `android/app/src/main/java/app/peermesh/TrailActivity.kt`
- `android/app/src/main/java/app/peermesh/TrustedContactsActivity.kt`
- `android/app/src/main/res/values/strings.xml`

### Step 15 - End-To-End Device Smoke Test

Purpose:

- Validate runtime event behavior across devices.

Recommended work:

- Build APK.
- Install on at least two devices/emulators in same group.
- Configure one trusted contact.
- Trigger automatic trail start.
- Confirm live trail appears only for trusted contact.
- Confirm manual trail audience still works independently.

Useful commands:

- `cd android && ./gradlew :app:assembleDebug`
- Existing project ADB install patterns may already be approved in this Codex environment.

Files likely to add/update:

- `docs/tests/trail_sharing_trusted_contacts_manual_checks.md`
- Optional logs under `logs/` should not be committed unless intentionally documenting results.

## Known Design/Implementation Caveats

- Step 9 means Automatic Trail no longer uses legacy live trail settings. This is intended.
- Manual Trail still calls `LiveTrailShareManager.prepareManualTrailShare(...)` before `TrailRecorder.startManual(...)`.
- `LiveTrailShareManager.onRecordingStarted(...)` now branches by `TrailRecordingMode`.
- Automatic recording with no trusted contacts silently records locally and does not start live share. The Trails indicator tells users when count is zero.
- `GroupSettingsActivity` is stale and should be removed soon.
- Existing users may have old `live_trail_share_*` prefs; no migration has been implemented because the semantics changed.

## Verification Run So Far

For Steps 2 through 9, `./gradlew :app:assembleDebug` was run successfully after implementation.

Build warnings seen repeatedly are pre-existing Kotlin warnings mostly in:

- `GroupMapActivity.kt`
- `TrailPlaybackActivity.kt`
- some deprecated location APIs in `TrailRecorder.kt`

They were not introduced by this redesign work.
