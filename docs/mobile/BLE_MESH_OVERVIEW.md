# BLE Mesh Overview

This document describes the BLE mesh flow from fresh install through app start, mesh restart, sending/receiving messages, and ACKs.

## 1) Big picture

BLE mesh runs two channels in parallel:

- **Advertise/Scan**: discover nearby peers and decide targets.
- **GATT Server/Client**: move real payloads using BLE writes.

Manufacturer data (device_id) is used to identify our app and de-duplicate targets.

---

## 2) High-level ASCII diagram

```
                ┌────────────────────────────────────────┐
                │              APP START                 │
                │  - init BleMeshRuntime                 │
                │  - set advertised device_id            │
                │  - start BLE scan + advertise + GATT   │
                └────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────────────────┐
│                        BLE MESH LOOP                                  │
│                                                                       │
│   [Scan] <---- BLE Adv ---- [Nearby device] ---- BLE Adv ----> [Scan] │
│      │                                                                │
│      │ ScanRecord + MfgData OK -> "matched" -> lastSeen[address]      │
│      │                                                                │
│      ▼                                                                │
│  Target selection (by device_id + best RSSI)                          │
│      │                                                                │
│      ▼                                                                │
│  GATT Connect (client) ---> GATT Server (peer)                        │
│      │                                                                │
│      ▼                                                                │
│  Chunked Write (size depends on MTU, capped)                          │
│      │                                                                │
│      ▼                                                                │
│  Reassemble -> Deliver Envelope -> Message pipeline                   │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 3) Fresh install

- Nothing special happens on install.
- **device_id is not created yet.** It is initialized on the first mesh start.

---

## 4) App open / mesh start

Sequence on first launch (or mesh start):

1. `BleMeshRuntime.init()`
2. `ensureAdvertisedDeviceId()`
   - `DeviceIdProvider.get(...)`
   - `SHA-256` digest
   - take first 8 bytes
   - store into `BleConfig.advertisedDeviceId`
   - log `BLE advertised device_id id=XXXX`
3. `BleMeshTransport` created
4. Start BLE components
   - `startScan()`
   - `startAdvertising()`
   - `startGattServer()`

---

## 5) Restart BLE mesh button

Restart is effectively:

```
Stop mesh → Close GATT → Stop scan/advertise
        ↓
Start mesh → Init scan + advertise + GATT server
```

It stops scan/advertise/GATT and restarts them, but it does **not**
explicitly clear internal queues or device caches in the current code.

---

## 6) Scan and match logic

Every scan result (when `scanFilterEnabled=false` this is higher volume):

- Extract ScanRecord
- Validate manufacturer data (if enabled)
- **Match logic**
  - If `useManufacturerData == true`: match on **manufacturer data OK**
  - If `useManufacturerData == false`: match on **service UUID present**

When matched:

- `lastSeen[address]` is updated
- RSSI saved
- device_id saved
- device becomes eligible to send/receive

Notes:

- `scanFilterEnabled=true` pushes filtering into the BLE stack, reducing CPU/log volume.
- `scanFilterEnabled=false` lets the app evaluate all scan results (better for debugging,
  but higher overhead).
- Manufacturer-data matching reduces false positives but can miss devices if the scan record
  omits or truncates manufacturer data; service-UUID matching is more permissive but noisier.

Recommended defaults for field use (balanced performance vs reliability):

- Keep `scanFilterEnabled=true`.
- Use `useManufacturerData=true` when devices are known to include the manufacturer payload.
- If you observe one-way traffic or low matches in the field, temporarily set
  `scanFilterEnabled=false` and/or switch to service-UUID matching to confirm discovery issues.

---

## 7) Sending a message

```
User taps Send
  │
  ▼
broadcastEnvelope(blob)
  │
  ├─> selectTargetsForBroadcast()
  │     - only devices in lastSeen
  │     - if mfg enabled: de-dup by device_id, pick best RSSI
  │
  ├─> chunk message (size depends on MTU; capped by `maxChunkBytesCap`)
  ├─> enqueue per device
  └─> connectIfNeeded(device)
```

Typical logs:

- `Queued BLE send targets=X`
- `Queued BLE targets addresses=...`
- `Resend scheduled attempt=1`

---

## 8) Receiving a message

On GATT server side:

- `GATT recv chunk` (repeat)
- Reassemble into full envelope
- `GATT deliver envelope`
- `Received message at ...`
- `Rendered message at ...`

---

## 9) ACK flows

ACKs are regular BLE messages with type:

- `received` = confirms delivery
- `seen` = confirms message was shown

Flow:

```
Receive message
  ├─> Send ACK received (immediate)
  └─> Send ACK seen (when displayed/seen)
