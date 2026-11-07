# Phase 3.1 Task List (UX/UI)

Purpose:
- This file tracks only milestone-level UX/UI work for Phase 3.1.
- Transport, DTN, and carrier debugging details live in `docs/tech_notes/DTN_DEVELOPMENT_NOTES.md`.
- This file should stay concise: task titles, current status, and short UX/UI snapshots.

## 1) BLE Header UX
- [x] Real-time BLE header icon updates when internal mesh state changes.
- [x] BLE header color mapping reflects `OFF / PAUSED / ON / ERROR`.
- [x] BLE header state no longer depends on active group selection.
- [x] Header status icons are started on secondary screens that include the app header, including Network Status, Settings, About, Group Settings, and Additional Notices.
- [x] Header icon refresh interval is aligned to the Network Status cadence (`2s`) so BLE mesh and relay indicators stay visually consistent.
- [x] BLE header tap action restarts BLE mesh with a short delay instead of requiring hidden internal recovery paths.
- [x] BLE header tap starts BLE mesh directly when it is currently off.
- [x] BLE header long-press stop gesture exists for manual shutdown.
- [x] Hold hint toast appears during long-press (`Hold 5s to stop BLE mesh`).
- [x] Manual stop toast appears when BLE mesh is turned off.
- [x] Manual restart toast appears when BLE mesh is restarted.
- [ ] Add a more discoverable affordance for BLE header controls without cluttering the header.

## 2) Network Status UX
- [x] `Mesh Network` status color matches the BLE header icon classifier.
- [x] `Mesh Network` BLE state updates in real time without relying on polling.
- [x] Network Status copy covers paused / restarting / error conditions more clearly.
- [x] Network Status was reframed around user-facing connection health: `Mesh Network` and `Internet Relay`.
- [x] Internet Relay status now distinguishes `Internet Available`, `Sync Ready`, and `Connected` instead of showing connected too early.
- [x] Internet Relay status colors use yellow for internet-only availability and green for sync-ready / connected states.
- [x] Relay traffic is shown with sent/received totals and rate-style text when traffic is present, matching the relay sync notification mental model.
- [x] Network Status refreshes every `2s` to stay consistent with header icon updates.
- [ ] Validate that the Network Status screen remains easy to read during rapid BLE churn and recovery.

## 3) BLE Status Semantics
- [x] `OFF` maps to service not running / manually stopped state.
- [x] `PAUSED` covers startup, scanning, restarting, idle, or no-nearby-peer conditions.
- [x] `ERROR` covers stalled / disconnected transport conditions.
- [x] Shared state-to-color mapping is centralized so header and Network Status stay consistent.
- [ ] Revisit whether `PAUSED` should eventually split into separate visual treatments for `SCANNING` vs `RESTARTING`.

## 4) UX Regression Guardrails
- [x] Manual BLE mesh stop is respected and should not auto-start again until the user turns it back on.
- [x] App launch / permission recovery / Bluetooth recovery can auto-start BLE mesh when the user did not manually stop it.
- [x] Header interaction changes are scoped to UI control flow and do not change DTN payload logic directly.
- [x] Hold-to-stop cancels any pending delayed BLE restart so releasing the icon cannot restart mesh unintentionally.
- [ ] Re-validate the header tap/hold behavior on physical devices after the latest transport patches.

## 5) Chat Group UX
- [x] Group chat overflow menu includes a `Leave group` action.
- [x] `Leave group` is styled as a destructive action using the error/red status color.
- [x] Leave flow shows a confirmation dialog before local group cleanup.
- [x] Chat composer no longer shows the low-value `BLE scan results` line.
- [x] Chat composer keeps nearby-peer status on the left and right-aligns the `n/200` message length counter.
- [x] Empty-send behavior no longer surfaces a duplicate bottom `Type a message` status line; the input hint remains the only prompt.
- [x] Group chat reply Phase 1 baseline is implemented and manually verified:
  - long-press reply entry
  - composer reply preview + cancel
  - `CHAT.REPLY` send path with `reply_to`
  - quoted parent preview truncated to a short snippet
  - tap-to-scroll parent navigation
  - transient cyan highlight after jump
