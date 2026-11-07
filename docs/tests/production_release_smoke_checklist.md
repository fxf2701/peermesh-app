# Peer Mesh Production Release Smoke Checklist

Use this checklist for every release candidate before uploading to Google Play internal testing or promoting an internal build to wider testing.

For the full Vietnamese Google Play release runbook, see `docs/private/google_play_release_guide_vi.md`.

## Release Candidate

| Item | Value |
|---|---|
| Branch | `production-free-tier-pro-unlock` |
| Package | `app.peermesh` |
| Build command | `./scripts/debug-kit/mesh_debug.sh release-smoke` |
| APK artifact | `android/app/build/outputs/apk/official/release/app-official-release.apk` |
| AAB artifact | `android/app/build/outputs/bundle/officialRelease/app-official-release.aab` |
| Subscription product ID | `peer_mesh_pro_monthly` |
| Trial/price | 14-day trial, then USD 4.99/month |

## Preflight

| Check | Expected | Result |
|---|---|---|
| Release smoke command | Builds Official release APK and AAB. |  |
| Package name | `app.peermesh`. |  |
| SDK levels | `minSdk=26`, `targetSdk=35`. |  |
| Pro subscription ID | `peer_mesh_pro_monthly`. |  |
| Release dev override | Disabled. |  |
| Release ActivityLog | Disabled. |  |
| Release debug tools | Disabled. |  |
| TestActivity | Removed from release manifest. |  |
| ActivityLog services/receivers | Removed from release manifest. |  |

## Device Matrix

Run smoke tests on at least one device per Android band that the app supports or targets for early production.

| Device | Android | Install source | Account state | Result |
|---|---|---|---|---|
|  | Android 8 or 9 | Internal testing link | Free/no subscription |  |
|  | Android 10 or 11 | Internal testing link | Free/no subscription |  |
|  | Android 12 or 13 | Internal testing link | Trial/Pro tester |  |
|  | Android 14 or 15 | Internal testing link | Trial/Pro tester |  |

## Free Tier Smoke

| Flow | Expected | Result |
|---|---|---|
| Fresh launch | App opens without crash. |  |
| Create group | Group is created. |  |
| Join by group ID | Join succeeds. |  |
| Join by QR | QR scan fills invite details and join succeeds. |  |
| Join by `peermesh://join?...` | Deeplink opens app and fills invite details. |  |
| Send/receive chat | Chat works between two devices. |  |
| Restart app | Groups and chat remain available. |  |
| Open Map | Pro unlock/paywall opens; Map does not start. |  |
| Open Trail | Pro unlock/paywall opens; Trail recorder does not start. |  |
| Enable Relay Sync | Gated or disabled; relay service/work does not start. |  |

## Pro Smoke

Use a tester account with an active trial or active subscription.

| Flow | Expected | Result |
|---|---|---|
| Open Pro unlock | Active Trial/Pro state is shown. |  |
| Open Map | Map opens. |  |
| Create marker | Marker is created and visible. |  |
| Draw area | Area is created and visible. |  |
| Open Trail | Trail UI opens. |  |
| Start/stop Trail recording | Foreground service starts/stops without crash. |  |
| Trail playback | Trail line and dots render. |  |
| Shared Live Trail | Shared trail renders in Map UI. |  |
| Relay Sync | Relay lifecycle starts without foreground-service crash. |  |
| Downgrade/expire account | Chat remains available; Pro features become gated. |  |

## Billing Smoke

Run the full billing test plan in `docs/tests/play_billing_internal_test_plan.md`. Record the summary here.

| Flow | Expected | Result |
|---|---|---|
| Product query | Play Billing returns `peer_mesh_pro_monthly`. |  |
| Trial presentation | 14-day trial and USD 4.99/month are visible to eligible testers. |  |
| Purchase success | Pro unlocks. |  |
| Purchase cancel | Free state remains unchanged. |  |
| Restore active purchase | Pro unlocks. |  |
| Restore without active purchase | State becomes Expired or remains Free; Chat works. |  |
| Billing unavailable | State becomes Unknown; Chat works; Pro remains gated. |  |

## Release Exit Criteria

The release candidate can move forward when:

- `./scripts/debug-kit/mesh_debug.sh release-smoke` passes.
- Official release AAB installs through Google Play internal testing.
- Free tier Chat, group create/join, QR, and deeplink work on the device matrix.
- Pro Map, Trail, Shared Live Trail, Relay Sync, and Enhanced Reliability work for active Trial/Pro testers.
- Expired, Free, and Unknown billing states do not block Chat.
- No crash appears in the app crash report screen during smoke testing.
- Any failed row above has a linked issue, fix commit, or explicit release-blocking decision.
