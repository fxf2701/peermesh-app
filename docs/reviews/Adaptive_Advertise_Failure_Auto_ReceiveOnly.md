# Adaptive Advertise Failure Handling & Auto Receive-Only Mode

## Peer Mesh -- BLE Mesh Runtime Policy

**Module:** BleMeshRuntime / Advertise Supervisor\
**Priority:** HIGH\
**Status:** Proposed Implementation Spec

------------------------------------------------------------------------

# 1. Objective

Implement an adaptive policy to:

-   Distinguish **Soft Advertise Failure** from normal Bluetooth OFF
    cases
-   Retry advertising with limited backoff
-   Automatically switch to **RECEIVE_ONLY mode** when needed
-   Periodically re-check (every 5 minutes) to restore FULL_RELAY mode

> Bluetooth OFF is already handled via user prompt → DO NOT degrade to
> RECEIVE_ONLY in that case.

------------------------------------------------------------------------

# 2. Scope

This policy applies only to:

-   BLE advertise start failures (`onStartFailure`)
-   Advertiser unavailable cases (`BluetoothLeAdvertiser == null`)

This does NOT apply to: - Bluetooth OFF (handled separately) -
Permission missing (handled via UX gating)

------------------------------------------------------------------------

# 3. Mode Definitions

``` kotlin
enum class MeshMode {
    FULL_RELAY,      // advertise + scan + gatt server
    RECEIVE_ONLY     // scan + gatt server, no advertise
}
```

------------------------------------------------------------------------

# 4. Failure Classification

## 4.1 Bluetooth OFF

-   Detected via BluetoothAdapter state
-   Already handled by user prompt
-   DO NOT switch to RECEIVE_ONLY

## 4.2 Hard Failure

Examples: - Advertiser == null - OS reports feature unsupported

Action: → Switch immediately to RECEIVE_ONLY\
→ Schedule periodic re-check (5 min)

## 4.3 Soft Failure

Examples: - Internal error - Too many advertisers - Transient stack
failure

Action: → Retry with backoff\
→ If retries exhausted → switch to RECEIVE_ONLY\
→ Schedule periodic re-check

------------------------------------------------------------------------

# 5. Retry Strategy (Soft Failure)

Immediate retry sequence:

1s → 3s → 10s

If all fail: → Switch to RECEIVE_ONLY\
→ Enter cooldown phase

------------------------------------------------------------------------

# 6. Cooldown & Periodic Re-check

When in RECEIVE_ONLY due to advertise failure:

-   Schedule re-check every **5 minutes**
-   Attempt advertise start again
-   If success → switch to FULL_RELAY
-   If fail → remain RECEIVE_ONLY

No aggressive retry loop during cooldown.

------------------------------------------------------------------------

# 7. State Machine

FULL_RELAY └── advertise fail ├── if Bluetooth OFF → ignore (handled
elsewhere) ├── if hard failure → RECEIVE_ONLY └── if soft failure →
RETRYING

RETRYING ├── success → FULL_RELAY └── retries exhausted → RECEIVE_ONLY

RECEIVE_ONLY ├── periodic 5-min check │ ├── success → FULL_RELAY │ └──
fail → stay RECEIVE_ONLY └── battery/power policy may override
separately

------------------------------------------------------------------------

# 8. Configuration Parameters

``` kotlin
val ADV_SOFT_RETRY_DELAYS = listOf(1000L, 3000L, 10000L)
const val ADV_RECHECK_INTERVAL_MS = 5 * 60 * 1000L
```

------------------------------------------------------------------------

# 9. Implementation Checklist

-   [ ] Add MeshMode enum
-   [ ] Add AdvertiseSupervisor
-   [ ] Wire onStartFailure → classification
-   [ ] Implement retry scheduler
-   [ ] Implement 5-min periodic re-check
-   [ ] Add logs for mode transitions
-   [ ] Add diagnostics counters

------------------------------------------------------------------------

# 10. Logging Requirements

Log format example:

    mesh_mode_switch FULL_RELAY -> RECEIVE_ONLY reason=soft_failure
    mesh_mode_retry attempt=2 delay=3000ms
    mesh_mode_restore FULL_RELAY

------------------------------------------------------------------------

# 11. QA Test Plan

## Test 1 -- Soft Failure Simulation

-   Force advertise start failure
-   Verify retry 3 times
-   Verify switch to RECEIVE_ONLY
-   After 5 min simulate success
-   Verify restore FULL_RELAY

## Test 2 -- Hard Failure

-   Simulate advertiser null
-   Immediate RECEIVE_ONLY
-   Verify periodic re-check

## Test 3 -- Bluetooth OFF

-   Turn BT off
-   Verify no RECEIVE_ONLY switch
-   Verify user prompt behavior intact

------------------------------------------------------------------------

# 12. Expected Outcome

-   Mesh runtime becomes resilient
-   No infinite retry loops
-   Graceful degradation
-   Automatic recovery when conditions improve

------------------------------------------------------------------------

End of Document
