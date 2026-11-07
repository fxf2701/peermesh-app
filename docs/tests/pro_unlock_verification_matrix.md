# Peer Mesh Pro Unlock Verification Matrix

Use this checklist before merging Pro unlock changes into a release branch. The production app is a single package (`app.peermesh`) with Free as the default state and Pro unlocked by Google Play subscription or trial entitlement.

Before release candidate promotion, run the production smoke checklist in `docs/tests/production_release_smoke_checklist.md`.

For real Play subscription validation, run the internal testing track plan in `docs/tests/play_billing_internal_test_plan.md`.

## Build Targets

| Target | Command | Expected |
|---|---|---|
| Official debug | `cd android && ./gradlew :app:assembleOfficialDebug` | Debug APK builds and exposes Test screen entitlement overrides. |
| Emergency debug | `cd android && ./gradlew :app:assembleEmergencyDebug` | Emergency debug APK builds. |
| Official release | `cd android && ./gradlew :app:assembleOfficialRelease` | Release APK builds with R8/resource shrink and no dev override. |
| Official release smoke | `./scripts/debug-kit/mesh_debug.sh release-smoke` | Release APK/AAB builds and key release settings are checked. |

## Entitlement States

Debug builds allow local state switching from `TestActivity > Pro entitlement debug`. Release builds must not expose these controls because `BuildConfig.ALLOW_PRO_DEV_OVERRIDE=false`.

| State | Source | Unlocks Pro | Expected capability tier |
|---|---|---:|---|
| Free | default or dev override | No | Free / Standard reliability |
| Trial | billing or dev override | Yes | Pro / Enhanced reliability |
| Pro | billing or dev override | Yes | Pro / Enhanced reliability |
| Expired | billing or dev override | No | Free / Standard reliability |
| Unknown | billing unavailable or dev override | No | Free / Standard reliability |

## Free State

| Area | Check | Expected |
|---|---|---|
| Chat | Create group, join group, send/receive message | Works without Pro. |
| QR/deeplink | Join with QR and `peermesh://join?...` | Works without Pro. |
| Drawer Trail entry | Tap Trail | Opens Pro unlock/paywall or remains gated by strategy. |
| Chat menu Map entry | Tap Map from group/chat flow | Opens Pro unlock/paywall. |
| Direct Map activity guard | Open Map after setting Free | Activity exits or redirects to Pro unlock; no map events are generated. |
| Direct Trail activity guard | Open Trail after setting Free | Activity exits or redirects to Pro unlock; no recorder lifecycle starts. |
| Relay/Enhanced reliability | Toggle relay sync or background enhanced features | Disabled or paywalled; no relay service/work starts. |
| Event generation | Create chat activity and inspect logs/DB | CHAT and required SYS events can be created; MAP, LIVE_TRAIL, relay-only events are blocked. |

## Trial And Pro Active

| Area | Check | Expected |
|---|---|---|
| Paywall | Set Trial or Pro, open Pro unlock | Status shows active and Pro flows can close/continue. |
| Map | Open group map and create/update marker | Map UI works and local MAP events can be generated. |
| Trail | Start/stop Trail recording | Recorder and foreground service can run. |
| Shared Live Trail | Enable live trail sharing from Map/Trail flow | Live trail events and cleanup/backfill lifecycle are allowed. |
| Relay sync | Enable relay sync with valid settings | Relay service/work can start. |
| Lifecycle refresh | Change entitlement from Free to Pro while app is running | Pro lifecycle hooks refresh without needing a force stop. |
| Downgrade | Change entitlement from Pro to Free/Expired while app is running | Pro lifecycle stops or becomes inert; UI gates refresh. |

## Expired

| Area | Check | Expected |
|---|---|---|
| Chat | Send/receive message | Still works. |
| Pro features | Open Map, Trail, Shared Live Trail, relay settings | Paywall/gated. |
| Restore | Tap Restore purchases with no active subscription | Shows no active subscription. |

## Unknown / Billing Unavailable

| Area | Check | Expected |
|---|---|---|
| Startup | Simulate billing unavailable or use Unknown debug state | App remains usable in Free tier. |
| Pro unlock status | Open Pro unlock screen | Shows that subscription could not be verified. |
| Restore | Tap Restore purchases while billing is unavailable | Shows billing unavailable, not "no active subscription". |
| Pro features | Open Map, Trail, Shared Live Trail, relay settings | Gated until billing can verify Trial/Pro. |

## Google Play Billing Manual Checks

| Flow | Expected |
|---|---|
| Product query for `peer_mesh_pro_monthly` | Product details load before purchase flow. |
| 14-day trial offer | Offer is configured in Play Console and appears in the subscription flow. |
| Purchase success | Entitlement becomes Pro, purchase is acknowledged if needed. |
| Purchase cancel | Entitlement is unchanged. |
| Restore active subscription | Entitlement becomes Pro. |
| Restore with no active subscription | Entitlement becomes Expired. |
| Billing setup/query failure | Entitlement becomes Unknown and Pro remains locked. |

Run the full Play Billing internal testing checklist before production rollout because local debug entitlement overrides do not validate Google Play purchase, restore, trial eligibility, or expiry behavior.

## Release Safety Checks

| Check | Expected |
|---|---|
| `ALLOW_PRO_DEV_OVERRIDE` in release | `false`. |
| ActivityLog in release | Disabled for Free and Pro release builds. |
| `applicationId` | Official production package remains `app.peermesh`. |
| R8/minify | Enabled for release. |
| Subscription ID | `BuildConfig.PEER_MESH_PRO_SUBSCRIPTION_ID` matches Play Console product ID. |
| Free fallback | Chat remains available even when trial expires or billing is unavailable. |