- [x] Group chat delete tombstone baseline is implemented:
  - long-press `Delete` action only for own messages
  - confirm dialog before delete
  - local `CHAT.DEL` apply mirrors receiver behavior
  - deleted bubbles render as muted italic `Deleted`
  - deleted parent previews no longer leak old text
- [ ] Re-check final dialog button wording on device so the safe dismiss action and destructive leave action are visually unambiguous.

## 6) Carrier / Non-Member Status UX
- [x] Network Status can show BLE mesh availability for carrier-only peers after leaving all groups.
- [x] BLE mesh status is no longer expected to collapse to `0 nearby` solely because the current profile has no active group key.
- [ ] Validate copy and status transitions for non-member carrier mode during physical DTN tests.

## 7) Settings / Pro UX
- [x] `Start Free Trial` no longer fails silently when Google Play returns no Pro product details; users now get explicit product-unavailable feedback.
- [x] Settings `Enable Cloud Sync` switch uses a compatible `CompoundButton` binding for `SwitchMaterial`, fixing the Settings crash on open.
- [x] Cloud Sync is no longer paywalled for Free users; the Settings switch now enables Cloud Sync directly.
- [x] Cloud Sync switch has dedicated thumb/track tint so the off state remains readable in both Dark and Light themes.
- [ ] Validate Cloud Sync switch visuals and toggle behavior on physical devices in both themes.
- [ ] Re-check the Pro unlock CTA on a Play-enabled build/test account to confirm the Billing sheet or product-unavailable feedback appears consistently.

## 8) Trails UX
- [x] Automatic Trail is presented as local-only in the Trails screen copy.
- [x] Automatic Trail no longer shows auto-sharing status, Trusted Contacts entry points, or sharing onboarding.
- [x] Automatic Trail recording skips live trail sharing at runtime and records locally only.
- [x] Manual Trail audience selection and sharing flow remains available separately from Automatic Trail.
- [x] Trail review draws a dot for each recorded trail point.
- [x] Trail lines were thickened for better outdoor readability.
- [x] Shared Live Trail in Map UI now uses the same dot-and-line drawing style as Trail review.
- [x] Shared Live Trail line width was normalized to `10f`.
- [x] Shared Live Trail segments are drawn as solid lines, with segment color based on the previous dot speed bucket.
- [ ] Re-check the Trails screen on device to confirm the local-only Automatic Trail copy is clear and does not imply hidden sharing behavior.

## 9) Map UI
- [x] Marker label text is white in both Light and Dark themes.
- [x] Light theme marker tip text above map markers is white for contrast.
- [x] User direction arrow on the map is larger and easier to see.
- [x] User direction arrow shape was adjusted with a notched tail to read more like a paper-plane direction marker.
- [x] Trail line rendering in Map UI was made thicker and visually aligned with Trail review.

## 10) Branding / Release Polish UX
- [x] App branding was updated from Crisis Link to Peer Mesh across user-facing surfaces.
- [x] App launcher/logo assets were rebuilt from `docs/assets/icons/peermesh-logo.svg` and `docs/assets/icons/peermesh-logo.png`.
- [x] Android launcher icon resources were regenerated from the current Peer Mesh logo asset.
- [x] Trail menu icon was removed to reduce header/menu visual noise.
- [x] Light theme secondary buttons now use a white background for cleaner contrast.
- [x] About screen copy was reviewed and polished for production readiness.
- [x] Additional Notices screen was checked and aligned with release-facing copy expectations.

