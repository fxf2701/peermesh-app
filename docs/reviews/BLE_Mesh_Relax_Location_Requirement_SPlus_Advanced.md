# BLE Mesh -- Relax Location Requirement on Android 12+ (S+)

## Advanced Technical Review & Production Readiness Guide

**Project:** Peer Mesh\
**Module:** BleForegroundService / Permission Handling\
**Audience:** Android Platform Team, Security Review Team, QA Team\
**Priority:** MEDIUM-HIGH\
**Revision:** Advanced Review Version

------------------------------------------------------------------------

# 1. Context & Architectural Background

Historically (Android 6--11), BLE scanning was tightly coupled with:

-   ACCESS_FINE_LOCATION permission
-   Location services enabled (GPS toggle)

Reason: BLE beacons could be used to infer physical location.

Starting Android 12 (API 31):

Google introduced dedicated Bluetooth runtime permissions:

-   BLUETOOTH_SCAN
-   BLUETOOTH_CONNECT
-   BLUETOOTH_ADVERTISE

Location is no longer technically required for BLE scanning **if the app
does not derive physical location**.

------------------------------------------------------------------------

# 2. Current Problem

The app currently enforces:

    LocationManagerCompat.isLocationEnabled(locationManager)

This blocks mesh startup when Location is OFF, even on Android 12+
devices where:

-   BLE permissions are granted
-   Bluetooth is enabled
-   Advertiser is available

Impact:

-   UX degradation
-   Increased support tickets
-   Perception that mesh is unstable
-   Forced enabling of GPS unnecessarily

------------------------------------------------------------------------

# 3. Platform Compatibility Matrix

  ----------------------------------------------------------------------------
  Android        API      BLE Permission     Location          Proposed
  Version                 Model              Required?         Behavior
  -------------- -------- ------------------ ----------------- ---------------
  Android 6--10  23--29   Location-based     Yes               Hard require

  Android 11     30       Location-based     Yes               Hard require

  Android 12     31       Bluetooth runtime  No                Soft allow

  Android 13     33       Bluetooth +        No                Soft allow
                          Notification                         

  Android 14     34       Bluetooth runtime  No                Soft allow

  Android 15     35       Bluetooth runtime  No                Soft allow
  ----------------------------------------------------------------------------

------------------------------------------------------------------------

# 4. OEM Behavior Notes

Certain OEM variants (Samsung, Xiaomi, Oppo):

-   May still couple BLE scan internally with location toggle in
    aggressive privacy modes.
-   May throttle background scan under power-saving profiles.

Mitigation:

-   Field test across Samsung / Xiaomi / Pixel devices.
-   Log diagnostics when scan returns zero devices for extended period.
-   Do not hard-block mesh; allow runtime fallback diagnostics.

------------------------------------------------------------------------

# 5. Privacy & Security Considerations

## 5.1 Are we deriving location?

Current mesh design: - Uses BLE for peer discovery - Does NOT compute
physical location - Does NOT use RSSI trilateration - Does NOT log
geographic data

Conclusion: Allowing BLE scan without GPS does not increase privacy
risk.

## 5.2 Manifest Flags

Ensure:

    usesPermissionFlags="neverForLocation"

is set for BLUETOOTH_SCAN in manifest (if applicable).

Example:

``` xml
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
```

------------------------------------------------------------------------

# 6. Production-Grade Implementation Plan

## 6.1 Version Gate

``` kotlin
val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

if (isPreS) {
    if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
        return false
    }
} else {
    if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
        Log.i(TAG, "Location disabled (allowed on Android S+)")
    }
}
```

## 6.2 Permission Verification Still Required

Do NOT relax:

-   BLUETOOTH_SCAN
-   BLUETOOTH_CONNECT
-   BLUETOOTH_ADVERTISE

Location toggle relaxation must NOT bypass permission checks.

------------------------------------------------------------------------

# 7. QA Deep Test Plan

## 7.1 Functional Tests

-   Android 11 device: Location OFF → mesh blocked
-   Android 12 device: Location OFF → mesh starts
-   Android 13 device: Location OFF → mesh starts
-   Android 14 device: Location OFF → mesh starts

## 7.2 Regression Tests

-   Scan returns peers normally
-   Advertiser continues broadcasting
-   Background service survives screen off
-   No crash in BleForegroundService

## 7.3 OEM Cross-Test

Devices recommended:

-   Samsung OneUI 5/6
-   Xiaomi MIUI 14
-   Pixel AOSP
-   Oppo ColorOS

------------------------------------------------------------------------

# 8. Telemetry / Diagnostics Recommendation

Add debug log events:

-   "mesh_start_blocked_location_preS"
-   "mesh_start_allowed_location_disabled_Splus"

This helps support team distinguish intentional behavior vs bug.

------------------------------------------------------------------------

# 9. Risk Analysis

  Risk                           Probability   Impact   Mitigation
  ------------------------------ ------------- -------- ----------------------
  OEM still requiring Location   Low           Medium   Field testing
  Unexpected scan throttling     Medium        Low      Diagnostics
  Privacy audit concern          Low           Low      Manifest flag review
  Android \< S regression        Low           High     Explicit API check

------------------------------------------------------------------------

# 10. Rollout Strategy

1.  Implement version gating
2.  Add diagnostics logs
3.  Internal QA validation
4.  Beta channel release
5.  Monitor crash + mesh stability logs
6.  Full production rollout

------------------------------------------------------------------------

# 11. Expected Production Outcome

-   Mesh works on Android 12+ without forcing GPS ON
-   Improved onboarding experience
-   Reduced support friction
-   Better compliance with modern Android permission model

------------------------------------------------------------------------

# 12. Conclusion

Relaxing Location requirement on Android S+ aligns with:

-   Modern Android permission architecture
-   Better UX
-   No added privacy risk
-   Improved production stability

Recommended to implement in next minor release.

------------------------------------------------------------------------

**End of Advanced Review Document**
