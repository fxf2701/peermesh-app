# BLE Mesh -- Relax Location Requirement on Android 12+ (S+)

## Technical Specification & Implementation Guide

**Project:** Peer Mesh\
**Module:** BleForegroundService / Permission Handling\
**Priority:** MEDIUM-HIGH (UX & Compatibility Improvement)\
**Status:** Proposed

------------------------------------------------------------------------

# 1. Executive Summary

Hiện tại hệ thống đang yêu cầu:

-   Bluetooth bật
-   BLE advertiser khả dụng
-   Location phải bật

Điều này đúng với Android \< 12 (S), nơi BLE scan phụ thuộc Location
permission & Location enabled.

Tuy nhiên, từ Android 12 (API 31) trở lên:

-   BLE sử dụng các permission mới:
    -   BLUETOOTH_SCAN
    -   BLUETOOTH_CONNECT
    -   BLUETOOTH_ADVERTISE
-   Location không bắt buộc phải bật nếu app không derive physical
    location.

Việc vẫn yêu cầu Location enabled trên Android S+ gây:

-   UX kém
-   Support issue: "Mesh không chạy dù đã cấp đủ BLE permission"
-   Người dùng phải bật GPS không cần thiết

------------------------------------------------------------------------

# 2. Mục tiêu Task

-   Chỉ yêu cầu Location enabled trên Android \< S
-   Trên Android S+:
    -   Không block mesh nếu Location disabled
    -   Có thể hiển thị warning nhẹ (optional)
-   Không thay đổi behavior Android \< S

------------------------------------------------------------------------

# 3. Phân tích kỹ thuật

## 3.1 Android \< 12 (API \< 31)

BLE scan yêu cầu: - ACCESS_FINE_LOCATION - Location services enabled

Nếu không: - Scan không trả device - Hệ thống hoạt động sai

→ Vẫn phải giữ hard requirement

------------------------------------------------------------------------

## 3.2 Android 12+ (API \>= 31)

BLE scan dùng: - BLUETOOTH_SCAN - BLUETOOTH_CONNECT -
BLUETOOTH_ADVERTISE

Location: - Không bắt buộc bật - Không còn gắn chặt với scan behavior

→ Không nên block mesh chỉ vì Location disabled

------------------------------------------------------------------------

# 4. Patch Strategy

## 4.1 File liên quan

-   BleForegroundService.kt
-   BlePermissionHelper.kt

------------------------------------------------------------------------

# 5. Implementation Proposal

## BEFORE

``` kotlin
if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
    return false
}
```

## AFTER

``` kotlin
val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

if (isPreS) {
    if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
        return false
    }
} else {
    // Android 12+: do not hard block
    // Optional: log warning
}
```

------------------------------------------------------------------------

# 6. Optional UX Improvement

Trên Android 12+:

Nếu Location disabled: - Log: "Location disabled (allowed on S+)" - Có
thể hiển thị non-blocking toast hoặc diagnostics badge

Không bắt buộc.

------------------------------------------------------------------------

# 7. Test Plan

## Scenario 1 -- Android 11 (API 30)

-   Location OFF
-   BLE permissions granted
-   Expected: mesh NOT start

## Scenario 2 -- Android 12+ (API 31+)

-   Location OFF
-   BLE permissions granted
-   Expected: mesh start normally

## Scenario 3 -- Android 12+

-   Location ON
-   BLE permissions granted
-   Expected: mesh start normally

## Scenario 4 -- Permission missing

-   Missing BLUETOOTH_SCAN
-   Expected: mesh NOT start

------------------------------------------------------------------------

# 8. Risk Assessment

  Risk                      Level   Mitigation
  ------------------------- ------- ---------------------------
  Behavior difference OEM   Low     Field test
  Privacy concern           Low     App không derive location
  Regression Android \< S   Low     Explicit version check

------------------------------------------------------------------------

# 9. Rollout Plan

1.  Implement version check
2.  Test on API 29/30
3.  Test on API 31/33/34
4.  Enable debug logs
5.  Release beta build
6.  Monitor mesh diagnostics

------------------------------------------------------------------------

# 10. Expected Outcome

-   Mesh chạy ổn trên Android 12+ khi Location OFF
-   UX cải thiện
-   Giảm support issue
-   Không ảnh hưởng Android cũ

------------------------------------------------------------------------

**End of Document**