```

Typical logs:

- `Sent ACK type=received ok=true target=...`
- `Sent ACK type=seen ok=true target=...`

---

## 10) Why one-way happens

When a device does **not match** scan results, it never updates `lastSeen`, so it never chooses targets for send. That yields:

- **Up-only traffic** (it sends but does not receive)
- **Nearby count > 0 but matched = 0**

Common cause:

- Manufacturer data is valid, but **service UUID missing in ScanRecord** (device-specific behavior). If matching incorrectly required UUID even in mfg mode, the device never matches and never receives.

---

## 11) Performance knobs (current code behavior)

- `scanFilterEnabled`: when `true`, only matched records are delivered to the app (reduces CPU/log volume).
- `scanMode` / `scanReportDelayMs`: affects scan intensity and batching.
- `advertiseMode` / `advertiseTxPower`: affects advertise intensity and range.
- **Backpressure**: when send queues grow, scan/advertise is reduced to LOW_POWER.
- **Adaptive duty cycle**: when traffic is active, scan/advertise shifts to LOW_LATENCY; when idle, LOW_POWER.

These behaviors can make BLE look “slow” in logs even when it is throttling on purpose.

---

## 12) Performance pitfalls & mitigations

- **Concurrency/race risk in queues/maps**: callbacks and handlers touch shared state.
  - Mitigation: keep BLE transport state on a single thread/dispatcher, or add explicit locks.
- **Stale device/queue buildup**: `lastSeen*` and `pendingByDevice` can grow over time.
  - Mitigation: prune stale devices and empty queues on a fixed cadence.
- **ACK/resend thread burst**: `MeshAckManager` spawns raw threads per retry.
  - Mitigation: use a bounded executor/coroutines and apply backoff/batching.
- **Backpressure tradeoff**: lowering scan/advertise under load can prolong recovery.
  - Mitigation: cap throttle duration and re‑enable low‑latency scan when backlog shrinks.

---

## 13) Quick field checklist

- `BLE advertised device_id` appears on both devices
- `BLE mfg match` appears on both devices
- `BLE scan callbacks matched > 0` on both devices
- `Queued BLE targets` shows device_id addresses for both devices
- GATT recv/reassembled/deliver appear on both sides

---

If you want a deeper sequence diagram per message transaction, I can add that too.

---

## 14) Deep message transaction sequence (with ACK timing)

This is a step-by-step sequence for a single message from **Device A** to **Device B**.
Timing is relative and indicative.

```
Time →

Device A (sender)                           Device B (receiver)
--------------------------------------------------------------------------------
[Scan loop]                                 [Scan loop]
  - sees B (mfg match)                        - sees A (mfg match)
  - lastSeen[B] updated                        - lastSeen[A] updated

User taps Send
  broadcastEnvelope(blob)
  ├─ selectTargets() -> B
  ├─ chunk (size depends on MTU; capped)
  ├─ enqueue chunks for B
  └─ connectIfNeeded(B)

Connect / GATT
  GATT connect to B  ----------------------->  GATT server accepts
  GATT services discovered  ---------------->  (server ready)

Chunk transfer
  write chunk #1  -------------------------->  recv chunk #1
  write chunk #2  -------------------------->  recv chunk #2
  ...
  write chunk #N  -------------------------->  recv chunk #N

Reassembly + delivery
                                              reassemble -> envelope
                                              deliver to ingest
                                              message displayed

ACK received (immediate)
                                              send ACK(received)
  receive ACK(received)  <-------------------

ACK seen (after render/seen)
                                              send ACK(seen)
  receive ACK(seen)  <-----------------------
--------------------------------------------------------------------------------
```

### Notes on ACK timing

- **ACK received** is sent immediately after the full envelope is reassembled
  and delivered to ingest.
- **ACK seen** is sent only after the message is rendered/seen.
- Both ACKs travel through the same BLE GATT path (chunked writes).

### Common log landmarks for each phase

1) **Send start**
   - `Queued BLE send`
   - `Queued BLE targets`
   - `Resend scheduled`

2) **GATT connect + MTU**
   - `GATT server conn`
   - `GATT connected`
   - `GATT services discovered`
   - `MTU negotiation`

3) **Payload transfer**
   - `GATT write attempt` / `GATT write start` / `GATT write ok`
   - `GATT recv chunk`

4) **Reassemble + deliver**
   - `GATT reassembled`
   - `GATT deliver envelope`
   - `Received message at`
   - `Rendered message at`

5) **ACKs**
   - `Sent ACK type=received`
   - `Sent ACK type=seen`

---

## 15) Event lifecycle (BLE mesh)

High-level flow for a single event from creation to render:

1) Create `EventCore` + `event_id`
2) Encrypt + MAC → build `EventEnvelope` (`mid = event_id`)
3) Broadcast over BLE (chunk + GATT write)
4) Receive chunks → reassemble envelope
5) Parse + verify MAC + decrypt core
6) Ingest (dedupe + reducer)
7) Render + ACK (received/seen)
