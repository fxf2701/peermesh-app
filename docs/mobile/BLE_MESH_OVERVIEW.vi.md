# Tổng quan BLE Mesh

Tài liệu này mô tả luồng BLE mesh từ lúc cài app, mở app, restart mesh, gửi/nhận tin, đến ACK received/seen.

## 1) Bức tranh tổng thể

BLE mesh chạy song song hai kênh:

- **Advertise/Scan**: nhìn thấy thiết bị gần và quyết định target.
- **GATT Server/Client**: truyền payload thật bằng BLE writes.

Manufacturer data (device_id) dùng để nhận diện đúng app và giảm trùng target.

---

## 2) Sơ đồ ASCII tổng quát

```
                ┌────────────────────────────────────────┐
                │              APP START                 │
                │  - init BleMeshRuntime                 │
                │  - set advertised device_id            │
                │  - start BLE scan + advertise + GATT    │
                └────────────────────────────────────────┘
                             │
                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                        BLE MESH LOOP                               │
│                                                                    │
│   [Scan] <---- BLE Adv ---- [Nearby device] ---- BLE Adv ----> [Scan]
│      │                                                              
│      │ ScanRecord + MfgData OK -> "matched" -> lastSeen[address]     
│      │                                                              
│      ▼                                                              
│  Chọn target (theo device_id + RSSI tốt nhất)                       
│      │                                                              
│      ▼                                                              
│  GATT Connect (client) ---> GATT Server (peer)                      
│      │                                                              
│      ▼                                                              
│  Chunked Write (kích thước theo MTU, có giới hạn)                    
│      │                                                              
│      ▼                                                              
│  Reassemble -> Deliver Envelope -> Message pipeline                 
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

---

## 3) Lúc vừa cài app

- Không có hành động đặc biệt.
- **device_id chưa được tạo** cho đến khi mesh khởi động lần đầu.

---

## 4) Mở app / start mesh

Trình tự khi mở app (hoặc start mesh):

1. `BleMeshRuntime.init()`
2. `ensureAdvertisedDeviceId()`
   - `DeviceIdProvider.get(...)`
   - hash `SHA-256`
   - lấy 8 bytes đầu
   - gán vào `BleConfig.advertisedDeviceId`
   - log `BLE advertised device_id id=XXXX`
3. Tạo `BleMeshTransport`
4. Start các thành phần BLE
   - `startScan()`
   - `startAdvertising()`
   - `startGattServer()`

---

## 5) Khi nhấn “Restart BLE mesh”

Restart thực chất là:

```
Stop mesh → Close GATT → Stop scan/advertise
        ↓
Start mesh → Init scan + advertise + GATT server
```

Dừng scan/advertise/GATT rồi khởi động lại, nhưng code hiện tại **không** xóa rõ ràng
các queue nội bộ hoặc device cache.

---

## 6) Logic scan & match

Mỗi scan result (khi `scanFilterEnabled=false` sẽ nhiều hơn):

- đọc ScanRecord
- validate manufacturer data (nếu bật)
- **Match logic**
  - Nếu `useManufacturerData == true`: match theo **manufacturer OK**
  - Nếu `useManufacturerData == false`: match theo **service UUID có mặt**

Nếu match:

- update `lastSeen[address]`
- lưu RSSI
- lưu device_id
- device đủ điều kiện gửi/nhận

Ghi chú:

- `scanFilterEnabled=true` đẩy việc lọc vào BLE stack, giảm CPU/log.
- `scanFilterEnabled=false` cho app tự đánh giá mọi scan result (hữu ích khi debug,
  nhưng tốn tài nguyên hơn).
- Match theo manufacturer data giảm false positives nhưng có thể bỏ sót nếu ScanRecord
  thiếu hoặc cắt cụt manufacturer data; match theo service UUID “dễ bắt” hơn nhưng nhiễu hơn.

Khuyến nghị mặc định cho môi trường thực địa (cân bằng hiệu năng và độ tin cậy):

- Giữ `scanFilterEnabled=true`.
- Dùng `useManufacturerData=true` khi chắc các thiết bị có gửi manufacturer payload.
- Nếu thấy one-way hoặc match thấp, tạm thời đặt `scanFilterEnabled=false` và/hoặc
  chuyển sang match service UUID để kiểm tra discovery.

---

## 7) Khi gửi tin nhắn

```
User bấm Send
  │
  ▼
broadcastEnvelope(blob)
  │
  ├─> selectTargetsForBroadcast()
  │     - chỉ chọn thiết bị trong lastSeen
  │     - nếu mfg: gộp theo device_id, lấy RSSI tốt nhất
  │
  ├─> chunk message (kích thước theo MTU; bị giới hạn bởi `maxChunkBytesCap`)
  ├─> enqueue theo device
  └─> connectIfNeeded(device)
```

Log thường thấy:

- `Queued BLE send targets=X`
- `Queued BLE targets addresses=...`
- `Resend scheduled attempt=1`

---

## 8) Khi nhận tin nhắn

Trên GATT server:

- `GATT recv chunk` (nhiều lần)
- reassemble thành envelope
- `GATT deliver envelope`
- `Received message at ...`
- `Rendered message at ...`

---

## 9) ACK received / ACK seen

ACK cũng là message bình thường, chỉ khác `type`:

- `received` = xác nhận đã nhận
- `seen` = xác nhận đã xem

Luồng ACK:

```
Nhận message
  ├─> Send ACK received (ngay lập tức)
  └─> Send ACK seen (khi hiển thị/seen)
