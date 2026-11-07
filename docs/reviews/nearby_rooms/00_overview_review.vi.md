# Nearby Room BLE Review

Nguồn đối chiếu chính:

- Blueprint: `docs/specs/NEARBY_ROOM_BLUEPRINT.md`, mục `4. Nearby Room BLE Transport Packet Lifecycle`
- Runtime wrapper: `android/app/src/main/java/app/peermesh/ble/BleForegroundService.kt:154`
- Shared BLE runtime: `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt:72`
- Nearby transport receiver: `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:20`
- Private-group receiver: `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt:38`

## 1. Logic xử lý BLE mesh của nearby room tách riêng, không xâm lấn vào BLE mesh của private group

### Phi kỹ thuật

Trạng thái: `Đạt một phần`

Nearby Room đã có nhánh xử lý riêng ở tầng ứng dụng:

- BLE discovery metadata của nearby room đi qua provider/listener riêng.
- Nearby room transport packet được chặn và xử lý ở wrapper riêng trước khi tới flow private group.
- Private group vẫn giữ flow cũ dựa trên `EnvelopeReceiverImpl`.

Tuy nhiên, hai hệ vẫn chưa tách riêng ở tầng hạ tầng BLE:

- Cùng dùng một `BleMeshRuntime`.
- Cùng dùng một `MeshTransport`.
- Cùng đi qua cùng một scheduler, queue, backpressure, scan/advertise mode.

### Kỹ thuật chi tiết

- `BleForegroundService` khởi tạo receiver theo chuỗi `NearbyRoomTransportReceiver(delegate = EnvelopeReceiverImpl(...))`, nghĩa là nearby room nằm trước private-group trong receive chain, nhưng vẫn dùng cùng service và cùng transport runtime:
  - `android/app/src/main/java/app/peermesh/ble/BleForegroundService.kt:154`
- `BleMeshRuntime.init()` chỉ tạo một `MeshTransport`, bind thêm `NearbyRoomBleCoordinator`, và dùng một receiver delegate chung:
  - `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt:72`
  - `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt:81`
  - `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt:83`
- `NearbyRoomTransportReceiver` decode packet nearby trước; nếu không phải nearby packet thì mới chuyển tiếp sang `EnvelopeReceiverImpl`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:21`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:22`
- `EnvelopeReceiverImpl` chỉ xử lý envelope/app-batch có chữ ký của private-group/event pipeline, nên nearby-room packet không đi vào ingest path này:
  - `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt:54`
  - `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt:73`

Kết luận:

- `Tách biệt logic app-layer`: có.
- `Tách biệt transport-layer`: chưa.

## 2. BLE flow của nearby room không chặn BLE flow của private group và ngược lại

### Phi kỹ thuật

Trạng thái: `Chưa bảo đảm`

Hai flow đang dùng chung đường BLE. Vì vậy nearby room và private group có thể tranh chấp tài nguyên với nhau.

Biểu hiện rõ nhất:

- Nếu queue BLE bị đầy, discovery burst của nearby room có thể bị bỏ qua.
- Nếu nearby room bắn nhiều packet transport, nó vẫn góp vào `pendingBytes` chung và có thể kích hoạt backpressure ảnh hưởng toàn mesh.
- Khi mesh không ở `FULL_RELAY`, refresh advertisement nearby room cũng bị từ chối.

### Kỹ thuật chi tiết

- Giao diện `MeshTransport` là shared abstraction cho mọi flow:
  - `android/transport/src/main/java/app/peermesh/transport/adapter/MeshTransport.kt:5`
