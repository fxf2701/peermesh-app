# Trail Sharing & Trusted Contacts Redesign - Step 1 Audit

## Scope

This audit maps the current Android implementation before separating:

- Manual Trail: user starts a temporary sharing session and chooses the audience at runtime.
- Auto Trail: automatic safety tracking shares only with persistent Trusted Contacts.

Source UX direction: `docs/backlogs/trail-sharing-trusted-contacts-redesign.md`.

## Current Entry Points

The current "Group settings" surface is only used for live trail sharing configuration.

- `GroupSettingsActivity` is registered in `android/app/src/main/AndroidManifest.xml:95`.
- Chat menu routes `menu_group_settings` through `PeerMeshFeatureUi.openSharedLiveTrailSettingsOrPaywall(...)`.
  - `android/app/src/main/java/app/peermesh/GroupChatActivity.kt:116`
  - `android/app/src/main/java/app/peermesh/PeerMeshFeatureUi.kt:45`
- Map menu opens `GroupSettingsActivity` directly instead of using the feature helper.
  - `android/app/src/main/java/app/peermesh/GroupMapActivity.kt:283`
- Both `group_chat_menu.xml` and `group_map_menu.xml` expose `menu_group_settings`.

## Current Sharing Configuration

`LiveTrailShareManager` stores persistent per-group live trail sharing config:

- active group: `live_trail_active_gid`
- enabled flag: `live_trail_enabled_{gid}`
- share scope: `live_trail_scope_{gid}`
- selected members: `live_trail_members_{gid}`

Relevant code:

- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt:21`
- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt:61`
- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt:69`
- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt:73`
- `android/app/src/main/java/app/peermesh/LiveTrailShareManager.kt:77`

This means sharing decisions are configured before a trail starts, which conflicts with the target manual flow.

## Current Recording Coupling

Manual and automatic recordings share the same `TrailRecorder.startRecording(...)` path.

- Manual start calls `startRecording(...)` from `TrailRecorder.startManual(...)`.
  - `android/app/src/main/java/app/peermesh/TrailRecorder.kt:189`
- Auto start also calls `startRecording(...)` when movement crosses the threshold.
  - `android/app/src/main/java/app/peermesh/TrailRecorder.kt:538`
- `startRecording(...)` always calls `LiveTrailShareManager.onRecordingStarted(...)`.
  - `android/app/src/main/java/app/peermesh/TrailRecorder.kt:659`
- `stopRecording(...)` always calls `LiveTrailShareManager.onRecordingStopped(...)`.
  - `android/app/src/main/java/app/peermesh/TrailRecorder.kt:701`

Because of this, live sharing behavior cannot currently distinguish manual audience selection from auto safety sharing.

## Current Trail Screen Behavior

`TrailActivity` has recording settings and manual start controls in one screen.

- Recording enabled switch initializes from `TrailRecorder.isEnabled(...)`.
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:148`
- Recording mode maps directly to `manual` or `auto`.
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:149`
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:216`
- Background auto toggle is separate from the mode radio group.
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:152`
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:224`
- Manual Start immediately calls `TrailRecorder.startManual(this)`.
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:265`
  - `android/app/src/main/java/app/peermesh/TrailActivity.kt:282`

The target manual UX needs an audience picker between the Start tap and `TrailRecorder.startManual(...)`.

## Reusable Existing Pieces

The old `GroupSettingsActivity` contains reusable member picker logic:

- loads profiles from `AppDatabaseProvider.get(this).userProfileDao().listByGid(gid)`
- excludes the current actor DID
- displays multi-choice labels with avatar/name
- returns selected actor DIDs

This can be extracted or duplicated carefully for:

- manual trail audience selection
- Trusted Contacts management

## Risks For Next Steps

- `GroupMapActivity` bypasses `PeerMeshFeatureUi`, so Step 2 must update both the direct map route and the chat helper route.
- Removing `GroupSettingsActivity` before adding replacement navigation would orphan the only live sharing configuration UI.
- `LiveTrailShareManager.onConfigChanged(...)` can restart sharing during an active recording, which will become undesirable for manual session sharing.
- Existing persisted `live_trail_share` prefs may remain on user devices. Later steps should ignore or migrate them deliberately.
- Trusted Contacts will need a separate store from the existing live trail selected members because their semantics are persistent safety authorization.

## Recommended Step 2 Boundary

Step 2 should only remove or neutralize the misleading "Group settings" live trail configuration entry points. It should not yet implement manual audience selection or Trusted Contacts.

Concrete Step 2 tasks:

1. Replace `menu_group_settings` behavior in Chat and Map with no live-sharing settings entry, or retitle it only if a real group settings screen is introduced.
2. Stop using `GroupSettingsActivity` as the destination for live trail sharing setup.
3. Keep `LiveTrailShareManager` behavior intact until Step 4/5 so recording and current live trail events do not regress mid-refactor.
4. Leave old strings/layout/activity in place only if needed temporarily for a compiling intermediate branch; otherwise remove once no references remain.
