
# BLE Mesh – GATT preparedWrite/offset Defensive Handling
## Technical Specification & Implementation Guide

**Project:** Peer Mesh  
**Module:** BLE GATT Server – `BleMeshTransport` (write path)  
**Audience:** Android Dev Team, QA Team  
**Priority:** HIGH (Rare Corruption / OEM Stack Variance)  
**Status:** Proposed  

---

# 1. Executive Summary

The BLE mesh transport currently expects a simple GATT write pattern:

- `preparedWrite == false`
- `offset == 0`
- payload contains a full “chunk” as defined by our chunking protocol

In real-world Android BLE stacks and across OEM devices, clients may sometimes send:

- **Prepared Write** (long write flow)
- writes with **non-zero offset**

If we accept these implicitly (or ignore `offset/preparedWrite`) we risk:

- corrupted chunk payloads
- reassembly failures
- rare, hard-to-debug message corruption
- inconsistent behavior across OEM devices

**Goal:** Explicitly accept only supported write modes and **reject everything else with correct GATT status codes + diagnostics**.

---

# 2. Non-Goals

- Do not implement long-write / reliable-write support (not needed for our protocol)
- Do not change chunking protocol format
- Do not change encryption/envelope format
- Do not change mesh routing

---

# 3. Background: GATT Write Types

## 3.1 Normal Write
- `preparedWrite = false`
- `offset = 0`
- data is directly delivered in a single call

## 3.2 Prepared Write (Long Write)
- `preparedWrite = true`
- data may come in multiple segments with `offset`
- client later sends “execute write” to commit
- requires server-side buffering & offset reassembly

Our protocol already performs **application-level chunking** and does not benefit from prepared write support.

---

# 4. Requirements

## 4.1 Accept
- `preparedWrite == false`
- `offset == 0`
- payload length within expected bounds (existing checks remain)

## 4.2 Reject
- `preparedWrite == true` → reject request
- `offset != 0` → reject request

## 4.3 Diagnostics
Add counters / logs for:
- `gatt_prepared_write_rejected`
- `gatt_offset_write_rejected`
- optionally `gatt_write_rejected` with reason string

---

# 5. Implementation Plan

## 5.1 Code Location
In `BleMeshTransport.kt`, inside the GATT server callback:
- `onCharacteristicWriteRequest(...)`

We apply checks early, before feeding payload into chunking/reassembly.

---

## 5.2 Recommended Status Codes

Android’s `BluetoothGatt` provides common constants:
- `BluetoothGatt.GATT_SUCCESS`
- `BluetoothGatt.GATT_FAILURE`
- `BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED`
- `BluetoothGatt.GATT_INVALID_OFFSET`

**Recommendation**
- preparedWrite: `GATT_REQUEST_NOT_SUPPORTED`
- offset != 0: `GATT_INVALID_OFFSET` (if available), else `GATT_FAILURE`

> Some OEM stacks are picky; we should still call `sendResponse(...)` when `responseNeeded == true`.

---

## 5.3 Pseudocode Patch

```kotlin
override fun onCharacteristicWriteRequest(
    device: BluetoothDevice,
    requestId: Int,
    characteristic: BluetoothGattCharacteristic,
    preparedWrite: Boolean,
    responseNeeded: Boolean,
    offset: Int,
    value: ByteArray
) {
    if (preparedWrite) {
        diagnostics?.onGattWriteRejected(device, reason = "prepared_write")
        if (responseNeeded) {
            gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
        return
    }

    if (offset != 0) {
        diagnostics?.onGattWriteRejected(device, reason = "invalid_offset:$offset")
        if (responseNeeded) {
            val status = BluetoothGatt.GATT_INVALID_OFFSET // fallback to GATT_FAILURE if needed
            gattServer.sendResponse(device, requestId, status, 0, null)
        }
        return
    }

    // existing flow: validate characteristic + pass value to chunking/reassembly
    ...
}
```

---

# 6. QA / Test Plan

## 6.1 Functional Regression
- Send normal multi-chunk messages (Write Request and/or Write Without Response)
- Verify:
  - no regressions
  - chunking + reassembly stable
  - ACK flow unchanged

## 6.2 Negative Tests (Prepared Write)
- Use a test client (or custom debug tool) to send prepared writes:
  - `preparedWrite = true`
  - offsets 0..N
- Verify server responds:
  - request rejected (status code as expected)
  - diagnostics counter increments
  - message is not processed

## 6.3 Negative Tests (Offset != 0)
- Send a write request with `offset = 1`
- Verify server responds:
  - invalid offset status (or failure fallback)
  - diagnostics increments
  - message is not processed

## 6.4 Multi-OEM Field Test
- Samsung / Xiaomi / Pixel receivers
- Sender sends large payloads and stress traffic
- Verify no corruption and no unexpected “execute write” path

---

# 7. Observability Requirements

Add logs (debug build):
- device address
- requestId
- characteristic UUID (optional)
- preparedWrite flag
- offset value
- responseNeeded flag
- rejection status

Recommended diagnostic tags:
- `BleMeshTransport:gatt_prepared_write_rejected`
- `BleMeshTransport:gatt_offset_write_rejected`

---

# 8. Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| OEM expects support for prepared writes | Low | Low | Our protocol uses app-level chunking |
| Wrong status code causes client retries | Low | Medium | Use standard codes, still respond when needed |
| Regression in normal write flow | Low | High | Regression test suite + field test |

---

# 9. Rollout Steps

1. Implement early rejection checks
2. Add diagnostics counters/logs
3. QA: regression + negative tests
4. Beta release on mixed OEM devices
5. Monitor logs for rejection counts
6. Production rollout

---

**End of Document**
