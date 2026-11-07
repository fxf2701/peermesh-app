# Android 13+ -- POST_NOTIFICATIONS Gating for Foreground Notifications

## Implementation Direction & Review Document

**Project:** Peer Mesh\
**Modules:** BLE Mesh Foreground Service, Trail Foreground Service, UI
Status/Settings\
**Audience:** Android Dev Team, QA, Security/Privacy Review\
**Priority:** HIGH (User Trust + Operational Visibility)\
**Goal:** Ensure users on Android 13+ reliably see foreground
notifications and understand state when notifications are disabled.

------------------------------------------------------------------------

# 1. Problem Statement

On Android 13+ (API 33+), posting notifications requires the runtime
permission:

-   `android.permission.POST_NOTIFICATIONS`

If the user denies this permission (or disables notifications at OS/app
level), the app may still run foreground services, but:

-   The notification may not appear or may be suppressed.
-   Users lose visibility that **mesh** or **trail recording** is
    running.
-   This increases support issues and trust risk (users feel "app runs
    secretly").

We want **gating** and **clear UX**: - Provide a clear call-to-action to
enable notifications. - Do not hard-block core features (mesh/trail can
still run), but show a warning state if notifications are disabled.

------------------------------------------------------------------------

# 2. Non-Goals

-   Do not force the user to enable notifications.
-   Do not stop mesh/trail if notifications are disabled (unless policy
    requires it later).
-   Do not request unrelated permissions (location remains separate).

------------------------------------------------------------------------

# 3. Desired Behavior (High Level)

## 3.1 Android \< 13 (API \< 33)

-   No runtime notification permission exists.
-   Notifications can still be disabled by the user at app level → treat
    as "notifications disabled" state.

## 3.2 Android 13+ (API \>= 33)

-   If `POST_NOTIFICATIONS` is not granted OR notifications are
    disabled:
    -   Show a **non-blocking warning** in UI (status indicator /
        settings).
    -   Provide a button "Enable notifications":
        -   If permission not granted → request runtime permission.
        -   If permission granted but notifications disabled → open app
            notification settings.
    -   Services still start and run, but diagnostics log should record
        this state.

------------------------------------------------------------------------

# 4. Architecture Proposal

## 4.1 Central Utility: NotificationGate

Create a small helper (single source of truth) e.g.

-   `NotificationGate.kt` (recommended), or
-   extend `BlePermissionHelper` / existing PermissionHelper pattern.

**Responsibilities** 1. Determine whether notifications are effectively
enabled: -
`NotificationManagerCompat.from(context).areNotificationsEnabled()` -
Android 13+: also require `POST_NOTIFICATIONS` granted. 2. Provide
action helpers: - `requestPostNotifications(activity)` -
`openAppNotificationSettings(context)`

**Pseudo API**

``` kotlin
object NotificationGate {
    fun isNotificationsEnabled(context: Context): Boolean
    fun needsRuntimePermission(context: Context): Boolean // API 33+ only
    fun requestPermission(activity: Activity, requestCode: Int)
    fun openSettings(context: Context)
}
```

------------------------------------------------------------------------

# 5. Implementation Details

## 5.1 Manifest

Add (if targetSdk \>= 33 or to be future-proof):

``` xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

> Note: This permission is ignored on \< 33, safe to include.

------------------------------------------------------------------------

## 5.2 Runtime Permission Flow (API 33+)

### When to request?

-   Only when user explicitly enables a feature that relies on
    visibility (mesh/trail), OR opens the settings screen and taps
    "Enable notifications".
-   Avoid requesting at app launch (unless product requires it).

### Suggested UI placement

-   Trail screen: status section / banner above "Start recording"
-   Mesh screen / main screen: status indicator banner
-   Settings screen: "Notifications" row

------------------------------------------------------------------------

## 5.3 Foreground Service Behavior

Even if notifications are disabled: - `startForeground()` should still
be called (service requirement). - Add diagnostics logs and degrade UI
state.

**Add log tags** - `notif_disabled_runtime_perm_missing` (API 33+
permission missing) - `notif_disabled_os_level` (areNotificationsEnabled
== false) - `notif_enabled`

**Example**

``` kotlin
val enabled = NotificationGate.isNotificationsEnabled(this)
if (!enabled) {
    Log.w(TAG, "Notifications disabled; foreground notification visibility may be limited.")
    // Optionally update status indicator via broadcasts/diagnostics listener
}
```

> Do NOT crash/stop service just because notifications are disabled.

------------------------------------------------------------------------

# 6. UI/UX Specification

## 6.1 Status Indicator States

Add a state marker: - **Notifications OFF** - message: "Notifications
are off. You may not see mesh/trail status." - action: "Enable
notifications"

## 6.2 Call-to-Action Behavior

If API \>= 33 and permission missing: - CTA requests permission via
`ActivityCompat.requestPermissions()`

If permission granted but notifications disabled: - CTA opens app
notification settings: - `ACTION_APP_NOTIFICATION_SETTINGS` with package
name - fallback to `ACTION_APPLICATION_DETAILS_SETTINGS`

## 6.3 Copywriting (Suggested)

-   Title: "Notifications are off"
-   Body: "This feature runs in the background with a notification.
    Please enable notifications to see status and controls."
-   Buttons:
    -   "Enable" (primary)
    -   "Not now" (secondary)

------------------------------------------------------------------------

# 7. Edge Cases & OEM Notes

-   Some OEMs suppress notifications under Battery Saver modes.
-   `areNotificationsEnabled()` may return true but channels disabled
    (Android O+):
    -   For robustness: check channel importance for the specific
        channel used by services.
    -   Optional: add
        `NotificationManager.getNotificationChannel(CHANNEL_ID)` and
        ensure `importance != IMPORTANCE_NONE`.

**Recommendation** - If channel importance is NONE → treat as disabled
and open channel settings page.

------------------------------------------------------------------------

# 8. Compatibility Matrix

  ---------------------------------------------------------------------------------
  Case   API   POST_NOTIFICATIONS   areNotificationsEnabled   Expected UI Service
  ------ ----- -------------------- ------------------------- ----------- ---------
  A      30    N/A                  true                      normal      runs

  B      30    N/A                  false                     warning +   runs
                                                              open        
                                                              settings    

  C      33    denied               true/false                warning +   runs
                                                              request     
                                                              perm        

  D      33    granted              false                     warning +   runs
                                                              open        
                                                              settings    

  E      33    granted              true                      normal      runs

  F      33    granted              true but channel NONE     warning +   runs
                                                              open        
                                                              channel     
                                                              settings    
  ---------------------------------------------------------------------------------

------------------------------------------------------------------------

# 9. Test Plan (QA)

## 9.1 Manual Tests (Recommended)

### Android 13/14

1.  Fresh install → deny POST_NOTIFICATIONS
    -   Start Trail recording → service runs, UI shows warning, CTA
        requests permission.
2.  Grant POST_NOTIFICATIONS but disable notifications in system
    settings
    -   Start Trail/Mesh → UI shows warning, CTA opens settings.
3.  Disable only the service notification channel
    -   UI should detect channel disabled (if implemented) and open
        channel settings.

### Android 11

1.  Disable notifications at app level
    -   UI warning appears, CTA opens app settings.

## 9.2 Automation Hooks (Optional)

-   Add a debug-only endpoint/state to report:
    -   `notificationsEnabled`
    -   `permissionGranted`
    -   `channelImportance`

------------------------------------------------------------------------

# 10. Rollout Plan

1.  Implement NotificationGate + manifest permission
2.  Add UI banner/CTA in Trail + Mesh screens
3.  Add diagnostics logs in services
4.  QA on API 30, 33, 34 across OEMs (Samsung/Xiaomi/Pixel)
5.  Beta release → monitor logs/support tickets
6.  Production rollout

------------------------------------------------------------------------

# 11. Security/Privacy Notes

-   This task improves transparency: user sees background operation
    clearly.
-   No additional sensitive data access introduced.
-   Avoid requesting permission at launch to prevent "permission
    fatigue" and reduce privacy concerns.

------------------------------------------------------------------------

# 12. Deliverables Checklist

-   [ ] Add POST_NOTIFICATIONS to manifest
-   [ ] Implement NotificationGate helper
-   [ ] UI warning + CTA in Trail screen
-   [ ] UI warning + CTA in Mesh screen / status indicator
-   [ ] Service logs when notifications disabled
-   [ ] Optional: channel importance check
-   [ ] QA verification across API levels and OEMs

------------------------------------------------------------------------

**End of Document**
