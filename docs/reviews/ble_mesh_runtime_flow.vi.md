# BLE Mesh Runtime Flow (Current Implementation)

## 1) Muc tieu tai lieu
- Mo ta luong hoat dong BLE mesh tu luc service khoi dong den luc send/receive.
- Khoanh vung cac diem de mat on dinh ket noi trong dieu kien thuc te (timeout/133, nearby dao dong, one-way delivery).
- Lam baseline de doi chieu khi tiep tuc hardening.

## 2) Thanh phan chinh
- `android/app/src/main/java/app/peermesh/ble/BleForegroundService.kt`
  - Quan ly dieu kien chay (permission, Bluetooth, Location), foreground lifecycle, auto recover.
- `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`
  - Khoi tao backend (`BleMeshTransport` / `NordicMeshTransport`), set diagnostics logger.
- `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
  - Discovery, target selection (MTL/PTL), queue chunk, connect/discover/write, receive/reassembly, cleanup loop.
- `android/app/src/main/java/app/peermesh/ble/BleMeshDiagnosticsLogger.kt`
  - Mapping callback -> Activity Log labels.

## 3) Luong tong quat (service level)

```text
[App/Worker]
    |
    v
BleForegroundService.startMesh()
    |
    +--> Kiem tra permission + Bluetooth + Location
    |       |
    |       +-- thieu dieu kien --> stopMeshInternal + toast reminder + notif "paused"
    |
    +--> BleMeshRuntime.init/get()
    |       |
    |       +--> build transport backend + set diagnostics listener
    |
    +--> meshTransport.start()
            |
            +--> start GATT server
            +--> start advertising
            +--> start scan
            +--> start cleanup loop (5s tick)
```

## 4) Discovery va target selection

### 4.1 Scan intake
- `scanCallback.onScanResult/onBatchScanResults`
  - Validate scan record, service/manufacturer data.
  - Update:
    - `lastSeenDevices[address]`
    - `lastSeenAtMs[address]`
    - `lastSeenRssi[address]`
    - `lastSeenDeviceId[address]` (neu co mfg deviceId)
    - `lastSeenAddrByDeviceId[deviceId]`
  - Fill target pools:
    - MTL (`mainTargetDeviceIds`, co cap)
    - PTL (`potentialTargetDeviceIds`, cap theo RAM)

### 4.2 Chon target de broadcast
- `selectTargetsForBroadcast(nowMs)`
  1. Build candidate map (`buildFreshCandidates`)
  2. Sync MTL/PTL (`syncTargetPools`)
  3. Loc penalty
  4. Neu all penalized -> cho 1 probe candidate
  5. Chon toi da `MAX_BROADCAST_TARGETS`
  6. Neu empty -> `onBleTargetSelectEmpty(...)` + co the `scan_restart`
  7. Neu selected -> `onBleTargetSelected(...)`

```text
Scan Results --> Candidate Map --> MTL/PTL Sync --> Penalty Filter --> Select N targets
      |                |                |                 |                |
      |                |                |                 +-- all blocked --+--> Probe 1 target
      |                |                |                                  |
      +----------------+----------------+----------------------------------+--> Empty => target_select_empty
```

## 5) Send path (broadcastEnvelope)

```text
Envelope bytes
   |
   v
selectTargetsForBroadcast()
   |
   +--> empty -> return false
   |
   +--> split chunks per target
         |
         +--> enqueue pendingByDevice[address]
         +--> offerDeviceForRoundRobin(address)
         +--> connectIfNeeded(address)
```

### Connect hardening hien tai (da co)
- Penalty theo **address** (khong phat ca deviceId).
- Khi failure:
  - `recordConnectFailure(address, reason)`
  - dat backoff cho address
  - thu rotate sang address khac moi hon cung deviceId (`rotateAddressAfterFailure`)
  - migrate pending queue sang address moi neu co.
- Khi success:
  - `resetConnectFailures(address)` clear failure/penalty lien quan peer.

## 6) Connection / write path

```text
connectIfNeeded(address)
   |
   +--> choose best address for deviceId (fresh + non-penalized)
   |
   +--> connectGatt
          |
          +--> onConnectionStateChange(CONNECTED) -> onGattConnected
          |        |
          |        +--> discoverServices
          |                 |
          |                 +--> onServicesDiscovered -> onGattServicesDiscovered
          |                        |
          |                        +--> writeNextChunk loop
          |
          +--> fail/timeout/status!=success -> recordConnectFailure + cleanup
```

## 7) Receive path

```text
Remote write chunk --> GATT server callback
   |
   +--> chunk dedupe + reassembler
   +--> complete envelope -> receiver.onEnvelopeReceived(blob)
   +--> ingest/reducer cap nhat DB/UI
```

## 8) Recovery loop (cleanup tick)
- Chay dinh ky (`CLEANUP_INTERVAL_MS = 5000`):
  - detect connect timeout
  - detect stalled write
  - prune dedupe caches
  - adaptive scan/advertise mode
  - maybe connect next (round-robin)
  - idle disconnect neu can
- Khi Bluetooth/Location OFF->ON: service co the `hardRestart()` de restart full chain.

## 9) Diem yeu hien tai can uu tien

1. **Connect fail nhieu (timeout/133)**
- Du co rotate/backoff, van gap fail lap tren mot so address.
- Tac dong: nearby dao dong, delivery one-way theo tung dot.

2. **Target stability chua du**
- Queue co the day nhieu address khong on dinh.
- Can chinh priority: uu tien address vua connect thanh cong gan nhat cho cung deviceId.

3. **Observability gap nho**
- Da co `BLE target selected`, `GATT connected`, `GATT services discovered`.
- Luu y: callback `onBleTargetPinned` co trong interface/logger, nhung hien tai transport chua goi callback nay.

## 10) Allowed labels khuyen nghi cho debug stability
- `BLE scan callback`
- `Nearby changed`
- `Queued BLE targets`
- `BLE target selected`
- `BLE target pinned`
- `BLE target select empty`
- `GATT connect failed`
- `GATT connect backoff`
- `GATT connected`
- `GATT services discovered`
- `GATT server started`
- `BLE advertise started`
- `Mesh send queued`

## 11) Exit criteria de xem la on dinh hon
- Trong 1 phien TC7/TC10:
  - Co `GATT connected` + `GATT services discovered` lap lai o ca 2 may (khong chi 1 may).
  - Tan suat `GATT connect failed (timeout/133)` giam ro ret so voi baseline.
  - Khong con pattern mot may chi queue target ma khong vao duoc `connected`.
