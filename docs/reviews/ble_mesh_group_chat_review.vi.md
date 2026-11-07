# Review BLE Mesh + Group Chat Activity

Tài liệu này tổng hợp lại nội dung phân tích BLE mesh và Group Chat Activity (bản Việt hoá) từ lần review gần nhất.

## Kết luận chính (ưu tiên cao → thấp)

### 1) Nghiêm trọng: Nhận BLE xử lý nặng trên thread callback GATT
- Trong `BleMeshTransport.onCharacteristicWriteRequest`, gọi `receiver.onEnvelopeReceived(...)` trực tiếp ngay trên callback của BLE stack.
- `EnvelopeReceiverImpl.onEnvelopeReceived` làm các bước nặng: parse JSON, load key, verify/decrypt, validate, ingest DB.
- Điều này có thể **chặn BLE stack**, làm tăng timeout GATT, rơi packet, dễ tạo “one-way drift” và lỗi `status=133`.
- File liên quan:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`
  - `android/data/src/main/java/app/peermesh/data/ingest/EnvelopeReceiverImpl.kt`

### 2) Cao: BLE runtime tự bật relay sync, có thể override ý người dùng
- `BleMeshRuntime.handleBleAvailability` tự bật relay sync khi BLE không sẵn sàng.
- Hàm này được gọi khi `BleMeshRuntime.get/init` — có thể bị gọi chỉ để đọc nearby status/UI.
- Nếu user chủ động tắt relay sync, app sẽ tự bật lại **không hỏi**, dễ gây mất tin cậy.
- File liên quan:
  - `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`

### 3) Cao: GATT ACK thành công trước khi kiểm tra dữ liệu
- `onCharacteristicWriteRequest` trả `GATT_SUCCESS` ngay cả khi chunk duplicate/malformed và bị drop sau đó.
- Điều này có thể làm sender nghĩ đã nhận ok và giảm khả năng adaptive retry đúng cách.
- File liên quan:
  - `android/transport/src/main/java/app/peermesh/transport/ble/BleMeshTransport.kt`

### 4) Trung bình: “Seen” ACK gửi ngay khi ingest
- `GroupChatActivity` gửi `seen` ACK khi message vừa được ingest/render.
- Nghĩa là **seen = mở màn hình chat** chứ không phải **seen = user thực sự nhìn thấy message**.
- Nếu muốn “seen” nghĩa là đã đọc, cần chuẩn hoá lại trigger (ví dụ khi message vào viewport).
- File liên quan:
  - `android/app/src/main/java/app/peermesh/GroupChatActivity.kt`

### 5) Trung bình: Nút send không phản ánh BLE readiness
- `updateSendState` chỉ hiển thị cảnh báo permission; không disable send khi BLE thiếu permission / BLE off.
- Điều này không sai với offline-first, nhưng **lệch** với UX guideline Phase 2 (disable send khi thiếu BLE permission).
- File liên quan:
  - `android/app/src/main/java/app/peermesh/GroupChatActivity.kt`

### 6) Thấp: Có thể init BLE transport khi chưa có receiver
- `BleMeshRuntime.get()` có thể tạo transport ngay cả khi chưa gọi `init()`, dẫn tới receiver = null.
- Có log cảnh báo, nhưng vẫn có nguy cơ drop envelope trong thời điểm đó.
- File liên quan:
  - `android/app/src/main/java/app/peermesh/ble/BleMeshRuntime.kt`

---

## Câu hỏi cần chốt
1. “Seen” ACK nên nghĩa là **mở màn hình chat** (như hiện tại) hay **message thật sự xuất hiện trên màn hình/viewport**?
2. Relay sync có nên **tự bật** khi BLE unavailable, hay phải **tôn trọng lựa chọn user**?

---

## Đề xuất hành động tiếp theo
1. **Offload ingest khỏi GATT callback thread** (queue/worker riêng) để giảm timeout và improve receive stability.
2. **Rà lại policy auto-enable relay** để tránh override user intent.
3. Chuẩn hoá **semantics “seen”** theo UX mong muốn.
