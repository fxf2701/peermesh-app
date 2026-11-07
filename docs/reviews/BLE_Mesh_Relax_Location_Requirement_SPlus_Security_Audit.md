# BLE Mesh -- Relax Location Requirement on Android 12+ (S+)

## Security & Platform Audit Edition

**Project:** Peer Mesh\
**Module:** BLE Mesh Transport / Foreground Service\
**Audience:** Security Review, Privacy Audit, Platform Compliance Team\
**Revision:** Security & Platform Audit Edition

------------------------------------------------------------------------

# 1. Purpose of This Document

This document expands the technical proposal to relax Location
requirement on Android 12+ with:

-   Security threat modeling
-   Privacy impact assessment
-   Platform compliance validation
-   Permission audit checklist
-   OEM behavior risk evaluation
-   Abuse and misuse scenarios

This edition is intended for formal internal security review before
production rollout.

------------------------------------------------------------------------

# 2. Security Threat Model

## 2.1 Assets

-   Peer identity (senderKey / advertisedDeviceId)
-   Encrypted message payload
-   BLE transport channel
-   Runtime permission state
-   Foreground service lifecycle

## 2.2 Trust Boundaries

-   Android permission system
-   BLE stack (OS level)
-   Foreground service execution boundary
-   Inter-app communication (none exposed)

## 2.3 Attack Surfaces

  Surface                Description
  ---------------------- ------------------------------
  BLE scanning           Device discovery
  BLE advertising        Device presence broadcast
  Runtime permissions    Abuse of granted permissions
  Background execution   Long-running service misuse
  Location inference     Indirect tracking risk

------------------------------------------------------------------------

# 3. Privacy Impact Assessment

## 3.1 Does relaxing Location increase tracking risk?

Current design:

-   No GPS access
-   No geographic coordinates stored
-   No RSSI triangulation
-   No beacon mapping to physical location
-   No server-side aggregation of device movement

Conclusion:

Relaxing Location requirement on Android S+ does NOT increase tracking
capability because:

-   BLUETOOTH_SCAN permission is already required
-   Manifest should declare `neverForLocation`
-   No code derives or stores geographic data

------------------------------------------------------------------------

# 4. Manifest Compliance Checklist

Security team must verify:

## 4.1 Bluetooth Permissions (Android 12+)

``` xml
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />

<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

## 4.2 Location Permission (Pre-S)

``` xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Ensure:

-   Location permission is NOT requested on S+ if not required for other
    features.
-   Runtime request flow is version-aware.

------------------------------------------------------------------------

# 5. Abuse Scenario Analysis

## 5.1 Could app silently scan nearby devices without user knowledge?

Mitigations:

-   Foreground service notification always visible
-   No hidden background scanning without notification
-   Mesh requires explicit user activation

## 5.2 Could relaxing Location allow stealth scanning?

No, because:

-   Bluetooth runtime permissions are still required
-   User must grant BLUETOOTH_SCAN explicitly
-   Foreground service visible

## 5.3 Could attackers exploit BLE transport?

No change from current threat model:

-   Transport remains encrypted at envelope layer
-   CRC only for integrity, not security
-   No change in cryptographic surface

------------------------------------------------------------------------

# 6. Platform Behavior Deep Analysis

## 6.1 Android 12+ Behavior

Google officially decoupled BLE scan from Location toggle if:

-   App uses BLUETOOTH_SCAN
-   App declares neverForLocation flag

However:

Certain OEM variants may:

-   Still internally check Location state
-   Restrict scan under aggressive battery saver

Recommendation:

Do NOT hard-block mesh. Instead: - Allow startup - Monitor scan
results - Log diagnostics if zero peers detected long-term

------------------------------------------------------------------------

# 7. Compliance Review Checklist

Security team should confirm:

-   [ ] No code path uses Location APIs on S+
-   [ ] No geolocation stored in logs
-   [ ] No beacon-to-location mapping
-   [ ] Foreground notification always shown
-   [ ] Permission request flow is version-gated
-   [ ] BLE scanning disabled if BLUETOOTH_SCAN revoked
-   [ ] Background execution respects Android power policy

------------------------------------------------------------------------

# 8. Performance & Power Impact Analysis

Relaxing Location requirement does NOT:

-   Increase scan frequency
-   Increase advertiser duty cycle
-   Change scanning interval parameters

Power profile remains identical.

However, QA should measure:

-   Idle drain with Location OFF vs ON
-   Scan latency
-   Advertise latency

Expected: no measurable difference.

------------------------------------------------------------------------

# 9. Logging & Observability Recommendations

Add structured log tags:

-   mesh_start_blocked_preS_location_disabled
-   mesh_start_allowed_Splus_location_disabled
-   mesh_scan_zero_results_warning

These help:

-   Support team
-   Security audits
-   Regression detection

------------------------------------------------------------------------

# 10. Risk Matrix

  Risk                          Probability   Impact   Risk Level
  ----------------------------- ------------- -------- ------------
  OEM still requires Location   Low           Medium   Low
  Misconfigured manifest        Medium        High     Medium
  Incorrect permission flow     Low           High     Medium
  Privacy misinterpretation     Low           Medium   Low

------------------------------------------------------------------------

# 11. Security Recommendation

Security team recommendation:

✔ Approve relaxing Location requirement on Android S+\
✔ Maintain strict permission checks\
✔ Enforce foreground service visibility\
✔ Keep diagnostics enabled in debug builds

------------------------------------------------------------------------

# 12. Final Conclusion

Relaxing Location requirement on Android 12+ is:

-   Platform-aligned
-   Privacy-safe
-   Security-neutral
-   UX-improving

This change does NOT increase attack surface or tracking capability.

Approved for controlled rollout after QA verification.

------------------------------------------------------------------------

**End of Security & Platform Audit Edition**