- Mọi nearby transport packet vẫn gọi chung `broadcastEnvelope(...)` hoặc `broadcastEnvelopeToDeviceIds(...)` của shared transport:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:57`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:87`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:131`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:158`
- Trong `BleMeshTransport`, cả broadcast thường và directed broadcast đều đi vào cùng `broadcastEnvelopeInternal()` và cùng queueing path:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:5574`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:5584`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:5615`
- Queue chung này cập nhật `backpressure` bằng `totalPendingBytes()` chung:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:5635`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:7339`
- Khi `backpressureActive`, transport hạ scan mode và advertise mode toàn cục:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:7341`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:7343`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:7346`
- `requestDiscoveryBurst()` của nearby room bị skip hoàn toàn nếu `backpressureActive`:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:8844`
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:8847`
- `refreshNearbyRoomAdvertisement()` bị reject nếu transport không chạy hoặc mesh không ở `FULL_RELAY`:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt:8821`

Kết luận:

- Không có bằng chứng cho thấy nearby/private-group được tách queue hoặc tách budget.
- Tiêu chí này hiện `không đạt`.

Sơ đồ ngắn:

```text
BLE hiện tại
|
+-- Shared BLE runtime / transport
|   |
|   +-- Private Group: 3 luồng
|   |   |
|   |   +-- 1. Mesh event / app-batch flow
|   |   |      - group chat, seen ack, mesh event ingest
|   |   |      - dùng chung send queue / scheduler / backpressure
|   |   |
|   |   +-- 2. DTN sync / control flow
|   |   |      - manifest, inventory, repair, control packets
|   |   |      - dùng chung send queue / scheduler / backpressure
|   |   |
|   |   +-- 3. Opaque relay / retry flow
|   |          - relay capsule, retry, recovery
|   |          - dùng chung send queue / scheduler / backpressure
|   |
|   +-- Nearby Room: 2 luồng
|       |
|       +-- 1. Discovery advertisement flow
|       |      - nearby room advertise payload
|       |      - nearby room discovery scan listener
|       |      - KHÔNG đi qua app-packet send queue
|       |      - nhưng vẫn dùng cùng radio state và vẫn bị ảnh hưởng bởi backpressure
|       |
|       +-- 2. Nearby room transport packet flow
|              - KR1, KB1, MR1, MS1, JR1, RC1, M2, MO1, MF1, MD1
|              - dùng chung send queue / scheduler / backpressure
|
+-- Side-channel advertise / scan
    |
    +-- Nearby Room discovery metadata
           - chỉ là advertise/scan side-channel
           - không tách khỏi shared BLE radio policy
```

Ghi chú:

- `Private Group` hiện có `3` luồng logic BLE, nhưng cả 3 đều hội tụ vào shared transport queue.
- `Nearby Room` hiện có `2` luồng logic BLE:
  - `discovery advertisement flow` là side-channel advertise/scan
  - `transport packet flow` đi chung queue với private-group
- Vì vậy, nearby room không có queue riêng độc lập khỏi private-group.



## 3. Ở màn hình Discovery, lấy được list các nearby room

### Phi kỹ thuật

Trạng thái: `Đạt một phần`

Màn hình Discovery có đủ flow để lấy room list:

- vào màn hình -> bật discovery mode
- xin discovery burst
- nhận BLE ad payload
- buffer/evaluate/upsert room
- đọc DB để render list

Nhưng room chỉ hiện khi metadata đã `RESOLVED`. Nếu room mới chỉ ở trạng thái `ACTIVE + PENDING`, nó vẫn tồn tại trong DB nhưng chưa hiện trên list.

### Kỹ thuật chi tiết

- `NearbyRoomsActivity.onResume()` vào discovery mode và trigger refresh:
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:87`
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:90`
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:92`
- Refresh flow gọi `requestDiscoveryBurst()`, sau đó `flushBufferedDiscovery()`, rồi lấy:
  - `listActiveRooms()`
  - `listVisibleRooms()`
  - enqueue metadata refresh
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:132`
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:133`
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:134`
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:135`
  - `android/app/src/main/java/app/peermesh/NearbyRoomsActivity.kt:137`
