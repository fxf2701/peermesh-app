# Peer Mesh Play Billing Internal Test Plan

Use this plan for the Google Play internal testing track before production release. Local debug overrides are useful for gate regression, but they do not prove Play Billing, trial offers, purchase acknowledgement, restore, or subscription expiry.

## Scope

| Item | Value |
|---|---|
| Package | `app.peermesh` |
| Artifact | Official release AAB |
| Subscription product ID | `peer_mesh_pro_monthly` |
| Offer | 14-day free trial, then USD 4.99/month |
| Paywall copy | `Try Peer Mesh Pro free for 14 days. Then $4.99/month. Cancel anytime.` |
| Free fallback | Chat, create/join group, QR, and deeplink remain available. |
| Pro gates | Map, Trail, Shared Live Trail, Relay Sync, Enhanced Reliability. |

## Play Console Setup

| Check | Expected |
|---|---|
| App package | Play Console app uses `app.peermesh`. |
| Internal testing track | Active, with tester email allowlist. |
| Release artifact | Upload `android/app/build/outputs/bundle/officialRelease/app-official-release.aab`. |
| Subscription product | `peer_mesh_pro_monthly` exists and is active. |
| Base plan | Monthly auto-renewing subscription priced at USD 4.99 or equivalent. |
| Trial offer | 14-day free trial is active for eligible users. |
| License testers | Test accounts are configured for Play Billing tests. |
| Store listing links | Privacy Policy and Terms URLs are reachable. |

## Device Setup

| Check | Expected |
|---|---|
| Install source | App is installed from the internal testing Play Store link, not ADB. |
| Account | Device is signed into a tester Google account. |
| Existing app | Any locally signed `app.peermesh` build is uninstalled first if signature mismatch occurs. |
| Network | Internet is available for billing product/purchase queries. |
| Baseline state | Fresh install starts as Free unless the tester account already owns the subscription. |

## Test Matrix

### 1. Free Baseline

| Step | Expected |
|---|---|
| Launch fresh internal-test install. | App opens without crash. |
| Create a group. | Group is created. |
| Join group by ID/QR/deeplink. | Join succeeds. |
| Send/receive chat between two devices. | Chat works without Pro. |
| Open Map, Trail, Shared Live Trail, Relay Sync. | Pro unlock/paywall opens; feature does not start. |

### 2. Product And Trial Offer

| Step | Expected |
|---|---|
| Open Pro unlock from a gated feature. | Paywall copy is visible and feature-specific text is correct. |
| Tap upgrade CTA. | Play Billing sheet opens. |
| Inspect product title/price. | Monthly subscription is shown. |
| Inspect trial. | 14-day free trial is shown for eligible tester accounts. |
| Cancel purchase sheet. | App remains Free; gated features remain locked. |

### 3. Trial Purchase Success

| Step | Expected |
|---|---|
| Complete trial subscription purchase with tester account. | Billing returns success. |
| Return to app. | Pro unlock status becomes active. |
| Open Map. | Map opens and can create marker/area events. |
| Open Trail. | Trail UI opens and recorder can start/stop. |
| Enable Shared Live Trail. | Shared live trail flow works. |
| Enable Relay Sync. | Relay lifecycle can start without foreground-service crash. |
| Restart app. | Entitlement refresh keeps Pro active. |

### 4. Restore Purchase

| Step | Expected |
|---|---|
| Reinstall app from internal testing link using same tester account. | App launches. |
| Open Pro unlock and tap Restore Purchase. | Entitlement becomes active. |
| Open Pro features. | Map/Trail/Relay are available. |

### 5. No Active Subscription

| Step | Expected |
|---|---|
| Use tester account without subscription. | App starts Free. |
| Tap Restore Purchase. | App shows no active subscription. |
| Open Chat. | Chat remains available. |
| Open Pro features. | Paywall/gates remain active. |

### 6. Billing Unavailable / Unknown

| Step | Expected |
|---|---|
| Disable network or use a device without Play Store billing availability. | Billing query cannot verify subscription. |
| Open Pro unlock. | App indicates subscription could not be verified. |
| Tap Restore Purchase. | App shows billing unavailable, not "no active subscription". |
| Open Chat. | Chat remains available. |
| Open Pro features. | Pro remains locked until billing verification succeeds. |

### 7. Subscription Expiry / Cancellation

| Step | Expected |
|---|---|
| Cancel subscription in Play test account tools. | Purchase becomes inactive after Play test renewal/expiry window. |
| Restart app or resume app. | Entitlement refresh runs. |
| Open Chat. | Chat remains available. |
| Open Map/Trail/Relay. | Pro features are gated. |

## Evidence To Capture

| Evidence | Purpose |
|---|---|
| Play Console internal release version. | Confirms tested artifact. |
| Tester account email category, not password. | Repro context without secrets. |
| Screenshots of billing sheet trial/price. | Confirms offer presentation. |
| Screenshots of paywall and active status. | Confirms app UI. |
| App version and Android version. | Repro context. |
| Crash report, if any. | Regression tracking. |

## Exit Criteria

Step 14 can pass when:

- Official release AAB is accepted by Play internal testing.
- New tester account sees the 14-day trial and USD 4.99/month subscription.
- Purchase success unlocks Map, Trail, Shared Live Trail, Relay Sync, and Enhanced Reliability.
- Restore purchase unlocks Pro for an active subscriber.
- Cancel/no active subscription keeps Chat free and gates Pro features.
- Billing unavailable falls back to Free/Unknown without blocking Chat.
- No crash occurs when opening Test/debug-gated paths in debug builds or Pro-gated paths in release builds.