## 11) Carried Over From Phase 2
- [ ] Add relay sync toggle UI outside the Test screen.
- [x] Optional UX: show deleted placeholders for tombstoned messages.
- [ ] Use neutral language labels such as `reported / observed / unverified`; avoid `official / verified / urgent`.
- [ ] Show freshness, TTL, and corroboration count on chat/map items.
- [ ] Keep the app free of authority signals such as badges/roles.
- [ ] Add progressive disclosure under load, prioritizing Map UI clustering/grouping.
- [ ] Add visible `limitations` section in-app for Emergency flavor if required.
- [ ] Apply relay-status refresh to other screens where delivery/status can change in background.
- [ ] Marker/Area tooltip should show item age, remaining TTL, and Observed/Reported state.
- [ ] Optional map performance polish: pre-simplify trail points for zoomed-out rendering.
- [ ] Optional map performance polish: cap max trail segments rendered per frame.
- [ ] Verify the Settings `Permissions & System` section after the move.
- [ ] Revisit whether marker consensus merge is still desired; if yes, define the UX and merge nearby same-type markers with similar TTL.
- [ ] If consensus merge is kept, show consensus TTL using median TTL and expose TTL range in detail.

## 12) Development Notes
- BLE header icon is now event-driven for mesh runtime changes instead of depending only on the old `2s` polling loop.
- `StatusIndicatorUpdater` owns the shared BLE state classifier and color mapping used by both the header and `StatisticsActivity`.
- The `Mesh Network` section in `Network Status` was aligned to the same BLE state semantics as the header, including paused, restarting, and error states.
- Network Status now separates relay availability levels so `Connected` is reserved for a recent successful relay sync, not merely internet availability.
- Header status icons now actively refresh on secondary screens that previously kept default gray icon tint.
- Header interaction was changed from a plain on/off toggle to:
  - tap: restart BLE mesh after a short stop window
  - hold: manual stop BLE mesh
- BLE header tap also starts BLE mesh directly when it is currently off.
- Long-hold UX now includes:
  - hint toast after holding for about `1s`
  - manual stop toast after a successful hold-stop
- Hold-stop now cancels any pending delayed restart before turning BLE mesh fully off.
- Recent fixes also addressed gesture handling bugs where long-hold could fail to trigger or could be followed by an unintended restart on release.
- Group chat now exposes leave-group from the overflow menu, with destructive styling and confirmation before cleanup.
- Group chat reply now supports:
  - long-press reply entry
  - composer reply preview + cancel
  - quoted parent snippet in message bubbles
  - tap-to-scroll parent navigation with transient highlight
- Group chat delete now supports:
  - long-press delete for own messages only
  - local tombstone/redaction apply on source device
  - muted italic deleted placeholders in bubbles and reply previews
- Carrier-only test flows should keep Network Status readable even when the user has left all groups but BLE local relay remains enabled for DTN forwarding.
- Settings UX now treats Cloud Sync as a trust-building reliability feature available to all users, while keeping other Pro feature gates separate.
- `SwitchMaterial` controls in Settings should be bound through `CompoundButton` or the exact Material type to avoid runtime cast crashes.
- Trails UX now treats Manual Trail sharing and Automatic Trail recording as separate user promises: Manual Trail can ask for an audience at start time, while Automatic Trail is local-only.
- Trail and Shared Live Trail rendering now share the same visual language: visible points, thicker solid lines, and speed-bucket coloring based on the previous point.
- Chat composer telemetry was reduced to the useful nearby-peer indicator plus right-aligned character count.
- Release-facing copy polish now includes About and Additional Notices, with broader Peer Mesh branding and icon work tracked here as UX/UI release polish.
- Current UX/UI focus is no longer “make the icon update at all”; it is now:
  - discoverability of BLE controls
  - readability during churn/recovery
  - Settings clarity for Cloud Sync and reliability-related controls
  - Trails copy/device validation for local-only Automatic Trail behavior
  - Map/Trail visual readability on physical devices
  - release polish for brand, About, and legal/support screens
  - regression checks on physical devices