- Nearby discovery metadata được bind riêng qua `NearbyRoomBleCoordinator`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt:24`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt:43`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt:104`
- DAO chỉ render `listVisible()` khi `metadata_status = RESOLVED`:
  - `android/data/src/main/java/app/peermesh/data/db/dao/NearbyRoomDao.kt:24`

Kết luận:

- `Lấy được active room từ BLE`: có.
- `Hiện được trên discovery screen ngay khi vừa discover`: chưa chắc.
- Điều kiện chặn hiện list là `metadata_status != RESOLVED`.

## 4. Ở màn hình chat, lấy được bootstrap key nếu chưa có

### Phi kỹ thuật

Trạng thái: `Đạt một phần`

Flow xin bootstrap key đã tồn tại và được nối tới UI:

- Khi vào room mà chưa có quyền chat, màn hình sẽ phát `KR1`.
- Peer có room key có thể trả `KB1`.
- Sau khi lưu key, máy joiner tự kéo tiếp metadata để hoàn tất trạng thái ready-to-chat.

Nhưng đây là cơ chế “có cố gắng”, chưa phải “bảo đảm thành công”, vì còn phụ thuộc:

- room phải có trong local DB
- có peer giữ key ở gần và trả lời
- cooldown không chặn
- transport queue không drop

### Kỹ thuật chi tiết

- `NearbyRoomActivity.ensureSecurityState()` phát `KR1` theo timer khi chưa ready:
  - `android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:415`
  - `android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:425`
- Active-room gate cho `KR1` và `KB1` được bypass ở receiver:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:26`
- `KR1` được broadcast:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:98`
- Receiver có key thì có thể trả `KB1` directed về requester:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:48`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:65`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:418`
- `KB1` receive sẽ store key và trigger post-join metadata refresh:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:65`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:79`

Kết luận:

- `Có flow lấy bootstrap key`: có.
- `Bảo đảm lấy được nếu chưa có`: chưa thể kết luận.

## 5. Dedupe hiệu quả để tránh các hành động dư thừa làm lãng phí BLE traffic

### Phi kỹ thuật

Trạng thái: `Đạt một phần`

Hiện tại có nhiều lớp chống lặp tốt:

- cooldown trả `KB1`
- cooldown trả `MS1`
- dedupe relay `JR1`
- dedupe relay `RC1`
- throttle/skip trong discovery accumulator
- relay want/offer dùng key DB để tránh tạo bản ghi trùng

Nhưng vẫn còn các khoảng trống:

- `KR1` không có dedupe ở transport layer; nếu nhận `M2` mà chưa có key thì có thể bắn `KR1` nhiều lần.
- Discovery list phụ thuộc metadata resolve; nếu `MR1/MS1` không thành công thì room cứ active nhưng không visible.
- Chưa thấy budget tách riêng cho nearby-room packet so với private-group packet.

### Kỹ thuật chi tiết

- Cooldown `KB1` reply theo `(roomId, requesterDeviceId)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:418`
- Cooldown `MS1` reply theo `(roomId, requesterDeviceId)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:448`
- Dedupe `JR1` relay theo `(room_id, origin_device_id, created_at_ms)`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:412`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:496`
- Dedupe `RC1` relay:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:406`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:492`
- Discovery accumulator đã có quyết định `SKIP_THROTTLED` và `SKIP_BUDGET`:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomBleCoordinator.kt:145`
- Relay want/offer upsert theo khóa DB:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:553`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomRepository.kt:604`
- `M2` khi thiếu key sẽ gọi thẳng `sendKeyRequest()` mà không có cooldown ở receiver path:
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportReceiver.kt:224`
  - `android/app/src/main/java/app/peermesh/nearby/NearbyRoomTransportManager.kt:98`
- Cooldown `KR1` hiện chỉ có ở UI room screen, không bao phủ mọi receive path:
  - `android/app/src/main/java/app/peermesh/NearbyRoomActivity.kt:420`

Kết luận:

- Có nền tảng dedupe tốt.
- Chưa đủ mạnh để khẳng định “hiệu quả toàn diện” trong mọi flow.

## Tổng kết ngắn

| Tiêu chí | Kết luận |
|---|---|
| 1. Tách logic nearby/private-group | `Đạt một phần` |
| 2. Không chặn flow lẫn nhau | `Chưa đạt` |
| 3. Discovery lấy được room list | `Đạt một phần` |
| 4. Chat lấy được bootstrap key nếu thiếu | `Đạt một phần` |
| 5. Dedupe hiệu quả | `Đạt một phần` |

Khuyến nghị ưu tiên:

1. Tách budget hoặc queue class cho nearby-room traffic và private-group traffic.
2. Thêm cooldown transport-layer cho `KR1`, đặc biệt từ nhánh `M2` thiếu key.
3. Xem lại rule visibility của discovery screen cho room `ACTIVE + PENDING`.
4. Thêm metric/log riêng cho packet outcome theo từng kind để test matrix dễ hơn.
