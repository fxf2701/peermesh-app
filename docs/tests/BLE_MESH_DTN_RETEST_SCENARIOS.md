# BLE Mesh + DTN Retest Scenarios

Status: Active  
Scope: Physical-device retest for BLE mesh transport, DTN control/data, opaque retry, ACK, and suppress-policy behavior.

## 1. Mục tiêu
- Re-test nhanh toàn bộ các luồng BLE mesh + DTN đang được dùng thật trên Android.
- Ưu tiên evidence từ:
  - `Delivery`
  - `Per-batch Verdict`
  - `ACK Activity`
  - `DTN Control Summary`
  - `DTN Opaque Retry Summary`
  - raw activity logs của `A / B / C`

## 2. Preconditions
- Có ít nhất 3 máy: `A`, `B`, `C`
- Cùng build / cùng branch / cùng group
- App mở sẵn trên cả 3 máy
- Nếu muốn reset sạch trước mỗi round:
  - clear app data trên cả 3 máy
- Với review round:
  - luôn check thêm timeline `BT on/off` hoặc `service start/stop`

## 3. Labels cần bật
Tối thiểu:

```text
Received message at
Rendered message at
BLE batch send progress
BLE batch send complete
BLE batch send failed
DTN inv_have sent
DTN inv_have recv
DTN inv_want sent
DTN inv_want recv
DTN data sent
DTN data recv
DTN repair suppress
DTN opaque retry store
DTN opaque retry queued
DTN opaque retry mark_forwarded
DTN opaque retry send_complete
DTN opaque retry delete
```

Nên giữ thêm:

```text
WRITE keep mesh envelope
WRITE prefer mesh batch
DTN control queue decision
DTN opaque retry bookkeeping_probe
DTN opaque retry pre_mark_probe
DTN opaque retry pre_mark_result
```

## 4. Scenarios

### S1. Mesh Direct Smoke
Mục tiêu:
- xác nhận direct BLE mesh vẫn ổn trước khi test DTN / relay

Steps:
1. `A` gần `B`, `C` không tham gia
2. `A` gửi:
   - `seed A #1`
   - `seed A #2`
   - `seed A #3`
3. chờ `10s`
4. export logs + generate report

Pass:
- `A -> B = 3/3`
- `source=mesh`
- không có fail bất thường ở direct path

### S2. Mesh Carrier Relay
Mục tiêu:
- xác nhận `A -> C -> B` relay vẫn ổn

Steps:
1. tạo direct seed như `S1`
2. cắt direct `A -> B` bằng:
   - di chuyển topology, hoặc
   - trong phòng nhỏ: `B off`, `C on`, rồi `A off`, `B on`
3. `A` gửi:
   - `stale A #1`
   - `stale A #2`
   - `stale A #3`
4. chờ `60–120s`
5. export logs + generate report

Pass:
- `A -> B = 3/3` cho stale set
- `Per-batch Verdict` cho stale là relay qua `C`
- `DTN Payload Ingest` vẫn rỗng hoặc không chi phối delivery chính

### S3. Forced-Mismatch DTN Control/Data
Mục tiêu:
- chứng minh `hello -> inv_have -> inv_want -> data -> ack` vẫn chạy thật
- ép một peer thấy peer kia có inventory mà local còn thiếu, để DTN control/data path phải nổ ra

Setup chuẩn:
- ưu tiên `A <-> C`
- `B` chỉ đóng vai phụ hoặc tắt hẳn để giảm noise
- tốt nhất dùng state sạch hoặc vừa clear app data trước round

Ý tưởng:
- một bên phải có inventory nhiều hơn bên còn lại
- sau đó tạo reconnect để DTN sync chạy
- review phải bám đúng timeline:
  - `hello`
  - `inv_have`
  - `inv_want`
  - `data` hoặc `carrier_data`
  - `ack`

Setup A - topology / di chuyển:
1. chỉ để `A` và `C` nói chuyện với nhau
2. trên `A`, gửi 3 message mới:
   - `DTN gap A #1`
   - `DTN gap A #2`
   - `DTN gap A #3`
3. đảm bảo `C` chưa có 3 message này
4. cắt link `A <-> C`
5. sau đó cho `A` gặp lại `C`
6. chờ `30–60s`
7. export logs + generate report

Setup B - phòng nhỏ / BT on-off:
1. bật BT trên `A`, tắt BT trên `C`
2. trên `A`, gửi 3 message mới:
   - `DTN gap A #1`
   - `DTN gap A #2`
   - `DTN gap A #3`
3. chờ `5–10s`
4. bật BT trên `C`
5. nếu cần ép reconnect rõ hơn:
   - bật `C` trước
   - rồi tắt/bật BT trên `A` thêm 1 nhịp ngắn
6. chờ `30–60s`
7. export logs + generate report

Nếu muốn ép mismatch theo chiều ngược lại:
1. tạo 2–3 message mới trên `C`
2. rồi reconnect `A <-> C`
3. kỳ vọng `A` là bên gửi `inv_want`

Điều kiện round tốt:
- chỉ nên có 2 máy chính `A/C`
- hạn chế direct app-delivery churn với `B`
- không trộn cùng lúc stale relay và ACK test, vì sẽ làm DTN control khó đọc

Pass:
- `DTN Control Summary` phải vượt `HELLO_ONLY`
- tối thiểu có:
  - `hello s/r`
  - `inv_have s/r`
  - `inv_want sent` hoặc `inv_want recv`
  - `data sent/recv` hoặc `carrier_data sent/recv`
  - `ack sent/recv`
