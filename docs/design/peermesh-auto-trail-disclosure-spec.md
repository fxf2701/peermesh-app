# PeerMesh Auto Trail Disclosure Spec

This document defines the disclosure and consent UX for Auto Trail on Android.

Goal:

- make Auto Trail understandable to users before it runs
- reduce ambiguity around background behavior
- support a stronger Google Play review posture
- keep the feature compatible with local-first and trusted-contact sharing

This spec assumes:

- Auto Trail is optional
- Auto Trail is off by default
- only the device owner can enable it on their device
- Auto Trail may continue operating while the app is not visible on screen
- Trail sharing is optional and limited to trusted contacts selected by the user

## 1. Product Positioning

Auto Trail should be described as:

- user-initiated continuous trail recording
- intended for movement continuity when direct screen interaction is inconvenient
- visible while active through a persistent foreground notification
- optional to share with trusted contacts chosen by the user

Auto Trail should not be described as:

- secret tracking
- stealth safety mode
- anti-kidnapping mode
- hidden evidence collection
- impossible to detect while active

## 2. Disclosure Requirements

Before Auto Trail is enabled, the app should clearly disclose all of the following:

- Auto Trail may start recording automatically after the user enables it
- it may continue while the app is not open on screen
- a persistent notification will remain visible while Auto Trail is active
- the user can stop Auto Trail at any time
- if sharing is enabled, trail updates may be shared to trusted contacts selected by the user

The disclosure must appear:

- before first enable
- again if the disclosure text changes materially in a future update

The disclosure should not be hidden inside a long settings page or Terms screen only.

## 3. First-Time Enable Flow

When the user turns on `Background Auto` or `Auto Trail`, the app should show a dedicated consent dialog or full-screen sheet before saving the setting.

### 3.1 Recommended UI Sequence

1. user toggles `Auto Trail` on
2. app shows disclosure sheet
3. user explicitly confirms
4. app saves setting and updates UI state
5. app optionally shows a second trusted-contact reminder if sharing is configured

If the user cancels:

- Auto Trail remains off

## 4. First-Time Enable Copy

### 4.1 Short Title

`Turn on Auto Trail?`

### 4.2 Body Copy

Recommended baseline copy:

`Auto Trail can automatically start recording your movement after you enable it.`

`It may continue while PeerMesh is not open on your screen.`

`A persistent notification will stay visible while Auto Trail is active, and you can stop it at any time.`

`If you enable trail sharing, updates may be shared with the trusted contacts you choose.`

### 4.3 Buttons

Primary:

`Turn On`

Secondary:

`Cancel`

### 4.4 Optional Checkbox

Optional confirmation checkbox:

`I understand Auto Trail may continue while the app is not open on screen.`

This is useful if you want stronger evidence of explicit user acknowledgment.

## 5. Trusted Contact Reminder

If trusted-contact sharing is configured, show an additional inline reminder after Auto Trail is enabled.

Recommended copy:

`Auto Trail sharing is limited to your selected trusted contacts.`

`Review trusted contacts`

If no trusted contacts are configured:

Recommended copy:

`Auto Trail can record locally without sharing. No trusted contacts are selected right now.`

## 6. Settings Screen Copy

The Settings or Trail screen should include a short explanation under the toggle.

Recommended summary:

`Automatically record your trail when movement is detected. A visible notification stays active while Auto Trail is running.`

If sharing exists:

`Shared trail updates are sent only to trusted contacts you select.`

## 7. Notification Requirements

Auto Trail should always use a persistent foreground notification while active.

The notification should:

- be ongoing
- make it clear that trail recording is active
- include a direct `Stop` action
- optionally include an `Open` action back to the Trail screen

### 7.1 Notification Title

Recommended:

`Auto Trail is active`

or

`Trail recording is active`

### 7.2 Notification Body

Recommended examples:

- `Recording your trail. Tap to review or stop.`
- `Auto Trail is monitoring movement and recording your trail.`

Avoid vague copy such as:

- `PeerMesh is active`
- `Tracking enabled`

Those do not tell the user enough.

### 7.3 Notification Actions

Required:

- `Stop`

Recommended:

- `Open`

`Stop` should immediately stop Auto Trail or stop the current trail recording and disable auto-watch, depending on product choice.

## 8. Stop Behavior

When the user taps `Stop` from the notification:

- trail recording should stop immediately
- Auto Trail background watch should stop if that is the intended master-stop behavior
- the notification should disappear promptly after shutdown
- the app should not silently restart Auto Trail without a fresh user action

This behavior is important for user trust and for policy review.

## 9. Re-Entry UI

When the user opens the Trail screen while Auto Trail is enabled, the screen should clearly show:

- Auto Trail is on
- whether recording is currently active
- whether trusted-contact sharing is active
- the current trusted-contact count if applicable

Recommended labels:

- `Auto Trail: On`
- `Recording now`
- `Sharing to 2 trusted contacts`

## 10. Permission And Notification Gating

Auto Trail should not be enabled silently if prerequisites are missing.

Before enable, the app should check:

- foreground location permission
- notification permission where required by Android version
- location services enabled

If any requirement is missing, the app should show a clear explanation and CTA.

Recommended examples:

- `Location permission is required for Auto Trail.`
- `Notifications must stay enabled so Auto Trail remains visible while active.`
- `Turn on Location to use Auto Trail.`

## 11. Share Disclosure

If the user also enables live trail sharing, there should be a separate disclosure point for sharing itself.

Recommended copy:

`When sharing is enabled, Auto Trail may send trail updates to the trusted contacts you selected.`

`You can review or change trusted contacts at any time.`

This should be distinct from the local recording disclosure.

## 12. Review Notes / Play Console Narrative

When describing the feature to Google Play reviewers, use wording like:

`Auto Trail is an optional, user-initiated feature for continuous trail recording.`

`It is off by default and can only be enabled by the user on their own device.`

`When active, it runs with a persistent foreground notification and can be stopped at any time.`

`Trail sharing is optional and limited to trusted contacts selected by the user.`

Avoid reviewer notes that emphasize:

- kidnapping
- covert monitoring
- hidden safety surveillance
- secret recording

Those terms increase risk even if your implementation is legitimate.

## 13. Copy To Avoid

Do not use:

- `Protect yourself if someone kidnaps you`
- `Secretly track your route`
- `Runs silently in the background`
- `Your family can always track you without interaction`
- `Invisible safety mode`

These phrases create the wrong interpretation for both users and reviewers.

## 14. Recommended Additional Guardrails

To strengthen the disclosure model, add:

- a one-tap `Stop` action in the notification
- a dedicated `Trusted contacts` review screen linked from the disclosure
- a visible `Auto Trail is On` banner in the Trail screen
- an audit entry when Auto Trail is enabled or disabled
- a timestamp showing when Auto Trail was last active

## 15. QA Checklist

Before release, verify:

- Auto Trail is off by default on fresh install
- first-time enable always shows disclosure
- canceling disclosure leaves Auto Trail off
- notification appears whenever Auto Trail is active
- notification includes a working `Stop` action
- opening the app clearly shows Auto Trail status
- sharing disclosure appears when trusted-contact sharing is involved
- trusted contacts are easy to review and modify

## 16. Bottom Line

The disclosure standard for Auto Trail should be:

- explicit before first enable
- clear that it may run while the app is not open on screen
- clear that a persistent notification stays visible
- clear that the user can stop it at any time
- clear that sharing is optional and limited to trusted contacts chosen by the user

If PeerMesh meets those conditions, Auto Trail is easier to defend as a user-initiated and visible feature rather than a covert tracking feature.