```

Log:

- `Sent ACK type=received ok=true target=...`
- `Sent ACK type=seen ok=true target=...`

---

## 10) Vì sao có thể 1 chiều

Nếu một máy **không match scan**, nó không update `lastSeen`, nên không chọn target để gửi xuống. Kết quả:

- Chỉ thấy upload, không thấy download
- `Nearby > 0` nhưng `matched = 0`

Nguyên nhân thường gặp:

- manufacturer data OK nhưng **service UUID không xuất hiện trong ScanRecord** (tuỳ máy). Nếu match sai điều kiện, thiết bị sẽ không match và không nhận tin.

---

## 11) Các nút điều chỉnh hiệu năng (code hiện tại)

- `scanFilterEnabled`: khi bật, chỉ đưa bản ghi “match” lên app (giảm CPU/log).
- `scanMode` / `scanReportDelayMs`: điều chỉnh cường độ scan và batching.
- `advertiseMode` / `advertiseTxPower`: điều chỉnh cường độ quảng bá và tầm phủ.
- **Backpressure**: khi queue gửi lớn, scan/advertise bị giảm xuống LOW_POWER.
- **Adaptive duty cycle**: có traffic thì LOW_LATENCY, rảnh thì LOW_POWER.

Các cơ chế này có thể làm BLE “trông chậm” trong log nhưng thực ra đang throttle có chủ đích.

---

## 12) Các điểm dễ gây giảm hiệu năng & cách giảm thiểu

- **Rủi ro race/concurrency trong queue/map**: callback và handler cùng chạm trạng thái chung.
  - Cách giảm: gom state BLE vào một thread/dispatcher, hoặc dùng lock rõ ràng.
- **Tích tụ thiết bị/queue cũ**: `lastSeen*` và `pendingByDevice` có thể phình dần.
  - Cách giảm: prune thiết bị stale và queue rỗng theo chu kỳ.
- **Bùng nổ thread do ACK/resend**: `MeshAckManager` tạo thread thô cho mỗi retry.
  - Cách giảm: dùng executor/coroutines có giới hạn và áp dụng backoff/batching.
- **Tradeoff backpressure**: giảm scan/advertise khi quá tải có thể kéo dài thời gian hồi phục.
  - Cách giảm: giới hạn thời gian throttle và bật lại low‑latency khi backlog giảm.

---

## 13) Checklist nhanh khi debug

- `BLE advertised device_id` xuất hiện ở cả 2 máy
- `BLE mfg match` xuất hiện ở cả 2 máy
- `BLE scan callbacks matched > 0` ở cả 2 máy
- `Queued BLE targets` có device_id ở cả 2 máy
- `GATT recv/reassembled/deliver` xuất hiện ở cả 2 máy

---

Nếu bạn muốn thêm sơ đồ sequence chi tiết cho từng giao dịch message, mình có thể bổ sung.

---

## 14) Sequence chi tiết cho 1 message (kèm ACK)

Sơ đồ chi tiết cho một message từ **Device A** sang **Device B**.
Thời gian chỉ là tương đối.

```
Time →

Device A (sender)                           Device B (receiver)
--------------------------------------------------------------------------------
[Scan loop]                                 [Scan loop]
  - thấy B (mfg match)                        - thấy A (mfg match)
  - update lastSeen[B]                        - update lastSeen[A]

User bấm Send
  broadcastEnvelope(blob)
  ├─ selectTargets() -> B
  ├─ chunk (kích thước theo MTU; có giới hạn)
  ├─ enqueue chunks
  └─ connectIfNeeded(B)

Connect / GATT
  GATT connect to B  ----------------------->  GATT server accept
  GATT services discovered  ---------------->  (server ready)

Chunk transfer
  write chunk #1  -------------------------->  recv chunk #1
  write chunk #2  -------------------------->  recv chunk #2
  ...
  write chunk #N  -------------------------->  recv chunk #N

Reassemble + deliver
                                              reassemble -> envelope
                                              deliver to ingest
                                              hiển thị message

ACK received (ngay lập tức)
                                              send ACK(received)
  receive ACK(received)  <-------------------

ACK seen (khi render/seen)
                                              send ACK(seen)
  receive ACK(seen)  <-----------------------
--------------------------------------------------------------------------------
```

### Ghi chú về thời điểm ACK

- **ACK received** được gửi ngay sau khi envelope reassemble xong và deliver vào ingest.
- **ACK seen** chỉ gửi khi message được render/seen.
- Cả hai ACK đều đi qua cùng đường BLE GATT (chunked writes).

### Mốc log theo từng phase

1) **Bắt đầu gửi**
   - `Queued BLE send`
   - `Queued BLE targets`
   - `Resend scheduled`

2) **Kết nối GATT + MTU**
   - `GATT server conn`
   - `GATT connected`
   - `GATT services discovered`
   - `MTU negotiation`

3) **Truyền payload**
   - `GATT write attempt` / `GATT write start` / `GATT write ok`
   - `GATT recv chunk`

4) **Reassemble + deliver**
   - `GATT reassembled`
   - `GATT deliver envelope`
   - `Received message at`
   - `Rendered message at`

5) **ACK**
   - `Sent ACK type=received`
   - `Sent ACK type=seen`