- raw log phải cho thấy cùng một session đi qua chuỗi:
  - `DTN hello`
  - `DTN inv_have`
  - `DTN inv_want`
  - `DTN data` hoặc `DTN carrier_data`
  - `DTN ack`

Pass mạnh hơn:
- `DTN Control Summary` có verdict `BEYOND_HELLO`
- có `control_handle_result` hoặc `sync_summary` với:
  - `responses > 0`
  - hoặc `sent > 0`
  - hoặc `ids > 0`
  - hoặc `data > 0`

Fail:
- chỉ có `hello`
- chỉ có `manifest_key` nhưng không có `inv_have/inv_want`
- có `inv_have` nhưng không có `inv_want`
- có `inv_want` nhưng không có `data/carrier_data`
- có data nhưng không có `ack`

Review note bắt buộc:
- ghi rõ mốc:
  - `BT off/on`
  - `service stop/start`
  - `DTN hello`
  - `DTN inv_have`
  - `DTN inv_want`
  - `DTN data` / `carrier_data`
  - `DTN ack`

### S4. ACK Return Path
Mục tiêu:
- chứng minh app-layer `SYS.ACK` vẫn quay về đúng

Steps:
1. `A` gửi 2–3 message thường sang `B`
2. chờ `B` ingest xong
3. chờ ACK return path drain
4. export logs + generate report

Pass:
- `ACK Activity` cho thấy `B` tạo ACK
- ACK batch tới `BLE batch send complete`
- `A` áp dụng `ACK.delivered` / `ACK.seen`

### S5. Opaque Retry Success Lifecycle
Mục tiêu:
- chứng minh opaque retry được store, queue, success, cleanup đúng

Steps:
1. ép `C` trở thành carrier:
   - `A` chỉ nói chuyện được với `C`
   - `B` chưa reachable
2. `A` gửi:
   - `opaque cleanup A #1`
   - `opaque cleanup A #2`
   - `opaque cleanup A #3`
3. sau đó cho `C` gặp `B`
4. chờ `60–120s`
5. export logs + generate report

Pass:
- trên `C` có:
  - `opaque retry store`
  - `opaque retry queued`
  - `opaque retry mark_forwarded`
  - `opaque retry send_complete`
  - `opaque retry delete reason=success_cleanup`

### S6. Mesh-First / DTN-Repair-Second + Suppress Rules
Mục tiêu:
- xác nhận 4 rule suppress vẫn đúng:
  - `local already-have`
  - `peer already-has`
  - `local forwarded-success`
  - `local pending relay`

Steps:
1. chạy direct seed trước
2. chạy stale relay qua `C`
3. sau khi `C -> B` success, tạo reconnect bằng topology hoặc BT on/off
4. export logs + generate report

Pass:
- `B` không `inv_want` lại stale ids
- `C` không `inv_have` lại stale ids cho `B`
- `C` không `inv_want` lại stale ids từ `A` sau success
- nếu reconnect xảy ra trong lúc pending relay:
  - có `DTN repair suppress reason=local_pending_relay`

### S7. BT / Service Churn Stability
Mục tiêu:
- xác nhận delivery và relay còn sống dưới churn

Small-room steps:
1. `A + B on`, `C off`, gửi `seed #1..#3`
2. `B off`, `C on`, gửi `stale #1..#3`
3. chờ `15–20s`
4. `A off`, `B on`, giữ `C on`
5. chờ `60–120s`
6. `A on` lại
7. chờ `30–60s`
8. export logs + generate report

Pass:
- delivery vẫn đạt cho seed + stale
- opaque lifecycle trên `C` vẫn khép
- khi review phải đối chiếu với:
  - mốc `BT off/on`
  - `stopMeshAndService`
  - `start_transport`

### S8. Stale-Target Fallback
Mục tiêu:
- chỉ dùng gần release

Status:
- postpone khỏi current milestone

Pass:
- chỉ tính khi có evidence trực tiếp cho fallback branch

## 5. Hai setup test chuẩn

### A. Topology / di chuyển thiết bị
Dùng khi có không gian:
- `A gần B`
- rồi cắt direct path
- để `C` làm carrier thật

### B. Phòng nhỏ / BT on-off
Dùng khi không thể mang máy đi xa:
- tạo partition logic bằng `Bluetooth off/on`
- luôn note rõ:
  - máy nào tắt trước
  - máy nào bật lại trước
  - lúc nào `A` bị loại khỏi direct path

## 6. Checklist review sau mỗi round
- [ ] `Delivery`
- [ ] `Per-batch Verdict`
- [ ] `ACK Activity`
- [ ] `DTN Control Summary`
- [ ] `DTN Opaque Retry Summary`
- [ ] raw log `A / B / C`
- [ ] timeline `BT on/off`
- [ ] timeline `service start/stop`
- [ ] map `inv_have / inv_want` với thời điểm `store_saved / success_cleanup`

## 7. Template note nhanh
```md
### Round: <timestamp>
- Build / branch:
- Devices:
- Setup:
  - topology hay BT on/off
- Messages sent:
- Expected:
- Actual:
- Delivery:
- DTN control:
- Opaque retry:
- ACK:
- BT/service timeline:
- Verdict: PASS | PARTIAL | FAIL
- Follow-up:
```
